package app.vela.tv.design

// Floors keep supporting text readable even when a translucent panel crosses bright art.
internal fun glassOpacity(dense: Boolean): Float = if (dense) 0.94f else 0.88f
internal const val GLASS_REFLECTION_ALPHA = 0.07f
