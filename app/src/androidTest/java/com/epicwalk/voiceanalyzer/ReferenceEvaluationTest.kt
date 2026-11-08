package com.epicwalk.voiceanalyzer

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.epicwalk.voiceanalyzer.data.Utterance
import com.epicwalk.voiceanalyzer.core.TranscriptionPipeline
import com.epicwalk.voiceanalyzer.diarization.BoundedDiarization
import com.epicwalk.voiceanalyzer.diarization.GlobalSpeakers
import com.epicwalk.voiceanalyzer.diarization.VoiceTurn
import com.epicwalk.voiceanalyzer.diarization.WordCheckpoint
import android.util.Base64
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class ReferenceEvaluationTest {
    private data class ReferenceTurn(val speaker: String, val startMs: Long, val text: String)

    @Test fun compareCurrentTranscriptToLocalReferenceWithoutExportingSpeech() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("evaluateReference") == "true")
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as ScribeApplication
        app.startup.await()
        val referenceFile = File(app.getExternalFilesDir(null), "reference.txt")
        assumeTrue("Copy the user-provided reference to app external files before opt-in run", referenceFile.isFile)
        val references = parse(referenceFile.readLines(Charsets.UTF_8))
        assertTrue("Reference transcript has no timestamped turns", references.isNotEmpty())

        assertTrue(app.modelPreparation.await())
        val conversation = app.database.dao().observeConversations().first()
            .firstOrNull { it.title.contains("Подорожник", ignoreCase = true) && it.status == "COMPLETE" }
        assumeTrue("Import and process Подорожник.m4a first", conversation != null)
        val item = conversation!!
        val referenceSpeakerCount = references.map { it.speaker }.distinct().size
        val expectedSpeakers = InstrumentationRegistry.getArguments().getString("expectedSpeakers")
            ?.toIntOrNull()?.coerceIn(0, 32) ?: referenceSpeakerCount
        val freshInference = InstrumentationRegistry.getArguments().getString("freshReference") != "false"
        val preferences = app.getSharedPreferences("processing", 0)
        val previousExpected = preferences.getInt("expected-speakers", 0)
        val embeddingModel = "titanet.onnx"
        var firstPhraseMs = -1L
        var reprocessWallMs = -1L
        var cpuElapsedMs = -1L
        val peakPssKiB = java.util.concurrent.atomic.AtomicLong(android.os.Debug.getPss().toLong())
        if (freshInference) {
            var reprocessStarted = 0L
            var sampler: Job? = null
            try {
                preferences.edit().putInt("expected-speakers", expectedSpeakers).commit()
                app.models.file(embeddingModel)
                // Force a true fresh evaluation pass; otherwise final rows/checkpoint may be reused.
                app.database.dao().archiveTranscription(item.id, "reference-previous:" + System.currentTimeMillis())
                File(app.repository.audioDirectory, item.id + ".diarization.json").delete()
                app.repository.audioDirectory.listFiles()?.filter { it.name.startsWith(item.id + ".words-") }
                    ?.forEach { it.deleteRecursively() }
                reprocessStarted = android.os.SystemClock.elapsedRealtime()
                val cpuStarted = android.os.Process.getElapsedCpuTime()
                sampler = launch(Dispatchers.Default) {
                    while (isActive) {
                        peakPssKiB.updateAndGet { maxOf(it, android.os.Debug.getPss().toLong()) }
                        delay(200)
                    }
                }
                TranscriptionPipeline(app).process(item.id) { state ->
                    if (state.stage == "Распознавание речи" && firstPhraseMs < 0)
                        firstPhraseMs = android.os.SystemClock.elapsedRealtime() - reprocessStarted
                }
                reprocessWallMs = android.os.SystemClock.elapsedRealtime() - reprocessStarted
                cpuElapsedMs = android.os.Process.getElapsedCpuTime() - cpuStarted
            } finally {
                sampler?.cancelAndJoin()
                preferences.edit().putInt("expected-speakers", previousExpected).commit()
            }
        }
        val rows = app.database.dao().utterances(item.id)
            .filter { it.sourcePass == "diarized" }
            .sortedWith(compareBy<Utterance> { it.startMs }.thenBy { it.endMs })
        assertTrue(rows.isNotEmpty())

        val referenceText = references.joinToString(" ") { it.text }
        val hypothesisText = rows.joinToString(" ") { it.editedText ?: it.rawText }
        val expectedWords = tokens(referenceText)
        val actualWords = tokens(hypothesisText)
        val edits = wordEdits(expectedWords, actualWords)
        val exactWordPairs = alignedWordPairs(expectedWords, actualWords)
        val referencePunctuation = punctuationAfterWords(referenceText)
        val hypothesisPunctuation = punctuationAfterWords(hypothesisText)
        val punctuationMatches = exactWordPairs.count { (refIndex, hypIndex) ->
            referencePunctuation.getOrNull(refIndex) == hypothesisPunctuation.getOrNull(hypIndex)
        }
        val punctuationAccuracy = exactWordPairs.size.takeIf { it > 0 }
            ?.let { punctuationMatches.toDouble() / it } ?: Double.NaN
        fun punctuationCounts(marks: List<String>) = JSONObject().also { counts ->
            marks.filter { it.isNotEmpty() }.groupingBy { it }.eachCount()
                .forEach { (mark, count) -> counts.put(mark, count) }
        }
        val starts = references.mapNotNull { ref ->
            val containing = rows.firstOrNull { it.startMs <= ref.startMs && it.endMs > ref.startMs }
            val nearest = containing ?: rows.minByOrNull { abs(it.startMs - ref.startMs) }
                ?.takeIf { abs(it.startMs - ref.startMs) <= 5000 }
            nearest?.let { Triple(ref, it, abs(it.startMs - ref.startMs)) }
        }
        val mapping = bestSpeakerMapping(references, starts)
        val correctSpeaker = starts.count { (ref, row, _) ->
            mapping[ref.speaker] != null && mapping[ref.speaker] == (row.editedSpeakerId ?: row.speakerId)
        }
        val meanStartError = starts.map { it.third }.average().takeIf { it.isFinite() } ?: 0.0
        val speakerConfusion = JSONObject()
        for (referenceSpeaker in references.map { it.speaker }.distinct()) {
            val counts = JSONObject()
            starts.filter { it.first.speaker == referenceSpeaker }
                .groupingBy { it.second.editedSpeakerId ?: it.second.speakerId }
                .eachCount()
                .forEach { (speakerId, count) -> counts.put(speakerId, count) }
            speakerConfusion.put(referenceSpeaker, counts)
        }
        fun speakerAt(row: Utterance, voices: List<VoiceTurn>): String {
            val start = row.startMs * 16
            val end = row.endMs * 16
            return voices.asSequence().map { voice ->
                voice to (minOf(voice.endSample, end) - maxOf(voice.startSample, start)).coerceAtLeast(0)
            }.filter { it.second > 0 }
                .maxByOrNull { it.second }?.first?.speakerId ?: "speaker_unknown"
        }
        val thresholdSweep = JSONObject()
        var ambiguitySweep = JSONArray()
        val checkpoint = File(app.repository.audioDirectory, item.id + ".diarization.json")
        var forwardVoiceTurns: List<VoiceTurn> = emptyList()
        if (checkpoint.isFile) {
            val saved = JSONObject(checkpoint.readText()).getJSONArray("turns")
            val voiceTurns = (0 until saved.length()).map { index ->
                val turn = saved.getJSONObject(index)
                val embedding = if (turn.has("vector")) {
                    val bytes = Base64.decode(turn.getString("vector"), Base64.NO_WRAP)
                    val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                    FloatArray(buffer.remaining() / 4) { buffer.float }
                } else null
                VoiceTurn(
                    turn.getLong("start"), turn.getLong("end"), embedding, turn.getBoolean("overlap"),
                    localSpeakerKey = turn.takeIf { it.has("localKey") }?.getLong("localKey")
                )
            }
            forwardVoiceTurns = voiceTurns
            for (threshold in listOf(0.35, 0.4, 0.45, 0.5, 0.55, 0.6, 0.65, 0.7)) {
                val reassigned = GlobalSpeakers.assign(voiceTurns, expectedSpeakers = 0, similarity = threshold)
                val candidateMatches = starts.map { (ref, row, error) ->
                    Triple(ref, row.copy(speakerId = speakerAt(row, reassigned), editedSpeakerId = null), error)
                }
                val candidateMapping = bestSpeakerMapping(references, candidateMatches)
                val correct = candidateMatches.count { (ref, row, _) ->
                    candidateMapping[ref.speaker] == row.speakerId
                }
                thresholdSweep.put(threshold.toString(), JSONObject()
                    .put("speaker_onset_accuracy_after_id_permutation",
                        if (candidateMatches.isEmpty()) JSONObject.NULL else correct.toDouble() / candidateMatches.size)
                    .put("clusters", reassigned.map { it.speakerId }.filter { it != "speaker_unknown" }.distinct().size)
                    .put("unknown_voice_segments", reassigned.count { it.speakerId == "speaker_unknown" }))
            }
            val margins = JSONArray()
            for (margin in listOf(0.0, 0.01, 0.02, 0.035, 0.05, 0.08)) {
                val reassigned = GlobalSpeakers.assign(
                    voiceTurns, expectedSpeakers = expectedSpeakers, ambiguityMargin = margin
                )
                val candidateMatches = starts.map { (ref, row, error) ->
                    Triple(ref, row.copy(speakerId = speakerAt(row, reassigned), editedSpeakerId = null), error)
                }
                val candidateMapping = bestSpeakerMapping(references, candidateMatches)
                val correct = candidateMatches.count { (ref, row, _) ->
                    candidateMapping[ref.speaker] == row.speakerId
                }
                margins.put(JSONObject()
                    .put("margin", margin)
                    .put("speaker_onset_accuracy_after_id_permutation",
                        if (candidateMatches.isEmpty()) JSONObject.NULL else correct.toDouble() / candidateMatches.size)
                    .put("clusters", reassigned.map { it.speakerId }.filter { it != "speaker_unknown" }.distinct().size)
                    .put("unknown_voice_segments", reassigned.count { it.speakerId == "speaker_unknown" }))
            }
            ambiguitySweep = margins
        }
        val wordDirectory = app.repository.audioDirectory.listFiles()
            ?.filter { it.isDirectory && it.name.startsWith(item.id + ".words-") }
            ?.maxByOrNull { it.lastModified() }
        val wordChunks = wordDirectory?.let { directory ->
            val store = WordCheckpoint(directory)
            store.files().map(store::read)
        }.orEmpty()
        if (InstrumentationRegistry.getArguments().getString("wholeFileExperiment") == "true") {
            val experiment = evaluateWholeFile(app, item.id, references, wordChunks)
            File(app.getExternalFilesDir(null), "whole-file-diarization.json")
                .writeText(experiment.toString(2))
        }
        // Audit saved evidence only. No model is run and no reference label enters production.
        val auditedVoices = if (forwardVoiceTurns.isEmpty()) emptyList()
            else GlobalSpeakers.assign(forwardVoiceTurns, expectedSpeakers)
        val wordPhrases = wordChunks.flatMap { chunk ->
            com.epicwalk.voiceanalyzer.diarization.WordSpeakerPlanner.plan(
                chunk.result.words, chunk.offset, chunk.start, chunk.end, auditedVoices)
        }
        val auditCounts = linkedMapOf<String, Int>()
        fun csv(value: Any?): String = "\"" + (value?.toString() ?: "").replace("\"", "\"\"") + "\""
        val csvRows = StringBuilder(
            "reference_start_ms,reference_speaker,local_track,global_voice_id,word_id,final_id,overlap,time_delta_ms,class\n")
        for (ref in references) {
            val sample = ref.startMs * 16
            val voice = auditedVoices.filter { it.startSample <= sample && it.endSample > sample }
                .maxByOrNull { it.endSample - it.startSample }
            val phrase = wordPhrases.firstOrNull { it.startSample <= sample && it.endSample > sample }
                ?: wordPhrases.minByOrNull { abs(it.startSample - sample) }
                    ?.takeIf { abs(it.startSample - sample) <= 80000 }
            val row = rows.firstOrNull { it.startMs <= ref.startMs && it.endMs > ref.startMs }
                ?: rows.minByOrNull { abs(it.startMs - ref.startMs) }
                    ?.takeIf { abs(it.startMs - ref.startMs) <= 5000 }
            val finalId = row?.editedSpeakerId ?: row?.speakerId ?: "speaker_unknown"
            val className = when {
                row == null -> "no_final_row_within_5s"
                voice == null -> "no_voice_interval_at_onset"
                voice.overlap -> "overlap_at_onset"
                voice.speakerId == "speaker_unknown" -> "global_voice_unknown"
                phrase == null -> "no_timed_word_near_onset"
                phrase.speakerId == "speaker_unknown" -> "word_assignment_unknown"
                finalId == "speaker_unknown" -> "final_assignment_unknown"
                else -> "label_comparison_requires_id_mapping"
            }
            auditCounts[className] = (auditCounts[className] ?: 0) + 1
            csvRows.append(listOf(ref.startMs, ref.speaker, voice?.localSpeakerKey,
                voice?.speakerId, phrase?.speakerId, finalId, voice?.overlap,
                row?.let { abs(it.startMs - ref.startMs) }, className)
                .joinToString(",") { csv(it) }).append('\n')
        }
        File(app.getExternalFilesDir(null), "reference-stage-audit.csv").writeText(csvRows.toString())
        val replay = JSONArray()
        for ((label, count, threshold) in listOf(
            Triple("auto_035", 0, 0.35), Triple("auto_040", 0, 0.40),
            Triple("auto_045", 0, 0.45), Triple("auto_050", 0, 0.50),
            Triple("fixed_4", 4, 0.40), Triple("adaptive_ahc", 0, 0.40),
            Triple("spectral_auto", 0, 0.40)
        )) {
            val started = android.os.SystemClock.elapsedRealtime()
            val assigned = when (label) {
                "adaptive_ahc" -> com.epicwalk.voiceanalyzer.diarization.ExperimentalGlobalClustering.assign(
                    forwardVoiceTurns, com.epicwalk.voiceanalyzer.diarization.ExperimentalGlobalClustering.Method.ADAPTIVE_AHC).turns
                "spectral_auto" -> com.epicwalk.voiceanalyzer.diarization.ExperimentalGlobalClustering.assign(
                    forwardVoiceTurns, com.epicwalk.voiceanalyzer.diarization.ExperimentalGlobalClustering.Method.SPECTRAL).turns
                else -> GlobalSpeakers.assign(forwardVoiceTurns, count, threshold)
            }
            val clusteringMs = android.os.SystemClock.elapsedRealtime() - started
            var pending: Utterance? = null
            val candidateRows = ArrayList<Utterance>()
            for (chunk in wordChunks) {
                val phrases = if (chunk.result.words.isNotEmpty())
                    com.epicwalk.voiceanalyzer.diarization.WordSpeakerPlanner.plan(
                        chunk.result.words, chunk.offset, chunk.start, chunk.end, assigned)
                else if (chunk.result.text.isNotBlank())
                    listOf(com.epicwalk.voiceanalyzer.diarization.SpokenPhrase(
                        chunk.start, chunk.end, "speaker_unknown", chunk.result.text, false))
                else emptyList()
                for (phrase in phrases) {
                    val next = Utterance(
                        label + ":" + chunk.start + ":" + phrase.startSample,
                        item.id, phrase.speakerId, phrase.startSample / 16,
                        maxOf(phrase.startSample / 16 + 1, phrase.endSample / 16),
                        phrase.text, sourcePass = "diarized", overlap = phrase.overlap,
                        speakerCandidates = phrase.candidateSpeakerIds
                            .takeIf { it.size > 1 }?.joinToString("|"))
                    val merged = pending?.let {
                        com.epicwalk.voiceanalyzer.data.UtteranceMerger.merge(it, next)
                    }
                    if (merged != null) pending = merged
                    else { pending?.let(candidateRows::add); pending = next }
                }
            }
            pending?.let(candidateRows::add)
            val candidateStarts = references.mapNotNull { ref ->
                val row = candidateRows.firstOrNull {
                    it.startMs <= ref.startMs && it.endMs > ref.startMs
                } ?: candidateRows.minByOrNull { abs(it.startMs - ref.startMs) }
                    ?.takeIf { abs(it.startMs - ref.startMs) <= 5000 }
                row?.let { Triple(ref, it, abs(it.startMs - ref.startMs)) }
            }
            val candidateMapping = bestSpeakerMapping(references, candidateStarts)
            val hits = candidateStarts.count { (ref, row, _) ->
                candidateMapping[ref.speaker] == row.speakerId
            }
            replay.put(JSONObject().put("configuration", label).put("clustering_ms", clusteringMs)
                .put("pss_after_kib", android.os.Debug.getPss()).put("onset_hits", hits)
                .put("matched_onsets", candidateStarts.size)
                .put("rows", candidateRows.size)
                .put("unknown_rows", candidateRows.count { it.speakerId == "speaker_unknown" })
                .put("unknown_duration_ms", candidateRows.filter {
                    it.speakerId == "speaker_unknown"
                }.sumOf { (it.endMs - it.startMs).coerceAtLeast(0) })
                .put("clusters", assigned.map { it.speakerId }
                    .filter { it != "speaker_unknown" }.distinct().size))
        }
        File(app.getExternalFilesDir(null), "reference-replay.json").writeText(replay.toString(2))
        val bidirectionalExperiment = if (
            InstrumentationRegistry.getArguments().getString("bidirectionalExperiment") == "true" &&
            forwardVoiceTurns.isNotEmpty()
        ) {
            val sourcePcm = File(app.repository.audioDirectory, item.id + ".pcm")
            val reverseFile = File(app.cacheDir, "reference-reverse.pcm")
            val reverseCheckpoint = File(app.cacheDir, "reference-reverse-diarization.json")
            val sampleCount = reversePcm(sourcePcm, reverseFile)
            val reverseStarted = android.os.SystemClock.elapsedRealtime()
            val reverseTurns = BoundedDiarization(app.models).use { engine ->
                engine.analyze(reverseFile, reverseCheckpoint, "reference-reverse-experiment-v1",
                    expectedSpeakers, {}, {})
            }.map { turn ->
                turn.copy(startSample = sampleCount - turn.endSample, endSample = sampleCount - turn.startSample,
                    localSpeakerKey = turn.localSpeakerKey?.or(1L shl 56))
            }
            val fused = GlobalSpeakers.assign(forwardVoiceTurns + reverseTurns, expectedSpeakers = expectedSpeakers)
            val forwardAssigned = GlobalSpeakers.assign(forwardVoiceTurns, expectedSpeakers = expectedSpeakers)
            val reverseAssigned = GlobalSpeakers.assign(reverseTurns, expectedSpeakers = expectedSpeakers)
            val directionMap = matchSpeakerCentroids(forwardAssigned, reverseAssigned)
            fun evaluateDirectionRule(rule: String): JSONObject {
                val predictions = starts.map { (ref, row, error) ->
                    val forwardId = speakerAt(row, forwardAssigned)
                    val reverseId = directionMap[speakerAt(row, reverseAssigned)] ?: "speaker_unknown"
                    val selected = when (rule) {
                        "forward_then_reverse_fill" -> if (forwardId != "speaker_unknown") forwardId else reverseId
                        else -> if (forwardId != "speaker_unknown" && forwardId == reverseId) forwardId else "speaker_unknown"
                    }
                    Triple(ref, row.copy(speakerId = selected, editedSpeakerId = null), error)
                }
                val mappingForRule = bestSpeakerMapping(references, predictions)
                val correct = predictions.count { (ref, row, _) -> mappingForRule[ref.speaker] == row.speakerId }
                return JSONObject()
                    .put("rule", rule)
                    .put("speaker_onset_accuracy_after_id_permutation",
                        if (predictions.isEmpty()) JSONObject.NULL else correct.toDouble() / predictions.size)
                    .put("unknown_at_reference_onsets", predictions.count { it.second.speakerId == "speaker_unknown" })
            }
            val candidates = starts.map { (ref, row, error) ->
                Triple(ref, row.copy(speakerId = speakerAt(row, fused), editedSpeakerId = null), error)
            }
            val candidateMapping = bestSpeakerMapping(references, candidates)
            val matched = candidates.count { (ref, row, _) -> candidateMapping[ref.speaker] == row.speakerId }
            JSONObject()
                .put("strategy", "sequential_forward_reverse_pyannote_titanet_fusion")
                .put("reverse_pass_ms", android.os.SystemClock.elapsedRealtime() - reverseStarted)
                .put("forward_voice_turns", forwardVoiceTurns.size)
                .put("reverse_voice_turns", reverseTurns.size)
                .put("fused_clusters", fused.map { it.speakerId }.filter { it != "speaker_unknown" }.distinct().size)
                .put("fused_unknown_turns", fused.count { it.speakerId == "speaker_unknown" })
                .put("direction_rule_sweep", JSONArray()
                    .put(evaluateDirectionRule("forward_then_reverse_fill"))
                    .put(evaluateDirectionRule("require_direction_agreement")))
                .put("speaker_onsets_matched", candidates.size)
                .put("speaker_onset_accuracy_after_id_permutation",
                    if (candidates.isEmpty()) JSONObject.NULL else matched.toDouble() / candidates.size)
                .put("mean_nearest_start_error_ms",
                    candidates.map { it.third }.average().takeIf { it.isFinite() } ?: 0.0)
                .put("note", "Diagnostic candidate only; reverse pass does not alter saved transcript.")
                .also { reverseFile.delete(); reverseCheckpoint.delete() }
        } else JSONObject.NULL

        val summary = JSONObject()
            .put("duration_ms", item.durationMs)
            .put("bidirectional_experiment", bidirectionalExperiment)
            .put("expected_speakers_for_this_run", expectedSpeakers)
            .put("speaker_embedding_model", embeddingModel)
            .put("fresh_inference", freshInference)
            .put("reprocess_wall_ms", if (freshInference) reprocessWallMs else JSONObject.NULL)
            .put("first_phrase_ms", firstPhraseMs)
            .put("cpu_elapsed_ms", if (freshInference) cpuElapsedMs else JSONObject.NULL)
            .put("sampled_peak_pss_kib", if (freshInference) peakPssKiB.get() else JSONObject.NULL)
            .put("pss_sampling_interval_ms", 200)
            .put("reference_turns", references.size)
            .put("reference_speakers", references.map { it.speaker }.distinct().size)
            .put("hypothesis_rows", rows.size)
            .put("overlap_rows", rows.count { it.overlap })
            .put("overlap_rows_with_multiple_candidates", rows.count {
                it.speakerCandidates?.split("|")?.distinct()?.size?.let { size -> size > 1 } == true
            })
            .put("recognition_chunks", wordChunks.size)
            .put("chunks_with_native_word_times", wordChunks.count { it.result.words.isNotEmpty() })
            .put("native_timed_words", wordChunks.sumOf { it.result.words.size })
            .put("hypothesis_speakers", rows.map { it.editedSpeakerId ?: it.speakerId }
                .filter { it != "speaker_unknown" }.distinct().size)
            .put("unknown_rows", rows.count { (it.editedSpeakerId ?: it.speakerId) == "speaker_unknown" })
            .put("reference_words", expectedWords.size)
            .put("hypothesis_words", actualWords.size)
            .put("word_edits", edits)
            .put("wer", if (expectedWords.isEmpty()) JSONObject.NULL else edits.toDouble() / expectedWords.size)
            .put("punctuation_aligned_exact_words", exactWordPairs.size)
            .put("punctuation_exact_boundary_matches", punctuationMatches)
            .put("punctuation_boundary_accuracy",
                if (punctuationAccuracy.isFinite()) punctuationAccuracy else JSONObject.NULL)
            .put("reference_punctuation_counts", punctuationCounts(referencePunctuation))
            .put("hypothesis_punctuation_counts", punctuationCounts(hypothesisPunctuation))
            .put("speaker_onsets_matched", starts.size)
            .put("speaker_onset_accuracy_after_id_permutation",
                if (starts.isEmpty()) JSONObject.NULL else correctSpeaker.toDouble() / starts.size)
            .put("mean_nearest_start_error_ms", meanStartError)
            .put("speaker_confusion_counts", speakerConfusion)
            .put("stage_audit_evidence_counts", JSONObject(auditCounts as Map<*, *>))
            .put("speaker_threshold_sweep", thresholdSweep)
            .put("speaker_ambiguity_sweep", ambiguitySweep)
            .put("note", "Local reference comparison only; not training. Speech and reference text are not written to this report.")
        File(app.getExternalFilesDir(null), "reference-evaluation.json").writeText(summary.toString(2))
    }

    /** One native process() call per complete recording, for an opt-in Android experiment. */
    private suspend fun evaluateWholeFile(
        app: ScribeApplication, id: String, references: List<ReferenceTurn>,
        chunks: List<com.epicwalk.voiceanalyzer.diarization.WordChunk>
    ): JSONArray {
        val pcm = File(app.repository.audioDirectory, id + ".pcm")
        require(pcm.isFile && pcm.length() % 2L == 0L)
        val sampleCount = pcm.length() / 2L
        require(sampleCount in 1..(30L * 60 * 16000)) { "Experiment limited to 30 minutes" }
        val samples = FloatArray(sampleCount.toInt())
        val readStarted = android.os.SystemClock.elapsedRealtime()
        java.io.FileInputStream(pcm).use { input ->
            val bytes = ByteArray(64 * 1024)
            var at = 0
            while (at < samples.size) {
                val count = minOf(bytes.size / 2, samples.size - at)
                var filled = 0
                while (filled < count * 2) {
                    val got = input.read(bytes, filled, count * 2 - filled)
                    check(got > 0) { "Unexpected PCM EOF" }
                    filled += got
                }
                for (index in 0 until count) {
                    val low = bytes[index * 2].toInt() and 255
                    val high = bytes[index * 2 + 1].toInt() shl 8
                    samples[at + index] = ((low or high).toShort()) / 32768f
                }
                at += count
            }
        }
        val readMs = android.os.SystemClock.elapsedRealtime() - readStarted
        val output = JSONArray()
        val arguments = InstrumentationRegistry.getArguments()
        val selectedClusters = when {
            arguments.getString("wholeFileFixedOnly") == "true" -> listOf(4)
            arguments.getString("wholeFileAutoOnly") == "true" -> listOf(-1)
            else -> listOf(-1, 4)
        }
        for (clusters in selectedClusters) {
            val peakPss = java.util.concurrent.atomic.AtomicLong(android.os.Debug.getPss().toLong())
            val peakRss = java.util.concurrent.atomic.AtomicLong(0L)
            fun rssKiB(): Long = runCatching {
                File("/proc/self/status").readLines()
                    .first { it.startsWith("VmRSS:") }.split(Regex("\\s+"))[1].toLong()
            }.getOrDefault(-1L)
            val sampler = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).launch {
                while (isActive) {
                    peakPss.updateAndGet { maxOf(it, android.os.Debug.getPss().toLong()) }
                    peakRss.updateAndGet { maxOf(it, rssKiB()) }
                    delay(200)
                }
            }
            var loadMs = -1L
            var processMs = -1L
            var segmentCount = 0
            var rows: List<Utterance> = emptyList()
            var voices: List<VoiceTurn> = emptyList()
            try {
                val started = android.os.SystemClock.elapsedRealtime()
                val engine = com.k2fsa.sherpa.onnx.OfflineSpeakerDiarization(
                    config = com.k2fsa.sherpa.onnx.OfflineSpeakerDiarizationConfig(
                        segmentation = com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationModelConfig(
                            pyannote = com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationPyannoteModelConfig(
                                app.models.file("sherpa-onnx-pyannote-segmentation-3-0/model.onnx").absolutePath),
                            numThreads = 1, provider = "cpu"),
                        embedding = com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig(
                            model = app.models.file("titanet.onnx").absolutePath,
                            numThreads = 1, provider = "cpu"),
                        clustering = com.k2fsa.sherpa.onnx.FastClusteringConfig(
                            numClusters = clusters, threshold = 0.5f)))
                loadMs = android.os.SystemClock.elapsedRealtime() - started
                try {
                    val processStarted = android.os.SystemClock.elapsedRealtime()
                    val segments = engine.process(samples)
                    processMs = android.os.SystemClock.elapsedRealtime() - processStarted
                    segmentCount = segments.size
                    val nativeRows = segments.map { segment ->
                        VoiceTurn((segment.start * 16000).toLong(),
                            (segment.end * 16000).toLong(), null, false,
                            "speaker_" + (segment.speaker + 1))
                    }.filter { it.endSample > it.startSample }.sortedBy { it.startSample }
                    val raw = JSONArray()
                    nativeRows.forEach { raw.put(JSONObject()
                        .put("start_sample", it.startSample).put("end_sample", it.endSample)
                        .put("speaker_id", it.speakerId)) }
                    File(app.getExternalFilesDir(null), "whole-file-native-" + clusters + ".json")
                        .writeText(raw.toString())
                    // Split at every boundary: a short overlap must never mark a whole turn.
                    val boundaries = nativeRows.flatMap { listOf(it.startSample, it.endSample) }
                        .distinct().sorted()
                    val split = ArrayList<VoiceTurn>()
                    for (index in 0 until boundaries.lastIndex) {
                        val start = boundaries[index]
                        val end = boundaries[index + 1]
                        val active = nativeRows.filter {
                            it.startSample < end && it.endSample > start
                        }
                        val overlapping = active.map { it.speakerId }.distinct().size > 1
                        active.forEach { split.add(it.copy(
                            startSample = start, endSample = end, overlap = overlapping)) }
                    }
                    voices = split
                    val collected = ArrayList<Utterance>()
                    var pending: Utterance? = null
                    for (chunk in chunks) {
                        val phrases = if (chunk.result.words.isNotEmpty())
                            com.epicwalk.voiceanalyzer.diarization.WordSpeakerPlanner.plan(
                                chunk.result.words, chunk.offset, chunk.start, chunk.end, voices)
                        else if (chunk.result.text.isNotBlank())
                            listOf(com.epicwalk.voiceanalyzer.diarization.SpokenPhrase(
                                chunk.start, chunk.end, "speaker_unknown", chunk.result.text, false))
                        else emptyList()
                        for (phrase in phrases) {
                            val next = Utterance("whole:" + clusters + ":" + chunk.start + ":" +
                                phrase.startSample, id, phrase.speakerId, phrase.startSample / 16,
                                maxOf(phrase.startSample / 16 + 1, phrase.endSample / 16),
                                phrase.text, sourcePass = "diarized", overlap = phrase.overlap,
                                speakerCandidates = phrase.candidateSpeakerIds
                                    .takeIf { it.size > 1 }?.joinToString("|"))
                            val joined = pending?.let {
                                com.epicwalk.voiceanalyzer.data.UtteranceMerger.merge(it, next)
                            }
                            if (joined != null) pending = joined
                            else { pending?.let(collected::add); pending = next }
                        }
                    }
                    pending?.let(collected::add)
                    rows = collected
                } finally { engine.release() }
            } finally { sampler.cancelAndJoin() }
            val starts = references.mapNotNull { ref ->
                val row = rows.firstOrNull { it.startMs <= ref.startMs && it.endMs > ref.startMs }
                    ?: rows.minByOrNull { abs(it.startMs - ref.startMs) }
                        ?.takeIf { abs(it.startMs - ref.startMs) <= 5000 }
                row?.let { Triple(ref, it, abs(it.startMs - ref.startMs)) }
            }
            val mapping = bestSpeakerMapping(references, starts)
            val hits = starts.count { (ref, row, _) -> mapping[ref.speaker] == row.speakerId }
            val strictStarts = references.mapNotNull { ref ->
                rows.minByOrNull { abs(it.startMs - ref.startMs) }
                    ?.takeIf { abs(it.startMs - ref.startMs) <= 5000 }
                    ?.let { Triple(ref, it, abs(it.startMs - ref.startMs)) }
            }
            val strictMapping = bestSpeakerMapping(references, strictStarts)
            val strictHits = strictStarts.count { (ref, row, _) ->
                strictMapping[ref.speaker] == row.speakerId
            }
            val confusion = JSONObject()
            for (speaker in references.map { it.speaker }.distinct()) {
                val counts = JSONObject()
                starts.filter { it.first.speaker == speaker }
                    .groupingBy { it.second.speakerId }.eachCount()
                    .forEach { (predicted, count) -> counts.put(predicted, count) }
                confusion.put(speaker, counts)
            }
            output.put(JSONObject().put("clusters_requested", clusters)
                .put("input_samples", sampleCount).put("pcm_float_bytes", sampleCount * 4)
                .put("pcm_read_ms", readMs).put("model_load_ms", loadMs)
                .put("native_process_ms", processMs).put("native_segments", segmentCount)
                .put("native_speakers", voices.map { it.speakerId }.distinct().size)
                .put("onset_hits", hits).put("onsets_matched", starts.size)
                .put("strict_start_hits", strictHits).put("strict_starts_matched", strictStarts.size)
                .put("unknown_rows", rows.count { it.speakerId == "speaker_unknown" })
                .put("unknown_duration_ms", rows.filter { it.speakerId == "speaker_unknown" }
                    .sumOf { (it.endMs - it.startMs).coerceAtLeast(0) })
                .put("rows", rows.size).put("speaker_confusion", confusion)
                .put("sampled_peak_pss_kib", peakPss.get())
                .put("sampled_peak_rss_kib", peakRss.get()))
        }
        return output
    }

    /** Reverse mono PCM16 samples in bounded chunks; no full-audio heap buffer. */
    private fun reversePcm(source: File, destination: File): Long {
        require(source.isFile && source.length() % 2L == 0L)
        val totalSamples = source.length() / 2L
        java.io.RandomAccessFile(source, "r").use { input ->
            java.io.FileOutputStream(destination, false).use { output ->
                var remaining = totalSamples
                val maxChunkSamples = 32 * 1024
                while (remaining > 0L) {
                    val count = minOf(maxChunkSamples.toLong(), remaining).toInt()
                    val firstSample = remaining - count
                    val raw = ByteArray(count * 2)
                    input.seek(firstSample * 2)
                    input.readFully(raw)
                    val reversed = ByteArray(raw.size)
                    for (index in 0 until count) {
                        val sourceIndex = (count - 1 - index) * 2
                        reversed[index * 2] = raw[sourceIndex]
                        reversed[index * 2 + 1] = raw[sourceIndex + 1]
                    }
                    output.write(reversed)
                    remaining = firstSample
                }
            }
        }
        return totalSamples
    }

    private fun parse(lines: List<String>): List<ReferenceTurn> {
        val speakerHeader = Regex("^\\s*Спикер\\s+(\\d+)\\s*:\\s*$", RegexOption.IGNORE_CASE)
        val timestamp = Regex("^\\s*(\\d{2}):(\\d{2}):(\\d{2})\\s*[—–-]\\s*(.*)$")
        val turns = mutableListOf<ReferenceTurn>()
        var speaker: String? = null
        var activeStart: Long? = null
        var activeText = StringBuilder()
        fun flush() {
            val currentSpeaker = speaker
            val currentStart = activeStart
            val text = activeText.toString().trim()
            if (currentSpeaker != null && currentStart != null && text.isNotEmpty())
                turns += ReferenceTurn("speaker_$currentSpeaker", currentStart, text)
            activeStart = null
            activeText = StringBuilder()
        }
        for (line in lines) {
            val header = speakerHeader.matchEntire(line)
            if (header != null) {
                flush()
                speaker = header.groupValues[1]
                continue
            }
            val timed = timestamp.matchEntire(line)
            if (timed != null) {
                flush()
                val hours = timed.groupValues[1].toLong()
                val minutes = timed.groupValues[2].toLong()
                val seconds = timed.groupValues[3].toLong()
                activeStart = ((hours * 60 + minutes) * 60 + seconds) * 1000
                activeText.append(timed.groupValues[4])
            } else if (activeStart != null && line.isNotBlank()) {
                if (activeText.isNotEmpty()) activeText.append(' ')
                activeText.append(line.trim())
            }
        }
        flush()
        return turns
    }

    private fun tokens(text: String): List<String> =
        Regex("[\\p{L}\\p{N}]+").findAll(text.lowercase().replace('ё', 'е')).map { it.value }.toList()

    private fun wordEdits(reference: List<String>, hypothesis: List<String>): Int {
        var previous = IntArray(hypothesis.size + 1) { it }
        var current = IntArray(hypothesis.size + 1)
        for (i in reference.indices) {
            current[0] = i + 1
            for (j in hypothesis.indices) {
                val substitution = previous[j] + if (reference[i] == hypothesis[j]) 0 else 1
                current[j + 1] = minOf(previous[j + 1] + 1, current[j] + 1, substitution)
            }
            val swap = previous
            previous = current
            current = swap
        }
        return previous[hypothesis.size]
    }

    private fun punctuationAfterWords(text: String): List<String> {
        val words = Regex("[\\p{L}\\p{N}]+").findAll(text).toList()
        val marks = Regex("[,.;:!?…]+")
        return words.mapIndexed { index, word ->
            val afterWord = word.range.last + 1
            val beforeNext = words.getOrNull(index + 1)?.range?.first ?: text.length
            marks.findAll(text.substring(afterWord, beforeNext)).lastOrNull()?.value.orEmpty()
        }
    }

    /** LCS-style edit alignment; only exact word pairs are used for punctuation scoring. */
    private fun alignedWordPairs(reference: List<String>, hypothesis: List<String>): List<Pair<Int, Int>> {
        if (reference.isEmpty() || hypothesis.isEmpty()) return emptyList()
        val width = hypothesis.size + 1
        val cells = (reference.size + 1L) * width
        if (cells > 30_000_000L) return emptyList()
        val direction = ByteArray(cells.toInt())
        var previous = IntArray(width) { it }
        var current = IntArray(width)
        for (j in 1 until width) direction[j] = 2
        for (i in reference.indices) {
            current[0] = i + 1
            direction[(i + 1) * width] = 1
            for (j in hypothesis.indices) {
                val diagonal = previous[j] + if (reference[i] == hypothesis[j]) 0 else 1
                val deletion = previous[j + 1] + 1
                val insertion = current[j] + 1
                val offset = (i + 1) * width + j + 1
                when {
                    diagonal <= deletion && diagonal <= insertion -> {
                        current[j + 1] = diagonal
                        direction[offset] = 0
                    }
                    deletion <= insertion -> {
                        current[j + 1] = deletion
                        direction[offset] = 1
                    }
                    else -> {
                        current[j + 1] = insertion
                        direction[offset] = 2
                    }
                }
            }
            val swap = previous
            previous = current
            current = swap
        }
        val pairs = ArrayList<Pair<Int, Int>>()
        var i = reference.size
        var j = hypothesis.size
        while (i > 0 || j > 0) {
            when {
                i > 0 && j > 0 && direction[i * width + j].toInt() == 0 -> {
                    if (reference[i - 1] == hypothesis[j - 1]) pairs += (i - 1) to (j - 1)
                    i--; j--
                }
                i > 0 && (j == 0 || direction[i * width + j].toInt() == 1) -> i--
                else -> j--
            }
        }
        pairs.reverse()
        return pairs
    }

    private fun matchSpeakerCentroids(
        reference: List<VoiceTurn>,
        candidate: List<VoiceTurn>
    ): Map<String, String> {
        fun centroids(turns: List<VoiceTurn>): Map<String, FloatArray> = turns.asSequence()
            .filter { !it.overlap && it.speakerId != "speaker_unknown" && it.embedding != null }
            .groupBy { it.speakerId }
            .mapNotNull { (id, group) ->
                val dimensions = group.first().embedding!!.size
                if (group.any { it.embedding!!.size != dimensions }) return@mapNotNull null
                val sum = DoubleArray(dimensions)
                for (turn in group) for (i in 0 until dimensions) sum[i] += turn.embedding!![i]
                val norm = kotlin.math.sqrt(sum.sumOf { it * it })
                if (norm <= 0.0) null else id to FloatArray(dimensions) { (sum[it] / norm).toFloat() }
            }.toMap()
        val left = centroids(reference)
        val right = centroids(candidate)
        val pairs = left.flatMap { (leftId, a) -> right.mapNotNull { (rightId, b) ->
            if (a.size != b.size) null else leftId to (rightId to a.indices.sumOf { a[it].toDouble() * b[it] })
        } }.sortedByDescending { it.second.second }
        val matchedLeft = HashSet<String>()
        val matchedRight = HashSet<String>()
        val mapping = LinkedHashMap<String, String>()
        for ((leftId, scoredRight) in pairs) {
            val (rightId, score) = scoredRight
            if (score >= 0.3 && leftId !in matchedLeft && rightId !in matchedRight) {
                mapping[rightId] = leftId
                matchedLeft += leftId
                matchedRight += rightId
            }
        }
        return mapping
    }

    private fun bestSpeakerMapping(
        references: List<ReferenceTurn>,
        matched: List<Triple<ReferenceTurn, Utterance, Long>>
    ): Map<String, String> {
        val expected = references.map { it.speaker }.distinct()
        val predicted = matched.map { it.second.editedSpeakerId ?: it.second.speakerId }
            .filter { it != "speaker_unknown" }.distinct()
        val counts = matched.groupingBy { (ref, row, _) ->
            ref.speaker to (row.editedSpeakerId ?: row.speakerId)
        }.eachCount()
        var bestScore = -1
        var best = emptyMap<String, String>()
        fun visit(index: Int, used: Set<String>, current: Map<String, String>, score: Int) {
            if (index == expected.size) {
                if (score > bestScore) { bestScore = score; best = current }
                return
            }
            val label = expected[index]
            visit(index + 1, used, current, score)
            for (candidate in predicted) if (candidate !in used) {
                visit(index + 1, used + candidate, current + (label to candidate),
                    score + (counts[label to candidate] ?: 0))
            }
        }
        visit(0, emptySet(), emptyMap(), 0)
        return best
    }
}
