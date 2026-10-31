package com.homeworkassistant.data

import com.google.gson.JsonParser
import com.homeworkassistant.data.ai.*
import com.homeworkassistant.data.security.SecretStore
import com.homeworkassistant.domain.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.toList
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.UnknownHostException

class AiConnectionTest {
    private class Keys(var value: String = "fixture-key-not-real") : SecretStore {
        override fun read() = value
        override fun write(value: String) { this.value = value }
        override fun clear() { value = "" }
    }
    private fun repository(key: String = "fixture-key-not-real", handler: (Request) -> Pair<Int, String>): AiRepositoryImpl =
        AiRepositoryImpl(Keys(key), OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
            .addInterceptor { chain ->
                val (status, body) = handler(chain.request())
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(status).message("fixture")
                    .body(body.toResponseBody()).build()
            }.build())
    private val modelList = """{"object":"list","data":[{"id":"model-b"},{"id":"model-a"},{"id":"model-a"}]}"""

    @Test fun baseAndFullEndpointResolveWithoutDuplicatingTheChatPath() {
        for (address in listOf("https://api.deepseek.com", "https://api.deepseek.com/", "https://api.deepseek.com/chat/completions/"))
            assertEquals("https://api.deepseek.com/", AiConnection.baseUrl(address).toString())
        assertEquals("https://api.deepseek.com/v1/", AiConnection.baseUrl(" https://api.deepseek.com/v1/chat/completions ").toString())
        assertEquals("https://provider.example/api/v1/", AiConnection.baseUrl("https://provider.example/api/v1/models").toString())
    }
    @Test fun invalidAddressesAreRejectedBeforeAnyKeyIsSent() {
        for (address in listOf("http://api.deepseek.com", "https://user:secret@example.com/v1", "https://api.deepseek.com?key=x",
            "https://api.deepseek.com/#x", "https://api.deepseek.com/anthropic", "not a url")) {
            assertThrows(IllegalArgumentException::class.java) { AiConnection.baseUrl(address) }
        }
    }
    @Test fun bearerPrefixAndWhitespaceAreNormalizedExactlyOnce() {
        assertEquals("fixture-key-not-real", AiConnection.key(" Bearer fixture-key-not-real "))
        assertThrows(IllegalArgumentException::class.java) { AiConnection.key("key\nsecond-line") }
    }
    @Test fun modelLookupUsesGetAndNeedsNoConfiguredModelOrGeneration() = runBlocking {
        val requests = mutableListOf<Request>()
        val repo = repository { request -> requests += request; 200 to modelList }
        val ids = repo.models(AppSettings(baseUrl = "https://api.deepseek.com/v1/chat/completions"))
        assertEquals(listOf("model-a", "model-b"), ids)
        assertEquals(1, requests.size)
        assertEquals("GET", requests.single().method)
        assertEquals("/v1/models", requests.single().url.encodedPath)
        assertEquals("Bearer fixture-key-not-real", requests.single().header("Authorization"))
        assertNull(requests.single().body)
        assertNull(requests.single().header("Cookie"))
    }
    @Test fun authenticationTestDoesNotCallChatOnDeepSeekAndIdentifiesAnUnknownModel() = runBlocking {
        var count = 0
        val repo = repository { request -> count++; assertEquals("/models", request.url.encodedPath); 200 to modelList }
        val settings = AppSettings(baseUrl = "https://api.deepseek.com", model = "model-a")
        assertTrue(repo.test(settings).contains("所选模型在服务列表中"))
        assertTrue(repo.test(settings.copy(model = "retired-model")).contains("未在列表中"))
        assertEquals(2, count)
    }
    @Test fun lookupWithoutSavedKeyNeverSendsNetworkTraffic() = runBlocking {
        var requests = 0
        val repo = repository("") { requests++; 200 to modelList }
        try { repo.models(AppSettings(baseUrl = "https://api.deepseek.com")); fail("Expected missing key") }
        catch (_: IllegalArgumentException) { }
        assertEquals(0, requests)
    }
    @Test fun httpErrorsExplainTheStatusWithoutExposingTheRawResponseOrKey() = runBlocking {
        for ((status, meaning) in listOf(401 to "Key", 402 to "余额不足", 404 to "接口路径或模型不存在", 429 to "额度受限")) {
            val repo = repository { status to "{\"error\":\"fixture-key-not-real very-private-response\"}" }
            try { repo.models(AppSettings(baseUrl = "https://api.deepseek.com")); fail("Expected HTTP error") }
            catch (e: AiHttpException) {
                assertEquals(status, e.status)
                assertTrue(e.message.orEmpty().contains(meaning))
                assertFalse(e.message.orEmpty().contains("fixture-key-not-real"))
                assertFalse(e.message.orEmpty().contains("very-private-response"))
            }
        }
    }
    @Test fun aRedirectDoesNotForwardTheAuthorizationToAnotherOrigin() = runBlocking {
        var count = 0
        val repo = AiRepositoryImpl(Keys(), OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
            .addInterceptor { chain ->
                count++
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(302).message("fixture")
                    .header("Location", "https://untrusted.example/collect").body("".toResponseBody()).build()
            }.build())
        try { repo.models(AppSettings(baseUrl = "https://api.deepseek.com")); fail("Expected redirect refusal") }
        catch (e: AiHttpException) { assertEquals(302, e.status) }
        assertEquals(1, count)
    }
    @Test fun htmlOrUnexpectedJsonIsNotReportedAsConnectionSuccess() = runBlocking {
        for (body in listOf("<html>Login page</html>", "{}", "{\"data\":[]}", "{\"data\":[{\"id\":123}]}")) {
            try {
                repository { 200 to body }.models(AppSettings(baseUrl = "https://api.deepseek.com"))
                fail("Expected incompatible or empty model data")
            } catch (_: IOException) { }
        }
    }
    @Test fun oversizedModelListIsBounded() = runBlocking {
        try {
            repository { 200 to "x".repeat(1_000_001) }.models(AppSettings(baseUrl = "https://api.deepseek.com"))
            fail("Expected size limit")
        } catch (e: IOException) { assertTrue(e.message.orEmpty().contains("过大")) }
    }
    @Test fun genericServiceWithoutModelsCanStillUseMinimalChatTest() = runBlocking {
        val requests = mutableListOf<Request>()
        val repo = repository { request ->
            requests += request
            if (request.url.encodedPath == "/v1/models") 404 to "{}"
            else {
                assertEquals("/v1/chat/completions", request.url.encodedPath)
                assertEquals("POST", request.method)
                val body = Buffer().also { request.body!!.writeTo(it) }.readUtf8()
                val json = JsonParser.parseString(body).asJsonObject
                assertFalse(json.has("temperature"))
                assertFalse(json["stream"].asBoolean)
                assertEquals("custom", json["model"].asString)
                200 to """{"choices":[{"message":{"content":"OK"}}]}"""
            }
        }
        assertTrue(repo.test(AppSettings(baseUrl = "https://provider.example/v1/chat/completions", model = "custom")).contains("聊天请求成功"))
        assertEquals(2, requests.size)
    }
    @Test fun deepSeek404IsNotHiddenByAutomaticChatFallback() = runBlocking {
        var count = 0
        try {
            repository { count++; 404 to "{}" }.test(AppSettings(baseUrl = "https://api.deepseek.com", model = "model-a"))
            fail("Expected HTTP error")
        } catch (e: AiHttpException) { assertEquals(404, e.status) }
        assertEquals(1, count)
    }
    @Test fun generationUsesTheSameNormalizedChatEndpoint() = runBlocking {
        val repo = repository { request ->
            assertEquals("/v1/chat/completions", request.url.encodedPath)
            val json = JsonParser.parseString(Buffer().also { request.body!!.writeTo(it) }.readUtf8()).asJsonObject
            assertEquals(0.35, json["temperature"].asDouble, 0.000001)
            200 to """data: {"choices":[{"delta":{"content":"{\"answers\":[{\"questionId\":\"q\",\"answer\":\"A\",\"explanation\":\"fixture\"}]}"},"finish_reason":"stop"}]}

data: [DONE]

"""
        }
        val events = repo.generate(listOf(Question("q", 1, QuestionType.SINGLE, "fixture", listOf("A", "B"))),
            AppSettings(baseUrl = "https://provider.example/v1/chat/completions", model = "custom", temperature = 0.35f)).toList()
        assertEquals("A", events.filterIsInstance<AiEvent.Batch>().single().answers.single().answer)
    }
    @Test fun dnsFailureGivesActionableTextWithoutCopyingRawExceptionDetails() = runBlocking {
        val repo = AiRepositoryImpl(Keys(), OkHttpClient.Builder().addInterceptor { throw UnknownHostException("private.network.detail") }.build())
        try { repo.models(AppSettings(baseUrl = "https://api.deepseek.com")); fail("Expected DNS failure") }
        catch (e: IOException) {
            assertTrue(e.message.orEmpty().contains("域名无法解析"))
            assertFalse(e.message.orEmpty().contains("private.network.detail"))
        }
    }
}
