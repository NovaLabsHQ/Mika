package com.wangzan101.mika.core.queue.github

import com.wangzan101.mika.core.queue.QueueException
import com.wangzan101.mika.core.queue.QueueException.Kind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** 条件请求的缓存:地址 → (ETag, 上次的正文)。正文只存看板用到的列表和 issue,不含令牌。 */
interface EtagStore {
    fun get(url: String): Pair<String, String>?
    fun put(url: String, etag: String, body: String)
}

class MemoryEtagStore : EtagStore {
    internal val map = LinkedHashMap<String, Pair<String, String>>()
    @Synchronized override fun get(url: String) = map[url]
    @Synchronized override fun put(url: String, etag: String, body: String) {
        map.remove(url)
        map[url] = etag to body
        while (map.size > MAX) map.remove(map.keys.first())
    }
    companion object { const val MAX = 64 }
}

/** 存成一个 JSON 文件,重启后第一次轮询也能拿到 304。写坏、读坏都只当没缓存。 */
class FileEtagStore(private val file: File) : EtagStore {
    private val mem = MemoryEtagStore()

    init {
        runCatching {
            if (file.isFile) (Json.parseToJsonElement(file.readText()) as JsonObject).forEach { (url, v) ->
                val o = v as JsonObject
                mem.put(url, o.getValue("e").jsonPrimitive.content, o.getValue("b").jsonPrimitive.content)
            }
        }
    }

    override fun get(url: String) = mem.get(url)

    @Synchronized override fun put(url: String, etag: String, body: String) {
        mem.put(url, etag, body)
        runCatching {
            val all = buildJsonObject {
                synchronized(mem) { mem.map.toMap() }.forEach { (u, p) -> put(u, buildJsonObject { put("e", p.first); put("b", p.second) }) }
            }
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(all.toString())
            if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
        }
    }
}

/** 一次请求的结果;[notModified] 时 [body] 是缓存里上次的正文。 */
class GhResponse(val code: Int, val body: String, val headers: Headers, val notModified: Boolean = false) {
    val json: JsonElement by lazy { Json.parseToJsonElement(body.ifBlank { "null" }) }
    /** Link 头里 rel="next" 的地址。 */
    val next: String? get() = headers["link"]?.split(',')?.map { it.trim() }
        ?.firstOrNull { it.endsWith("rel=\"next\"") }?.substringAfter('<')?.substringBefore('>')
}

/**
 * GitHub REST 的最小客户端:带版本头、令牌,GET 走 ETag 条件请求(304 不算额度),
 * 按 retry-after / x-ratelimit-* 停一阵,401 直接报令牌坏了。改动类请求之间至少隔 [mutationGapMs](官方建议 1 秒)。
 * 令牌只放在请求头里,不进日志、不进异常消息。
 */
