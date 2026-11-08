package com.epicwalk.voiceanalyzer.diarization

import kotlin.math.sqrt

data class VoiceTurn(val startSample: Long, val endSample: Long, val embedding: FloatArray?,
    val overlap: Boolean = false, val speakerId: String = "speaker_unknown",
    /** Local pyannote identity scoped to one diarization window, for overlap attribution only. */
    val localSpeakerKey: Long? = null)

/** Global clustering across the recording, never a fresh speaker namespace per block. */
object GlobalSpeakers {
    private class Group(
        val sum: DoubleArray,
        var weight: Double,
        val windows: MutableSet<Long>,
        val localKeys: MutableSet<Long>
    ) {
        fun centroid(): FloatArray {
            val norm = sqrt(sum.sumOf { it * it })
            return FloatArray(sum.size) { if (norm > 0) (sum[it] / norm).toFloat() else 0f }
        }
    }
    private fun normalized(vector: FloatArray): FloatArray? {
        if (vector.isEmpty() || vector.any { !it.isFinite() }) return null
        val norm = sqrt(vector.sumOf { it.toDouble() * it })
        return if (norm < 1e-8) null else FloatArray(vector.size) { (vector[it] / norm).toFloat() }
    }
    private fun cosine(a: FloatArray, b: FloatArray): Double =
        a.indices.sumOf { a[it].toDouble() * b[it] }

