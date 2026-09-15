package com.friday.assistant.security

import kotlin.math.sqrt

/**
 * Lightweight, local speaker-verification baseline.
 *
 * This is a similarity signal, not biometric authentication. It deliberately uses
 * simple deterministic acoustic features so the baseline has no model download,
 * network dependency, or secret material. A production verifier should replace the
 * feature extractor with a trained speaker-embedding model and anti-spoofing checks.
 */
class SpeakerVerificationBaseline(
    private val threshold: Float = 0.82f,
    private val minSamples: Int = 1600
) {
    private var enrolled: FloatArray? = null

    fun isEnrolled(): Boolean = enrolled != null

    fun clearEnrollment() {
        enrolled = null
    }

    fun enroll(pcm16: ShortArray): Boolean {
        val embedding = extractEmbedding(pcm16) ?: return false
        enrolled = embedding
        return true
    }

    fun restoreProfile(profile: FloatArray): Boolean {
        if (profile.isEmpty() || profile.any { !it.isFinite() }) return false
        enrolled = profile.copyOf()
        return true
    }

    fun exportProfile(): FloatArray? = enrolled?.copyOf()

    fun verify(pcm16: ShortArray): VerificationResult {
        val profile = enrolled ?: return VerificationResult(false, 0f, "NO_PROFILE")
        val candidate = extractEmbedding(pcm16)
            ?: return VerificationResult(false, 0f, "INSUFFICIENT_AUDIO")
        val similarity = cosine(profile, candidate)
        return VerificationResult(
            matched = similarity >= threshold,
            similarity = similarity,
            reason = if (similarity >= threshold) "MATCH" else "MISMATCH"
        )
    }

    data class VerificationResult(
        val matched: Boolean,
        val similarity: Float,
        val reason: String
    )

    private fun extractEmbedding(pcm16: ShortArray): FloatArray? {
        if (pcm16.size < minSamples) return null

        // 20 frames × 8 normalized log-energy bands + zero-crossing + RMS = 200 features.
        val frameSize = 320
        val frameCount = minOf(20, pcm16.size / frameSize)
        if (frameCount < 5) return null
        val output = FloatArray(frameCount * 10)
        var out = 0

        for (frame in 0 until frameCount) {
            val start = frame * frameSize
            val end = start + frameSize
            var sumSquares = 0.0
            var crossings = 0
            var previous = pcm16[start].toInt()
            val bands = DoubleArray(8)
            val bandWidth = frameSize / bands.size

            for (i in start until end) {
                val sample = pcm16[i].toInt() / 32768.0
                val abs = kotlin.math.abs(sample)
                sumSquares += sample * sample
                if ((sample >= 0) != (previous >= 0)) crossings++
                previous = pcm16[i].toInt()
                val band = ((i - start) / bandWidth).coerceAtMost(bands.lastIndex)
                bands[band] += abs * abs
            }

            val rms = sqrt(sumSquares / frameSize).coerceAtLeast(1e-7)
            val maxBand = bands.maxOrNull()?.coerceAtLeast(1e-12) ?: 1e-12
            for (band in bands.indices) {
                output[out++] = (kotlin.math.ln(bands[band] / maxBand + 1e-6) / 8.0).toFloat()
            }
            output[out++] = (crossings.toFloat() / frameSize)
            output[out++] = kotlin.math.ln(rms).toFloat()
        }
        normalize(output)
        return output
    }

    private fun normalize(values: FloatArray) {
        var mean = 0f
        for (v in values) mean += v
        mean /= values.size
        var variance = 0f
        for (v in values) {
            val d = v - mean
            variance += d * d
        }
        val std = sqrt((variance / values.size).toDouble()).toFloat().coerceAtLeast(1e-5f)
        for (i in values.indices) values[i] = (values[i] - mean) / std
    }

    private fun cosine(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size) return 0f
        var dot = 0.0
        var na = 0.0
        var nb = 0.0
        for (i in a.indices) {
            dot += a[i] * b[i]
            na += a[i] * a[i]
            nb += b[i] * b[i]
        }
        if (na <= 0.0 || nb <= 0.0) return 0f
        return (dot / (sqrt(na) * sqrt(nb))).toFloat().coerceIn(-1f, 1f)
    }
}
