package com.epicwalk.voiceanalyzer.diarization

import kotlin.math.*

/** Opt-in research only. No reference labels or expected speaker count enter clustering. */
object ExperimentalGlobalClustering {
    enum class Method { ADAPTIVE_AHC, SPECTRAL }
    data class Result(val turns: List<VoiceTurn>, val clusters: Int, val threshold: Double?)

    fun assign(input: List<VoiceTurn>, method: Method): Result {
        val turns = input.sortedBy { it.startSample }
        fun normalize(v: FloatArray?): DoubleArray? {
            if (v == null || v.isEmpty() || v.any { !it.isFinite() }) return null
            val norm = sqrt(v.sumOf { it.toDouble() * it })
            return if (norm < 1e-8) null else DoubleArray(v.size) { v[it] / norm }
        }
        // Bound quadratic matrices and avoid giving short, overlapping speech equal weight.
        val eligible = turns.indices.filter {
            !turns[it].overlap && turns[it].endSample - turns[it].startSample >= 16000 &&
                normalize(turns[it].embedding) != null
        }
        if (eligible.isEmpty()) return Result(turns.map { it.copy(speakerId = "speaker_unknown") }, 0, null)
        val dimensions = turns[eligible.first()].embedding!!.size
        val valid = eligible.filter { turns[it].embedding!!.size == dimensions }
        // Deterministic evenly distributed anchors preserve evidence from the end of long files.
        val anchors = if (valid.size <= 64) valid else (0 until 64).map {
            valid[(it.toLong() * (valid.size - 1) / 63).toInt()]
        }
        val vectors = anchors.map { normalize(turns[it].embedding)!! }
        fun dot(a: DoubleArray, b: DoubleArray) = a.indices.sumOf { a[it] * b[it] }
        val n = vectors.size
        val sim = Array(n) { i -> DoubleArray(n) { j -> dot(vectors[i], vectors[j]) } }
        val nearest = (0 until n).map { i ->
            (0 until n).filter { it != i }.maxOfOrNull { sim[i][it] } ?: 1.0
        }.sorted()
        val threshold = if (n > 1) (nearest[n / 2] - 0.12).coerceIn(0.45, 0.75) else 0.75
        val labels = when (method) {
            Method.ADAPTIVE_AHC -> {
                val groups = MutableList(n) { mutableListOf(it) }
                while (groups.size > 1) {
                    var best = -2.0; var left = -1; var right = -1
                    for (a in groups.indices) for (b in a + 1 until groups.size) {
                        val score = groups[a].sumOf { i -> groups[b].sumOf { j -> sim[i][j] } } /
                            (groups[a].size * groups[b].size)
                        if (score > best) { best = score; left = a; right = b }
                    }
                    if (left < 0 || best < threshold) break
                    groups[left].addAll(groups[right]); groups.removeAt(right)
                }
                IntArray(n).also { out -> groups.forEachIndexed { k, group -> group.forEach { out[it] = k } } }
            }
            Method.SPECTRAL -> spectral(sim)
        }
        val groups = labels.indices.groupBy { labels[it] }.values
        val retained = groups.filter { group ->
            group.size >= 2 || turns[anchors[group.first()]].endSample - turns[anchors[group.first()]].startSample >= 48000
        }.ifEmpty { listOf(groups.maxBy { it.size }) }
        val centers = retained.map { group ->
            val sum = DoubleArray(dimensions)
            group.forEach { i -> vectors[i].indices.forEach { d -> sum[d] += vectors[i][d] } }
            val norm = sqrt(sum.sumOf { it * it }).coerceAtLeast(1e-8)
            DoubleArray(dimensions) { sum[it] / norm }
        }
        val ids = LinkedHashMap<Int, String>()
        val assigned = turns.map { turn ->
            val vector = if (!turn.overlap) normalize(turn.embedding) else null
            if (vector == null || vector.size != dimensions) turn.copy(speakerId = "speaker_unknown")
            else {
                val scores = centers.indices.map { it to dot(vector, centers[it]) }.sortedByDescending { it.second }
                val best = scores.first()
                if (best.second < 0.4 || (scores.size > 1 && best.second - scores[1].second < 0.03))
                    turn.copy(speakerId = "speaker_unknown")
                else turn.copy(speakerId = ids.getOrPut(best.first) { "speaker_${ids.size + 1}" })
            }
        }
        // A lane can label a short fragment only if its clean evidence agrees.
        val laneIds = assigned.filter { !it.overlap && it.localSpeakerKey != null && it.speakerId != "speaker_unknown" }
            .groupBy { it.localSpeakerKey!! }.mapValues { (_, spans) ->
                spans.map { it.speakerId }.distinct().singleOrNull()
            }
        val result = assigned.map { turn ->
            if (turn.embedding == null && !turn.overlap)
                turn.copy(speakerId = turn.localSpeakerKey?.let { laneIds[it] } ?: "speaker_unknown")
            else turn
        }
        return Result(result, result.map { it.speakerId }.filter { it != "speaker_unknown" }.distinct().size,
            if (method == Method.ADAPTIVE_AHC) threshold else null)
    }

