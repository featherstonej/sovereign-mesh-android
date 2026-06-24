package com.sovereignmesh.android.util.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale

class MeshTtsEngine(context: Context) : TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = null
    private var isInitialized = false

    companion object {
        private const val TAG = "MeshTtsEngine"
    }

    init {
        tts = TextToSpeech(context.applicationContext, this)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts?.setLanguage(Locale.US)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.e(TAG, "Language pack not supported/installed locally")
            } else {
                isInitialized = true
                Log.d(TAG, "TTS initialized successfully in offline US Locale")
            }
        } else {
            Log.e(TAG, "TTS initialization failed")
        }
    }

    /**
     * Speaks the given alert text locally on the device's default speech synthesis engine.
     */
    fun speakAlert(text: String) {
        if (isInitialized) {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "MeshAlert")
        } else {
            Log.w(TAG, "TTS not initialized yet")
        }
    }

    fun shutdown() {
        tts?.shutdown()
        tts = null
        isInitialized = false
    }
}
