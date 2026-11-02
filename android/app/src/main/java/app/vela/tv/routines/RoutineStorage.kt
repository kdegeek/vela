package app.vela.tv.routines

import kotlinx.serialization.json.Json

/** One engine owns a store. Writes must replace the entire document atomically or throw. */
interface RoutineStorage {
    fun read(): RoutineSnapshot
    fun write(snapshot: RoutineSnapshot)
}

object RoutineDocuments {
    const val MAX_ROUTINES = 100
    const val MAX_TERMINAL_HISTORY = 500
    const val MAX_DOCUMENT_BYTES = 16 * 1024 * 1024
    val json = Json { classDiscriminator = "type"; encodeDefaults = true }

    fun encode(snapshot: RoutineSnapshot): String {
        validate(snapshot)
        return json.encodeToString(RoutineSnapshot.serializer(), snapshot).also {
            require(it.toByteArray(Charsets.UTF_8).size <= MAX_DOCUMENT_BYTES) { "Routine storage is too large." }
        }
    }

    fun decode(text: String): RoutineSnapshot {
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_DOCUMENT_BYTES) { "Routine storage is too large." }
        return json.decodeFromString(RoutineSnapshot.serializer(), text).also(::validate)
    }

    fun validate(snapshot: RoutineSnapshot) {
        require(snapshot.version == 1) { "Unsupported routine storage version." }
        require(snapshot.routines.size <= MAX_ROUTINES)
        require(snapshot.routines.map { it.id }.distinct().size == snapshot.routines.size)
        require(snapshot.history.size <= MAX_TERMINAL_HISTORY + MAX_ROUTINES * 2)
        require(snapshot.history.map { it.id }.distinct().size == snapshot.history.size)
        snapshot.routines.forEach {
            require(it.id.isNotBlank() && it.id.length <= 128 && it.revision > 0)
            it.spec.validated()
            require(it.nextRunAt == null || it.nextRunAt >= 0)
        }
        snapshot.history.forEach {
            require(it.id.isNotBlank() && it.id.length <= 128 && it.routineId.isNotBlank() && it.revision > 0)
            require(it.name.isNotBlank() && it.name.length <= 120 && it.task.isNotBlank() && it.task.length <= 8_000)
            require(it.detail == null || it.detail.length <= 1_000)
            require(it.requestKey == null || it.requestKey.matches(Regex("[A-Za-z0-9_-]{1,128}")))
            require(it.scheduledAt >= 0 && it.queuedAt >= 0 && it.expiresAt >= it.queuedAt)
            require(it.state.terminal == (it.finishedAt != null))
        }
        val keys = snapshot.history.mapNotNull { run -> run.requestKey?.let { run.routineId to it } }
        require(keys.distinct().size == keys.size) { "Duplicate routine request key." }
    }
}
