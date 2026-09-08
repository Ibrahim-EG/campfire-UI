package com.campfire.canvas.livingpainting

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.PlaybackParams
import android.os.Build
import kotlin.math.PI
import kotlin.math.sin

class CozyAudioEngine {
    private var windTrack: AudioTrack? = null
    private var fireTrack: AudioTrack? = null
    private var windThread: Thread? = null
    private var fireThread: Thread? = null
    @Volatile private var playing = false
    @Volatile private var paused = false
    @Volatile private var intensity = 0f

    fun start() {
        if (playing) return
        playing = true; paused = false

        // BINAURAL WIND: stereo brown noise, decorrelated ears + 0.05 Hz pan LFO + rustle AM
        windThread = Thread {
            try {
                val sr = 22050
                var bs = AudioTrack.getMinBufferSize(sr, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
                if (bs <= 0) bs = 8192
                windTrack = AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                    .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(sr).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
                    .setBufferSizeInBytes(bs).setTransferMode(AudioTrack.MODE_STREAM).build()
                windTrack?.play()
                val frames = bs / 4
                val buf = ShortArray(frames * 2)
                var bl = 0.0; var br = 0.0
                var t = 0.0
                while (playing) {
                    if (!paused) {
                        for (i in 0 until frames) {
                            t += 1.0 / sr
                            val pan = 0.25 * sin(2.0 * PI * 0.05 * t)          // slow spatial sweep
                            val rustle = 0.55 + 0.45 * sin(2.0 * PI * 0.31 * t + sin(t * 0.7) * 2.0)
                            val wl = Math.random() * 2 - 1; val wr = Math.random() * 2 - 1
                            bl = (bl + 0.02 * wl) / 1.02; br = (br + 0.02 * wr) / 1.02
                            val gl = 0.16 * rustle * (1.0 - pan); val gr = 0.16 * rustle * (1.0 + pan)
                            val leafL = wl * 0.012 * rustle; val leafR = wr * 0.012 * rustle
                            buf[i * 2] = ((bl * gl + leafL) * 32767).toInt().coerceIn(-32768, 32767).toShort()
                            buf[i * 2 + 1] = ((br * gr + leafR) * 32767).toInt().coerceIn(-32768, 32767).toShort()
                        }
                        windTrack?.write(buf, 0, buf.size)
                    } else Thread.sleep(120)
                }
            } catch (e: Exception) { }
        }; windThread?.start()

        // FIRE BED: low rumble + poisson crackle pops; pitch & volume track flame height
        fireThread = Thread {
            try {
                val sr = 22050
                var bs = AudioTrack.getMinBufferSize(sr, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
                if (bs <= 0) bs = 8192
                fireTrack = AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                    .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(sr).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
                    .setBufferSizeInBytes(bs).setTransferMode(AudioTrack.MODE_STREAM).build()
                fireTrack?.play()
                val frames = bs / 4
                val buf = ShortArray(frames * 2)
                var rumble = 0.0
                while (playing) {
                    if (!paused && intensity > 0.03f) {
                        for (i in 0 until frames) {
                            val rw = Math.random() * 2 - 1
                            rumble = (rumble + 0.015 * rw) / 1.015
                            val pop = if (Math.random() < 0.0035 * intensity) (Math.random() * 2 - 1) * 0.9 else 0.0
                            val v = (rumble * 0.5 * intensity + pop * intensity) * 32767 * 0.8
                            val s = v.toInt().coerceIn(-32768, 32767).toShort()
                            buf[i * 2] = s; buf[i * 2 + 1] = s
                        }
                        fireTrack?.write(buf, 0, buf.size)
                    } else Thread.sleep(120)
                }
            } catch (e: Exception) { }
        }; fireThread?.start()
    }

    fun updateFire(f: Float) {
        intensity = f.coerceIn(0f, 1f)
        runCatching {
            fireTrack?.setVolume(0.15f + 0.85f * intensity, 0.15f + 0.85f * intensity)
            if (Build.VERSION.SDK_INT >= 23 && intensity > 0.03f) {
                fireTrack?.playbackParams = PlaybackParams.create().setPitch(0.85f + 0.5f * intensity).setSpeed(0.92f + 0.25f * intensity)
            }
        }
    }

    fun playDrop() { // low-end thump + sharp whoosh
        Thread {
            try {
                val sr = 44100
                val nT = (sr * 0.16).toInt(); val nW = (sr * 0.30).toInt()
                val buf = ShortArray(nT + nW)
                for (i in 0 until nT) {
                    val t = i.toDouble() / sr
                    val f = 70 - t * 260
                    val env = 1 - t / 0.16
                    buf[i] = ((sin(2 * PI * f * t) * 0.9 + (Math.random() * 2 - 1) * 0.25) * env * 32767 * 0.7).toInt().coerceIn(-32768, 32767).toShort()
                }
                for (i in 0 until nW) {
                    val t = i.toDouble() / sr
                    val env = sin(PI * (t / 0.30)).coerceAtLeast(0.0)
                    val sweep = sin(2 * PI * (220 + 700 * t / 0.30) * t)
                    buf[nT + i] = (((Math.random() * 2 - 1) * 0.5 + sweep * 0.2) * env * 32767 * 0.35).toInt().coerceIn(-32768, 32767).toShort()
                }
                val tr = AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build())
                    .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(sr).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setBufferSizeInBytes(buf.size * 2).setTransferMode(AudioTrack.MODE_STATIC).build()
                tr.write(buf, 0, buf.size); tr.play(); Thread.sleep(500); tr.release()
            } catch (e: Exception) { }
        }.start()
    }

    fun pause() { paused = true; runCatching { windTrack?.pause() }; runCatching { fireTrack?.pause() } }
    fun resume() { paused = false; runCatching { windTrack?.play() }; runCatching { fireTrack?.play() } }
    fun stop() {
        playing = false
        windThread?.interrupt(); fireThread?.interrupt()
        runCatching { windTrack?.release() }; runCatching { fireTrack?.release() }
        windTrack = null; fireTrack = null
    }
}
