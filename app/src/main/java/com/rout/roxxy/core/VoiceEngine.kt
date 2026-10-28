package com.rout.roxxy.core

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/**
 * Voice in/out for ROXXY: Android SpeechRecognizer (en-IN / Hinglish) + TTS.
 * Must be created and used on the main thread.
 */
class VoiceEngine(
    context: Context,
    private val onPartial: (String) -> Unit = {},
    private val onFinalResult: (String) -> Unit,
    private val onListeningState: (Boolean) -> Unit = {},
    private val onSpeakingState: (Boolean) -> Unit = {},
    private val onError: (Int) -> Unit = {}
) {
    private var tts: TextToSpeech? = null
    private var recognizer: SpeechRecognizer? = null
    var ttsReady = false
        private set
    var isListening = false
        private set

    init {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale("en", "IN")
                tts?.setPitch(1.08f)
                tts?.setSpeechRate(1.05f)
                ttsReady = true
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) = onSpeakingState(true)
                    override fun onDone(id: String?) = onSpeakingState(false)
                    override fun onError(id: String?) = onSpeakingState(false)
                })
            }
        }
        if (SpeechRecognizer.isRecognitionAvailable(context)) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(context)
            recognizer?.setRecognitionListener(object : RecognitionListener {
                override fun onResults(results: Bundle?) {
                    isListening = false
                    onListeningState(false)
                    val spoken = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: ""
                    if (spoken.isNotBlank()) onFinalResult(spoken) else onError(SpeechRecognizer.ERROR_NO_MATCH)
                }
                override fun onPartialResults(partialResults: Bundle?) {
                    val part = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: ""
                    if (part.isNotBlank()) onPartial(part)
                }
                override fun onError(error: Int) {
                    isListening = false
                    onListeningState(false)
                    onError(error)
                }
                override fun onReadyForSpeech(params: Bundle?) { isListening = true; onListeningState(true) }
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }
    }

    fun listen() {
        val rec = recognizer
        if (rec == null) { onError(-1); return }
        try { rec.cancel() } catch (_: Exception) {}
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        }
        try { rec.startListening(intent) } catch (e: Exception) { onError(-2) }
    }

    fun stopListening() { try { recognizer?.stopListening() } catch (_: Exception) {} }
    fun cancelListening() { isListening = false; try { recognizer?.cancel() } catch (_: Exception) {} }

    fun speak(text: String) {
        if (ttsReady) tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "ROXXY_SPEECH")
    }

    fun stopSpeaking() { try { tts?.stop() } catch (_: Exception) {} }

    fun shutdown() {
        try { recognizer?.destroy() } catch (_: Exception) {}
        try { tts?.shutdown() } catch (_: Exception) {}
        recognizer = null
        tts = null
        ttsReady = false
    }
}
