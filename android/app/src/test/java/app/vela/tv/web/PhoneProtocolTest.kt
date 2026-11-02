package app.vela.tv.web

import org.junit.Assert.*
import org.junit.Test

class PhoneProtocolTest {
    private fun rejected(block: () -> Unit) { assertTrue(runCatching(block).isFailure) }
    @Test fun `task text is trimmed and bounded`() {
        assertEquals("hello", PhoneProtocol.task("""{"text":" hello "}"""))
        rejected { PhoneProtocol.task("""{"text":" "}""") }
        rejected { PhoneProtocol.task("""{"text":1}""") }
        rejected { PhoneProtocol.task("""{"text":null}""") }
        rejected { PhoneProtocol.task("[]") }
        rejected { PhoneProtocol.task("{" ) }
        rejected { PhoneProtocol.task("""{"text":"${"a".repeat(8001)}"}""") }
    }
    @Test fun `approval requires a literal boolean and a prompt id`() {
        assertEquals(PhoneProtocol.Answer(false,"p"), PhoneProtocol.answer("""{"allow":false,"promptId":"p"}"""))
        rejected { PhoneProtocol.answer("""{"allow":true}""") }
        rejected { PhoneProtocol.answer("""{"allow":"true","promptId":"p"}""") }
        rejected { PhoneProtocol.answer("""{"allow":null,"promptId":"p"}""") }
        rejected { PhoneProtocol.answer("""{"allow":true,"promptId":""}""") }
        rejected { PhoneProtocol.answer("""{"allow":true,"promptId":42}""") }
    }
    @Test fun `remote accepts only known keys`() {
        for (key in listOf("up","down","left","right","back","home","select")) assertEquals(key, PhoneProtocol.remote("""{"key":"$key"}"""))
        rejected { PhoneProtocol.remote("""{"key":"factory_reset"}""") }
        rejected { PhoneProtocol.remote("""{"key":1}""") }
    }
    @Test fun `body limit counts UTF8 bytes`() {
        rejected { PhoneProtocol.task("""{"text":"${"🦊".repeat(70_000)}"}""") }
    }
}
