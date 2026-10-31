package com.homeworkassistant.data

import com.google.gson.*
import com.homeworkassistant.data.ai.*
import com.homeworkassistant.data.security.SecretStore
import com.homeworkassistant.data.session.PlatformReadGate
import com.homeworkassistant.data.mcp.CookieMcpReadTransport
import com.homeworkassistant.domain.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.toList
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64
import java.io.IOException

class AiQuestionIntegrationTest {
    private val gson = Gson()
    private val settings = AppSettings(baseUrl = "https://provider.example/v1", model = "fixture-model")
    private val keys = object : SecretStore {
        override fun read() = "fixture-key-not-real"
        override fun write(value: String) = Unit
        override fun clear() = Unit
    }
    private val question = Question("frame::q", 1, QuestionType.SINGLE, "选择", listOf("101. 甲", "202. 乙"), optionKeys = listOf("101", "202"))
    private fun sse(answers: String) = "data: " + gson.toJson(mapOf("choices" to listOf(mapOf("delta" to mapOf("content" to answers), "finish_reason" to "stop")))) + "\n\ndata: [DONE]\n\n"
    private fun client(handler: (Request) -> Pair<Int, String>) = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .addInterceptor { chain -> val (code, body) = handler(chain.request())
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("fixture").body(body.toResponseBody()).build()
        }.build()
    private fun payload(request: Request) = JsonParser.parseString(Buffer().also { request.body!!.writeTo(it) }.readUtf8()).asJsonObject

