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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import app.vela.tv.VelaApp
import app.vela.tv.design.*

/** Speech and text use the same task path. Errors always leave typing and Back available. */
class AskActivity : ComponentActivity() {
    private var recognizer: SpeechRecognizer? = null
    private val listening = mutableStateOf(false)
    private val heard = mutableStateOf("")
    private val error = mutableStateOf<String?>(null)
    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
        if (allowed) listen() else error.value = "Microphone access is off. You can type here or use your phone."
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as VelaApp
        setContent {
            val settings by app.settings.settings.collectAsState()
            var text by remember { heard }
            val field = remember { FocusRequester() }
            LaunchedEffect(Unit) { field.requestFocus() }
            VelaTheme(settings) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background.copy(alpha = 0.94f)), contentAlignment = Alignment.Center) {
                    Surface(Modifier.widthIn(max = 620.dp).padding(24.dp), shape = VelaDesign.PanelShape) {
                        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(if (listening.value) "I'm listening…" else "What would you like?", style = MaterialTheme.typography.headlineMedium)
                            Text("Find something to watch, set the mood, or make Vela yours.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            OutlinedTextField(text, { text = it.take(8000) }, modifier = Modifier.fillMaxWidth().focusRequester(field),
                                placeholder = { Text("Put on the next episode") }, singleLine = true,
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send), keyboardActions = KeyboardActions(onSend = { send(text) }))
                            error.value?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                VelaButton("Ask") { send(text) }
                                if (SpeechRecognizer.isRecognitionAvailable(this@AskActivity)) VelaButton(if (listening.value) "Stop listening" else "Speak") {
                                    if (listening.value) { recognizer?.cancel(); listening.value = false } else listen()
                                }
                                VelaButton("Back") { finish() }
                            }
                            Text("You can also ask from the phone paired in Settings.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
    private fun send(text: String) {
        if (text.isBlank()) return
        try { (application as VelaApp).runner.ask(text); finish() }
        catch (e: Exception) { error.value = e.message ?: "Couldn't start the task." }
    }
    private fun listen() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            micPermission.launch(Manifest.permission.RECORD_AUDIO); return
        }
        error.value = null
        val r = recognizer ?: SpeechRecognizer.createSpeechRecognizer(this).also { recognizer = it }
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { listening.value = true }
            override fun onResults(results: Bundle?) {
                listening.value = false
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                if (text.isNotBlank()) send(text) else error.value = "I didn't catch that. Try again or type your request."
            }
            override fun onPartialResults(partial: Bundle?) { partial?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { heard.value = it.take(8000) } }
            override fun onError(code: Int) {
                listening.value = false
                error.value = when (code) {
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "I didn't catch that. Try again or type your request."
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Speech couldn't connect. Try typing or use your phone."
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone access is off. Type here or use your phone."
                    else -> "Speech is unavailable ($code). Type here or use your phone."
                }
            }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { listening.value = false }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        try { listening.value = true; r.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)) }
        catch (e: Exception) { listening.value = false; error.value = "Speech is unavailable. Type here or use your phone." }
    }
    override fun onStop() { recognizer?.cancel(); listening.value = false; super.onStop() }
    override fun onDestroy() { recognizer?.destroy(); super.onDestroy() }
}
