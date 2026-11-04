package com.qcbru.remind

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.util.Base64
import java.io.File

class VoiceRecorder(private val context: Context) {
    private var recorder: MediaRecorder? = null
    private var file: File? = null
    private var startedAt = 0L

    fun start() {
        check(recorder == null)
        val output = File.createTempFile("remind-voice-", ".m4a", context.cacheDir)
        val recording = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else @Suppress("DEPRECATION") MediaRecorder()
        try {
            recording.setAudioSource(MediaRecorder.AudioSource.MIC)
            recording.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            recording.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            recording.setAudioEncodingBitRate(64_000)
            recording.setAudioSamplingRate(16_000)
            recording.setOutputFile(output.absolutePath)
            recording.setMaxDuration(60_000)
            recording.prepare()
            recording.start()
            recorder = recording
            file = output
            startedAt = System.currentTimeMillis()
        } catch (error: Exception) {
            recording.release(); output.delete(); throw error
        }
    }

    fun stop(): String {
        val recording = recorder ?: error("No recording")
        val output = file ?: error("No recording file")
        recorder = null; file = null
        try { recording.stop() } catch (error: Exception) { output.delete(); throw error }
        finally { recording.release() }
        try {
            require(System.currentTimeMillis() - startedAt >= 250) { "Recording was too short" }
            require(output.length() in 1..5_000_000) { "Recording must be under 5 MB" }
            return Base64.encodeToString(output.readBytes(), Base64.NO_WRAP)
        } finally { output.delete() }
    }

    fun cancel() {
        runCatching { recorder?.stop() }
        recorder?.release(); recorder = null
        file?.delete(); file = null
    }
}
