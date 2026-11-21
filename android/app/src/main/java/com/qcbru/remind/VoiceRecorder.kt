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

    fun start(onError: () -> Unit) {
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
            recording.setOnErrorListener { source, _, _ ->
                if (recorder === source) onError()
            }
            recording.prepare()
            recording.start()
            recorder = recording
            file = output
            startedAt = System.currentTimeMillis()
        } catch (error: Exception) {
            recording.release(); output.delete(); throw error
        }
    }

    fun amplitude(): Int = runCatching { recorder?.maxAmplitude ?: 0 }.getOrDefault(0)

    fun finish() {
        val recording = recorder ?: return
        val output = file ?: error("No recording file")
        recorder = null
        try { recording.stop() } catch (error: Exception) { file = null; output.delete(); throw error }
        finally { recording.release() }
    }

    fun stop(): String {
        finish()
        val output = file ?: error("No recording file")
        file = null
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
