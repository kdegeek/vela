package app.vela.tv.launcher

import java.util.Locale

/** Presentation choices only. ManagementForms and the household store still own parsing/validation.
 * Never canonicalize a saved tag just to show a readable label. */
internal object GlassLanguages {
    val choices = linkedMapOf(
        "" to "Follow the title", "en" to "English", "es" to "Spanish", "fr" to "French",
        "de" to "German", "it" to "Italian", "pt" to "Portuguese", "ja" to "Japanese",
        "ko" to "Korean", "zh" to "Chinese", "ar" to "Arabic", "hi" to "Hindi",
    )

    fun label(raw: String): String {
        val tags = raw.split(',').map(String::trim).filter(String::isNotEmpty)
        return if (tags.isEmpty()) choices.getValue("")
        else tags.joinToString(", ") { choices[it.lowercase(Locale.ROOT)] ?: it }
    }

    fun choose(raw: String, tag: String): String {
        val tags = raw.split(',').map(String::trim).filter(String::isNotEmpty)
        return if ((tag.isEmpty() && tags.isEmpty()) || (tags.size == 1 && tags.single().equals(tag, ignoreCase = true))) raw else tag
    }
}
