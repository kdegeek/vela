package app.vela.tv

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Milestone 0 placeholder screen: shows the box's facts and opens the accessibility settings. */
class ProbeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dm = resources.displayMetrics
        val facts = "Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT}) · " +
            "${android.os.Build.MODEL} · window ${dm.widthPixels}x${dm.heightPixels} @ ${dm.densityDpi} dpi"
        setContent {
            Column(
                Modifier.fillMaxSize().background(Color(0xFF0E1024)).padding(48.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                Text("Vela", color = Color(0xFFF6F1E7), fontSize = 48.sp)
                Text(facts, color = Color(0xFFF6F1E7), fontSize = 18.sp)
                Button(onClick = { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }) {
                    Text("Turn on the Vela agent")
                }
            }
        }
    }
}
