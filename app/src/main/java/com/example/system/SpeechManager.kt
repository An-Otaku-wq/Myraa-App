package com.example.system

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale

class SpeechManager(
    private val context: Context,
    private val onSpeechVolumeChanged: (Float) -> Unit = {},
    private val onSpeechStatusMsg: (String) -> Unit = {},
    private val onSpeechResult: (String) -> Unit = {}
) : TextToSpeech.OnInitListener {

    private var textToSpeech: TextToSpeech? = null
    private var speechRecognizer: SpeechRecognizer? = null
    private var isTtsReady = false

    var isListening = false
        private set

    var onSpeakingStateChanged: (Boolean) -> Unit = {}
    var onListeningStateChanged: (Boolean) -> Unit = {}

    init {
        // Initialize Text-To-Speech
        textToSpeech = TextToSpeech(context, this)

        // Initialize Speech-To-Text
        if (SpeechRecognizer.isRecognitionAvailable(context)) {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                setRecognitionListener(createSpeechListener())
            }
        } else {
            onSpeechStatusMsg("STT unavailable on this system")
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = textToSpeech?.setLanguage(Locale.US)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.e("SpeechManager", "Language not supported")
                onSpeechStatusMsg("TTS language missing/not supported")
            } else {
                isTtsReady = true
                setupTtsProgressListener()
            }
        } else {
            Log.e("SpeechManager", "TTS initialization failed")
            onSpeechStatusMsg("TTS setup failed")
        }
    }

    private fun setupTtsProgressListener() {
        textToSpeech?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                onSpeakingStateChanged(true)
            }

            override fun onDone(utteranceId: String?) {
                onSpeakingStateChanged(false)
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                onSpeakingStateChanged(false)
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                onSpeakingStateChanged(false)
            }
        })
    }

    fun speak(text: String) {
        if (!isTtsReady) {
            onSpeechStatusMsg("Speech system still waking up...")
            return
        }
        try {
            textToSpeech?.stop() // Stop any ongoing speech
            
            // On-the-fly bilingual language switching based on Hindi Devanagari character presence
            val containsHindi = text.any { it in '\u0900'..'\u097F' }
            if (containsHindi) {
                textToSpeech?.setLanguage(Locale("hi", "IN"))
            } else {
                textToSpeech?.setLanguage(Locale.US)
            }

            val params = Bundle().apply {
                putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "MYRAA_SPEAK_UTTERANCE")
            }
            textToSpeech?.speak(text, TextToSpeech.QUEUE_FLUSH, params, "MYRAA_SPEAK_UTTERANCE")
        } catch (e: Exception) {
            Log.e("SpeechManager", "TTS failed", e)
        }
    }

    fun setPitch(pitch: Float) {
        try {
            textToSpeech?.setPitch(pitch)
        } catch (e: Exception) {
            Log.e("SpeechManager", "Failed to set pitch", e)
        }
    }

    fun setSpeechRate(rate: Float) {
        try {
            textToSpeech?.setSpeechRate(rate)
        } catch (e: Exception) {
            Log.e("SpeechManager", "Failed to set rate", e)
        }
    }

    fun stopSpeaking() {
        try {
            textToSpeech?.stop()
            onSpeakingStateChanged(false)
        } catch (e: Exception) {
            Log.e("SpeechManager", "Stop failed", e)
        }
    }

    fun startListening() {
        if (speechRecognizer == null) {
            onSpeechStatusMsg("STT engine not available")
            return
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        isListening = true
        onListeningStateChanged(true)
        speechRecognizer?.startListening(intent)
    }

    fun stopListening() {
        speechRecognizer?.stopListening()
        isListening = false
        onListeningStateChanged(false)
    }

    fun destroy() {
        try {
            textToSpeech?.stop()
            textToSpeech?.shutdown()
            speechRecognizer?.destroy()
        } catch (e: Exception) {
            Log.e("SpeechManager", "Speech destroy error", e)
        }
    }

    private fun createSpeechListener() = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            isListening = true
            onListeningStateChanged(true)
            onSpeechStatusMsg("Awaiting your command...")
        }

        override fun onBeginningOfSpeech() {
            onSpeechStatusMsg("Receiving energy spikes...")
        }

        override fun onRmsChanged(rmsdB: Float) {
            // Map rmsdB (usually -2 to 10+) to normalized 0f-1f float for visualizer orb amplitude!
            val amplitude = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
            onSpeechVolumeChanged(amplitude)
        }

        override fun onBufferReceived(buffer: ByteArray?) {}

        override fun onEndOfSpeech() {
            isListening = false
            onListeningStateChanged(false)
            onSpeechStatusMsg("Processing transmissions...")
        }

        override fun onError(error: Int) {
            isListening = false
            onListeningStateChanged(false)
            val errorMsg = when (error) {
                SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
                SpeechRecognizer.ERROR_CLIENT -> "Client error"
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Permissions required"
                SpeechRecognizer.ERROR_NETWORK -> "Network issue"
                SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
                SpeechRecognizer.ERROR_NO_MATCH -> "Unable to decode"
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Core busy"
                SpeechRecognizer.ERROR_SERVER -> "AI cloud servers offline"
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Command expired"
                else -> "Speech decoding error: $error"
            }
            onSpeechStatusMsg(errorMsg)
            onSpeechVolumeChanged(0f)
        }

        override fun onResults(results: Bundle?) {
            isListening = false
            onListeningStateChanged(false)
            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val text = matches?.firstOrNull() ?: ""
            if (text.isNotBlank()) {
                onSpeechResult(text)
            }
            onSpeechVolumeChanged(0f)
        }

        override fun onPartialResults(partialResults: Bundle?) {}

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }
}