    fun assign(
        turns: List<VoiceTurn>,
        expectedSpeakers: Int = 0,
        similarity: Double = 0.4,
        ambiguityMargin: Double = 0.0
    ): List<VoiceTurn> {
        require(expectedSpeakers in 0..32 && similarity in 0.2..0.95 && ambiguityMargin in 0.0..0.2)
        val sorted = turns.sortedBy { it.startSample }
        val vectors = sorted.map { if (it.overlap) null else it.embedding?.let(::normalized) }
        // Pyannote's simultaneous local lanes are hard evidence that two voices differ.
        // Carry those cannot-link constraints into both seed assignment and global merging.
        fun pair(a: Long, b: Long) = if (a < b) a to b else b to a
        val simultaneous = sorted.filter { it.overlap && it.localSpeakerKey != null }
        val cannotLink = HashSet<Pair<Long, Long>>()
        for (i in simultaneous.indices) {
            val first = simultaneous[i]
            val firstKey = first.localSpeakerKey!!
            for (j in i + 1 until simultaneous.size) {
                val second = simultaneous[j]
                if (second.startSample >= first.endSample) break
                val secondKey = second.localSpeakerKey ?: continue
                if (firstKey != secondKey && first.startSample < second.endSample)
                    cannotLink += pair(firstKey, secondKey)
            }
        }
        fun conflicts(first: Set<Long>, second: Set<Long>): Boolean =
            first.any { a -> second.any { b -> a != b && pair(a, b) in cannotLink } }

        val groups = ArrayList<Group>()
        // Preserve distinct voices before count-constrained clustering; early broad merges cannot be undone.
        val seedSimilarity = maxOf(0.8, similarity)
        for ((index, vector) in vectors.withIndex()) {
            if (vector == null) continue
            val key = sorted[index].localSpeakerKey
            val best = groups.indices
                .filter { groupIndex -> key == null || !conflicts(groups[groupIndex].localKeys, setOf(key)) }
                .maxByOrNull { cosine(vector, groups[it].centroid()) }
            val weight = (sorted[index].endSample - sorted[index].startSample).coerceIn(1, 48000).toDouble()
            if (best != null && cosine(vector, groups[best].centroid()) >= seedSimilarity) {
                val group = groups[best]
                for (dimension in vector.indices) group.sum[dimension] += vector[dimension] * weight
                group.weight += weight
                group.windows += sorted[index].startSample / 480000
                key?.let(group.localKeys::add)
            } else if (groups.size < 128) {
                groups.add(Group(
                    DoubleArray(vector.size) { vector[it] * weight },
                    weight,
                    mutableSetOf(sorted[index].startSample / 480000),
                    key?.let { mutableSetOf(it) } ?: mutableSetOf()
                ))
            }
            // Outliers beyond the bounded candidate set remain uncertain during assignment below.
        }
        while (groups.size > 1) {
            val centers = groups.map { it.centroid() }
            var best = -2.0
            var left = -1
            var right = -1
            for (a in centers.indices) for (b in a + 1 until centers.size) {
                if (conflicts(groups[a].localKeys, groups[b].localKeys)) continue
                val score = cosine(centers[a], centers[b])
                if (score > best) { best = score; left = a; right = b }
            }
            if (left < 0) break
            if (expectedSpeakers > 0) { if (groups.size <= expectedSpeakers) break }
            else if (best < similarity) break
            for (dimension in groups[left].sum.indices) groups[left].sum[dimension] += groups[right].sum[dimension]
            groups[left].weight += groups[right].weight
            groups[left].windows += groups[right].windows
            groups[left].localKeys += groups[right].localKeys
            groups.removeAt(right)
        }
        // A brief single-window outlier must not invent another participant.
        // A one-speaker / short recording still keeps its best-supported voice.
        val established = if (expectedSpeakers > 0) groups else groups.filter {
            it.windows.size >= 2 || it.weight >= 2 * 16000
        }.ifEmpty { groups.maxByOrNull { it.weight }?.let(::listOf).orEmpty() }
        val centers = established.map { it.centroid() }
        val ids = LinkedHashMap<Int, String>()
        val preliminary = sorted.mapIndexed { index, turn ->
            val vector = vectors[index]
            val key = turn.localSpeakerKey
            val candidates = if (vector == null) emptyList() else centers.indices
                .filter { group -> key == null || !conflicts(established[group].localKeys, setOf(key)) }
                .map { it to cosine(vector, centers[it]) }.sortedByDescending { it.second }
            val best = candidates.firstOrNull()
            val ambiguous = candidates.size > 1 && candidates[0].second - candidates[1].second < ambiguityMargin
            if (best == null || best.second < 0.35 || ambiguous) turn.copy(speakerId = "speaker_unknown")
            else {
                turn.copy(speakerId = ids.getOrPut(best.first) { "speaker_${ids.size + 1}" })
            }
        }
        // Pyannote speaker labels are local to a window. Clean spans with embeddings
        // establish the global identity for their lane; mixed audio is never embedded.
        val localSpeakerIds = preliminary.asSequence()
            .filter { !it.overlap && it.localSpeakerKey != null && it.speakerId != "speaker_unknown" }
            .groupBy { it.localSpeakerKey!! }
            .mapValues { (_, localTurns) ->
                localTurns.groupBy { it.speakerId }
                    .mapValues { (_, sameSpeaker) -> sameSpeaker.sumOf { (it.endSample - it.startSample).coerceAtLeast(0) } }
                    .maxByOrNull { it.value }!!.key
            }
        // Short clean fragments often cannot support their own embedding. Reuse the
        // global identity of a longer clean span from the same Pyannote lane and same
        // 30-second window; localSpeakerKey is window-scoped, so this never crosses windows.
        val withLaneSpeakers = preliminary.map { turn ->
            val key = turn.localSpeakerKey
            val laneSpeaker = key?.let(localSpeakerIds::get)
            if (laneSpeaker != null && (turn.overlap || turn.embedding == null))
                turn.copy(speakerId = laneSpeaker)
            else turn
        }
        // Correct brief, low-margin embedding flips inside an otherwise continuous turn.
        // Require agreement on both sides and a close embedding match; never smooth overlaps.
        return withLaneSpeakers.mapIndexed { index, turn ->
            val duration = turn.endSample - turn.startSample
            if (duration <= 0 || duration > 24000L || index == 0 || index == preliminary.lastIndex)
                return@mapIndexed turn
            val previous = withLaneSpeakers[index - 1]
            val next = withLaneSpeakers[index + 1]
            val gapBefore = turn.startSample - previous.endSample
            val gapAfter = next.startSample - turn.endSample
            if (previous.speakerId == "speaker_unknown" || previous.speakerId != next.speakerId ||
                previous.overlap || next.overlap || gapBefore !in 0..8000 || gapAfter !in 0..8000) {
                return@mapIndexed turn
            }
            // A brief overlap with the same voice on both sides is treated as that voice
            // continuing through cross-talk. Keep overlap=true so the UI still flags it.
            if (turn.overlap && duration <= 8000L) return@mapIndexed turn.copy(speakerId = previous.speakerId)
            if (turn.overlap) return@mapIndexed turn
            val targetGroup = ids.entries.firstOrNull { it.value == previous.speakerId }?.key
                ?: return@mapIndexed turn
            val vector = vectors[index] ?: return@mapIndexed turn
            val targetScore = cosine(vector, centers[targetGroup])
            val bestScore = centers.maxOfOrNull { cosine(vector, it) } ?: return@mapIndexed turn
            if (targetScore >= 0.30 && bestScore - targetScore <= 0.08)
                turn.copy(speakerId = previous.speakerId)
            else turn
        }
    }
}
