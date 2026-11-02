package app.vela.tv.web

import app.vela.tv.routines.RoutineSchedule
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.descriptors.*
import kotlinx.serialization.json.*

/** Strict owner writes: no quoted integers/booleans, unknown fields, duplicate keys or deep parser recursion. */
object ManagementProtocol {
    private val json = Json { encodeDefaults = true }
    fun objectBody(text: String): JsonObject {
        require(text.toByteArray(Charsets.UTF_8).size <= PhoneProtocol.MAX_BODY_BYTES)
        data class Frame(val objectFrame: Boolean, val keys: MutableSet<String> = mutableSetOf())
        val stack = ArrayList<Frame>()
        var i = 0
        while (i < text.length) {
            when (text[i]) {
                '{', '[' -> { stack += Frame(text[i] == '{'); require(stack.size <= 24) }
                '}', ']' -> { require(stack.isNotEmpty()); stack.removeAt(stack.lastIndex) }
                '"' -> {
                    val start = i++
                    while (i < text.length && text[i] != '"') { if (text[i] == '\\') i++; i++ }
                    require(i < text.length)
                    var next = i + 1
                    while (next < text.length && text[next].isWhitespace()) next++
                    if (next < text.length && text[next] == ':') {
                        val frame = stack.lastOrNull(); require(frame?.objectFrame == true)
                        require(frame.keys.add(Json.parseToJsonElement(text.substring(start, i + 1)).jsonPrimitive.content))
                    }
                }
            }
            i++
        }
        require(stack.isEmpty())
        return Json.parseToJsonElement(text) as? JsonObject ?: throw IllegalArgumentException()
    }
    fun only(value: JsonObject, vararg keys: String): JsonObject = value.also { require(it.keys.all { key -> key in keys }) }
    fun revision(value: JsonObject): Long = integer(value.getValue("revision"))
    fun integer(value: JsonElement): Long {
        val scalar = value as? JsonPrimitive ?: throw IllegalArgumentException()
        require(!scalar.isString && scalar.content.matches(Regex("0|[1-9][0-9]*")))
        return scalar.content.toLongOrNull() ?: throw IllegalArgumentException()
    }
    fun bool(value: JsonObject, key: String): Boolean {
        val scalar = value[key] as? JsonPrimitive ?: throw IllegalArgumentException()
        require(!scalar.isString && scalar.booleanOrNull != null); return scalar.boolean
    }
    fun string(value: JsonObject, key: String): String {
        val scalar = value[key] as? JsonPrimitive ?: throw IllegalArgumentException()
        require(scalar.isString); return scalar.content
    }
    fun <T> decode(serializer: DeserializationStrategy<T>, value: JsonElement): T {
        validate(serializer.descriptor, value)
        return json.decodeFromJsonElement(serializer, value)
    }
    @OptIn(ExperimentalSerializationApi::class)
    private fun validate(descriptor: SerialDescriptor, value: JsonElement) {
        if (value == JsonNull) { require(descriptor.isNullable); return }
        when (descriptor.kind) {
            PrimitiveKind.STRING, SerialKind.ENUM -> require(value is JsonPrimitive && value.isString)
            PrimitiveKind.BOOLEAN -> require(value is JsonPrimitive && !value.isString && value.booleanOrNull != null)
            PrimitiveKind.INT, PrimitiveKind.LONG, PrimitiveKind.SHORT, PrimitiveKind.BYTE -> {
                require(value is JsonPrimitive && !value.isString && value.content.matches(Regex("-?(0|[1-9][0-9]*)")) && value.content.toLongOrNull() != null)
            }
            StructureKind.CLASS -> {
                require(value is JsonObject)
                val fields = (0 until descriptor.elementsCount).associateBy { descriptor.getElementName(it) }
                require(value.keys.all { it in fields })
                value.forEach { (name, field) -> validate(descriptor.getElementDescriptor(fields.getValue(name)), field) }
            }
            StructureKind.LIST -> { require(value is JsonArray); value.forEach { validate(descriptor.getElementDescriptor(0), it) } }
            PolymorphicKind.SEALED -> {
                require(value is JsonObject)
                val serializer = when (string(value, "type")) {
                    "once" -> RoutineSchedule.Once.serializer()
                    "interval" -> RoutineSchedule.Interval.serializer()
                    "weekly" -> RoutineSchedule.Weekly.serializer()
                    "monthly" -> RoutineSchedule.Monthly.serializer()
                    else -> throw IllegalArgumentException()
                }
                validate(serializer.descriptor, JsonObject(value.filterKeys { it != "type" }))
            }
            else -> throw IllegalArgumentException()
        }
    }
}