class GitHubApi(
    private val token: () -> String?,
    private val http: OkHttpClient = defaultClient(),
    val baseUrl: String = "https://api.github.com",
    private val etags: EtagStore = MemoryEtagStore(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val mutationGapMs: Long = 1_000,
) {
    @Volatile var blockedUntilMs = 0L
        private set
    @Volatile var rateRemaining: Int? = null
        private set
    /** `github-authentication-token-expiration` 头(细粒度令牌、有期限的经典令牌才有),原样记下。 */
    @Volatile var tokenExpiration: String? = null
        private set
    @Volatile private var lastMutationMs = 0L

    suspend fun get(path: String, conditional: Boolean = false): GhResponse = call("GET", path, null, conditional)
    suspend fun post(path: String, body: JsonElement): GhResponse = call("POST", path, body, false)
    suspend fun patch(path: String, body: JsonElement): GhResponse = call("PATCH", path, body, false)
    suspend fun put(path: String, body: JsonElement): GhResponse = call("PUT", path, body, false)
    suspend fun delete(path: String): GhResponse = call("DELETE", path, null, false)

    private suspend fun call(method: String, path: String, body: JsonElement?, conditional: Boolean): GhResponse {
        val now = clock()
        if (blockedUntilMs > now) throw QueueException(Kind.RateLimited, "GitHub rate limit; waiting", blockedUntilMs - now)
        val tok = token()?.takeIf { it.isNotBlank() } ?: throw QueueException(Kind.Auth, "No GitHub token")
        if (method != "GET") {
            val wait = lastMutationMs + mutationGapMs - clock()
            if (wait > 0) delay(wait)
        }
        val url = if (path.startsWith("http")) path else baseUrl.trimEnd('/') + path
        val cached = if (conditional) etags.get(url) else null
        val req = Request.Builder().url(url)
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", API_VERSION)
            .header("Authorization", "Bearer $tok")
            .header("User-Agent", "Mika-Tester")
            .apply { cached?.let { header("If-None-Match", it.first) } }
            .method(method, body?.toString()?.toRequestBody(JSON) ?: if (method == "GET" || method == "DELETE") null else "".toRequestBody(JSON))
            .build()
        val resp = withContext(Dispatchers.IO) {
            try {
                http.newCall(req).execute().use { r -> GhResponse(r.code, r.body?.string().orEmpty(), r.headers) }
            } catch (e: IOException) { throw QueueException(Kind.Network, "GitHub unreachable: ${e::class.simpleName}", cause = e) }
        }
        if (method != "GET") lastMutationMs = clock()
        noteLimits(resp.headers)
        if (resp.code == 304 && cached != null) return GhResponse(304, cached.second, resp.headers, notModified = true)
        if (resp.code in 200..299) {
            if (method == "GET") resp.headers["etag"]?.let { etags.put(url, it, resp.body) }
            return resp
        }
        throw error(resp)
    }

    private fun noteLimits(h: Headers) {
        h["github-authentication-token-expiration"]?.let { tokenExpiration = it }
        val remaining = h["x-ratelimit-remaining"]?.toIntOrNull()
        if (remaining != null) rateRemaining = remaining
        val reset = h["x-ratelimit-reset"]?.toLongOrNull()
        // 额度快用完时先停到重置,不等真的被拒
        if (remaining != null && remaining < LOW_REMAINING && reset != null) blockedUntilMs = maxOf(blockedUntilMs, reset * 1000)
    }

    private fun error(r: GhResponse): QueueException {
        val msg = runCatching { (r.json as? JsonObject)?.get("message")?.jsonPrimitive?.content }.getOrNull()
            ?.take(200) ?: "HTTP ${r.code}"
        val retryAfter = r.headers["retry-after"]?.toLongOrNull()?.times(1000)
        val limited = r.code == 429 || (r.code == 403 && (retryAfter != null || r.headers["x-ratelimit-remaining"] == "0" || msg.contains("rate limit", true)))
        return when {
            r.code == 401 -> QueueException(Kind.Auth, "GitHub rejected the token (401)")
            limited -> {
                val reset = r.headers["x-ratelimit-reset"]?.toLongOrNull()?.times(1000)?.minus(clock())
                // 都没说多久时至少等一分钟(官方建议)
                val wait = retryAfter ?: reset?.coerceAtLeast(1_000) ?: 60_000
                blockedUntilMs = maxOf(blockedUntilMs, clock() + wait)
                QueueException(Kind.RateLimited, "GitHub rate limit: $msg", wait)
            }
            r.code == 403 -> QueueException(Kind.Forbidden, "GitHub refused (403): $msg")
            r.code == 404 -> QueueException(Kind.NotFound, "Not found on GitHub (404): $msg")
            else -> QueueException(Kind.Http, "GitHub error ${r.code}: $msg", status = r.code)
        }
    }

    companion object {
        const val API_VERSION = "2022-11-28"
        const val LOW_REMAINING = 50
        private val JSON = "application/json; charset=utf-8".toMediaType()

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).writeTimeout(60, TimeUnit.SECONDS)
            .build()
    }
}
