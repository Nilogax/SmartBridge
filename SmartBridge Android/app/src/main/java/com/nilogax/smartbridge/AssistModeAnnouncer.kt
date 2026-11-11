package com.nilogax.smartbridge

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlin.math.*

private const val TAG = "AssistModeAnnouncer"

/**
 * Announces the assist mode with N short beeps (mode 1–4) or a single
 * long tone (assist off):
 *
 * Usage:
 *   val announcer = AssistModeAnnouncer(context)
 *   announcer.onConnected()          // call when bike connects
 *   announcer.onMode(assistMode)     // call on every mode packet
 *   announcer.setEnabled(false)      // user toggle
 *   announcer.destroy()              // call from service onDestroy
 */
class AssistModeAnnouncer(private val context: Context) {

    companion object {
        private const val SAMPLE_RATE    = 44100
        private const val CHUNK_SAMPLES  = 512

        private const val BEEP_HZ        = 1760.0
        private const val MODE_OFF_HZ    = 1396.0

        private const val BEEP_MS        = 70      // tone duration per beep
        private const val GAP_MS         = 90      // silence between beeps in a sequence
        private const val MODE_OFF_MS    = 300     // duration of the mode-0 long tone

        private const val DEBOUNCE_MS    = 500L    // wait after last mode change before playing
    }

    @Volatile var enabled: Boolean = true
        private set

    // -1 = unknown / just connected; updated on every mode packet that differs from last
    private var pendingMode: Int = -1

    private val handler = Handler(Looper.getMainLooper())

    @Volatile private var playing = false
    private var playThread: Thread? = null

    private val debounceRunnable = Runnable {
        val mode = pendingMode
        if (mode >= 0 && enabled) playMode(mode)
    }


    fun onConnected() {
        pendingMode = -1
        handler.removeCallbacks(debounceRunnable)
        Log.d(TAG, "onConnected — will announce first mode packet")
    }

    fun onMode(mode: Int) {
        if (!enabled) return
        if (mode == pendingMode) return  // same-mode BLE repeat — do not reset timer

        Log.d(TAG, "mode change: $pendingMode → $mode, debounce reset")
        pendingMode = mode

        // Interrupt any in-progress sequence immediately
        cancelPlayback()

        handler.removeCallbacks(debounceRunnable)
        handler.postDelayed(debounceRunnable, DEBOUNCE_MS)
    }


    fun onDisconnected() {
        handler.removeCallbacks(debounceRunnable)
        cancelPlayback()
        pendingMode = -1
    }

    fun setEnabled(isEnabled: Boolean) {
        enabled = isEnabled
        if (!isEnabled) {
            handler.removeCallbacks(debounceRunnable)
            cancelPlayback()
        }
        Log.d(TAG, "enabled=$isEnabled")
    }


    fun destroy() {
        setEnabled(false)
    }


    private fun cancelPlayback() {
        playing = false
        playThread?.interrupt()
        playThread = null
    }

    private fun playMode(mode: Int) {
        cancelPlayback()
        playing = true

        playThread = Thread {
            Log.d(TAG, "playing mode $mode")

            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            val format = AudioFormat.Builder()
                .setSampleRate(SAMPLE_RATE)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build()
            val minBuf = AudioTrack.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            val track = AudioTrack(
                attrs, format, maxOf(minBuf, CHUNK_SAMPLES * 2),
                AudioTrack.MODE_STREAM, AudioManager.AUDIO_SESSION_ID_GENERATE
            )

            try {
                track.play()

                if (mode == 0) {
                    // Single longer low tone
                    writeTone(track, MODE_OFF_HZ, MODE_OFF_MS)
                } else {
                    val count = mode.coerceIn(1, 4)
                    for (i in 0 until count) {
                        if (!playing) break
                        writeTone(track, BEEP_HZ, BEEP_MS)
                        if (i < count - 1 && playing) {
                            writeGap(track, GAP_MS)
                        }
                    }
                }

                // Allow the AudioTrack buffer to drain before releasing
                writeGap(track, 150)
                track.stop()

            } catch (e: InterruptedException) {
                Log.d(TAG, "playback interrupted")
                track.pause()
                track.flush()
            } finally {
                track.release()
                playing = false
            }

        }.apply { isDaemon = true; name = "AssistModeAnnouncer"; start() }
    }

    private fun writeTone(track: AudioTrack, hz: Double, durationMs: Int) {
        val totalSamples = SAMPLE_RATE * durationMs / 1000
        var written = 0
        while (written < totalSamples && playing) {
            val chunk = minOf(CHUNK_SAMPLES, totalSamples - written)
            val buf = ShortArray(chunk)
            for (i in 0 until chunk) {
                val s = written + i
                val angle = 2.0 * PI * s * hz / SAMPLE_RATE
                // Hann envelope: smooth attack and release
                val env = (1.0 - cos(2.0 * PI * s / (totalSamples - 1))) / 2.0
                buf[i] = (sin(angle) * env * Short.MAX_VALUE).toInt().toShort()
            }
            track.write(buf, 0, chunk)
            written += chunk
        }
    }


    private fun writeGap(track: AudioTrack, durationMs: Int) {
        val totalSamples = SAMPLE_RATE * durationMs / 1000
        var written = 0
        while (written < totalSamples && playing) {
            val chunk = minOf(CHUNK_SAMPLES, totalSamples - written)
            track.write(ShortArray(chunk), 0, chunk)
            written += chunk
        }
    }
}