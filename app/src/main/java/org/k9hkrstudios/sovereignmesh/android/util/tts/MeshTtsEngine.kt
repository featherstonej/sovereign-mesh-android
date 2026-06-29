/*
 * Sovereign Mesh (Android)
 * Copyright (C) 2025 Sovereign Mesh Contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package org.k9hkrstudios.sovereignmesh.android.util.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale

/**
 * MeshTtsEngine provides an offline-first Text-to-Speech synthesis wrapper
 * for announcing mesh alerts and incoming messages.
 */
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
                Log.e(TAG, "Language pack not supported or installed locally for US Locale")
            } else {
                isInitialized = true
                Log.d(TAG, "TTS initialized successfully for offline US Locale")
            }
        } else {
            Log.e(TAG, "TTS initialization failed with status: $status")
        }
    }

    /**
     * Speaks the given alert text locally on the device's default speech synthesis engine.
     * @param text The string to be synthesized.
     */
    fun speakAlert(text: String) {
        if (isInitialized) {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "MeshAlert")
        } else {
            Log.w(TAG, "Attempted to speak but TTS engine is not initialized yet")
        }
    }

    /**
     * Shuts down and releases the underlying TTS resources.
     */
    fun shutdown() {
        tts?.shutdown()
        tts = null
        isInitialized = false
    }
}
