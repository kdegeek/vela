package app.vela.tv.launcher

import android.graphics.Bitmap
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.vela.tv.VelaApp
import app.vela.tv.design.*
import app.vela.tv.layout.TileAction
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll

/** Native sizing follows the surface granted by Android, independently of panel resolution. */
class HomeActivity : ComponentActivity() {
    private val resumed = mutableIntStateOf(0)
    override fun onResume() { super.onResume(); resumed.intValue++ }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as VelaApp
        setContent { TvShell(app, resumed.intValue) }
    }
}

/** Shell-owned stale data survives destination changes, but never a profile revision. */
@Stable internal class HomePresentation {
    var rows by mutableStateOf<List<LoadedRow>>(emptyList())
    var loaded by mutableStateOf(false)
    var appliedReset = 0
    var focused by mutableStateOf<Tile?>(null)
}

@Composable internal fun HomeContent(app: VelaApp, refresh: Int, onSelect: (Tile) -> Unit,
    onSettings: () -> Unit, onArrange: () -> Unit, onAsk: () -> Unit, compact: Boolean,
    reset: Int, presentation: HomePresentation) {
    val data = remember(app) { HomeData(app) }
    val layout by app.settings.layout.collectAsState()
    val settings by app.settings.settings.collectAsState()
    val saved by app.settings.watchlist.collectAsState()
    var loading by remember { mutableStateOf(true) }
    var retry by remember { mutableIntStateOf(0) }
    val list = rememberLazyListState()
    LaunchedEffect(reset) {
        if (reset != presentation.appliedReset) {
            list.scrollToItem(0)
            presentation.appliedReset = reset
        }
    }
    LaunchedEffect(layout, settings, refresh, saved, retry) {
        loading = true
        try {
            val loaded = layout.rows.map { spec -> async { data.load(spec) } }.awaitAll()
            presentation.rows = loaded
            presentation.loaded = true
            presentation.focused = presentation.focused?.let { old -> loaded.flatMap { it.tiles }.firstOrNull { it.key == old.key } }
        } finally { loading = false }
    }
    BoxWithConstraints(Modifier.fillMaxSize().background(Vela.colors.canvas)) {
        VelaBackdrop(presentation.focused?.backdrop, Modifier.matchParentSize())
        val showHero = settings.agentReady || presentation.focused?.action?.let { it !is TileAction.LaunchApp } == true
        // Keep the nominal hero fixed on the baseline; short surfaces scroll the complete content.
        val scrollHero = maxHeight < 260.dp || LocalDensity.current.fontScale > 1.3f
        Column(Modifier.fillMaxSize()) {
            if (showHero && !scrollHero) Hero(presentation.focused, compact, loading, Modifier.safeHorizontal())
            LazyColumn(Modifier.weight(1f).fillMaxWidth().focusRestorer(), state = list,
                contentPadding = PaddingValues(top = VelaSpace.M, bottom = VelaSpace.S),
                verticalArrangement = Arrangement.spacedBy(VelaSpace.RowGap)) {
                if (showHero && scrollHero) item("hero") { Hero(presentation.focused, compact, loading, Modifier.safeHorizontal()) }
                if (!settings.agentReady) insetItem("pair") { PairCard(app, Modifier.fillMaxWidth()) }
                if (!presentation.loaded) {
                    layout.rows.forEach { spec -> item(ownerRowKey(spec.id)) { SkeletonRow(spec.title, spec.style.cardFormat()) } }
                } else {
                    if (presentation.rows.all { it.tiles.isEmpty() && it.error == null }) insetItem("empty") {
                        EmptyState("Your home is ready", "Open Apps or connect your library from Settings.", "Settings", onSettings)
                    }
                    presentation.rows.filter { it.tiles.isNotEmpty() || it.error != null }.forEach { row ->
                        item(ownerRowKey(row.spec.id)) { TileRow(row, { presentation.focused = it }, onSelect, onRetry = { retry++ }) }
                    }
                }
                insetItem("arrange") {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(VelaSpace.S), verticalArrangement = Arrangement.spacedBy(VelaSpace.S)) {
                        VelaButton("Arrange home", variant = ButtonVariant.Quiet, onClick = onArrange)
                        VelaButton("Ask Vela to change home", variant = ButtonVariant.Agent, onClick = onAsk)
                    }
                }
            }
        }
    }
}

@Composable private fun Hero(tile: Tile?, compact: Boolean, loading: Boolean, modifier: Modifier) {
    val content = tile?.takeUnless { it.action is TileAction.LaunchApp }
    Column(modifier.fillMaxWidth().heightIn(min = if (compact) VelaSize.HeroCompact else VelaSize.Hero),
        verticalArrangement = Arrangement.Center) {
        if (!compact) Text(if (loading) "Refreshing…" else content?.kicker ?: "Make yourself at home",
            style = Vela.type.meta, color = Vela.colors.accent, maxLines = 1, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        Text(content?.title ?: "Your evening, on your terms.", style = if (compact) Vela.type.section else Vela.type.display,
            color = Vela.colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(if (compact && loading) "Refreshing…" else content?.subtitle ?: "Ask for a show. Set the mood. Make yourself at home.",
            style = Vela.type.meta, color = Vela.colors.inkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable internal fun PairCard(app: VelaApp, modifier: Modifier) {
    val url = app.phone.pairingUrl()
    val qr = remember(url) { url?.let { runCatching { qrBitmap(it, 512) }.getOrNull() } }
    Surface(modifier, shape = VelaShapes.Panel, color = Vela.colors.surface,
        border = if (Vela.highContrast) androidx.compose.foundation.BorderStroke(1.dp, Vela.colors.lineStrong) else null) {
        Row(Modifier.padding(VelaSpace.M), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(VelaSpace.M)) {
            // Live QR is deliberately unredacted; exported evidence must mask it separately.
            if (qr != null && !qr.isRecycled) Image(qr.asImageBitmap(), "Scan to pair your phone",
                Modifier.size(CardFormat.Poster.width).clip(VelaShapes.Thumb))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(VelaSpace.Xs)) {
                Text("Connect your phone", color = Vela.colors.ink, style = Vela.type.title, modifier = Modifier.semantics { heading() })
                Text("Scan to chat, use the remote, and manage connections. Browsing apps works without AI.", color = Vela.colors.inkMuted, style = Vela.type.body)
                Text(app.phone.shortUrl() ?: "Connect the TV to your network, then reopen Settings.", color = Vela.colors.ink, style = Vela.type.meta)
            }
        }
    }
}
private fun qrBitmap(text: String, size: Int): Bitmap {
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(com.google.zxing.EncodeHintType.MARGIN to 2))
    val pixels = IntArray(size * size) { i -> if (matrix[i % size, i / size]) VelaPalettes.QR_DARK.toInt() else VelaPalettes.QR_LIGHT.toInt() }
    return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
}
