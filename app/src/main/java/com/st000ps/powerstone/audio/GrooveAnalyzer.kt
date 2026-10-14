package com.st000ps.powerstone.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.nio.ByteOrder

/**
 * Listens to a stretch of a track (decoding ~16 s from about a third of the way in) and works out
 * its groove for the widget's pads. Runs once per track; the result is kept (see WidgetStore).
 */
object GrooveAnalyzer {
    private const val SECONDS = 16

    /** [headers] carry the signed-in cookie for streams. Null if the audio can't be read. */
    fun analyze(context: Context, uri: Uri, headers: Map<String, String>, durationMs: Long, bpm: Int): Groove? {
        val ex = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            if (uri.scheme == "http" || uri.scheme == "https") ex.setDataSource(uri.toString(), headers) else ex.setDataSource(context, uri, null)
            val track = (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true } ?: return null
            ex.selectTrack(track)
            val fmt = ex.getTrackFormat(track)
            val rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            if (durationMs > 60_000) ex.seekTo(durationMs * 1000 / 3, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
            codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
            codec.configure(fmt, null, null, 0)
            codec.start()

            val frame = 256
            val state = GrooveMath.FilterState()
            val env = GrooveMath.Envelopes()
            val wanted = rate.toLong() * SECONDS
            var got = 0L
            var mono = ShortArray(0)
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            while (got < wanted) {
                if (!inputDone) {
                    val i = codec.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        val buf = codec.getInputBuffer(i)!!
                        val n = ex.readSampleData(buf, 0)
                        if (n < 0) { codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true }
                        else { codec.queueInputBuffer(i, 0, n, ex.sampleTime, 0); ex.advance() }
                    }
                }
                val o = codec.dequeueOutputBuffer(info, 10_000)
                if (o >= 0) {
                    val bytes = codec.getOutputBuffer(o)!!.order(ByteOrder.LITTLE_ENDIAN)
                    // Most decoders give 16-bit PCM; some give float.
                    val float = codec.outputFormat.let { it.containsKey(MediaFormat.KEY_PCM_ENCODING) && it.getInteger(MediaFormat.KEY_PCM_ENCODING) == android.media.AudioFormat.ENCODING_PCM_FLOAT }
                    val samples = bytes.remaining() / (if (float) 4 else 2) / channels
                    if (mono.size < samples) mono = ShortArray(samples)
                    if (float) {
                        val fb = bytes.asFloatBuffer()
                        for (s in 0 until samples) {
                            var sum = 0f
                            for (c in 0 until channels) sum += fb.get(s * channels + c)
                            mono[s] = (sum / channels * 32767f).toInt().coerceIn(-32768, 32767).toShort()
                        }
                    } else {
                        val sb = bytes.asShortBuffer()
                        for (s in 0 until samples) {
                            var sum = 0
                            for (c in 0 until channels) sum += sb.get(s * channels + c)
                            mono[s] = (sum / channels).toShort()
                        }
                    }
                    GrooveMath.envelopes(mono, samples, rate, frame, state, env)
                    got += samples
                    codec.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
            if (env.low.size < 200) return null
            val profile = GrooveMath.profile(env.low.toFloatArray(), env.mid.toFloatArray(), env.high.toFloatArray(), frame.toDouble() / rate, bpm.toDouble())
            return GrooveMath.pick(profile)
        } catch (e: Exception) {
            return null
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            ex.release()
        }
    }
}
