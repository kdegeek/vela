package app.vela.tv.agent

import kotlinx.coroutines.test.runTest
import mockwebserver3.*
import org.junit.Assert.*
import org.junit.Test

class HttpReliabilityTest {
    private val answer = """{"choices":[{"message":{"content":"ready"}}]}"""
    @Test fun `429 retry is bounded and uses the same request`() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse.Builder().code(429).addHeader("Retry-After", "0").body("{}").build())
            server.enqueue(MockResponse.Builder().body(answer).build())
            val result = OpenAiProvider(server.url("/v1").toString(), "secret", "test").complete("s", listOf(Msg.User("hi")), emptyList())
            assertEquals("ready", result.text)
            assertEquals(2, server.requestCount)
            assertEquals(server.takeRequest().body!!.utf8(), server.takeRequest().body!!.utf8())
        }
    }
    @Test fun `long retry-after is returned without waiting or retrying`() = runTest {
        MockWebServer().use { server ->
            server.start(); server.enqueue(MockResponse.Builder().code(429).addHeader("Retry-After", "3600").body("{}").build())
            val failure = runCatching { OpenAiProvider(server.url("/v1").toString(), "secret", "test").complete("s", listOf(Msg.User("hi")), emptyList()) }.exceptionOrNull()
            assertTrue(failure is ProviderException); assertEquals(1, server.requestCount)
        }
    }
    @Test fun `redirects never forward Anthropic credentials`() = runTest {
        MockWebServer().use { source -> MockWebServer().use { target ->
            source.start(); target.start()
            source.enqueue(MockResponse.Builder().code(307).addHeader("Location", target.url("/capture")).build())
            val failure = runCatching { AnthropicProvider("actual-secret", "test", source.url("/").toString()).complete("s", listOf(Msg.User("hi")), emptyList()) }.exceptionOrNull()
            assertTrue(failure is ProviderException); assertEquals(0, target.requestCount)
        } }
    }
    @Test fun `provider errors redact a server echo of the exact key`() = runTest {
        MockWebServer().use { server ->
            server.start(); server.enqueue(MockResponse.Builder().code(401).body("""{"error":{"message":"invalid actual-secret"}}""").build())
            val failure = runCatching { OpenAiProvider(server.url("/v1").toString(), "actual-secret", "test").complete("s", listOf(Msg.User("hi")), emptyList()) }.exceptionOrNull()!!
            assertFalse(failure.message!!.contains("actual-secret")); assertTrue(failure.message!!.contains("401"))
        }
    }
    @Test fun `truncated completions are errors not a successful final answer`() = runTest {
        MockWebServer().use { server ->
            server.start(); server.enqueue(MockResponse.Builder().body("""{"choices":[{"finish_reason":"length","message":{"content":"partial"}}]}""").build())
            assertTrue(runCatching { OpenAiProvider(server.url("/v1").toString(), null, "test").complete("s", emptyList(), emptyList()) }.exceptionOrNull() is ProviderException)
        }
    }

    @Test fun `503 follow-ups cannot exceed the three explicit model attempts`() = runTest {
        MockWebServer().use { server ->
            server.start()
            repeat(6) { server.enqueue(MockResponse.Builder().code(503).addHeader("Retry-After", "0").body("{}").build()) }
            assertTrue(runCatching { OpenAiProvider(server.url("/v1").toString(), null, "test").complete("s", listOf(Msg.User("hi")), emptyList()) }.exceptionOrNull() is ProviderException)
            assertEquals(3, server.requestCount)
        }
    }
    @Test fun `credential is redacted before the error detail is truncated`() {
        val key = "dummy-credential-for-boundary-test"
        val body = """{"error":{"message":"${"x".repeat(195)}$key trailing"}}"""
        val error = runCatching { HttpReply(401, body).requireOk("Model", key) }.exceptionOrNull()!!
        assertFalse(error.message!!, error.message!!.contains("dummy"))
    }
}
