package app.vela.tv.profile

import app.vela.tv.memory.MemoryKind
import app.vela.tv.memory.OwnerMemorySource
import app.vela.tv.memory.OwnerMemoryWrite
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

internal class TestHouseholdStorage(initial: ByteArray? = null) : HouseholdStorage {
    @Volatile var bytes: ByteArray? = initial?.copyOf()
    var writes = 0
    var failBeforeWrite = false
    var failAfterWrite = false
    var beforeWrite: (() -> Unit)? = null
    override fun read(): ByteArray? = bytes?.copyOf()
    override fun writeAtomically(bytes: ByteArray) {
        beforeWrite?.invoke()
        if (failBeforeWrite) throw IOException("secret exception detail must not escape")
        this.bytes = bytes.copyOf()
        writes++
        if (failAfterWrite) throw IOException("commit outcome unknown")
    }
}

internal class TestHouseholdFixture {
    val storage = TestHouseholdStorage()
    var time = 1_000L
    private val counter = AtomicInteger()
    val ids = HouseholdIdGenerator { "00000000-0000-4000-8000-%012d".format(counter.incrementAndGet()) }
    suspend fun open(): HouseholdStore = HouseholdStore.open(storage, dispatcher = Dispatchers.Default, clock = HouseholdClock { time }, ids = ids)
}

internal fun ownerMemory(text: String = "I prefer documentaries", share: Boolean = true, confirmed: Boolean = true) =
    OwnerMemoryWrite(MemoryKind.PREFERENCE, text, OwnerMemorySource.PHONE, confirmed, listOf("movies"), share)

internal suspend fun expectHouseholdError(expected: HouseholdError, block: suspend () -> Unit) {
    try {
        block()
        fail("Expected $expected")
    } catch (error: HouseholdException) {
        assertEquals(expected, error.code)
    }
}
