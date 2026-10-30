package app.vela.tv

import android.app.Application
import app.vela.tv.agent.Runner
import app.vela.tv.settings.SettingsStore
import app.vela.tv.web.PhoneServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class VelaApp : Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    lateinit var settings: SettingsStore
        private set
    lateinit var runner: Runner
        private set
    lateinit var phone: PhoneServer
        private set

    override fun onCreate() {
        super.onCreate()
        settings = SettingsStore(this)
        runner = Runner(this, scope)
        phone = PhoneServer(this).also { it.start() }
    }
}
