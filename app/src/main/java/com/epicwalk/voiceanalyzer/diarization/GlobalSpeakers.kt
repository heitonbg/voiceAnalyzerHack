package com.epicwalk.voiceanalyzer.diarization

import kotlin.math.sqrt

data class VoiceTurn(val startSample: Long, val endSample: Long, val embedding: FloatArray?,
    val overlap: Boolean = false, val speakerId: String = "speaker_unknown")

/** Global clustering across the recording, never a fresh speaker namespace per block. */
object GlobalSpeakers {
    private class Group(val sum: DoubleArray, var weight: Double) {
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

    fun assign(turns: List<VoiceTurn>, expectedSpeakers: Int = 0, similarity: Double = 0.55): List<VoiceTurn> {
        require(expectedSpeakers in 0..32 && similarity in 0.2..0.95)
        val sorted = turns.sortedBy { it.startSample }
        val vectors = sorted.map { if (it.overlap) null else it.embedding?.let(::normalized) }
        val groups = ArrayList<Group>()
        for ((index, vector) in vectors.withIndex()) {
            if (vector == null) continue
            val best = groups.indices.maxByOrNull { cosine(vector, groups[it].centroid()) }
            val weight = (sorted[index].endSample - sorted[index].startSample).coerceIn(1, 48000).toDouble()
            if (best != null && cosine(vector, groups[best].centroid()) >= similarity) {
                val group = groups[best]
                for (dimension in vector.indices) group.sum[dimension] += vector[dimension] * weight
                group.weight += weight
            } else if (groups.size < 64) {
                groups.add(Group(DoubleArray(vector.size) { vector[it] * weight }, weight))
            }
            // Outliers beyond the bounded candidate set remain uncertain during assignment below.
        }
        while (groups.size > 1) {
            val centers = groups.map { it.centroid() }
            var best = -2.0
            var left = 0
            var right = 1
            for (a in centers.indices) for (b in a + 1 until centers.size) {
                val score = cosine(centers[a], centers[b])
                if (score > best) { best = score; left = a; right = b }
            }
            if (expectedSpeakers > 0) { if (groups.size <= expectedSpeakers) break }
            else if (best < similarity) break
            for (dimension in groups[left].sum.indices) groups[left].sum[dimension] += groups[right].sum[dimension]
            groups[left].weight += groups[right].weight
            groups.removeAt(right)
        }
        val centers = groups.map { it.centroid() }
        val ids = LinkedHashMap<Int, String>()
        return sorted.mapIndexed { index, turn ->
            val vector = vectors[index]
            val candidates = if (vector == null) emptyList() else centers.indices
                .map { it to cosine(vector, centers[it]) }.sortedByDescending { it.second }
            val best = candidates.firstOrNull()
            val ambiguous = candidates.size > 1 && candidates[0].second - candidates[1].second < 0.035
            val speaker = if (best == null || best.second < 0.35 || ambiguous) "speaker_unknown"
                else ids.getOrPut(best.first) { "speaker_${ids.size + 1}" }
            turn.copy(speakerId = speaker)
        }
    }
}
