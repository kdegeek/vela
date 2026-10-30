package app.vela.tv.agent

import android.app.SearchManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import app.vela.tv.VelaApp
import app.vela.tv.control.HandsService
import app.vela.tv.control.MediaListener
import app.vela.tv.guard.Guard
import app.vela.tv.guard.GuardHit
import app.vela.tv.guard.Risk
import app.vela.tv.layout.Layouts
import app.vela.tv.player.PlayerActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** Everything the agent can do on the TV. Tools that change the screen return the screen afterwards. */
class TvTools(private val app: VelaApp) {
    private val pm get() = app.packageManager

    private fun hands(): HandsService = HandsService.current
        ?: throw IllegalStateException("Vela's agent service is off. Turn on \"Vela agent\" in the TV's accessibility settings.")

    private suspend fun afterwards(result: String): ToolOutcome {
        delay(SETTLE_MS)
        return ToolOutcome("$result\n\n${hands().snapshot()}")
    }

    fun all(): List<Tool> = listOf(
        tool("screen_read", "Read what is on the TV now: the app in front and its elements, numbered like [12].") {
            ToolOutcome(hands().snapshot())
        },
        tool(
            "screen_press", "Press an element by its number from the latest screen read.",
            props = { int("id", "element number") },
            check = { a -> hands().let { h -> Guard.press(h.label(a.int("id")), h.foreground) } },
        ) { a -> afterwards(hands().press(a.int("id"))) },
        tool("screen_focus", "Move the remote's focus to an element by number.", props = { int("id", "element number") }) { a ->
            afterwards(hands().focus(a.int("id")))
        },
        tool(
            "screen_type", "Type text into a text field by number (e.g. a search box).",
            props = { int("id", "element number"); str("text", "what to type") },
        ) { a -> afterwards(hands().type(a.int("id"), a.str("text"))) },
        tool(
            "screen_scroll", "Scroll a scrollable element by number.",
            props = { int("id", "element number"); enum("direction", "forward or backward", "forward", "backward") },
        ) { a -> afterwards(hands().scroll(a.int("id"), a.str("direction") != "backward")) },
        tool(
            "remote", "Press a remote button. 'select' is the middle OK button.",
            props = { enum("key", "button", "up", "down", "left", "right", "select", "back", "home") ; int("times", "how many presses, default 1", required = false) },
            check = { a -> if (a.str("key") == "select") hands().let { Guard.press(it.focusedLabel(), it.foreground) } else null },
        ) { a ->
            val h = hands()
            val times = (a.intOrNull("times") ?: 1).coerceIn(1, 20)
            var r = ""
            repeat(times) {
                r = when (val k = a.str("key")) {
                    "back" -> h.back()
                    "home" -> { h.goHome(); "Went to Vela's home screen." }
                    "select" -> h.dpad("center")
                    else -> h.dpad(k)
                }
                if (times > 1) delay(150)
            }
            afterwards(r)
        },
        tool("wait", "Wait for something to load.", props = { int("seconds", "1 to 10") }) { a ->
            delay(a.int("seconds").coerceIn(1, 10) * 1000L)
            ToolOutcome(hands().snapshot())
        },
        tool("apps_list", "List the TV apps installed, with their package names.") {
            ToolOutcome(tvApps().joinToString("\n") { (pkg, label) -> "$label: $pkg" })
        },
        tool("app_open", "Open an installed app by package name.", props = { str("package", "package name from apps_list") }) { a ->
            val p = a.str("package")
            val i = launchIntent(p) ?: return@tool ToolOutcome("$p isn't installed or can't be opened.", isError = true)
            app.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            afterwards("Opened $p.")
        },
        tool(
            "open_link",
            "Open a deep link or web URL, optionally in a specific app, e.g. https://www.youtube.com/watch?v=ID " +
                "or plex://... . Usually the fastest way to a specific title.",
            props = { str("uri", "the link"); str("package", "app to open it in", required = false) },
            check = { a -> linkRisk(a.str("uri"), a.strOrNull("package")) },
        ) { a ->
            val i = linkIntent(a.str("uri"), a.strOrNull("package"))
            if (i.resolveActivity(pm) == null) return@tool ToolOutcome("No installed app opens that link.", isError = true)
            app.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            afterwards("Opened the link.")
        },
        tool(
            "app_search", "Search inside an app, the way its search screen would (works for YouTube, Plex and most TV apps).",
            props = { str("package", "package name"); str("query", "what to search for") },
        ) { a ->
            val p = a.str("package")
            val i = Intent(Intent.ACTION_SEARCH).setPackage(p).putExtra(SearchManager.QUERY, a.str("query"))
            if (i.resolveActivity(pm) == null) return@tool ToolOutcome("$p doesn't accept searches; open it and use its search screen.", isError = true)
            app.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            afterwards("Searched $p.")
        },
        tool("media_status", "What is playing now, in any app, with position and length.") {
            ToolOutcome(sessions().ifEmpty { return@tool ToolOutcome("Nothing is playing.") }.joinToString("\n") { describe(it) })
        },
        tool(
            "media_control", "Control what is playing: play, pause, stop, next, previous, seek (to a position) or skip (by seconds, negative goes back).",
            props = {
                enum("action", "what to do", "play", "pause", "stop", "next", "previous", "seek", "skip")
                int("seconds", "position for seek, or amount for skip", required = false)
                str("package", "app to control; default is the one playing", required = false)
            },
        ) { a ->
            val all = sessions()
            val c = a.strOrNull("package")?.let { p -> all.firstOrNull { it.packageName == p } }
                ?: all.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: all.firstOrNull()
                ?: return@tool ToolOutcome("Nothing is playing.", isError = true)
            val t = c.transportControls
            val s = (a.intOrNull("seconds") ?: 0) * 1000L
            when (a.str("action")) {
                "play" -> t.play()
                "pause" -> t.pause()
                "stop" -> t.stop()
                "next" -> t.skipToNext()
                "previous" -> t.skipToPrevious()
                "seek" -> t.seekTo(s)
                "skip" -> t.seekTo(((c.playbackState?.position ?: 0L) + s).coerceAtLeast(0))
            }
            delay(SETTLE_MS)
            ToolOutcome(describe(c))
        },
        tool("home_states", "Smart-home devices and their state, optionally for one domain (light, media_player, climate…).", props = { str("domain", "e.g. light", required = false) }) { a ->
            val ha = app.settings.settings.value.homeAssistant() ?: return@tool ToolOutcome("Home Assistant isn't set up.", isError = true)
            ToolOutcome(ha.states(a.strOrNull("domain")).take(150).joinToString("\n") { "${it.entityId} (${it.friendlyName ?: "-"}): ${it.state}" })
        },
        tool(
            "home_call", "Call a Home Assistant service, e.g. light.turn_off on light.living_room, or with data like {\"brightness_pct\":30}.",
            props = {
                str("domain", "e.g. light"); str("service", "e.g. turn_on")
                str("entity_id", "target entity, or comma-separated entities", required = false)
                obj("data", "extra service data", required = false)
            },
            check = { a -> homeRisk(a.str("domain"), a.str("service"), entities(a), a["data"] as? JsonObject) },
        ) { a ->
            val ha = app.settings.settings.value.homeAssistant() ?: return@tool ToolOutcome("Home Assistant isn't set up.", isError = true)
            val data = buildJsonObject {
                (a["data"] as? JsonObject)?.forEach { (k, v) -> put(k, v) }
                val ids = entities(a)
                if (ids.isNotEmpty()) put("entity_id", JsonArray(ids.map(::JsonPrimitive)))
            }
            val changed = ha.call(a.str("domain"), a.str("service"), data)
            ToolOutcome("Done. " + changed.joinToString { "${it.entityId}: ${it.state}" })
        },
        tool("jellyfin_resume", "The owner's Continue Watching list on Jellyfin.") {
            val jf = jellyfin() ?: return@tool ToolOutcome("Jellyfin isn't set up.", isError = true)
            ToolOutcome(jf.resume(20).joinToString("\n") { "${it.id}: ${it.seriesName?.let { s -> "$s – " } ?: ""}${it.name} (${it.year ?: "?"}) ${it.progress?.let { p -> "${(p * 100).toInt()}% watched" } ?: ""}" })
        },
        tool("jellyfin_search", "Search the owner's Jellyfin library for movies, shows and episodes.", props = { str("query", "title or words") }) { a ->
            val jf = jellyfin() ?: return@tool ToolOutcome("Jellyfin isn't set up.", isError = true)
            val items = jf.search(a.str("query"), 20)
            ToolOutcome(items.ifEmpty { return@tool ToolOutcome("Nothing in Jellyfin matches.") }.joinToString("\n") { "${it.id}: ${it.type} ${it.seriesName?.let { s -> "$s – " } ?: ""}${it.name} (${it.year ?: "?"})" })
        },
        tool("jellyfin_play", "Play a Jellyfin movie or episode by id in Vela's player, resuming where the owner left off.", props = { str("item_id", "id from jellyfin_search or jellyfin_resume") }) { a ->
            jellyfin() ?: return@tool ToolOutcome("Jellyfin isn't set up.", isError = true)
            app.startActivity(PlayerActivity.intent(app, a.str("item_id")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            delay(SETTLE_MS)
            ToolOutcome("Playing.")
        },
        tool("layout_get", "The home screen layout as JSON.") {
            ToolOutcome(Layouts.encode(app.settings.layout.value))
        },
        tool(
            "layout_set", "Replace the home screen layout. Read it first with layout_get and keep rows the owner didn't ask to change. Format:\n${Layouts.SCHEMA_HELP}",
            props = { str("layout", "the whole layout as a JSON string") },
            check = { a -> Layouts.decode(a.str("layout")).getOrNull()?.let(::layoutRisk) },
        ) { a ->
            Layouts.decode(a.str("layout")).fold(
                { app.settings.setLayout(it); ToolOutcome("Home screen updated.") },
                { ToolOutcome("That layout isn't valid: ${it.message}", isError = true) },
            )
        },
    )

    /** The guard reads every target the call will reach, including ones inside [data]. Devices and areas can hold anything. */
    private fun homeRisk(domain: String, service: String, ids: List<String>, data: JsonObject?): GuardHit? {
        val inData = data?.get("entity_id")?.let { e ->
            when (e) {
                is JsonPrimitive -> e.contentOrNull?.split(',')?.map { it.trim() }.orEmpty()
                is JsonArray -> e.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                else -> emptyList()
            }
        }.orEmpty()
        val broad = listOf("device_id", "area_id", "floor_id", "label_id").filter { data?.containsKey(it) == true }
        if (broad.isNotEmpty()) return GuardHit(Risk.SECURITY, "$domain.$service on a whole ${broad.first().removeSuffix("_id")}")
        return Guard.homeService(domain, service, ids + inData)
    }

    /** A saved tile runs later without the agent, so its action is judged now, when the agent writes it. */
    private fun layoutRisk(layout: app.vela.tv.layout.HomeLayout): GuardHit? = layout.rows.asSequence()
        .mapNotNull { (it.source as? app.vela.tv.layout.Source.Pinned)?.items }
        .flatten()
        .mapNotNull { item ->
            when (val act = item.action) {
                is app.vela.tv.layout.TileAction.HomeService -> Guard.homeService(act.domain, act.service, listOf(act.entityId))
                is app.vela.tv.layout.TileAction.OpenUri -> linkRisk(act.uri, act.packageName)
                else -> null
            }?.let { hit -> hit.copy(reason = "a home tile \"${item.title}\" would ${hit.risk.label}: ${hit.reason}") }
        }
        .firstOrNull()

    private fun jellyfin() = app.settings.settings.value.jellyfin(app.settings.deviceId)

    private fun entities(a: JsonObject) = a.strOrNull("entity_id")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()

    fun tvApps(): List<Pair<String, String>> {
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
        return pm.queryIntentActivities(i, 0)
            .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
            .filter { it.first != app.packageName }
            .distinctBy { it.first }
            .sortedBy { it.second.lowercase() }
    }

    private fun launchIntent(pkg: String): Intent? = pm.getLeanbackLaunchIntentForPackage(pkg) ?: pm.getLaunchIntentForPackage(pkg)

    /** Builds the intent a link would start. `intent:` links are parsed by Android itself so the guard sees their real target. */
    private fun linkIntent(uri: String, pkg: String?): Intent {
        val i = if (uri.trim().startsWith("intent:", ignoreCase = true)) {
            Intent.parseUri(uri.trim(), Intent.URI_INTENT_SCHEME).apply {
                selector = null // a selector could send the intent somewhere other than what it names
                component = null
                addCategory(Intent.CATEGORY_BROWSABLE)
            }
        } else {
            Intent(Intent.ACTION_VIEW, Uri.parse(uri.trim()))
        }
        if (pkg != null) i.setPackage(pkg)
        return i
    }

    /** The guard for links: what the link says, and which app Android would actually open it in. Fails closed. */
    private fun linkRisk(uri: String, pkg: String?): GuardHit? {
        val i = runCatching { linkIntent(uri, pkg) }.getOrElse { return GuardHit(Risk.INSTALL, "the link can't be read safely") }
        val handler = i.`package` ?: i.resolveActivity(pm)?.packageName?.takeIf { it != "android" }
        if (uri.trim().startsWith("intent:", ignoreCase = true) && handler == null) {
            return GuardHit(Risk.INSTALL, "can't tell which app the link opens")
        }
        val fallback = i.getStringExtra("browser_fallback_url")
        return Guard.uri(uri, handler) ?: fallback?.let { Guard.uri(it) }
    }

    private fun sessions(): List<MediaController> {
        val msm = app.getSystemService(MediaSessionManager::class.java)
        return try {
            msm.getActiveSessions(ComponentName(app, MediaListener::class.java))
        } catch (e: SecurityException) {
            throw IllegalStateException("Vela can't see what's playing: allow Vela's notification access in the TV's settings.")
        }
    }

    private fun describe(c: MediaController): String {
        val st = c.playbackState
        val md = c.metadata
        val state = when (st?.state) {
            PlaybackState.STATE_PLAYING -> "playing"
            PlaybackState.STATE_PAUSED -> "paused"
            PlaybackState.STATE_BUFFERING -> "buffering"
            PlaybackState.STATE_STOPPED -> "stopped"
            else -> "idle"
        }
        val title = md?.description?.title ?: md?.getString(android.media.MediaMetadata.METADATA_KEY_TITLE)
        val len = md?.getLong(android.media.MediaMetadata.METADATA_KEY_DURATION)?.takeIf { it > 0 }
        val pos = st?.position?.takeIf { it >= 0 }
        return "${c.packageName}: $state ${title?.let { "\"$it\"" } ?: ""} ${pos?.let { "at ${it / 1000}s" } ?: ""}${len?.let { " of ${it / 1000}s" } ?: ""}".trim()
    }

