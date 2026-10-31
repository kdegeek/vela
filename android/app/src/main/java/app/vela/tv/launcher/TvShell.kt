package app.vela.tv.launcher

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.*
import app.vela.tv.DeviceStatus
import app.vela.tv.VelaApp
import app.vela.tv.control.NowPlayingAccess
import app.vela.tv.design.*
import app.vela.tv.layout.*
import app.vela.tv.profile.HouseholdState
import kotlinx.coroutines.*

@Composable internal fun TvShell(app: VelaApp, resume: Int) {
    val settings by app.settings.settings.collectAsState()
    var selected by rememberSaveable { mutableStateOf(Page.HOME) }
    var refresh by remember { mutableIntStateOf(0) }
    var homeReset by remember { mutableIntStateOf(0) }
    var homeTabFocused by remember { mutableStateOf(false) }
    var tile by remember { mutableStateOf<Tile?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var failedAction by remember { mutableStateOf<TileAction?>(null) }
    var opening by remember { mutableStateOf(false) }
    var nowPlayingDetails by rememberSaveable { mutableStateOf(false) }
    var nowPlayingVisible by remember { mutableStateOf(false) }
    var mediaAccess by remember { mutableStateOf(NowPlayingAccess.LOADING) }
    var settingsOrigin by rememberSaveable { mutableStateOf<String?>(null) }
    var restoreSettings by remember { mutableIntStateOf(0) }
    val pages = rememberSaveableStateHolder()
    val scope = rememberCoroutineScope()
    val data = remember(app) { HomeData(app) }
    val tabs = remember { Page.tabs.associateWith { FocusRequester() } }
    val navigation = rememberLazyListState()
    val household by app.householdReady.collectAsState()
    val householdState = household?.state?.collectAsState()?.value
    val identity = (householdState as? HouseholdState.Ready)?.document?.activeProfile?.displayName
    val revision = (householdState as? HouseholdState.Ready)?.document?.revision
    val home = remember(revision) { HomePresentation() }
    val running by app.runner.running.collectAsState()
    val pending by app.runner.pending.collectAsState()
    val lines by app.runner.lines.collectAsState()
    val remoteAnswers = remember(resume) { DeviceStatus.read(app).accessibility }
    LaunchedEffect(revision) { tile = null; failedAction = null; refresh++ }
    fun focusTab(page: Page) { scope.launch {
        navigation.scrollToItem(Page.tabs.indexOf(page.tab))
        withFrameNanos { }
        tabs.getValue(page.tab).requestFocus()
    } }
    fun openSettingsChild(page: Page, origin: String) { settingsOrigin = origin; selected = page }
    fun askScreen() { app.startActivity(Intent(app, AskActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    fun perform(action: TileAction, closeTitle: Boolean = false) {
        if (opening) return
        opening = true
        val originatingTile = tile?.key
        scope.launch {
            try { data.perform(action); if (closeTitle && tile?.key == originatingTile) tile = null; failedAction = null }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { failedAction = action.takeIf { it is TileAction.LaunchApp || it is TileAction.OpenUri || it is TileAction.PlayJellyfin }; error = e.message ?: "That action could not be completed." }
            finally { opening = false }
        }
    }
    fun select(t: Tile) { if (t.action is TileAction.PlayJellyfin || t.action is TileAction.OpenUri) tile = t else perform(t.action) }
    fun ask(prompt: String) {
        runCatching { app.runner.ask(prompt); selected = Page.ACTIVITY }
            .onFailure { failedAction = null; error = it.message ?: "Couldn't start the task." }
    }
    BackHandler(enabled = tile == null && error == null && !nowPlayingDetails) {
        if (selected.isSettingsChild) { selected = Page.SETTINGS; restoreSettings++ }
        else if (selected != Page.HOME || !homeTabFocused) {
            selected = Page.HOME; homeReset++; home.focused = null; focusTab(Page.HOME)
        }
    }
    VelaTheme(settings) {
        Surface(Modifier.fillMaxSize(), color = Vela.colors.canvas) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val shortSurface = maxHeight < 360.dp
                Column(Modifier.fillMaxSize().then(if (shortSurface) Modifier.verticalScroll(rememberScrollState()) else Modifier)
                    .padding(top = VelaSpace.SafeVertical, bottom = VelaSpace.SafeVertical)) {
                    TopBar(selected, identity, agentIndicator(running, pending, lines.lastOrNull()?.text), navigation, tabs,
                        { selected = it }, { openSettingsChild(Page.PROFILES, "profiles") }, ::askScreen, { home.focused = null }, { homeTabFocused = it })
                    Spacer(Modifier.height(VelaSpace.Xs))
                    LaunchedEffect(Unit) { focusTab(Page.HOME) }
                    Box((if (shortSurface) Modifier.height(300.dp) else Modifier.weight(1f)).focusProperties {
                        onExit = { if (requestedFocusDirection == FocusDirection.Up) { cancelFocusChange(); focusTab(selected.tab) } }
                    }.focusGroup()) {
                        pages.SaveableStateProvider(selected.name) {
                            when (selected) {
                                Page.HOME -> HomeContent(app, resume + refresh, ::select, { selected = Page.SETTINGS },
                                    { openSettingsChild(Page.ARRANGE, "arrange") }, ::askScreen, nowPlayingVisible, homeReset, home)
                                Page.SEARCH -> SearchPage(app, refresh, ::select, ::ask) { selected = Page.SETTINGS }
                                Page.APPS -> RowsPage(app, RowSpec("all-apps", "Your apps", Source.Apps(), TileStyle.APP), resume + refresh, ::select)
                                Page.LIST -> RowsPage(app, RowSpec("my-list", "My list", Source.Watchlist), resume + refresh, ::select)
                                Page.HOUSE -> HousePage(app, resume + refresh, ::select, { selected = Page.SETTINGS }) { refresh++ }
                                Page.ACTIVITY -> ActivityPage(app, remoteAnswers, ::ask, ::askScreen)
                                Page.SETTINGS -> SettingsHub(app, resume, mediaAccess, settingsOrigin, restoreSettings,
                                    { failedAction = null; error = it }, { page, origin -> openSettingsChild(page, origin) },
                                    { nowPlayingDetails = true }) { refresh++ }
                                Page.PROFILES -> ProfilesPage(app)
                                Page.ROUTINES -> RoutinesPage(app)
                                Page.CONTROL -> ExternalControlPage(app)
                                Page.ARRANGE -> ArrangeHome(app) { selected = Page.SETTINGS; restoreSettings++ }
                            }
                        }
                    }
                    NowPlayingSurface(app, resume, nowPlayingDetails, { nowPlayingDetails = true }, { nowPlayingDetails = false },
                        onStatus = { visible, access -> nowPlayingVisible = visible; mediaAccess = access })
                }
            }
        }
        tile?.let { chosen -> TitleDialog(app, chosen, { tile = null }, { refresh++ }, opening) { action -> perform(action, true) } }
        error?.let { message ->
            val back = remember { FocusRequester() }
            VelaDialog("Couldn't complete that", { error = null }, back, actions = {
                VelaButton("Back", Modifier.focusRequester(back)) { error = null }
                failedAction?.let { action -> VelaButton("Retry") { error = null; perform(action, tile != null) } }
            }) { Text(message, style = Vela.type.body) }
        }
    }
}
