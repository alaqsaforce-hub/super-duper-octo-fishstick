package com.voicedock.ai.voice

import java.io.ByteArrayOutputStream
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Splits a 16 kHz mono PCM stream into speech clips at natural pauses (pure Kotlin, unit-tested).
 *
 * - Adaptive noise floor: a frame is "speech" when it is clearly louder than the recent background.
 * - A clip starts after [minSpeechFrames] speech frames (with [preRollFrames] of audio before it, so the first
 *   syllable is not cut) and ends after [endSilenceMs] of silence, or at [maxClipMs] (cut at the quietest recent frame).
 * - Clips with less than [minSpeechMs] of speech (clicks, coughs) are dropped — they contain no words.
 */
class AudioChunker(
    private val sampleRate: Int = 16_000,
    private val frameMs: Int = 20,
    private val endSilenceMs: Int = 900,
    private val maxClipMs: Int = 25_000,
    private val minSpeechMs: Int = 300,
    private val minSpeechFrames: Int = 3,
    private val preRollFrames: Int = 15,
    private val absoluteFloorDb: Double = -50.0,
    private val marginDb: Double = 10.0,
) {
    val frameSamples = sampleRate * frameMs / 1000
    private var noiseDb = -60.0
    private val preRoll = ArrayDeque<ShortArray>()
    private val clip = ArrayList<ShortArray>()
    private val clipDb = ArrayList<Double>()
    private var inClip = false
    private var speechRun = 0
    private var silenceRun = 0
    private var speechFramesInClip = 0
    var lastFrameWasSpeech = false; private set
    var lastDb = -90.0; private set

    /** Feeds one frame (exactly [frameSamples] samples). Returns a finished clip or null. */
    fun feed(frame: ShortArray): ShortArray? {
        val db = dbfs(frame)
        lastDb = db
        val speech = db > max(absoluteFloorDb, noiseDb + marginDb)
        lastFrameWasSpeech = speech
        // background level follows quiet frames quickly and loud frames very slowly
        noiseDb = if (!speech) noiseDb * 0.95 + db * 0.05 else noiseDb * 0.999 + db * 0.001

        if (!inClip) {
            preRoll.addLast(frame); if (preRoll.size > preRollFrames) preRoll.removeFirst()
            speechRun = if (speech) speechRun + 1 else 0
            if (speechRun >= minSpeechFrames) {
                inClip = true; silenceRun = 0; speechFramesInClip = speechRun
                for (f in preRoll) { clip += f; clipDb += dbfs(f) }
                preRoll.clear()
            }
            return null
        }
        clip += frame; clipDb += db
        if (speech) { silenceRun = 0; speechFramesInClip++ } else silenceRun++
        if (silenceRun * frameMs >= endSilenceMs) return close(clip.size)
        if (clip.size * frameMs >= maxClipMs) {
            // cut at the quietest frame in the last 2 s so a word is not split; keep the rest for the next clip
            val window = (2000 / frameMs).coerceAtMost(clip.size - 1)
            var cut = clip.size
            var best = Double.MAX_VALUE
            for (i in clip.size - window until clip.size) if (clipDb[i] < best) { best = clipDb[i]; cut = i + 1 }
            return close(cut)
        }
        return null
    }

    /** Ends the stream (Pause/Finish): returns the pending clip if it holds speech. */
    fun flush(): ShortArray? = if (inClip) close(clip.size) else null

    private fun close(cut: Int): ShortArray? {
        val taken = clip.subList(0, cut).toList()
        val rest = clip.subList(cut, clip.size).toList()
        val restDb = clipDb.subList(cut, clipDb.size).toList()
        val enough = speechFramesInClip * frameMs >= minSpeechMs
        clip.clear(); clipDb.clear()
        if (rest.isNotEmpty()) { clip += rest; clipDb += restDb; speechFramesInClip = restDb.count { it > max(absoluteFloorDb, noiseDb + marginDb) } }
        else { inClip = false; speechRun = 0; speechFramesInClip = 0 }
        silenceRun = 0
        if (!enough) return null
        val out = ShortArray(taken.sumOf { it.size })
        var o = 0
        for (f in taken) { f.copyInto(out, o); o += f.size }
        return out
    }

    companion object {
        fun dbfs(frame: ShortArray): Double {
            if (frame.isEmpty()) return -90.0
            var sum = 0.0
            for (s in frame) sum += s.toDouble() * s
            val rms = sqrt(sum / frame.size) / 32768.0
            return if (rms <= 1e-9) -90.0 else 20 * log10(rms)
        }
    }
}

/** 16-bit PCM mono → WAV bytes. */
object Wav {
    fun encode(pcm: ShortArray, sampleRate: Int = 16_000): ByteArray {
        val dataLen = pcm.size * 2
        val out = ByteArrayOutputStream(44 + dataLen)
        fun i32(v: Int) { out.write(v and 0xff); out.write(v shr 8 and 0xff); out.write(v shr 16 and 0xff); out.write(v shr 24 and 0xff) }
        fun i16(v: Int) { out.write(v and 0xff); out.write(v shr 8 and 0xff) }
        out.write("RIFF".toByteArray()); i32(36 + dataLen); out.write("WAVE".toByteArray())
        out.write("fmt ".toByteArray()); i32(16); i16(1); i16(1); i32(sampleRate); i32(sampleRate * 2); i16(2); i16(16)
        out.write("data".toByteArray()); i32(dataLen)
        val buf = ByteArray(dataLen)
        for (i in pcm.indices) { buf[2 * i] = (pcm[i].toInt() and 0xff).toByte(); buf[2 * i + 1] = (pcm[i].toInt() shr 8 and 0xff).toByte() }
        out.write(buf)
        return out.toByteArray()
    }
}
