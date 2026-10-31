package app.vela.tv.design

/** Presentation rules kept independent of Android so malformed metadata cannot break layout. */
internal fun displayProgress(value: Float?): Float? = value?.takeIf { it.isFinite() }?.coerceIn(0f, 1f)

internal enum class TitleContent { LOADING, ERROR, EPISODES, PLAYABLE, UNSUPPORTED }

internal fun titleContent(loading: Boolean, failed: Boolean, type: String?, missing: Boolean = false): TitleContent = when {
    loading -> TitleContent.LOADING
    failed -> TitleContent.ERROR
    missing -> TitleContent.UNSUPPORTED
    type == "Series" -> TitleContent.EPISODES
    type in setOf("Movie", "Episode", "Video") -> TitleContent.PLAYABLE
    else -> TitleContent.UNSUPPORTED
}
