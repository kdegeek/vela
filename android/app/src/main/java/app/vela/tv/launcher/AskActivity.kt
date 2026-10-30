package app.vela.tv.launcher

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.vela.tv.VelaApp

/**
 * "What do you want to watch?": speech when the box lets apps hear the remote's microphone, typing otherwise. It
 * closes as soon as the request is sent, so the agent works on whatever app is underneath, with progress in the overlay.
 */
class AskActivity : ComponentActivity() {
    private var recognizer: SpeechRecognizer? = null
    private val listening = mutableStateOf(false)
    private val heard = mutableStateOf("")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as VelaApp
        val canListen = SpeechRecognizer.isRecognitionAvailable(this)
        setContent {
            val lines by app.runner.lines.collectAsState()
            var text by remember { heard }
            val field = remember { FocusRequester() }
            LaunchedEffect(Unit) { runCatching { field.requestFocus() } }
            fun send(t: String) {
                if (t.isBlank()) return
                app.runner.ask(t)
                finish()
            }
            Box(Modifier.fillMaxSize().background(Color(0xCC0E1024)), contentAlignment = Alignment.Center) {
                Column(
                    Modifier.width(560.dp).clip(RoundedCornerShape(20.dp)).background(Color(0xFF1B1840))
                        .border(1.dp, Color(0x26F6F1E7), RoundedCornerShape(20.dp)).padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("What would you like?", color = Color(0xFFF6F1E7), fontSize = 24.sp)
                    for (l in lines.takeLast(3)) Text(l.text, color = Color(0xA6F6F1E7), fontSize = 14.sp, maxLines = 2)
                    OutlinedTextField(
                        value = text, onValueChange = { text = it },
                        modifier = Modifier.fillMaxWidth().focusRequester(field),
                        placeholder = { Text("Put on the next episode of Severance") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { send(text) }),
                        colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color(0xFFF6F1E7), unfocusedTextColor = Color(0xFFF6F1E7)),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(onClick = { send(text) }) { Text("Ask") }
                        if (canListen) Button(onClick = { listen() }) { Text(if (listening.value) "Listening…" else "Speak") }
                    }
                }
            }
        }
    }

    private fun listen() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
            return
        }
        val r = recognizer ?: SpeechRecognizer.createSpeechRecognizer(this).also { recognizer = it }
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { listening.value = true }
            override fun onResults(results: Bundle?) {
                listening.value = false
                val said = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                if (said.isNotBlank()) {
                    (application as VelaApp).runner.ask(said)
                    finish()
                }
            }
            override fun onPartialResults(partial: Bundle?) {
                partial?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { heard.value = it }
            }
            override fun onError(error: Int) { listening.value = false }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        r.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true))
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) listen()
    }

    override fun onDestroy() {
        recognizer?.destroy()
        super.onDestroy()
    }
}
