package com.example.flutter_karaoke

import androidx.annotation.NonNull
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

class MainActivity : FlutterActivity() {
    private val CHANNEL = "com.example.flutter_karaoke/audio_loopback"
    private var engine: AudioPassthroughEngine? = null

    override fun configureFlutterEngine(@NonNull flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        engine = AudioPassthroughEngine(applicationContext)

        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL).setMethodCallHandler { call, result ->
            when (call.method) {
                "start" -> {
                    val success = engine?.start() ?: false
                    if (success) {
                        result.success(true)
                    } else {
                        result.error("AUDIO_ERROR", "Failed to start audio loopback", null)
                    }
                }
                "stop" -> {
                    engine?.stop()
                    result.success(true)
                }
                "isRunning" -> {
                    result.success(engine?.isRecording() ?: false)
                }
                else -> {
                    result.notImplemented()
                }
            }
        }
    }

    override fun onDestroy() {
        engine?.stop()
        super.onDestroy()
    }
}
