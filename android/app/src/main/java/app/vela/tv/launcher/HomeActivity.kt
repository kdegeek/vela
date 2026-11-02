package app.vela.tv.launcher

import android.graphics.Bitmap
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
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
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.vela.tv.VelaApp
import app.vela.tv.design.*
import app.vela.tv.settings.LayoutUndo
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
    var media by mutableStateOf<Tile?>(null)
    var acknowledgedUndo by mutableStateOf<LayoutUndo?>(null)
}

@Composable internal fun HomeContent(app: VelaApp, refresh: Int, onSelect: (Tile) -> Unit,
    onSettings: () -> Unit, onArrange: () -> Unit, onUndo: (LayoutUndo) -> Unit, compact: Boolean,
    reset: Int, presentation: HomePresentation, undoing: Boolean) {
    val data = remember(app) { HomeData(app) }
    val layout by app.settings.layout.collectAsState()
    val settings by app.settings.settings.collectAsState()
    val saved by app.settings.watchlist.collectAsState()
    val undo by app.settings.layoutUndo.collectAsState()
    val owner by app.control.owner.collectAsState()
    val running by app.runner.running.collectAsState()
    val undoUnavailable = undoing || running || owner != null
    val notice = undo?.takeUnless { it === presentation.acknowledgedUndo }
    LaunchedEffect(undo) {
        if (presentation.acknowledgedUndo !== undo) presentation.acknowledgedUndo = null
    }
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
            presentation.focused = presentation.focused?.let { old -> loaded.flatMap { it.tiles }.firstOrNull { it.key == old.key && it.action == old.action } }
            presentation.media = homeMediaTile(loaded, presentation.focused, presentation.media)
        } finally { loading = false }
    }
    // One scroll boundary owns complete rows, captions and utilities. The shell owns the scene.
    LazyColumn(Modifier.fillMaxSize().focusRestorer(), state = list,
        contentPadding = PaddingValues(bottom = VelaSpace.M),
        verticalArrangement = Arrangement.spacedBy(VelaSpace.Xs)) {
        notice?.let { receipt ->
            insetItem("receipt") {
                key(receipt) {
                    VelaGlassSurface(Modifier.fillMaxWidth()) {
                        FlowRow(Modifier.padding(horizontal = VelaSpace.M, vertical = VelaSpace.Xs),
                            horizontalArrangement = Arrangement.spacedBy(VelaSpace.S),
                            verticalArrangement = Arrangement.spacedBy(VelaSpace.Xs)) {
                            Row(Modifier.weight(1f).align(Alignment.CenterVertically),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(VelaSpace.Xs)) {
                                Glyph(VelaGlyph.Check, null, tint = Vela.colors.accent)
                                Text("Home updated", style = Vela.type.label, color = Vela.colors.ink)
                            }
                            // Capture the displayed receipt, never re-read a newer receipt at activation.
                            VelaButton(if (undoing) "Restoring…" else "Undo", enabled = !undoUnavailable,
                                busy = undoing) { onUndo(receipt) }
                            VelaButton("Keep layout", variant = ButtonVariant.Quiet) {
                                presentation.acknowledgedUndo = receipt
                            }
                        }
                    }
                }
            }
        }
        presentation.media?.let { media ->
            insetItem("hero") { MediaHero(media, loading, compact || notice != null) }
        }
        if (!presentation.loaded) {
            layout.rows.forEach { spec -> item(ownerRowKey(spec.id)) { SkeletonRow(spec.title, spec.style.cardFormat()) } }
        } else {
            presentation.rows.filter { it.tiles.isNotEmpty() || it.error != null }.forEach { row ->
                item(ownerRowKey(row.spec.id)) {
                    TileRow(row, {
                        presentation.focused = it
                        presentation.media = homeMediaTile(presentation.rows, it, presentation.media)
                    }, onSelect, onRetry = { retry++ })
                }
            }
            if (presentation.rows.all { it.tiles.isEmpty() && it.error == null }) insetItem("empty") {
                Column(verticalArrangement = Arrangement.spacedBy(VelaSpace.Xs)) {
                    Text("Home, at your pace", style = Vela.type.headline, color = Vela.colors.ink)
                    Text("Your apps are in Apps. Saved titles and library rows appear here when available.",
                        style = Vela.type.body, color = Vela.colors.inkMuted)
                }
            }
        }
        insetItem("arrange") {
            VelaGlassSurface {
                FlowRow(Modifier.padding(VelaSpace.Xxs), horizontalArrangement = Arrangement.spacedBy(VelaSpace.Xxs),
                    verticalArrangement = Arrangement.spacedBy(VelaSpace.Xs)) {
                    VelaButton("Arrange home", variant = ButtonVariant.Quiet, onClick = onArrange)
                    undo?.takeIf { it === presentation.acknowledgedUndo }?.let { receipt ->
                        key(receipt) {
                            VelaButton("Undo last home change", enabled = !undoUnavailable, busy = undoing,
                                variant = ButtonVariant.Quiet) { onUndo(receipt) }
                        }
                    }
                    if (presentation.loaded && presentation.media == null) {
                        VelaButton("Library settings", variant = ButtonVariant.Quiet, onClick = onSettings)
                    }
                }
            }
        }
    }
}

@Composable private fun MediaHero(tile: Tile, loading: Boolean, compact: Boolean) {
    Column(Modifier.fillMaxWidth().heightIn(min = if (compact) 56.dp else 60.dp)
        .semantics { if (loading) stateDescription = "Refreshing" },
        verticalArrangement = Arrangement.Center) {
        Text(tile.title, style = Vela.type.headline, color = Vela.colors.ink,
            maxLines = 2, overflow = TextOverflow.Ellipsis)
        val metadata = listOfNotNull(tile.subtitle?.takeIf { it.isNotBlank() },
            homeMediaProvider(tile)?.takeUnless { tile.subtitle?.contains(it, ignoreCase = true) == true })
            .joinToString(" · ")
        if (metadata.isNotBlank()) Text(metadata, style = Vela.type.meta, color = Vela.colors.inkMuted,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
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
