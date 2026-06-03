package com.atombits.pocopaw.overlay

import android.content.Intent
import androidx.activity.ComponentActivity
import android.os.Bundle
import android.speech.RecognizerIntent
import androidx.activity.result.contract.ActivityResultContracts
import com.atombits.pocopaw.R
import java.util.Locale

class OverlayVoiceInputHostActivity : ComponentActivity() {

    companion object {
        const val EXTRA_VOICE_RESULT = "extra_voice_result"
        const val EXTRA_VOICE_ERROR = "extra_voice_error"
        const val RESULT_VOICE_SUCCESS = 1001
        const val RESULT_VOICE_ERROR = 1002
    }

    private val voiceInputLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val matches = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            val spokenText = matches?.firstOrNull()
            if (spokenText.isNullOrBlank()) {
                finishWithError(getString(R.string.voice_input_no_result))
            } else {
                finishWithSuccess(spokenText)
            }
        } else {
            finishWithError(getString(R.string.voice_input_no_result))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Transparent, no-display activity just to host the voice input launcher
        launchVoiceRecognition()
    }

    private fun launchVoiceRecognition() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PROMPT, getString(R.string.voice_input_prompt))
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }

        try {
            voiceInputLauncher.launch(intent)
        } catch (e: Exception) {
            finishWithError(getString(R.string.voice_input_not_supported))
        }
    }

    private fun finishWithSuccess(text: String) {
        val resultIntent = Intent().apply {
            putExtra(EXTRA_VOICE_RESULT, text)
        }
        setResult(RESULT_VOICE_SUCCESS, resultIntent)
        finish()
    }

    private fun finishWithError(error: String) {
        val resultIntent = Intent().apply {
            putExtra(EXTRA_VOICE_ERROR, error)
        }
        setResult(RESULT_VOICE_ERROR, resultIntent)
        finish()
    }
}