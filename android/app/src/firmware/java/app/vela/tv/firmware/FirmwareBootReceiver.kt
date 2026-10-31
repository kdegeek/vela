package app.vela.tv.firmware

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** System wake only; the same entrypoint also handles ordinary HOME/application startup. */
class FirmwareBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            FirmwareBootstrap.onApplicationCreate(context)
        }
    }
}
