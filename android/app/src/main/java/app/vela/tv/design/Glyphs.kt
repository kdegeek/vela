package app.vela.tv.design

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.vector.*
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Fixed, cached vectors: no per-frame parsing, downloadable fonts or icon dependencies. */
private val vectors: Map<VelaGlyph, ImageVector> by lazy {
    VelaGlyph.entries.associateWith { glyph ->
        val data = when (glyph) {
            VelaGlyph.Sail -> "M12 3 L12 18 L3 18 Z M15 7 L21 18 L15 18 Z M3 21 L21 21"
            VelaGlyph.Search -> "M10 3 A7 7 0 1 0 10 17 A7 7 0 1 0 10 3 M15 15 L21 21"
            VelaGlyph.Apps -> "M3 3 H9 V9 H3 Z M15 3 H21 V9 H15 Z M3 15 H9 V21 H3 Z M15 15 H21 V21 H15 Z"
            VelaGlyph.Bookmark -> "M6 3 H18 V21 L12 17 L6 21 Z"
            VelaGlyph.House -> "M3 11 L12 3 L21 11 M5 9 V21 H10 V15 H14 V21 H19 V9"
            VelaGlyph.Activity -> "M3 12 H7 L10 5 L14 19 L17 12 H21"
            VelaGlyph.Settings -> "M10 3 H14 L15 6 L18 7 L21 7 V11 L19 12 L21 14 V18 L17 18 L15 20 L14 22 H10 L9 19 L6 18 L3 18 V14 L5 12 L3 10 V6 H7 L9 5 Z M12 8 A4 4 0 1 0 12 16 A4 4 0 1 0 12 8"
            VelaGlyph.Play -> "M7 4 L21 12 L7 20 Z"
            VelaGlyph.Pause -> "M7 4 V20 M17 4 V20"
            VelaGlyph.Stop -> "M5 5 H19 V19 H5 Z"
            VelaGlyph.Previous -> "M5 4 V20 M19 5 L8 12 L19 19 Z"
            VelaGlyph.Next -> "M19 4 V20 M5 5 L16 12 L5 19 Z"
            VelaGlyph.Check -> "M4 12 L9 17 L20 6"
            VelaGlyph.Caution -> "M12 3 L22 21 H2 Z M12 9 V14 M12 17 V17.2"
            VelaGlyph.Error -> "M12 2 A10 10 0 1 0 12 22 A10 10 0 1 0 12 2 M8 8 L16 16 M16 8 L8 16"
            VelaGlyph.Mic -> "M9 5 A3 3 0 0 1 15 5 V12 A3 3 0 0 1 9 12 Z M5 11 V12 A7 7 0 0 0 19 12 V11 M12 19 V22 M9 22 H15"
            VelaGlyph.Ask -> "M12 3 L15 9 L21 12 L15 15 L12 21 L9 15 L3 12 L9 9 Z M20 2 V6 M18 4 H22"
            VelaGlyph.Link -> "M10 14 L14 10 M8 15 L6 17 A3 3 0 0 1 2 13 L7 8 A3 3 0 0 1 11 8 M13 16 A3 3 0 0 0 17 16 L22 11 A3 3 0 0 0 18 7 L16 9"
            VelaGlyph.Profile -> "M12 3 A4 4 0 1 0 12 11 A4 4 0 1 0 12 3 M4 21 V19 A8 6 0 0 1 20 19 V21"
            VelaGlyph.Refresh -> "M20 8 A9 9 0 1 0 21 15 M20 3 V8 H15"
        }
        ImageVector.Builder(glyph.name, 24.dp, 24.dp, 24f, 24f).apply {
            addPath(PathParser().parsePathString(data).toNodes(), fill = null, stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
        }.build()
    }
}

@Composable fun Glyph(glyph: VelaGlyph, contentDescription: String?, modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current, size: Dp = VelaSize.Glyph) {
    Image(vectors.getValue(glyph), contentDescription, modifier.size(size), colorFilter = ColorFilter.tint(tint))
}
