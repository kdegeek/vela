package app.vela.tv.launcher

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.*
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import app.vela.tv.VelaApp
import app.vela.tv.design.*

/** Speech fills a reviewable draft. Permission and network failures always leave typing and Back available. */
class AskActivity : ComponentActivity() {
    private var recognizer: SpeechRecognizer? = null
    private var acceptingSpeech = false
    private val listening = mutableStateOf(false)
    private val heard = mutableStateOf("")
    private val error = mutableStateOf<String?>(null)
    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
        if (allowed) listen() else error.value = "Microphone access is off. You can type here or use your phone."
    }
    @OptIn(ExperimentalLayoutApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        heard.value = savedInstanceState?.getString("draft").orEmpty()
        val app = application as VelaApp
        setContent {
            val settings by app.settings.settings.collectAsState()
            var text by remember { heard }
            val field = remember { FocusRequester() }
            LaunchedEffect(Unit) { field.requestFocus() }
            VelaTheme(settings) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).imePadding().padding(24.dp), contentAlignment = Alignment.Center) {
                    Surface(Modifier.widthIn(max = 620.dp).fillMaxWidth(), shape = VelaDesign.PanelShape) {
                        Column(Modifier.verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(if (listening.value) "I'm listening…" else "What would you like?", style = MaterialTheme.typography.headlineMedium,
                                modifier = Modifier.semantics { heading(); liveRegion = LiveRegionMode.Polite })
                            Text("Find something to watch, set the mood, or make Vela yours.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (!settings.agentReady) Text("Connect AI from your paired phone in Settings. You can browse apps while AI is disconnected.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            OutlinedTextField(text, { text = it.take(8000); error.value = null }, modifier = Modifier.fillMaxWidth().focusRequester(field),
                                label = { Text("Your request") }, placeholder = { Text("Put on the next episode") }, singleLine = true,
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send), keyboardActions = KeyboardActions(onSend = { send(text) }))
                            error.value?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                VelaButton("Ask", enabled = settings.agentReady && text.isNotBlank() && !listening.value) { send(text) }
                                if (SpeechRecognizer.isRecognitionAvailable(this@AskActivity)) VelaButton(if (listening.value) "Stop listening" else "Speak") {
                                    if (listening.value) stopListening() else listen()
                                }
                                VelaButton("Back") { finish() }
                            }
                            Text(if (listening.value) "Speak your request. Review the text, then choose Ask." else "You can type, speak, or ask from your paired phone.",
                                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
    override fun onSaveInstanceState(outState: Bundle) { outState.putString("draft", heard.value); super.onSaveInstanceState(outState) }
    private fun send(text: String) {
        if (listening.value) return
        if (text.isBlank()) { error.value = "Type or speak a request first."; return }
        stopListening()
        if (!(application as VelaApp).settings.settings.value.agentReady) {
            error.value = "Connect AI from your paired phone in Settings, then try again."; return
        }
        try { (application as VelaApp).runner.ask(text.trim()); finish() }
        catch (e: Exception) { error.value = e.message ?: "Couldn't start the task. Check your connection in Settings." }
    }
    private fun stopListening() { acceptingSpeech = false; recognizer?.cancel(); listening.value = false }
    private fun listen() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            micPermission.launch(Manifest.permission.RECORD_AUDIO); return
        }
        error.value = null
        try {
            val r = recognizer ?: SpeechRecognizer.createSpeechRecognizer(this).also { recognizer = it }
            r.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) { if (acceptingSpeech) listening.value = true }
                override fun onResults(results: Bundle?) {
                    if (!acceptingSpeech) return
                    acceptingSpeech = false; listening.value = false
                    val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                    if (text.isNotBlank()) heard.value = text.take(8000) else error.value = "I didn't catch that. Try again or type your request."
                }
                override fun onPartialResults(partial: Bundle?) {
                    if (acceptingSpeech) partial?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { heard.value = it.take(8000) }
                }
                override fun onError(code: Int) {
                    if (!acceptingSpeech) return
                    acceptingSpeech = false; listening.value = false
                    error.value = when (code) {
                        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "I didn't catch that. Try again or type your request."
                        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Speech couldn't connect. Try typing or use your phone."
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone access is off. Type here or use your phone."
                        else -> "Speech is unavailable. Type here or use your phone."
                    }
                }
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                // Recognition is still pending after the microphone stops; keep duplicate starts disabled.
                override fun onEndOfSpeech() {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
            acceptingSpeech = true; listening.value = true
            r.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true))
        } catch (e: Exception) { acceptingSpeech = false; listening.value = false; error.value = "Speech is unavailable. Type here or use your phone." }
    }
    override fun onStop() { stopListening(); super.onStop() }
    override fun onDestroy() { recognizer?.destroy(); super.onDestroy() }
}
