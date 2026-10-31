package app.vela.tv.launcher

import android.graphics.Bitmap
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.vela.tv.VelaApp
import app.vela.tv.design.*
import app.vela.tv.layout.TileStyle
import coil3.compose.AsyncImage
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll

/** Native dp sizing follows the surface granted by Android, independently of the panel resolution. */
class HomeActivity : ComponentActivity() {
    private val resumed = mutableIntStateOf(0)
    override fun onResume() { super.onResume(); resumed.intValue++ }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as VelaApp
        setContent { TvShell(app, resumed.intValue) }
    }
}

@Composable
internal fun HomeContent(app: VelaApp, refresh: Int, onSelect: (Tile) -> Unit, onSettings: () -> Unit) {
    val data = remember { HomeData(app) }
    val layout by app.settings.layout.collectAsState()
    val settings by app.settings.settings.collectAsState()
    val saved by app.settings.watchlist.collectAsState()
    var loading by remember { mutableStateOf(true) }
    var retry by remember { mutableIntStateOf(0) }
    var rows by remember { mutableStateOf<List<LoadedRow>>(emptyList()) }
    var focused by remember { mutableStateOf<Tile?>(null) }
    val list = rememberLazyListState()
    LaunchedEffect(layout, settings, refresh, saved, retry) {
        loading = true
        try { rows = layout.rows.map { spec -> async { data.load(spec) } }.awaitAll() }
        finally { loading = false }
    }
    val c = MaterialTheme.colorScheme
    Box(Modifier.fillMaxSize().background(c.background)) {
        Crossfade(targetState = focused?.backdrop ?: (focused?.image as? String), animationSpec = tween(if (LocalReducedMotion.current) 0 else VelaDesign.BackdropDuration), label = "backdrop") { url ->
            if (url != null && !LocalHighContrast.current) AsyncImage(url, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to c.background, 0.55f to c.background.copy(alpha = 0.85f), 1f to c.background.copy(alpha = 0.6f))))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color.Transparent, 1f to c.background)))
        // Hero and pairing scroll with content. Even a short surface can reveal a full poster row.
        LazyColumn(Modifier.fillMaxSize().focusRestorer(), state = list,
            contentPadding = PaddingValues(start = VelaDesign.SafeHorizontal, end = VelaDesign.SafeHorizontal, bottom = VelaDesign.SafeVertical),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item("hero") {
                if (!settings.agentReady) PairCard(app, Modifier.fillMaxWidth())
                else Hero(focused, Modifier.fillMaxWidth().padding(vertical = 16.dp))
            }
            if (loading) item("loading") { LoadingState("Refreshing your home…") }
            if (!loading && rows.all { it.tiles.isEmpty() && it.error == null }) item("empty") {
                EmptyState("Your home is ready", "Open Apps or connect Jellyfin from Settings to bring your library here.", "Settings", onSettings)
            }
            for (row in rows) {
                if (row.tiles.isEmpty() && row.error == null) continue
                item(row.spec.id) {
                    if (row.error != null) EmptyState(row.spec.title, row.error, "Retry") { retry++ }
                    else TileRow(row, null, { focused = it }, onSelect)
                }
            }
        }
    }
}

