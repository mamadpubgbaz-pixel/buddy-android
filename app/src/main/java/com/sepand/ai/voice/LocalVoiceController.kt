package com.sepand.ai.voice

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice

/** Uses Android's on-device speech recognizer only; no network recognizer fallback is allowed. */
class LocalVoiceController(context: Context) {
    private val appContext = context.applicationContext
    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    init {
        tts = TextToSpeech(appContext) { result -> ttsReady = result == TextToSpeech.SUCCESS }
    }

    fun listen(language: String, onText: (String) -> Unit, onStatus: (String) -> Unit) {
        if (Build.VERSION.SDK_INT < 31 || !SpeechRecognizer.isOnDeviceRecognitionAvailable(appContext)) {
            onStatus("No Android on-device speech recognizer is installed. Install an offline speech pack; Sepand will not fall back to cloud recognition.")
            return
        }
        runCatching {
            recognizer?.destroy()
            recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(appContext).also { service ->
                service.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) { onStatus("Listening · on-device") }
                    override fun onBeginningOfSpeech() { onStatus("Listening…") }
                    override fun onRmsChanged(rmsdB: Float) = Unit
                    override fun onBufferReceived(buffer: ByteArray?) = Unit
                    override fun onEndOfSpeech() { onStatus("Recognizing locally…") }
                    override fun onError(error: Int) {
                        onStatus("Offline speech recognition error: ${errorLabel(error)}")
                        recognizer?.destroy(); recognizer = null
                    }
                    override fun onResults(results: Bundle?) {
                        val candidates = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        val text = candidates?.firstOrNull().orEmpty()
                        if (text.isNotBlank()) onText(text)
                        onStatus(if (text.isBlank()) "No speech recognized" else "Voice captured locally")
                        recognizer?.destroy(); recognizer = null
                    }
                    override fun onPartialResults(partialResults: Bundle?) {
                        val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                        if (!text.isNullOrBlank()) onStatus("$text …")
                    }
                    override fun onEvent(eventType: Int, params: Bundle?) = Unit
                })
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, if (language == "fa") "fa-IR" else "en-US")
                    putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                }
                service.startListening(intent)
            }
        }.onFailure { onStatus("Could not start offline speech recognition: ${it.message}") }
    }

    fun speak(text: String, language: String, onStatus: (String) -> Unit) {
        if (!ttsReady) { onStatus("Offline text-to-speech engine is still starting or unavailable."); return }
        val engine = tts ?: run { onStatus("Text-to-speech is unavailable."); return }
        val lang = if (language == "fa") "fa" else "en"
        val voice: Voice? = engine.voices.orEmpty()
            .filter { it.locale.language == lang && !it.features.contains(TextToSpeech.Engine.KEY_FEATURE_NETWORK_SYNTHESIS) }
            .maxByOrNull { it.quality }
        if (voice == null) {
            onStatus("No offline ${if (lang == "fa") "Persian" else "English"} TTS voice is installed. Download an offline voice in Android Text-to-speech settings.")
            return
        }
        engine.voice = voice
        val result = engine.speak(text.take(4000), TextToSpeech.QUEUE_FLUSH, Bundle(), "sepand-local-tts")
        onStatus(if (result == TextToSpeech.SUCCESS) "Speaking with local voice" else "The offline voice could not start")
    }

    fun stop() {
        recognizer?.cancel(); recognizer?.destroy(); recognizer = null
        tts?.stop()
    }

    fun release() {
        stop(); tts?.shutdown(); tts = null
    }

    private fun errorLabel(code: Int): String = when (code) {
        SpeechRecognizer.ERROR_AUDIO -> "audio"
        SpeechRecognizer.ERROR_CLIENT -> "client"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "microphone permission"
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "network request attempted or service unavailable"
        SpeechRecognizer.ERROR_NO_MATCH -> "no match"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "recognizer busy"
        SpeechRecognizer.ERROR_SERVER -> "recognition service"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "speech timeout"
        else -> "code $code"
    }
}
