package app.vela.tv.agent

import app.vela.tv.VelaApp
import app.vela.tv.control.HandsService
import app.vela.tv.guard.GuardHit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import java.text.DateFormat
import java.util.Date

/** One line of the conversation as the owner sees it, on the TV overlay and the phone page. */
@Serializable
data class Line(val kind: String, val text: String)

/**
 * Runs one agent task at a time for the whole app. A new request cancels the one in progress. The conversation is
 * kept for follow-ups ("no, the other one") and forgotten after a quiet spell.
 */
class Runner(private val app: VelaApp, private val scope: CoroutineScope) {
    private val _lines = MutableStateFlow<List<Line>>(emptyList())
    val lines: StateFlow<List<Line>> = _lines
    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running
    private val _pending = MutableStateFlow<String?>(null)
    /** The question waiting for the owner's OK, if any. */
    val pending: StateFlow<String?> = _pending

    /** When the current question appeared (uptime ms), for telling fresh remote presses from ones already under way. */
    @Volatile
    var pendingSince: Long? = null
        private set

    private var job: Job? = null
    private var answer: CompletableDeferred<Boolean>? = null
    private var history: List<Msg> = emptyList()
    private var lastAt = 0L

    private val hands get() = HandsService.current

    fun attach(service: HandsService) {
        if (_running.value) service.overlay.show(_lines.value.map { it.text })
    }

    fun ask(text: String) {
        val task = text.trim()
        if (task.isEmpty()) return
        job?.cancel()
        val settings = app.settings.settings.value
        val provider = settings.makeProvider()
        if (System.currentTimeMillis() - lastAt > FORGET_AFTER_MS) history = emptyList()
        add(Line("you", task))
        if (provider == null) {
            add(Line("error", "Vela has no AI set up yet. Open the phone page shown on Vela's home screen to add one."))
            return
        }
        val agent = Agent(provider, TvTools(app).all(), systemPrompt(), ::confirm)
        job = scope.launch {
            _running.value = true
            try {
                history = agent.run(task, history) { e -> onEvent(e) }
            } finally {
                lastAt = System.currentTimeMillis()
                _running.value = false
                pendingSince = null
                _pending.value = null
                hands?.overlay?.show(_lines.value.takeLast(2).map { it.text }, hideAfterMs = 6000)
            }
        }
    }

    fun stop() {
        job?.cancel()
        add(Line("error", "Stopped."))
    }

    fun answer(allow: Boolean) {
        answer?.complete(allow)
    }

    private suspend fun confirm(call: ToolCall, hit: GuardHit): Boolean {
        val d = CompletableDeferred<Boolean>()
        answer = d
        val q = "Vela wants to ${hit.risk.label}: ${hit.reason}"
        pendingSince = android.os.SystemClock.uptimeMillis()
        _pending.value = q
        hands?.overlay?.show(_lines.value.map { it.text }, prompt = q)
        val ok = withTimeoutOrNull(60_000) { d.await() } ?: false
        pendingSince = null
        _pending.value = null
        add(Line("step", if (ok) "Allowed." else "Not allowed."))
        return ok
    }

    private fun onEvent(e: AgentEvent) {
        when (e) {
            is AgentEvent.Said -> add(Line("said", e.text))
            is AgentEvent.Calling -> add(Line("step", describe(e.call)))
            is AgentEvent.Returned -> if (e.outcome.isError) add(Line("step", e.outcome.content.lineSequence().first().take(140)))
            is AgentEvent.Asking -> {}
            is AgentEvent.Done -> add(Line("said", e.text?.takeIf { it.isNotBlank() } ?: "Done."))
            is AgentEvent.Failed -> add(Line("error", e.message))
        }
    }

    private fun add(l: Line) {
        _lines.value = (_lines.value + l).takeLast(60)
        if (_running.value || l.kind == "you") hands?.overlay?.show(_lines.value.map { it.text })
    }

    private fun describe(c: ToolCall): String {
        // Short values only: a whole layout or long text would fill the overlay.
        val a = c.arguments.entries.map { (k, v) -> k to v.toString().trim('"') }.filter { it.second.length <= 40 }
            .joinToString(" ") { (k, v) -> "$k=$v" }
        return "${c.name.replace('_', ' ')} $a".trim()
    }

    private fun systemPrompt() = """
        You are Vela, the agent built into this TV box. The owner talks to you from the couch; you do what they ask on the
        TV, start to finish, so they never have to work out how. Today is ${DateFormat.getDateInstance(DateFormat.FULL).format(Date())}.

        How to work:
        - Prefer direct routes: open_link with an app's deep link or web URL, app_search, media_control, home_call,
          jellyfin_play. Step through screens with the remote or by pressing elements only when there is no direct route.
        - After acting, check the screen (most tools return it). If something didn't work, try another way.
        - Element numbers like [12] come from the latest screen read and change every time the screen is read.
        - Apps that draw their own screens show few elements; use the remote keys there and read again.
        - You can rebuild the home screen with layout_get and layout_set when the owner asks for a different home.
        - Some actions (spending money, locks and alarms, accounts, installing apps, resets) ask the owner first.
          If they say no, don't look for another way; tell them.
        - Your replies appear on the TV in a small panel: one or two short sentences, no markdown.
    """.trimIndent()

    companion object {
        private const val FORGET_AFTER_MS = 15 * 60 * 1000L
    }
}