    companion object {
        private const val SETTLE_MS = 800L
    }
}

// --- small helpers for tool specs and arguments ---

private class Props {
    val obj = mutableMapOf<String, JsonObject>()
    val required = mutableListOf<String>()
    private fun add(name: String, schema: JsonObject, req: Boolean) {
        obj[name] = schema
        if (req) required += name
    }
    fun str(name: String, desc: String, required: Boolean = true) = add(name, schema("string", desc), required)
    fun int(name: String, desc: String, required: Boolean = true) = add(name, schema("integer", desc), required)
    fun obj(name: String, desc: String, required: Boolean = true) = add(name, schema("object", desc), required)
    fun enum(name: String, desc: String, vararg values: String) = add(
        name,
        buildJsonObject {
            put("type", "string"); put("description", desc)
            putJsonArray("enum") { values.forEach { add(JsonPrimitive(it)) } }
        },
        true,
    )
    private fun schema(type: String, desc: String) = buildJsonObject { put("type", type); put("description", desc) }
}

private fun tool(
    name: String,
    description: String,
    props: Props.() -> Unit = {},
    check: (suspend (JsonObject) -> GuardHit?)? = null,
    run: suspend (JsonObject) -> ToolOutcome,
): Tool {
    val p = Props().apply(props)
    val schema = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") { p.obj.forEach { (k, v) -> put(k, v) } }
        putJsonArray("required") { p.required.forEach { add(JsonPrimitive(it)) } }
    }
    return object : Tool {
        override val spec = ToolSpec(name, description, schema)
        override suspend fun check(args: JsonObject) = check?.invoke(args)
        override suspend fun run(args: JsonObject) = withContext(Dispatchers.Default) { run(args) }
    }
}

private fun JsonObject.strOrNull(k: String): String? = (this[k] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
private fun JsonObject.str(k: String): String = strOrNull(k) ?: throw IllegalArgumentException("missing $k")
private fun JsonObject.intOrNull(k: String): Int? = (this[k] as? JsonPrimitive)?.let { it.intOrNull ?: it.contentOrNull?.toDoubleOrNull()?.toInt() }
private fun JsonObject.int(k: String): Int = intOrNull(k) ?: throw IllegalArgumentException("missing number $k")
