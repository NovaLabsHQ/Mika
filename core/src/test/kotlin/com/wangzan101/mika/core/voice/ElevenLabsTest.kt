package com.wangzan101.mika.core.voice

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class ElevenLabsTest {
    private lateinit var server: MockWebServer
    private val http = OkHttpClient()

    @Before fun setUp() { server = MockWebServer().apply { start() } }
    @After fun tearDown() { server.shutdown() }

    private fun base() = server.url("/").toString()

    @Test fun scribeSendsRawPcmAsMultipart() = runTest {
        server.enqueue(MockResponse().setBody("""{"language_code":"fr","language_probability":0.9,"text":" Bonjour ","words":[]}"""))
        val scribe = ElevenLabsScribe(http, { "k-123" }, base())
        val t = scribe.transcribe(shortArrayOf(1, -2, 300))
        assertEquals(Transcript("Bonjour", "fr"), t)
        val req = server.takeRequest()
        assertEquals("/v1/speech-to-text", req.path)
        assertEquals("k-123", req.getHeader("xi-api-key"))
        assertTrue(req.getHeader("Content-Type")!!.startsWith("multipart/form-data"))
        val body = req.body.readUtf8()
        assertTrue(body.contains("name=\"model_id\"\r\nContent-Length: 9\r\n\r\nscribe_v2") || body.contains("scribe_v2"))
        assertTrue(body.contains("pcm_s16le_16"))
        assertTrue(!body.contains("language_code"))
    }

    @Test fun scribePassesLanguageWhenKnown() = runTest {
        server.enqueue(MockResponse().setBody("""{"text":"hi"}"""))
        ElevenLabsScribe(http, { "k" }, base()).transcribe(shortArrayOf(1), "en")
        assertTrue(server.takeRequest().body.readUtf8().contains("name=\"language_code\""))
    }

    @Test fun scribeWithoutKeyFailsBeforeNetwork() = runTest {
        try {
            ElevenLabsScribe(http, { " " }, base()).transcribe(shortArrayOf(1))
            fail()
        } catch (_: VoiceError.NoKey) {}
        assertEquals(0, server.requestCount)
    }

    @Test fun scribeMapsErrors() = runTest {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"detail":{"status":"invalid_api_key"}}"""))
        server.enqueue(MockResponse().setResponseCode(429).setHeader("retry-after", "2"))
        server.enqueue(MockResponse().setResponseCode(500))
        server.enqueue(MockResponse().setBody("not json"))
        val scribe = ElevenLabsScribe(http, { "k" }, base())
        suspend fun err() = try { scribe.transcribe(shortArrayOf(1)); null } catch (e: VoiceError) { e }
        assertTrue(err() is VoiceError.Auth)
        assertEquals(2000L, (err() as VoiceError.RateLimited).retryAfterMs)
        assertEquals(500, (err() as VoiceError.Server).status)
        assertTrue(err() is VoiceError.BadResponse)
    }

    @Test fun ttsStreamsPcmChunks() = runTest {
        val samples = ShortArray(5000) { (it * 7 - 9000).toShort() }
        server.enqueue(MockResponse().setBody(Buffer().write(Pcm16.toLittleEndian(samples))).throttleBody(1001, 0, java.util.concurrent.TimeUnit.MILLISECONDS))
        val tts = ElevenLabsTts(http, { "k" }, { "voice42" }, base())
        val chunks = tts.synthesize("Hello there.", "en").toList()
        assertTrue(chunks.all { it.sampleRate == 24_000 })
        assertArrayEquals(samples, chunks.flatMap { it.samples.toList() }.toShortArray())
        val req = server.takeRequest()
        assertEquals("/v1/text-to-speech/voice42/stream?output_format=pcm_24000", req.path)
        assertEquals("k", req.getHeader("xi-api-key"))
        val body = req.body.readUtf8()
        assertTrue(body.contains("\"text\":\"Hello there.\""))
        assertTrue(body.contains("\"model_id\":\"eleven_flash_v2_5\""))
        assertTrue(body.contains("\"language_code\":\"en\""))
    }

    @Test fun ttsMapsErrorsAndNeedsKey() = runTest {
        server.enqueue(MockResponse().setResponseCode(403))
        try { ElevenLabsTts(http, { "k" }, baseUrl = base()).synthesize("hi").toList(); fail() } catch (_: VoiceError.Auth) {}
        try { ElevenLabsTts(http, { null }, baseUrl = base()).synthesize("hi").toList(); fail() } catch (_: VoiceError.NoKey) {}
        assertEquals(1, server.requestCount)
    }

    @Test fun decoderKeepsOddBytesAcrossChunks() {
        val samples = shortArrayOf(0x1234, -1, Short.MIN_VALUE, Short.MAX_VALUE, 7)
        val bytes = Pcm16.toLittleEndian(samples)
        val d = Pcm16.Decoder()
        val out = listOf(bytes.copyOfRange(0, 3), bytes.copyOfRange(3, 4), bytes.copyOfRange(4, 9), bytes.copyOfRange(9, 10))
            .flatMap { d.feed(it).toList() }
        assertArrayEquals(samples, out.toShortArray())
    }
}
