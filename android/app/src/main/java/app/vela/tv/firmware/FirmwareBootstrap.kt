package app.vela.tv.firmware

import android.Manifest
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.UserManager
import android.provider.Settings
import android.util.Log
import app.vela.tv.BuildConfig

/** The only shared-app integration. Stock builds return before consulting or changing settings. */
object FirmwareBootstrap {
    // Session retains only applicationContext for process-lifetime observers.
    @android.annotation.SuppressLint("StaticFieldLeak")
    private var session: Session? = null

    fun onApplicationCreate(context: Context) {
        if (!BuildConfig.FIRMWARE || Build.VERSION.SDK_INT < 35) return
        val app = context.applicationContext
        // Run after VelaApp finishes initialising: Android may bind HandsService after enrollment.
        Handler(Looper.getMainLooper()).post {
            if (session == null) {
                try {
                    if (isSystemOwner(app)) session = Session(app).also { it.start() }
                } catch (_: Exception) {
                    Log.w(TAG, "Firmware bootstrap unavailable; no automatic recovery attempted.")
                }
            }
        }
    }

    private fun isSystemOwner(context: Context): Boolean {
        val flags = context.applicationInfo.flags
        return flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0 &&
            context.getSystemService(UserManager::class.java)?.isSystemUser == true &&
            context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED &&
            context.checkSelfPermission(Manifest.permission.MEDIA_CONTENT_CONTROL) == PackageManager.PERMISSION_GRANTED
    }

    @android.annotation.TargetApi(35)
    private class Session(private val context: Context) : FirmwareBootstrapStore {
        private val resolver = context.contentResolver
        private val handler = Handler(Looper.getMainLooper())
        private var finished = false
        private var observing = false
        private var receiving = false
        private val observer = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) = attempt()
        }
        private val unlock = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) = attempt()
        }

        fun start() {
            try {
                observing = true
                listOf(FirmwareSettings.OWNER_OPT_IN, FirmwareSettings.ATTEMPTED,
                    FirmwareSettings.USER_SETUP_COMPLETE, FirmwareSettings.TV_USER_SETUP_COMPLETE).forEach {
                    resolver.registerContentObserver(Settings.Secure.getUriFor(it), false, observer)
                }
                resolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.DEVICE_PROVISIONED), false, observer)
                context.registerReceiver(unlock, IntentFilter().apply {
                    addAction(Intent.ACTION_USER_UNLOCKED)
                    addAction(Intent.ACTION_USER_PRESENT)
                }, Context.RECEIVER_NOT_EXPORTED)
                receiving = true
                attempt()
            } catch (_: Exception) {
                finish(FirmwareBootstrapResult.FAILED)
            }
        }

        private fun attempt() {
            if (finished) return
            val result = enrollFirmwareHands(this)
            if (result != FirmwareBootstrapResult.WAITING) finish(result)
        }

        private fun finish(result: FirmwareBootstrapResult) {
            finished = true
            if (observing) runCatching { resolver.unregisterContentObserver(observer) }
            if (receiving) runCatching { context.unregisterReceiver(unlock) }
            Log.i(TAG, "Firmware accessibility bootstrap: $result")
        }

        override fun environment(): FirmwareEnvironment {
            val users = context.getSystemService(UserManager::class.java)
            val keyguard = context.getSystemService(KeyguardManager::class.java)
            return FirmwareEnvironment(
                firmwareBuild = BuildConfig.FIRMWARE,
                supportedPlatform = Build.VERSION.SDK_INT >= 35,
                systemApp = context.applicationInfo.flags and
                    (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0,
                ownerUser = users?.isSystemUser == true,
                userUnlocked = users?.isUserUnlocked == true,
                deviceLocked = keyguard == null || keyguard.isDeviceLocked || keyguard.isKeyguardLocked,
                writeSecureSettingsGranted = context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED,
                mediaContentControlGranted = context.checkSelfPermission(Manifest.permission.MEDIA_CONTENT_CONTROL) == PackageManager.PERMISSION_GRANTED,
                deviceProvisioned = Settings.Global.getString(resolver, Settings.Global.DEVICE_PROVISIONED),
                userSetupComplete = Settings.Secure.getString(resolver, FirmwareSettings.USER_SETUP_COMPLETE),
                tvUserSetupComplete = Settings.Secure.getString(resolver, FirmwareSettings.TV_USER_SETUP_COMPLETE),
                ownerOptIn = Settings.Secure.getString(resolver, FirmwareSettings.OWNER_OPT_IN),
                imageEnrollmentDefault = BuildConfig.FIRMWARE_ENROLL_BY_DEFAULT,
            )
        }

        override fun attempted(): String? = Settings.Secure.getString(resolver, FirmwareSettings.ATTEMPTED)
        override fun markAttempted(): Boolean =
            Settings.Secure.putString(resolver, FirmwareSettings.ATTEMPTED, "1") && attempted() == "1"
        override fun enabledServices(): String? = Settings.Secure.getString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        override fun putEnabledServices(value: String): Boolean =
            Settings.Secure.putString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, value)
    }

    private const val TAG = "VelaFirmware"
}