    @Test fun upstreamSchemaPreservesExactChoiceKeysAndReason() {
        val answer = AiAnswerCodec.decode("""{"answers":[{"id":"frame::q","answer":["202"],"reason":"说明"}]}""", listOf(question)).single()
        assertEquals("202", answer.answer); assertEquals("说明", answer.explanation)
    }
    @Test fun multipleAndMatchingArraysRemainUnambiguous() {
        assertEquals("101,202", AiAnswerCodec.decode("""{"answers":[{"id":"frame::q","answer":["101","202"]}]}""",
            listOf(question.copy(type = QuestionType.MULTIPLE))).single().answer)
        assertEquals("[\"B\",\"A\"]", AiAnswerCodec.decode("""{"answers":[{"id":"frame::q","answer":["B","A"]}]}""",
            listOf(question.copy(type = QuestionType.MATCHING))).single().answer)
    }
    @Test fun wrongMissingDuplicateAndEmptyAnswersCannotBecomeSuccessfulBatches() {
        for (json in listOf("""{"answers":[]}""", """{"answers":[{"id":"other","answer":"A"}]}""",
            """{"answers":[{"id":"frame::q","answer":"A"},{"id":"frame::q","answer":"A"}]}""", """{"answers":[{"id":"frame::q","answer":[]}]}""")) {
            assertThrows(Exception::class.java) { AiAnswerCodec.decode(json, listOf(question)) }
        }
    }
    @Test fun structuredPromptIncludesKeysButNoImageResourceUrlsOrDomMetadata() = runBlocking {
        val repo = AiRepositoryImpl(keys, client { request ->
            val text = payload(request)["messages"].asJsonArray[1].asJsonObject["content"].asString
            assertTrue(text.contains("101")); assertFalse(text.contains("sourceAdapter")); assertFalse(text.contains("canFill"))
            assertNull(request.header("Cookie")); 200 to sse("""{"answers":[{"questionId":"frame::q","answer":"202","explanation":"说明"}]}""")
        })
        assertEquals("202", repo.generate(listOf(question), settings).toList().filterIsInstance<AiEvent.Batch>().single().answers.single().answer)
    }
    @Test fun imageQuestionsRequireExplicitOptInAndNeverDegradeToTextOnly() = runBlocking {
        var requests = 0
        val repo = AiRepositoryImpl(keys, client { requests++; error("Must not send a partial question") })
        try { repo.generate(listOf(question.copy(images = listOf(QuestionImage("i", "https://p.ananas.chaoxing.com/a.png")))), settings).toList(); fail() }
        catch (e: IllegalArgumentException) { assertTrue(e.message.orEmpty().contains("没有可处理")) }
        assertEquals(0, requests)
    }
    @Test fun imagePayloadSendsBytesAndOptionAssociationWithoutPlatformCredentials() = runBlocking {
        val sourceUrl = "https://p.ananas.chaoxing.com/a.png?ticket=fixture-private-ticket"
        var imageLoads = 0
        val repo = AiRepositoryImpl(keys, client { request ->
            val text = payload(request).toString()
            assertFalse(text.contains("fixture-private-ticket")); assertFalse(text.contains("p.ananas.chaoxing.com"))
            assertTrue(text.contains("data:image/png;base64,")); assertTrue(text.contains("optionKey")); assertNull(request.header("Cookie"))
            200 to sse("""{"answers":[{"id":"frame::q","answer":["202"],"reason":"看图"}]}""")
        }, AiImageLoader { assertEquals(sourceUrl,it); imageLoads++; "data:image/png;base64,iVBORw0KGgo=" })
        val result = repo.generate(listOf(question.copy(images = listOf(QuestionImage("i", sourceUrl, "202", "option")))), settings.copy(imageInputEnabled = true)).toList()
        assertEquals(1, imageLoads); assertEquals("202", result.filterIsInstance<AiEvent.Batch>().single().answers.single().answer)
    }
    @Test fun allImagesReachTheModelWhenAQuestionHasMoreThanFourPictures() = runBlocking {
        for (count in listOf(5, 12, 40)) {
            val loaded = mutableListOf<String>()
            val repo = AiRepositoryImpl(keys, client { request ->
                val content = payload(request)["messages"].asJsonArray[1].asJsonObject["content"].asJsonArray
                assertEquals(count, content.count { it.asJsonObject["type"].asString == "image_url" })
                val associations = content.filter { it.asJsonObject["type"].asString == "text" }.drop(1)
                    .map { JsonParser.parseString(it.asJsonObject["text"].asString).asJsonObject["imageId"].asString }
                assertEquals((1..count).map { "i$it" }, associations)
                200 to sse("""{"answers":[{"id":"frame::q","answer":"202"}]}""")
            }, AiImageLoader { url -> loaded += url; "data:image/png;base64,iVBORw0KGgo=" })
            val pictures = (1..count).map { QuestionImage("i$it", "https://p.ananas.chaoxing.com/$it.png") }
            val result = repo.generate(listOf(question.copy(images = pictures)), settings.copy(imageInputEnabled = true)).toList()
            assertEquals(pictures.map { it.url }, loaded)
            assertEquals(1, result.filterIsInstance<AiEvent.Batch>().size)
            assertTrue(result.filterIsInstance<AiEvent.Warning>().isEmpty())
        }
    }
    @Test fun failedImageOnlySkipsItsQuestionAndKeepsOtherAnswers() = runBlocking {
        val repo = AiRepositoryImpl(keys, client { request ->
            assertFalse(payload(request).toString().contains("image-question"))
            200 to sse("""{"answers":[{"id":"frame::q","answer":["202"]}]}""")
        }, AiImageLoader { error("fixture") })
        val events = repo.generate(listOf(question, question.copy(id = "image-question", number = 2,
            images = listOf(QuestionImage("i", "https://p.ananas.chaoxing.com/a.png")))), settings.copy(imageInputEnabled = true)).toList()
        assertEquals(1, events.filterIsInstance<AiEvent.Warning>().size)
        assertEquals("frame::q", events.filterIsInstance<AiEvent.Batch>().single().answers.single().questionId)
    }
    @Test fun cancellationDuringImageReadSendsNoGenerationRequest() = runBlocking {
        var requests = 0
        val repo = AiRepositoryImpl(keys, client { requests++; error("unexpected") }, AiImageLoader { throw CancellationException("fixture") })
        try { repo.generate(listOf(question.copy(images = listOf(QuestionImage("i", "https://p.ananas.chaoxing.com/a.png")))), settings.copy(imageInputEnabled = true)).toList(); fail() }
        catch (_: CancellationException) { assertEquals(0, requests) }
    }
    @Test fun unsupportedContentIsSkippedWithoutRequestingGuessedText() = runBlocking {
        var count = 0
        val repo = AiRepositoryImpl(keys, client { count++; error("unexpected") })
        try { repo.generate(listOf(question.copy(canAnalyze = false, diagnostics = listOf("混淆字体"))), settings).toList(); fail() }
        catch (_: IllegalArgumentException) { assertEquals(0, count) }
    }
    @Test fun imageReaderRejectsUntrustedUrlsBeforeNetworkOrCookieAccess() = runBlocking {
        var requests = 0; var cookies = 0
        val loader = ChaoxingImageLoader(client { requests++; error("unexpected") }, { cookies++; "fixture-cookie" }, { 0L })
        for (url in listOf("https://chaoxing.com.evil.test/x.png", "file:///x", "https://user:pass@p.ananas.chaoxing.com/x.png", "https://p.ananas.chaoxing.com:8443/x.png")) {
            try { loader.load(url); fail() } catch (_: IllegalArgumentException) { }
        }
        assertEquals(0, requests); assertEquals(0, cookies)
    }
    @Test fun imageReaderRejectsRedirectsAndHtmlDisguisedAsPictures() = runBlocking {
        for (code in listOf(302,200)) {
            val loader = ChaoxingImageLoader(client { code to "<html>login</html>" }, { "" }, { 0L })
            try { loader.load("https://p.ananas.chaoxing.com/a.png"); fail() } catch (_: Exception) { }
        }
    }
    @Test fun inlineImagesAreBoundedAndHaveVerifiedFileHeaders() = runBlocking {
        val loader = ChaoxingImageLoader(client { error("unexpected network") }, { error("unexpected cookie read") }, { 0L })
        val valid = "data:image/png;base64," + Base64.getEncoder().encodeToString(byteArrayOf(137.toByte(),80,78,71,13,10,26,10))
        assertEquals(valid, loader.load(valid))
        try { loader.load("data:image/png;base64," + Base64.getEncoder().encodeToString("<html>".toByteArray())); fail() }
        catch (_: ImageReadException) { }
    }
    private val png = byteArrayOf(137.toByte(),80,78,71,13,10,26,10)
    private fun imageClient(handler: (Request) -> Response) = OkHttpClient.Builder().addInterceptor { handler(it.request()) }.build()
    private fun imageResponse(request: Request, bytes: ByteArray = png, code: Int = 200, location: String? = null): Response =
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("fixture")
            .apply { location?.let { header("Location", it) } }.body(bytes.toResponseBody()).build()

