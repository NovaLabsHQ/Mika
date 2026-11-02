package com.wangzan101.mika.settings

import com.wangzan101.mika.core.queue.AllowedUser
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/** 任务看板(GitHub issues)的设置;令牌不在这里,见 [BoardSettings.token]。 */
data class BoardConfig(
    val enabled: Boolean = false,
    val owner: String = "",
    val repo: String = "",
    /** 截图仓库 `owner/name`;空着就用看板仓库。 */
    val artifactsRepo: String = "",
    /** 用户填的登录名(逗号或空格分开)。 */
    val allowedLogins: String = "",
    /** 测试连接时换出来的数字编号;只按它认人。登录名改了要重新测试连接。 */
    val allowedUsers: List<AllowedUserJson> = emptyList(),
    val pollMinutes: Int = DEFAULT_POLL_MINUTES,
    val wifiOnly: Boolean = true,
    val unlockedOnly: Boolean = true,
    /** 这台手机在领取标记里的编号;第一次读设置时生成,不随别的设置变。 */
    val deviceId: String = "",
) {
    val artifactsOwner get() = artifactsRepo.substringBefore('/', "").ifBlank { owner }
    val artifactsName get() = artifactsRepo.substringAfter('/', "").ifBlank { repo }
    val users get() = allowedUsers.map { AllowedUser(it.login, it.id) }
    val ready get() = owner.isNotBlank() && repo.isNotBlank() && allowedUsers.isNotEmpty()

    fun logins(): List<String> = allowedLogins.split(',', ' ', '\n', ';').map { it.trim().removePrefix("@") }.filter { it.isNotEmpty() }.distinct()

    companion object {
        const val DEFAULT_POLL_MINUTES = 2
        const val MIN_POLL_MINUTES = 1
        const val MAX_POLL_MINUTES = 60
        private val NAME = Regex("^[A-Za-z0-9_.-]{1,100}$")
        fun validName(s: String) = NAME.matches(s) && s != "." && s != ".."

        // app 模块没有序列化插件,手写读写;读不懂的字段按默认
        fun toJson(c: BoardConfig): String = buildJsonObject {
            put("enabled", c.enabled); put("owner", c.owner); put("repo", c.repo); put("artifactsRepo", c.artifactsRepo)
            put("allowedLogins", c.allowedLogins)
            put("allowedUsers", buildJsonArray { c.allowedUsers.forEach { u -> add(buildJsonObject { put("login", u.login); put("id", u.id) }) } })
            put("pollMinutes", c.pollMinutes); put("wifiOnly", c.wifiOnly); put("unlockedOnly", c.unlockedOnly); put("deviceId", c.deviceId)
        }.toString()

        fun fromJson(text: String): BoardConfig {
            val o = Json.parseToJsonElement(text).jsonObject
            fun str(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull
            fun bool(k: String) = (o[k] as? JsonPrimitive)?.booleanOrNull
            val d = BoardConfig()
            return BoardConfig(
                enabled = bool("enabled") ?: d.enabled, owner = str("owner") ?: d.owner, repo = str("repo") ?: d.repo,
                artifactsRepo = str("artifactsRepo") ?: d.artifactsRepo, allowedLogins = str("allowedLogins") ?: d.allowedLogins,
                allowedUsers = runCatching {
                    o["allowedUsers"]!!.jsonArray.mapNotNull { e ->
                        val u = e as? JsonObject ?: return@mapNotNull null
                        val login = u["login"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                        val id = u["id"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null
                        AllowedUserJson(login, id)
                    }
                }.getOrDefault(emptyList()),
                pollMinutes = (o["pollMinutes"] as? JsonPrimitive)?.intOrNull ?: d.pollMinutes,
                wifiOnly = bool("wifiOnly") ?: d.wifiOnly, unlockedOnly = bool("unlockedOnly") ?: d.unlockedOnly,
                deviceId = str("deviceId") ?: d.deviceId,
            )
        }
    }
}

data class AllowedUserJson(val login: String, val id: Long)

/** 上一次轮询的结果,设置页显示(不存盘)。 */
data class BoardStatus(val atMs: Long, val ok: Boolean, val message: String)

/**
 * 看板设置存成 `settings/board.json`(读不懂按关着);GitHub 令牌和 MCP 令牌一样经 [KeyCipher] 加密另存
 * `settings/board-token.bin`,只在发请求时读出来,不进日志、不回显。
 */
class BoardSettings(private val dir: File, private val cipher: KeyCipher) {
    private val file = File(dir, "board.json")
    private val tokenFile = File(dir, "board-token.bin")
    private val _config: MutableStateFlow<BoardConfig>
    private val _status = MutableStateFlow<BoardStatus?>(null)
    @Volatile private var cachedToken: String? = null

    init {
        val read = runCatching { BoardConfig.fromJson(file.readText()) }.getOrNull() ?: BoardConfig()
        _config = MutableStateFlow(read)
        if (read.deviceId.isBlank()) update { it.copy(deviceId = UUID.randomUUID().toString().take(8)) }
    }

    val config: StateFlow<BoardConfig> = _config.asStateFlow()
    val status: StateFlow<BoardStatus?> = _status.asStateFlow()

    fun setStatus(s: BoardStatus) { _status.value = s }

    @Synchronized fun update(change: (BoardConfig) -> BoardConfig) {
        val next = change(_config.value).let { c ->
            c.copy(pollMinutes = c.pollMinutes.coerceIn(BoardConfig.MIN_POLL_MINUTES, BoardConfig.MAX_POLL_MINUTES))
        }
        _config.value = next
        dir.mkdirs()
        val tmp = File(dir, file.name + ".tmp")
        tmp.writeText(BoardConfig.toJson(next))
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    @Synchronized fun token(): String? {
        cachedToken?.let { return it }
        return runCatching { if (tokenFile.isFile) String(cipher.decrypt(tokenFile.readBytes()), Charsets.UTF_8) else null }
            .getOrNull()?.takeIf { it.isNotBlank() }?.also { cachedToken = it }
    }

    val hasToken: Boolean get() = token() != null

    /** 换令牌;空字符串表示删掉。 */
    @Synchronized fun setToken(value: String) {
        val t = value.trim()
        cachedToken = null
        if (t.isEmpty()) { tokenFile.delete(); return }
        dir.mkdirs()
        val tmp = File(dir, tokenFile.name + ".tmp")
        tmp.writeBytes(cipher.encrypt(t.toByteArray(Charsets.UTF_8)))
        Files.move(tmp.toPath(), tokenFile.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        cachedToken = t
    }
}