    /** Symmetric normalized affinity; Jacobi eigensolver and eigengap choose K automatically. */
    private fun spectral(sim: Array<DoubleArray>): IntArray {
        val n = sim.size
        if (n == 1) return intArrayOf(0)
        val affinity = Array(n) { i -> DoubleArray(n) { j ->
            if (i == j) 0.0 else max(0.0, sim[i][j] - 0.3).pow(3)
        } }
        val degree = DoubleArray(n) { i -> affinity[i].sum().coerceAtLeast(1e-12) }
        val matrix = Array(n) { i -> DoubleArray(n) { j ->
            (if (i == j) 1.0 else 0.0) - affinity[i][j] / sqrt(degree[i] * degree[j])
        } }
        val eigenvectors = Array(n) { i -> DoubleArray(n) { j -> if (i == j) 1.0 else 0.0 } }
        for (iteration in 0 until 8 * n * n) {
            var max = 0.0; var p = 0; var q = 0
            for (i in 0 until n) for (j in i + 1 until n) if (abs(matrix[i][j]) > max) {
                max = abs(matrix[i][j]); p = i; q = j
            }
            if (max < 1e-8) break
            val angle = 0.5 * atan2(2 * matrix[p][q], matrix[q][q] - matrix[p][p])
            val c = cos(angle); val s = sin(angle)
            val app = matrix[p][p]; val aqq = matrix[q][q]; val apq = matrix[p][q]
            matrix[p][p] = c*c*app - 2*s*c*apq + s*s*aqq
            matrix[q][q] = s*s*app + 2*s*c*apq + c*c*aqq
            matrix[p][q] = 0.0; matrix[q][p] = 0.0
            for (i in 0 until n) {
                if (i != p && i != q) {
                    val a = matrix[i][p]; val b = matrix[i][q]
                    matrix[i][p] = c*a - s*b; matrix[p][i] = matrix[i][p]
                    matrix[i][q] = s*a + c*b; matrix[q][i] = matrix[i][q]
                }
                val a = eigenvectors[i][p]; val b = eigenvectors[i][q]
                eigenvectors[i][p] = c*a - s*b; eigenvectors[i][q] = s*a + c*b
            }
        }
        val order = (0 until n).sortedBy { matrix[it][it] }
        val maxK = minOf(12, n - 1)
        val k = (1..maxK).maxBy { matrix[order[it]][order[it]] - matrix[order[it - 1]][order[it - 1]] }
        val points = Array(n) { i ->
            val v = DoubleArray(k) { eigenvectors[i][order[it]] }
            val norm = sqrt(v.sumOf { it * it }).coerceAtLeast(1e-12)
            DoubleArray(k) { v[it] / norm }
        }
        val seeds = mutableListOf(0)
        while (seeds.size < k) seeds += (0 until n).filter { it !in seeds }.maxBy { i ->
            seeds.minOf { j -> points[i].indices.sumOf { d -> (points[i][d] - points[j][d]).pow(2) } }
        }
        val centers = seeds.map { points[it].copyOf() }.toTypedArray()
        val labels = IntArray(n)
        repeat(30) {
            for (i in 0 until n) labels[i] = centers.indices.minBy { c ->
                points[i].indices.sumOf { d -> (points[i][d] - centers[c][d]).pow(2) }
            }
            for (c in centers.indices) {
                val members = points.indices.filter { labels[it] == c }
                if (members.isNotEmpty()) for (d in 0 until k) centers[c][d] = members.sumOf { points[it][d] } / members.size
            }
        }
        return labels
    }
}