    @Test fun officialImageRedirectsKeepRefererAndRecomputeCookiesForEachHop() = runBlocking {
        val requested = mutableListOf<String>()
        val cookieTargets = mutableListOf<String>()
        val referer = "https://mooc1.chaoxing.com/work/do?ticket=local-only"
        val loader = ChaoxingImageLoader(imageClient { request ->
            requested += request.url.toString()
            assertEquals(referer, request.header("Referer"))
            assertTrue(request.header("User-Agent").orEmpty().contains("Mozilla"))
            assertEquals("cookie-for-${request.url.host}", request.header("Cookie"))
            if (request.url.host == "p.ananas.chaoxing.com") imageResponse(request, code = 302,
                location = "http://p1.chaoxing.com/cdn/a.png?ticket=a%2Bb") else imageResponse(request)
        }, { url -> cookieTargets += url; "cookie-for-${url.toHttpUrl().host}" }, { 0L })
        assertTrue(loader.load("http://p.ananas.chaoxing.com/original.png", referer).startsWith("data:image/png;base64,"))
        assertEquals(listOf("https://p.ananas.chaoxing.com/original.png", "https://p1.chaoxing.com/cdn/a.png?ticket=a%2Bb"), requested)
        assertEquals(requested, cookieTargets)
    }

    @Test fun externalImageRedirectIsRejectedBeforeCookiesOrNetworkReachIt() = runBlocking {
        var calls = 0; var cookies = 0
        val loader = ChaoxingImageLoader(imageClient { request ->
            calls++; imageResponse(request, code = 302, location = "https://external.example/picture.png")
        }, { cookies++; "local-cookie" }, { 0L })
        try { loader.load("https://p.ananas.chaoxing.com/picture.png"); fail() }
        catch (e: ImageReadException) { assertTrue(e.message.orEmpty().contains("未支持")) }
        assertEquals(1, calls); assertEquals(1, cookies)
    }

