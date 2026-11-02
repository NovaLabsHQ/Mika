package com.wangzan101.mika.core.queue.github

import com.wangzan101.mika.core.queue.AllowedUser
import com.wangzan101.mika.core.queue.BoardLabels
import com.wangzan101.mika.core.queue.Fetched
import com.wangzan101.mika.core.queue.QueueComment
import com.wangzan101.mika.core.queue.QueueException
import com.wangzan101.mika.core.queue.QueueItem
import com.wangzan101.mika.core.queue.QueueUser
import com.wangzan101.mika.core.queue.TaskQueue
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.net.URLEncoder
import java.time.Instant

/** GitHub issues 当任务看板:`owner/repo` 里带 [BoardLabels.TASK] 的打开的 issue 就是排队的任务。 */
class GitHubBoard(
    private val api: GitHubApi,
    val owner: String,
    val repo: String,
) : TaskQueue {
    override val id: String = "github:$owner/$repo"
    private val base = "/repos/${enc(owner)}/${enc(repo)}"

    override suspend fun queued(): Fetched<List<QueueItem>> {
        // 参数每次一样、排序稳定,没变化时才拿得到 304
        val r = api.get("$base/issues?labels=${BoardLabels.TASK}&state=open&sort=created&direction=asc&per_page=$PAGE", conditional = true)
        if (r.notModified) return Fetched.Unchanged
        return Fetched.Changed(items(r))
    }

    override suspend fun inProgress(): List<QueueItem> =
        items(api.get("$base/issues?labels=${BoardLabels.IN_PROGRESS}&state=open&sort=created&direction=asc&per_page=$PAGE"))

    override suspend fun item(number: Int): Fetched<QueueItem> {
        val r = api.get("$base/issues/$number", conditional = true)
        if (r.notModified) return Fetched.Unchanged
        return Fetched.Changed(issue(r.json.jsonObject))
    }

    override suspend fun comments(number: Int, sinceMs: Long?): List<QueueComment> {
        val since = sinceMs?.let { "&since=" + enc(Instant.ofEpochMilli(it.coerceAtLeast(0)).toString()) }.orEmpty()
        val out = mutableListOf<QueueComment>()
        var url: String? = "$base/issues/$number/comments?per_page=100$since"
        var pages = 0
        while (url != null && pages++ < MAX_PAGES) {
            val r = api.get(url)
            r.json.jsonArray.forEach { c ->
                val o = c.jsonObject
                out += QueueComment(o.long("id"), user(o["user"]), o.str("body"), o["created_at"]?.jsonPrimitive?.contentOrNull?.let { Instant.parse(it).toEpochMilli() } ?: 0)
            }
            url = r.next
        }
        return out.sortedBy { it.id }
    }

    override suspend fun addComment(number: Int, body: String): Long =
        api.post("$base/issues/$number/comments", buildJsonObject { put("body", body) }).json.jsonObject.long("id")

    override suspend fun editComment(commentId: Long, body: String) {
        api.patch("$base/issues/comments/$commentId", buildJsonObject { put("body", body) })
    }

    override suspend fun addLabels(number: Int, labels: Set<String>) {
        api.post("$base/issues/$number/labels", buildJsonObject { put("labels", JsonArray(labels.map(::JsonPrimitive))) })
    }

    override suspend fun removeLabel(number: Int, label: String) {
        try { api.delete("$base/issues/$number/labels/${enc(label)}") }
        catch (e: QueueException) { if (e.kind != QueueException.Kind.NotFound) throw e }
    }

    /** 缺的标签建上;已有的(422)不动,颜色说明也不改。 */
    override suspend fun ensureLabels() {
        val have = api.get("$base/labels?per_page=100").json.jsonArray.map { it.jsonObject.str("name").lowercase() }.toSet()
        for ((name, color, description) in BoardLabels.ALL) {
            if (name in have) continue
            try {
                api.post("$base/labels", buildJsonObject { put("name", name); put("color", color); put("description", description) })
            } catch (e: QueueException) { if (e.status != 422) throw e }
        }
    }

    override fun itemUrl(number: Int) = "https://github.com/$owner/$repo/issues/$number"

    /** 「测试连接」:仓库读得到、issues 读得到;返回仓库是否私有。 */
    suspend fun check(): RepoInfo {
        val o = api.get(base).json.jsonObject
        api.get("$base/issues?per_page=1")
        val perms = o["permissions"] as? JsonObject
        return RepoInfo(
            fullName = o.str("full_name"),
            private = o["private"]?.jsonPrimitive?.booleanOrNull == true,
            hasIssues = o["has_issues"]?.jsonPrimitive?.booleanOrNull != false,
            canPush = perms?.get("push")?.jsonPrimitive?.booleanOrNull,
            tokenExpiration = api.tokenExpiration,
        )
    }

    data class RepoInfo(val fullName: String, val private: Boolean, val hasIssues: Boolean, val canPush: Boolean?, val tokenExpiration: String?)

    private fun items(r: GhResponse): List<QueueItem> =
        r.json.jsonArray.map { it.jsonObject }.filter { "pull_request" !in it }.map(::issue)

    private fun issue(o: JsonObject) = QueueItem(
        number = o.long("number").toInt(),
        title = o.str("title"),
        body = o.str("body"),
        author = user(o["user"]),
        labels = (o["labels"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.str("name") }.toSet(),
        open = o.str("state") == "open",
    )

    companion object {
        const val PAGE = 10
        const val MAX_PAGES = 10

        /** 名单上的登录名换成数字编号(GET /users/{login});之后只按编号认人。 */
        suspend fun resolveUsers(api: GitHubApi, logins: List<String>): List<AllowedUser> = logins.map { it.trim().removePrefix("@") }
            .filter { it.isNotEmpty() }.distinct().map { login ->
                val o = api.get("/users/${enc(login)}").json.jsonObject
                AllowedUser(o.str("login"), o.long("id"))
            }

        internal fun user(e: kotlinx.serialization.json.JsonElement?): QueueUser {
            val o = e as? JsonObject ?: return QueueUser(-1, "ghost")
            return QueueUser(o.long("id"), o.str("login"), o.str("type") == "Bot" || o.str("login").endsWith("[bot]"))
        }

        internal fun enc(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
        private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull.orEmpty()
        private fun JsonObject.long(k: String) = (this[k] as? JsonPrimitive)?.longOrNull ?: -1
    }
}
