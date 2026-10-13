package com.nextgen.agent

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/** Continuous listen -> think -> speak -> listen loop (ears + mouth of the assistant). */
class Voice(
    private val ctx: Context,
    private val onText: (String) -> Unit,
    private val onState: (Boolean) -> Unit
) {
    private val main = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var sr: SpeechRecognizer? = null
    @Volatile private var holding = false     // true while thinking / speaking
    var active = false; private set
    var lang = "ta-IN"
    var female = true

    init {
        tts = TextToSpeech(ctx) { }.also { t ->
            t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) {}
                override fun onDone(id: String?) { release() }
                override fun onError(id: String?) { release() }
            })
        }
    }

    private fun release() { holding = false; listen() }

    fun start() { active = true; holding = false; onState(true); listen() }

    fun stop() {
        active = false; holding = false
        main.post { sr?.destroy(); sr = null }
        tts?.stop()
        onState(false)
    }

    fun shutdown() { stop(); tts?.shutdown() }

    /**
     * Android does not expose voice gender, so: female = first installed voice of the language, higher pitch;
     * male = a different (last) voice of the language, lower pitch.
     */
    fun speak(text: String) {
        val t = tts ?: return
        holding = true
        main.post { sr?.cancel() }
        val loc = Locale.forLanguageTag(lang)
        t.language = loc
        val vs = t.voices?.filter { it.locale.language == loc.language }?.sortedBy { it.name }.orEmpty()
        if (vs.isNotEmpty()) t.voice = if (female) vs.first() else vs.last()
        t.setPitch(if (female) 1.1f else 0.8f)
        if (t.speak(text, TextToSpeech.QUEUE_FLUSH, null, "u" + System.nanoTime()) != TextToSpeech.SUCCESS) release()
    }

    private fun listen() {
        if (!active || holding) return
        main.post {
            if (!active || holding) return@post
            sr?.destroy()
            sr = SpeechRecognizer.createSpeechRecognizer(ctx).apply {
                setRecognitionListener(object : RecognitionListener {
                    override fun onResults(r: Bundle?) {
                        val t = r?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                        if (!t.isNullOrBlank()) { holding = true; onText(t) }
                        else main.postDelayed({ listen() }, 200)
                    }
                    override fun onError(e: Int) {
                        if (e == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) { stop(); return }
                        if (active && !holding) main.postDelayed({ listen() }, 350)
                    }
                    override fun onReadyForSpeech(p: Bundle?) {}
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(v: Float) {}
                    override fun onBufferReceived(b: ByteArray?) {}
                    override fun onEndOfSpeech() {}
                    override fun onPartialResults(p: Bundle?) {}
                    override fun onEvent(t: Int, p: Bundle?) {}
                })
                startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang))
            }
        }
    }
}