@Composable
private fun Hero(t: Tile?, modifier: Modifier) {
    val c = MaterialTheme.colorScheme
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (t == null || t.action is app.vela.tv.layout.TileAction.LaunchApp) {
            Text("Your evening, on your terms.", style = MaterialTheme.typography.displaySmall, color = c.onBackground)
            Text("Ask for a show. Set the mood. Make yourself at home.", color = c.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge)
        } else {
            t.kicker?.let { Text(it, color = c.primary, style = MaterialTheme.typography.labelLarge) }
            Text(t.title, color = c.onBackground, style = MaterialTheme.typography.displaySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            t.subtitle?.let { Text(it, color = c.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis) }
            t.overview?.let { Text(it, color = c.onBackground, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        }
    }
}

@Composable
internal fun TileRow(row: LoadedRow, first: FocusRequester?, onFocus: (Tile) -> Unit, onClick: (Tile) -> Unit) {
    Column {
        Text(row.spec.title, color = MaterialTheme.colorScheme.onBackground, style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { heading() })
        if (row.error != null) { Text(row.error, color = MaterialTheme.colorScheme.error); return@Column }
        LazyRow(Modifier.offset(x = (-12).dp).focusRestorer(), horizontalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp)) {
            items(row.tiles, key = { it.key }) { t -> TileCard(t, row.spec.style, if (t.key == row.tiles.first().key) first else null, onFocus, onClick) }
        }
    }
}

@Composable
internal fun TileCard(t: Tile, style: TileStyle, requester: FocusRequester?, onFocus: (Tile) -> Unit, onClick: (Tile) -> Unit) {
    var focused by remember(t.key) { mutableStateOf(false) }
    var imageLoaded by remember(t.image) { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.04f else 1f, tween(if (LocalReducedMotion.current) 0 else VelaDesign.FocusDuration), label = "scale")
    val (w, h) = when (style) {
        TileStyle.POSTER -> 112.dp to 168.dp
        TileStyle.APP -> 160.dp to 90.dp
        TileStyle.WIDE -> 224.dp to 126.dp
    }
    val c = MaterialTheme.colorScheme
    val progress = displayProgress(t.progress)
    Column(Modifier.width(w)) {
        Box(Modifier.size(w, h).scale(scale).clip(VelaDesign.CardShape)
            .background(if (focused) c.surfaceVariant else c.surface)
            .border(if (focused) 3.dp else 1.dp, if (focused) c.primary else c.outline.copy(alpha = 0.35f), VelaDesign.CardShape)
            .let { if (requester != null) it.focusRequester(requester) else it }
            .onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocus(t) }
            .semantics {
                contentDescription = listOfNotNull(t.title, t.subtitle).joinToString(". ")
                progress?.let { stateDescription = "${(it * 100).toInt()} percent watched" }
            }
            .clickable(role = Role.Button, onClickLabel = "Open ${t.title}") { onClick(t) }, contentAlignment = Alignment.Center) {
            if (!imageLoaded) Text(t.title, color = c.onSurface, style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(12.dp).clearAndSetSemantics {}, maxLines = 4, overflow = TextOverflow.Ellipsis)
            if (t.image != null) AsyncImage(t.image, null, Modifier.fillMaxSize(),
                contentScale = if (style == TileStyle.APP) ContentScale.Fit else ContentScale.Crop,
                onSuccess = { imageLoaded = true }, onError = { imageLoaded = false })
            progress?.let { p ->
                Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp).background(c.surface)) {
                    Box(Modifier.fillMaxHeight().fillMaxWidth(p).background(c.primary))
                }
            }
        }
        Text(t.title, color = if (focused) c.onBackground else c.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium,
            maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp).clearAndSetSemantics {})
    }
}

@Composable
internal fun PairCard(app: VelaApp, modifier: Modifier) {
    val url = app.phone.pairingUrl()
    val qr = remember(url) { url?.let { runCatching { qrBitmap(it, 512) }.getOrNull() } }
    val c = MaterialTheme.colorScheme
    Row(modifier.clip(VelaDesign.PanelShape).background(c.surface).border(1.dp, c.outline, VelaDesign.PanelShape).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        if (qr != null) Image(qr.asImageBitmap(), "Scan to pair your phone", Modifier.size(112.dp).clip(RoundedCornerShape(8.dp)))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Connect your phone", color = c.onSurface, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
            Text("Scan to chat, use the remote, and manage connections. Browsing apps works without AI.", color = c.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
            Text(app.phone.shortUrl() ?: "Connect the TV to your network, then reopen Settings.", color = c.onSurface, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

private fun qrBitmap(text: String, size: Int): Bitmap {
    val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(com.google.zxing.EncodeHintType.MARGIN to 2))
    val px = IntArray(size * size) { i -> if (m[i % size, i / size]) 0xFF0E1024.toInt() else 0xFFF6F1E7.toInt() }
    return Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888)
}
