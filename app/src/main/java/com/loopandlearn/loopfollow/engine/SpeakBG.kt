package com.loopandlearn.loopfollow.engine

import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import com.loopandlearn.loopfollow.LoopFollowApplication
import com.loopandlearn.loopfollow.helpers.Localizer
import com.loopandlearn.loopfollow.helpers.LogManager
import com.loopandlearn.loopfollow.storage.Storage
import java.util.Locale
import kotlin.math.abs

/** Speaks new glucose readings when the Speak BG conditions are met. */
object SpeakBG {
    private var tts: TextToSpeech? = null
    private var ready = false
    private var pending: ((TextToSpeech) -> Unit)? = null
    private var lastSpokenBGDate = 0.0

    private class Texts(val stable: String, val increase: String, val decrease: String, val currentBGIs: String)

    private fun texts(language: String) = when (language) {
        "fr" -> Texts("et c'est stable", "et c'est monté de", "et c'est descendu de", "La glycémie est")
        "it" -> Texts("ed è stabile", "ed è salita di", "ed è scesa di", "Glicemia attuale è")
        "sk" -> Texts("a je stabilná", "a stúpla o", "a klesla o", "Aktuálna glykémia je")
        "sv" -> Texts("och det är stabilt", "och det har ökat med", "och det har minskat med", "Blodsockret är")
        else -> Texts("and it is stable", "and it is up", "and it is down", "Glucose is")
    }

    private fun voiceLocale(language: String): Locale = when (language) {
        "fr" -> Locale.FRANCE
        "it" -> Locale.ITALY
        "sk" -> Locale("sk", "SK")
        "sv" -> Locale("sv", "SE")
        else -> Locale.US
    }

    fun evaluateSpeakConditions(currentValue: Int, previousValue: Int) {
        if (!Storage.speakBG.value) return
        val low = Storage.speakLowBGLimit.value.toInt()
        val high = Storage.speakHighBGLimit.value.toInt()
        val fastDrop = Storage.speakFastDropDelta.value.toInt()

        if (Storage.speakBGAlways.value) {
            speak(currentValue, previousValue)
            LogManager.log(LogManager.Category.GENERAL, "Speaking because 'Always' is enabled.", isDebug = true)
            return
        }
        if (Storage.speakLowBG.value && (currentValue <= low || previousValue <= low)) {
            speak(currentValue, previousValue)
            LogManager.log(LogManager.Category.GENERAL, "Speaking because of 'Low' condition.", isDebug = true)
            return
        }
        if (Storage.speakProactiveLowBG.value) {
            val first = MainEngine.predictionData.firstOrNull()
            val predictive = first != null && first.sgv <= Storage.speakLowBGLimit.value
            if (predictive || currentValue <= low || previousValue <= low || (currentValue <= high && previousValue - currentValue >= fastDrop)) {
                speak(currentValue, previousValue)
                LogManager.log(LogManager.Category.GENERAL, "Speaking because of 'Proactive Low' condition. Predictive trigger: $predictive", isDebug = true)
                return
            }
        }
        if (Storage.speakHighBG.value && (currentValue >= high || previousValue >= high)) {
            speak(currentValue, previousValue)
            LogManager.log(LogManager.Category.GENERAL, "Speaking because of 'High' condition.", isDebug = true)
            return
        }
        LogManager.log(LogManager.Category.GENERAL, "No condition met for speaking.", isDebug = true)
    }

    /** Speaks the current value and the change from the previous one, once per reading. */
    private fun speak(currentValue: Int, previousValue: Int) {
        val lastBG = MainEngine.bgData.lastOrNull() ?: return
        if (lastBG.date <= lastSpokenBGDate) return
        lastSpokenBGDate = lastBG.date

        val language = Storage.speakLanguage.value
        val t = texts(language)
        val diff = currentValue - previousValue
        val current = Localizer.toDisplayUnits(currentValue).replace(",", ".")
        val text = if (abs(diff) <= 3) {
            "${t.currentBGIs} $current ${t.stable}"
        } else {
            val direction = if (diff < 0) t.decrease else t.increase
            "${t.currentBGIs} $current $direction ${Localizer.toDisplayUnits(abs(diff)).replace(",", ".")}"
        }
        withTts { engine ->
            engine.language = voiceLocale(language)
            engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "bg")
        }
    }

    /** Runs [block] once the engine is ready; while it initializes, only the latest block is kept. */
    private fun withTts(block: (TextToSpeech) -> Unit) = synchronized(this) {
        val existing = tts
        if (existing != null && ready) {
            block(existing)
            return@synchronized
        }
        pending = block
        if (existing != null) return@synchronized
        var created: TextToSpeech? = null
        var earlyStatus: Int? = null
        // The listener can run inside the constructor, before the engine reference exists.
        created = TextToSpeech(LoopFollowApplication.instance) { status ->
            synchronized(this) {
                val engine = created
                if (engine == null) earlyStatus = status else onInit(engine, status)
            }
        }
        tts = created
        earlyStatus?.let { onInit(created, it) }
    }

    private fun onInit(engine: TextToSpeech, status: Int) {
        if (tts !== engine) return
        val block = pending
        pending = null
        if (status == TextToSpeech.SUCCESS) {
            ready = true
            engine.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            block?.invoke(engine)
        } else {
            // A later reading tries again with a new engine.
            engine.shutdown()
            tts = null
            LogManager.log(LogManager.Category.GENERAL, "speakBG, text-to-speech unavailable")
        }
    }
}
