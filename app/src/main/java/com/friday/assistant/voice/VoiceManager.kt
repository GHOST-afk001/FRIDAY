package com.friday.assistant.voice

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.friday.assistant.runtime.FridayStateFlow
import java.util.Locale

/** Reliable one-shot Android speech wrapper. Uses system recognition first for device compatibility. */
class VoiceManager(context: Context, private val listener: Listener) {
    interface Listener { fun onListening(); fun onResult(text: String); fun onError(message: String); fun onAmplitude(value: Float) = Unit }
    private val appContext=context.applicationContext
    private val main=Handler(Looper.getMainLooper())
    private var recognizer:SpeechRecognizer?=null
    private var destroyed=false
    private var generation=0L
    private var retried=false
    private var usingOnDevice=false
    init { runMain { createRecognizer() } }
    fun start(){runMain{if(destroyed)return@runMain;val r=recognizer?:return@runMain listener.onError("No Android speech recognition service is available.");val g=++generation;retried=false;runCatching{r.setRecognitionListener(listenerFor(r,g));r.startListening(intent())}.onFailure{listener.onError("Could not start microphone listening.")}}}
    fun cancel(){runMain{generation++;runCatching{recognizer?.cancel()};FridayStateFlow.resetAmplitude()}}
    fun destroy(){runMain{if(destroyed)return@runMain;destroyed=true;generation++;runCatching{recognizer?.cancel()};runCatching{recognizer?.destroy()};recognizer=null;FridayStateFlow.resetAmplitude()}}
    private fun createRecognizer(){if(destroyed||recognizer!=null)return;recognizer=runCatching{SpeechRecognizer.createSpeechRecognizer(appContext)}.getOrNull();usingOnDevice=false;if(recognizer==null&&Build.VERSION.SDK_INT>=31&&runCatching{SpeechRecognizer.isOnDeviceRecognitionAvailable(appContext)}.getOrDefault(false)){recognizer=runCatching{SpeechRecognizer.createOnDeviceSpeechRecognizer(appContext)}.getOrNull();usingOnDevice=true};recognizer?.setRecognitionListener(listenerFor(recognizer!!,generation));if(recognizer==null)listener.onError("Android SpeechRecognizer is unavailable. Install/enable a speech recognition service.")}
    private fun listenerFor(owner:SpeechRecognizer,g:Long)=object:RecognitionListener{
        override fun onReadyForSpeech(p:Bundle?){if(owner===recognizer&&!destroyed&&g==generation)listener.onListening()}
        override fun onResults(r:Bundle?){if(owner!==recognizer||destroyed||g!=generation)return;FridayStateFlow.resetAmplitude();listener.onResult(r?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty())}
        override fun onError(e:Int){if(owner!==recognizer||destroyed||g!=generation)return;FridayStateFlow.resetAmplitude();if(!retried&&usingOnDevice){retried=true;replaceWithSystem(g);return};listener.onError(message(e))}
        override fun onRmsChanged(v:Float){val n=((v+2f)/12f).coerceIn(0f,1f);FridayStateFlow.updateAmplitude(n);listener.onAmplitude(n)}
        override fun onBeginningOfSpeech(){};override fun onBufferReceived(b:ByteArray?){};override fun onEndOfSpeech(){FridayStateFlow.updateAmplitude(0f)};override fun onEvent(t:Int,p:Bundle?){};override fun onPartialResults(p:Bundle?){}
    }
    private fun replaceWithSystem(g:Long){runMain{if(destroyed||g!=generation)return@runMain;runCatching{recognizer?.cancel();recognizer?.destroy()};recognizer=runCatching{SpeechRecognizer.createSpeechRecognizer(appContext)}.getOrNull();usingOnDevice=false;val r=recognizer;if(r==null){listener.onError("No system speech service is available.");return@runMain};runCatching{r.setRecognitionListener(listenerFor(r,g));r.startListening(intent())}.onFailure{listener.onError("System speech service could not be started.")}}}
    private fun intent()=Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply{putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);putExtra(RecognizerIntent.EXTRA_LANGUAGE,speechLocale());putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE,"en-IN");putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE,false);putExtra(RecognizerIntent.EXTRA_MAX_RESULTS,3);putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS,false);putExtra(RecognizerIntent.EXTRA_PROMPT,"Speak to FRIDAY")}
    private fun speechLocale():String{val t=Locale.getDefault().toLanguageTag();return if(t.startsWith("hi",true)||t.startsWith("en",true))t else "en-IN"}
    private fun runMain(b:()->Unit){if(Looper.myLooper()==Looper.getMainLooper())b()else main.post(b)}
    private fun message(e:Int)=when(e){SpeechRecognizer.ERROR_AUDIO->"Audio recording failed.";SpeechRecognizer.ERROR_CLIENT->"Speech recognition was interrupted.";SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS->"Microphone permission is required.";SpeechRecognizer.ERROR_NETWORK,SpeechRecognizer.ERROR_NETWORK_TIMEOUT->"Speech service/network unavailable.";SpeechRecognizer.ERROR_NO_MATCH->"No speech recognized. Speak clearly and try again.";SpeechRecognizer.ERROR_SPEECH_TIMEOUT->"No speech detected. Try again.";SpeechRecognizer.ERROR_RECOGNIZER_BUSY->"Speech recognizer is busy.";else->"Speech recognition failed (error $e)."}
}
