package app.vela.tv.profile

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** The only supported historical format: the profile-only v1 pilot document. */
@Serializable
internal data class HouseholdV1(
    val version: Int,
    val revision: Long,
    val activeProfileId: String,
    val profiles: List<HouseholdProfile>,
)

internal data class DecodedHousehold(val document: HouseholdDocument, val migrated: Boolean)

/** Strict decoding: no dropped unknown fields, fallback defaults, silent repairs or overwritten future files. */
object HouseholdCodec {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = false; isLenient = false }

    fun encode(document: HouseholdDocument): ByteArray {
        validateDocument(document)
        val bytes = json.encodeToString(HouseholdDocument.serializer(), document).toByteArray(Charsets.UTF_8)
        householdRequire(bytes.size <= HouseholdLimits.MAX_DOCUMENT_BYTES, "Household document is too large.", HouseholdError.LIMIT_EXCEEDED)
        return bytes
    }

    internal fun decode(bytes: ByteArray): DecodedHousehold {
        householdRequire(bytes.size <= HouseholdLimits.MAX_DOCUMENT_BYTES, "Household storage is too large.", HouseholdError.CORRUPT_STORAGE)
        try {
            val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
            val root = json.parseToJsonElement(text) as? JsonObject
                ?: throw HouseholdException(HouseholdError.CORRUPT_STORAGE, "Household storage must be an object.")
            val version = root["version"] as? JsonPrimitive
            householdRequire(version != null && !version.isString && version.content.matches(Regex("[1-9][0-9]*")),
                "Household storage needs an integer schema version.", HouseholdError.CORRUPT_STORAGE)
            val migrated = version!!.content == "1"
            val document = when (version.content) {
                "1" -> {
                    val old = json.decodeFromString(HouseholdV1.serializer(), text)
                    requireWireValues(root, json.encodeToJsonElement(HouseholdV1.serializer(), old))
                    householdRequire(old.revision >= 0 && old.revision < Long.MAX_VALUE, "Invalid legacy household revision.")
                    HouseholdDocument(revision = old.revision + 1, activeProfileId = old.activeProfileId, profiles = old.profiles, memories = emptyList())
                }
                HouseholdLimits.SCHEMA_VERSION.toString() -> json.decodeFromString(HouseholdDocument.serializer(), text).also {
                    requireWireValues(root, json.encodeToJsonElement(HouseholdDocument.serializer(), it))
                }
                else -> throw HouseholdException(HouseholdError.UNSUPPORTED_VERSION, "This household was written by an unsupported app version. Update Vela before opening it.")
            }
            validateDocument(document)
            return DecodedHousehold(document.frozen(), migrated)
        } catch (e: HouseholdException) {
            if (e.code == HouseholdError.UNSUPPORTED_VERSION || e.code == HouseholdError.CORRUPT_STORAGE) throw e
            throw HouseholdException(HouseholdError.CORRUPT_STORAGE, "Household storage failed validation.")
        } catch (_: Exception) {
            // Decoder messages may contain private owner text. Do not carry them into UI/API/logs.
            throw HouseholdException(HouseholdError.CORRUPT_STORAGE, "Household storage could not be decoded.")
        }
    }

    /** Some JSON decoders accept quoted numbers/booleans. Persisted schema primitives are exact. */
    private fun requireWireValues(input: JsonElement, canonical: JsonElement) {
        val matches = when {
            input is JsonObject && canonical is JsonObject -> input.all { (key, value) ->
                val expected = canonical[key] ?: return@all false
                requireWireValues(value, expected)
                true
            }
            input is JsonArray && canonical is JsonArray -> input.size == canonical.size && input.indices.all {
                requireWireValues(input[it], canonical[it])
                true
            }
            else -> input == canonical
        }
        householdRequire(matches, "Household storage has invalid field types or noncanonical numbers.", HouseholdError.CORRUPT_STORAGE)
    }
}