    @Test fun binaryOrWrongMimeAndImagesLargerThanTwoMegabytesAreAcceptedByFileHeader() = runBlocking {
        for (type in listOf("application/octet-stream", "image/jpg", "text/plain")) {
            val bytes = png + ByteArray(2_000_001)
            val loader = ChaoxingImageLoader(imageClient { request -> imageResponse(request, bytes).newBuilder().header("Content-Type", type).build() }, { "" }, { 0L })
            val result = loader.load("https://p.ananas.chaoxing.com/image")
            assertTrue(result.startsWith("data:image/png;base64,"))
            assertArrayEquals(bytes, Base64.getDecoder().decode(result.substringAfter(',')))
        }
    }

    @Test fun transientImageNetworkAndServerFailuresAreRetried() = runBlocking {
        var calls = 0
        val loader = ChaoxingImageLoader(imageClient { request ->
            when (++calls) { 1 -> throw IOException("private-url-ticket"); 2 -> imageResponse(request, code = 503); else -> imageResponse(request) }
        }, { "" }, { 0L })
        assertTrue(loader.load("https://p.ananas.chaoxing.com/image").startsWith("data:image/png;base64,"))
        assertEquals(3, calls)
    }

    @Test fun aFailedPictureReportsItsPositionAndNeverSendsAnIncompleteQuestion() = runBlocking {
        var loads = 0; var requests = 0
        val repo = AiRepositoryImpl(keys, client { requests++; error("partial question must not be sent") }, AiImageLoader {
            if (++loads == 3) throw IOException("private-resource-ticket")
            "data:image/png;base64,iVBORw0KGgo="
        })
        val events = repo.generate(listOf(question.copy(images = (1..8).map {
            QuestionImage("i$it", "https://p.ananas.chaoxing.com/$it.png")
        })), settings.copy(imageInputEnabled = true)).toList()
        assertEquals(0, requests); assertEquals(3, loads)
        val warning = events.filterIsInstance<AiEvent.Warning>().single().message
        assertTrue(warning.contains("3/8")); assertFalse(warning.contains("private-resource-ticket"))
        assertTrue(events.filterIsInstance<AiEvent.Batch>().isEmpty())
    }

    @Test fun imageRefererIsAvailableLocallyButNeverSentToAi() = runBlocking {
        val referer = "https://mooc1.chaoxing.com/work/do?ticket=private-page-ticket"
        var loaded = false
        val loader = object : AiImageLoader {
            override suspend fun load(url: String): String = error("contextual image loader should be used")
            override suspend fun load(url: String, refererValue: String): String {
                assertEquals(referer, refererValue); loaded = true
                return "data:image/png;base64,iVBORw0KGgo="
            }
        }
        val repo = AiRepositoryImpl(keys, client { request ->
            assertFalse(payload(request).toString().contains("private-page-ticket"))
            200 to sse("""{"answers":[{"id":"frame::q","answer":"202"}]}""")
        }, loader)
        repo.generate(listOf(question.copy(images = listOf(QuestionImage("i", "https://p.ananas.chaoxing.com/a", referer = referer)))),
            settings.copy(imageInputEnabled = true)).toList()
        assertTrue(loaded)
    }
    @Test fun platformHtmlAndImagesShareTheSameSerialRateLimit() = runBlocking {
        val gate = PlatformReadGate()
        val starts = mutableListOf<Long>()
        val html = CookieMcpReadTransport(client { synchronized(starts) { starts += System.nanoTime() }; 200 to "<p>fixture</p>" }, gate)
        val image = ChaoxingImageLoader(client { synchronized(starts) { starts += System.nanoTime() }; 200 to "not an image" }, { "" }, { 0L }, gate)
        coroutineScope {
            val first = async { html.get("https://i.chaoxing.com/base") }
            val second = async { try { image.load("https://p.ananas.chaoxing.com/a.png") } catch (_: Exception) { } }
            first.await(); second.await()
        }
        assertEquals(2, starts.size)
        assertTrue((starts[1] - starts[0]) / 1_000_000 >= 950)
    }
}
