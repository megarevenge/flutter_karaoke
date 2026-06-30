package com.example.flutter_karaoke

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Process
import android.util.Log

class AudioPassthroughEngine(private val context: Context) {
    companion object {
        private const val TAG = "AudioPassthroughEngine"
    }

    private var isRunning = false
    private var workerThread: Thread? = null

    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var echoCanceler: AcousticEchoCanceler? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private var isNoiseSuppressorEnabled = true

    @Synchronized
    fun start(): Boolean {
        if (isRunning) {
            Log.d(TAG, "Already running")
            return true
        }

        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        
        // Query hardware optimal sample rate and buffer size
        val sampleRateStr = audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)
        val sampleRate = sampleRateStr?.toIntOrNull() ?: 44100
        val framesPerBufferStr = audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)
        val framesPerBuffer = framesPerBufferStr?.toIntOrNull() ?: 256

        Log.d(TAG, "Hardware optimal sample rate: $sampleRate, frames per buffer: $framesPerBuffer")

        val channelConfigIn = AudioFormat.CHANNEL_IN_MONO
        val channelConfigOut = AudioFormat.CHANNEL_OUT_MONO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT

        val minRecBufSize = AudioRecord.getMinBufferSize(sampleRate, channelConfigIn, audioFormat)
        if (minRecBufSize == AudioRecord.ERROR_BAD_VALUE || minRecBufSize == AudioRecord.ERROR) {
            Log.e(TAG, "Invalid AudioRecord min buffer size: $minRecBufSize")
            return false
        }

        // We want buffer sizes matching optimal hardware frame count to minimize latency
        // 2 bytes per sample for ENCODING_PCM_16BIT
        val optimalBufSize = framesPerBuffer * 2
        val recBufferSize = Math.max(minRecBufSize, optimalBufSize)

        try {
            // USAGE_VOICE_COMMUNICATION and VOICE_COMMUNICATION source are critical for echo cancellation (AEC)
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                sampleRate,
                channelConfigIn,
                audioFormat,
                recBufferSize
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord initialization failed")
                releaseResources()
                return false
            }

            val sessionId = audioRecord!!.audioSessionId

            // Enable hardware echo cancellation if available
            if (AcousticEchoCanceler.isAvailable()) {
                echoCanceler = AcousticEchoCanceler.create(sessionId)?.apply {
                    enabled = true
                    Log.d(TAG, "AcousticEchoCanceler initialized and enabled")
                }
            } else {
                Log.w(TAG, "AcousticEchoCanceler not available on this device")
            }

            // Enable hardware noise suppression if available and enabled by user settings
            if (NoiseSuppressor.isAvailable()) {
                noiseSuppressor = NoiseSuppressor.create(sessionId)?.apply {
                    enabled = isNoiseSuppressorEnabled
                    Log.d(TAG, "NoiseSuppressor initialized and set to enabled = $isNoiseSuppressorEnabled")
                }
            }

            val minPlayBufSize = AudioTrack.getMinBufferSize(sampleRate, channelConfigOut, audioFormat)
            val playBufferSize = Math.max(minPlayBufSize, optimalBufSize)

            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(audioFormat)
                        .setSampleRate(sampleRate)
                        .setChannelMask(channelConfigOut)
                        .build()
                )
                .setBufferSizeInBytes(playBufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .build()

            if (audioTrack?.state != AudioTrack.STATE_INITIALIZED) {
                Log.e(TAG, "AudioTrack initialization failed")
                releaseResources()
                return false
            }

            isRunning = true
            workerThread = Thread({
                Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
                
                // Read chunks of size framesPerBuffer to match optimal hardware timing
                val buffer = ShortArray(framesPerBuffer)

                try {
                    audioRecord?.startRecording()
                    audioTrack?.play()

                    Log.d(TAG, "Passthrough loop started")

                    while (isRunning) {
                        val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                        if (read > 0) {
                            audioTrack?.write(buffer, 0, read)
                        } else if (read < 0) {
                            Log.e(TAG, "AudioRecord read error: $read")
                            break
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error in passthrough loop: ${e.message}", e)
                } finally {
                    try {
                        audioRecord?.stop()
                    } catch (e: Exception) {
                        Log.e(TAG, "Error stopping AudioRecord: ${e.message}")
                    }
                    try {
                        audioTrack?.stop()
                    } catch (e: Exception) {
                        Log.e(TAG, "Error stopping AudioTrack: ${e.message}")
                    }
                }
            }, "AudioPassthroughThread")

            workerThread?.start()
            return true

        } catch (e: Exception) {
            Log.e(TAG, "Failed to start audio loopback: ${e.message}", e)
            releaseResources()
            return false
        }
    }

    @Synchronized
    fun stop() {
        if (!isRunning) return
        Log.d(TAG, "Stopping audio loopback")
        isRunning = false
        try {
            workerThread?.join(500)
        } catch (e: InterruptedException) {
            Log.e(TAG, "Worker thread join interrupted", e)
        }
        workerThread = null
        releaseResources()
    }

    @Synchronized
    fun isRecording(): Boolean {
        return isRunning
    }

    @Synchronized
    fun toggleNoiseSuppressor(enabled: Boolean): Boolean {
        if (!NoiseSuppressor.isAvailable()) return false
        isNoiseSuppressorEnabled = enabled
        noiseSuppressor?.let { ns ->
            ns.enabled = enabled
            Log.d(TAG, "NoiseSuppressor running instance enabled set to: $enabled")
        }
        return true
    }

    @Synchronized
    fun isNoiseSuppressorEnabled(): Boolean {
        return isNoiseSuppressorEnabled
    }

    fun isNoiseSuppressorSupported(): Boolean {
        return NoiseSuppressor.isAvailable()
    }

    private fun releaseResources() {
        echoCanceler?.release()
        echoCanceler = null

        noiseSuppressor?.release()
        noiseSuppressor = null

        audioRecord?.release()
        audioRecord = null

        audioTrack?.release()
        audioTrack = null
        
        Log.d(TAG, "Audio resources released")
    }
}
