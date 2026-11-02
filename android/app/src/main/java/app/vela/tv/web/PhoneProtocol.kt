package app.vela.tv.web

import kotlinx.serialization.json.*

/** Shared request validation, kept independent of Android and HTTP for adversarial payload tests. */
object PhoneProtocol {
    const val MAX_BODY_BYTES = 262_144
    private fun objectBody(text: String): JsonObject {
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_BODY_BYTES) { "Request is too large." }
        return Json.parseToJsonElement(text) as? JsonObject ?: error("Expected a JSON object.")
    }
    fun task(text: String): String {
        val value = objectBody(text)["text"] as? JsonPrimitive
        require(value?.isString == true) { "text must be a string." }
        return value.content.trim().also { require(it.isNotBlank() && it.length <= 8000) { "Ask in 1–8000 characters." } }
    }
    fun remote(text: String): String {
        val value = objectBody(text)["key"] as? JsonPrimitive
        require(value?.isString == true && value.content in setOf("up", "down", "left", "right", "select", "back", "home")) { "Unknown remote key." }
        return value.content
    }
    data class Answer(val allow: Boolean, val promptId: String)
    fun answer(text: String): Answer {
        val value = objectBody(text)
        val allow = value["allow"] as? JsonPrimitive
        require(allow?.isString == false && allow.booleanOrNull != null) { "allow must be a boolean." }
        val id = value["promptId"] as? JsonPrimitive
        require(id?.isString == true && id.content.isNotBlank() && id.content.length <= 100) { "An answer must name the prompt it displayed." }
        return Answer(allow.boolean, id.content)
    }
}
