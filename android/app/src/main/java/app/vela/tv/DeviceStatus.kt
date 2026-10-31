package app.vela.tv

import android.content.ComponentName
import android.media.session.MediaSessionManager
import android.os.Build
import app.vela.tv.control.HandsService
import app.vela.tv.control.MediaListener
import kotlinx.serialization.Serializable

/** Facts available to UI, phone and agent. Configured does not imply a successful remote connection. */
@Serializable
data class DeviceStatus(
    val version: String,
    val androidApi: Int,
    val model: String,
    val surfaceWidth: Int,
    val surfaceHeight: Int,
    val agentConfigured: Boolean,
    val accessibility: Boolean,
    val mediaAccess: Boolean,
    val jellyfinConfigured: Boolean,
    val homeConfigured: Boolean,
    val nativeDpad: Boolean,
    val runtime: app.vela.tv.runtime.RuntimeStatus = app.vela.tv.runtime.RuntimeStatus(),
    val plexConfigured: Boolean = false,
) {
    companion object {
        fun read(app: VelaApp): DeviceStatus {
            val s = app.settings.settings.value
            val metrics = app.resources.displayMetrics
            return DeviceStatus(BuildConfig.VERSION_NAME, Build.VERSION.SDK_INT, "${Build.MANUFACTURER} ${Build.MODEL}",
                metrics.widthPixels, metrics.heightPixels, s.agentReady, HandsService.current != null,
                runCatching { app.getSystemService(MediaSessionManager::class.java).getActiveSessions(ComponentName(app, MediaListener::class.java)); true }.getOrDefault(false),
                s.jellyfinUrl.isNotBlank() && s.jellyfinToken.isNotBlank() && s.jellyfinUserId.isNotBlank(),
                s.homeAssistantUrl.isNotBlank() && s.homeAssistantToken.isNotBlank(), Build.VERSION.SDK_INT >= 33, app.runtimeStatus.value,
                plexConfigured = s.plexConfigured)
        }
    }
}
