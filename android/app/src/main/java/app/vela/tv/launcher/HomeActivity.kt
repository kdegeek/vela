package app.vela.tv.launcher

import android.graphics.Bitmap
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.vela.tv.VelaApp
import app.vela.tv.layout.TileStyle
import coil3.compose.AsyncImage
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

private val Abyss = Color(0xFF0E1024)
private val Starlight = Color(0xFFF6F1E7)
private val Dim = Starlight.copy(alpha = 0.65f)
private val Display = FontFamily.Serif

/** Vela's home: the focused title's art fills the screen, rows of tiles below. All sizes are dp of a 960x540 canvas, so 1080p and 4K look the same, only sharper. */
class HomeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as VelaApp
        setContent { Home(app) }
    }
}

@Composable
private fun Home(app: VelaApp) {
    val data = remember { HomeData(app) }
    val layout by app.settings.layout.collectAsState()
    val settings by app.settings.settings.collectAsState()
    var rows by remember { mutableStateOf<List<LoadedRow>>(emptyList()) }
    var focused by remember { mutableStateOf<Tile?>(null) }
    val scope = rememberCoroutineScope()
    val first = remember { FocusRequester() }
    val list = androidx.compose.foundation.lazy.rememberLazyListState()

    LaunchedEffect(layout, settings) {
        rows = layout.rows.map { spec -> async { data.load(spec) } }.awaitAll()
    }
    LaunchedEffect(rows.isNotEmpty()) {
        if (rows.any { it.tiles.isNotEmpty() }) runCatching { first.requestFocus() }
    }
    BackHandler { } // this is home: Back has nowhere to go

    Box(Modifier.fillMaxSize().background(Abyss)) {
        Crossfade(targetState = focused?.backdrop ?: (focused?.image as? String), animationSpec = tween(700), label = "backdrop") { url ->
            if (url != null) AsyncImage(url, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to Abyss, 0.55f to Abyss.copy(alpha = 0.55f), 1f to Color.Transparent)))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.35f to Color.Transparent, 1f to Abyss)))

        Column(Modifier.fillMaxSize().padding(start = 48.dp, top = 28.dp)) {
            TopBar(app, Modifier.padding(end = 48.dp))
            Spacer(Modifier.height(24.dp))
            // Until the AI is set up, the pairing card stands where the hero goes, so the rows never scroll it away.
            if (!settings.agentReady) PairCard(app, Modifier.height(170.dp))
            else Hero(focused, Modifier.height(170.dp).fillMaxWidth(0.55f))
            Spacer(Modifier.height(12.dp))
            LazyColumn(
                Modifier.fillMaxSize(),
                state = list,
                verticalArrangement = Arrangement.spacedBy(14.dp),
                contentPadding = PaddingValues(bottom = 40.dp),
            ) {
                var firstAssigned = false
                for (row in rows) {
                    if (row.tiles.isEmpty() && row.error == null) continue
                    val takesFirst = !firstAssigned && row.tiles.isNotEmpty()
                    if (takesFirst) firstAssigned = true
                    val index = rows.indexOf(row)
                    item(row.spec.id) {
                        TileRow(row, if (takesFirst) first else null, onFocus = {
                            focused = it
                            scope.launch { list.animateScrollToItem(rows.take(index).count { r -> r.tiles.isNotEmpty() || r.error != null }) }
                        }) { t ->
                            scope.launch { data.perform(t.action) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TopBar(app: VelaApp, modifier: Modifier) {
    var now by remember { mutableStateOf(Date()) }
    LaunchedEffect(Unit) { while (true) { now = Date(); delay(15_000) } }
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("Vela", color = Starlight, fontSize = 30.sp, fontFamily = Display, fontStyle = FontStyle.Italic)
        Spacer(Modifier.weight(1f))
        Pill("Ask Vela") { app.startActivity(android.content.Intent(app, AskActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
        Spacer(Modifier.width(20.dp))
        Text(DateFormat.getTimeInstance(DateFormat.SHORT).format(now), color = Starlight, fontSize = 18.sp)
    }
}

@Composable
private fun Pill(label: String, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    Text(
        label,
        color = if (focused) Abyss else Starlight,
        fontSize = 15.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (focused) Starlight else Starlight.copy(alpha = 0.12f))
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 7.dp),
    )
}

@Composable
private fun Hero(t: Tile?, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.Bottom) {
        // Apps have no story to tell; the hero is for titles.
        if (t == null || t.action is app.vela.tv.layout.TileAction.LaunchApp) return@Column
        t.kicker?.let { Text(it.uppercase(), color = Dim, fontSize = 12.sp, letterSpacing = 1.5.sp) }
        Text(t.title, color = Starlight, fontSize = 40.sp, fontFamily = Display, maxLines = 1, overflow = TextOverflow.Ellipsis)
        t.subtitle?.let { Text(it, color = Dim, fontSize = 15.sp, maxLines = 1) }
        t.overview?.let { Text(it, color = Starlight.copy(alpha = 0.85f), fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp)) }
        t.progress?.let { p ->
            Box(Modifier.padding(top = 10.dp).width(220.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(Starlight.copy(alpha = 0.2f))) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(p.coerceIn(0f, 1f)).background(Starlight))
            }
        }
    }
}

@Composable
private fun TileRow(row: LoadedRow, first: FocusRequester?, onFocus: (Tile) -> Unit, onClick: (Tile) -> Unit) {
    Column {
        Text(row.spec.title, color = Starlight, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 8.dp))
        if (row.error != null) {
            Text(row.error, color = Dim, fontSize = 13.sp)
            return@Column
        }
        // Bleeds left by the focus scale's overhang so the first tile isn't clipped when it grows.
        LazyRow(
            Modifier.offset(x = (-12).dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            contentPadding = PaddingValues(start = 12.dp, end = 48.dp, top = 8.dp, bottom = 8.dp),
        ) {
            items(row.tiles, key = { it.key }) { t ->
                TileCard(t, row.spec.style, if (t === row.tiles.first()) first else null, onFocus, onClick)
            }
        }
    }
}

@Composable
private fun TileCard(t: Tile, style: TileStyle, requester: FocusRequester?, onFocus: (Tile) -> Unit, onClick: (Tile) -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.08f else 1f, tween(160), label = "scale")
    val (w, h) = when (style) {
        TileStyle.POSTER -> 112.dp to 168.dp
        TileStyle.APP -> 160.dp to 90.dp
        TileStyle.WIDE -> 224.dp to 126.dp
    }
    val shape = RoundedCornerShape(10.dp)
    Column(Modifier.width(w)) {
        Box(
            Modifier
                .size(w, h)
                .scale(scale)
                .clip(shape)
                .background(Starlight.copy(alpha = 0.08f))
                .then(if (focused) Modifier.border(2.dp, Starlight, shape) else Modifier)
                .let { if (requester != null) it.focusRequester(requester) else it }
                .onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocus(t) }
                .clickable { onClick(t) },
            contentAlignment = Alignment.Center,
        ) {
            if (t.image != null) {
                AsyncImage(t.image, t.title, Modifier.fillMaxSize(), contentScale = if (style == TileStyle.APP) ContentScale.Fit else ContentScale.Crop)
            } else {
                Text(t.title, color = Starlight, fontSize = 15.sp, modifier = Modifier.padding(10.dp), maxLines = 3)
            }
            t.progress?.let { p ->
                Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp).background(Abyss.copy(alpha = 0.6f))) {
                    Box(Modifier.fillMaxHeight().fillMaxWidth(p.coerceIn(0f, 1f)).background(Starlight))
                }
            }
        }
        if (style != TileStyle.APP && t.image != null) {
            Text(t.title, color = if (focused) Starlight else Dim, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

@Composable
private fun PairCard(app: VelaApp, modifier: Modifier) {
    val url = remember { app.phone.pairingUrl() }
    Row(
        modifier.clip(RoundedCornerShape(16.dp)).background(Abyss.copy(alpha = 0.8f)).border(1.dp, Starlight.copy(alpha = 0.15f), RoundedCornerShape(16.dp)).padding(18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val qr = remember(url) { url?.let { qrBitmap(it, 512) } }
        if (qr != null) Image(qr.asImageBitmap(), "Pairing code", Modifier.size(120.dp).clip(RoundedCornerShape(8.dp)))
        Spacer(Modifier.width(20.dp))
        Column(Modifier.width(380.dp)) {
            Text("Set up Vela's AI", color = Starlight, fontSize = 22.sp, fontFamily = Display)
            Text(
                "Scan with your phone to add an API key or a model server on your network.",
                color = Dim, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp),
            )
            Text(app.phone.shortUrl() ?: "Connect the TV to your network first.", color = Starlight, fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp))
        }
    }
}

private fun qrBitmap(text: String, size: Int): Bitmap {
    val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(com.google.zxing.EncodeHintType.MARGIN to 2))
    val px = IntArray(size * size) { i -> if (m[i % size, i / size]) 0xFF0E1024.toInt() else 0xFFF6F1E7.toInt() }
    return Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888)
}
