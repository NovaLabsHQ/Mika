package com.wangzan101.mika.core.queue.github

import com.wangzan101.mika.core.queue.QueueException
import com.wangzan101.mika.core.queue.ScreenshotLink
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.Base64

/** 截图缩小成 JPEG(App 里用 BitmapFactory 实现;核心层不碰 Android)。返回 null 表示这张读不出来,跳过。 */
fun interface ImageShrinker {
    fun shrink(bytes: ByteArray, maxEdgePx: Int, maxBytes: Int): ByteArray?
}

/**
 * 截图放到仓库的 [BRANCH] 分支(contents API 一张一个提交),评论里只放链接,不往 issue 里塞图。
 * 仓库可以和看板不同(私有看板 + 私有截图仓库,或者反过来);分支没有就建一个无父提交的孤立分支,不碰默认分支。
 */
class GitHubArtifacts(
    private val api: GitHubApi,
    val owner: String,
    val repo: String,
    private val shrinker: ImageShrinker,
    private val maxEdgePx: Int = 1080,
    private val maxBytes: Int = 300_000,
    /** 一次结果最多传多少字节,超了后面的不传(评论里说明)。 */
    private val budgetBytes: Int = 3_000_000,
) {
    private val base = "/repos/${GitHubBoard.enc(owner)}/${GitHubBoard.enc(repo)}"
    @Volatile private var branchReady = false

    data class Upload(val links: List<ScreenshotLink>, val skipped: Int)

    /** [shots]:(说明, 原图)。路径 `runs/<issue>/<runId>/NNNN.jpg`;同名文件已在(重发)算已传。 */
    suspend fun upload(issue: Int, runId: String, shots: List<Pair<String, ByteArray>>): Upload {
        if (shots.isEmpty()) return Upload(emptyList(), 0)
        ensureBranch()
        val links = mutableListOf<ScreenshotLink>()
        var used = 0
        var skipped = 0
        shots.forEachIndexed { i, (label, raw) ->
            val jpg = shrinker.shrink(raw, maxEdgePx, maxBytes)
            if (jpg == null || used + jpg.size > budgetBytes) { skipped++; return@forEachIndexed }
            val path = "runs/$issue/${safe(runId)}/%04d.jpg".format(i + 1)
            try {
                api.put("$base/contents/$path", buildJsonObject {
                    put("message", "Mika screenshot for #$issue ($runId)")
                    put("content", Base64.getEncoder().encodeToString(jpg))
                    put("branch", BRANCH)
                })
            } catch (e: QueueException) {
                // 422:同名文件已在(上一次传到一半);不覆盖,直接用
                if (e.status != 422) throw e
            }
            used += jpg.size
            links += ScreenshotLink(label, link(path))
        }
        return Upload(links, skipped)
    }

    fun link(path: String) = "https://github.com/$owner/$repo/blob/$BRANCH/$path?raw=true"

    /** 分支不在就建:README 树 → 无父提交 → 引用;空仓库(409)先用 contents 建默认分支再来。 */
    suspend fun ensureBranch() {
        if (branchReady) return
        try {
            api.get("$base/git/ref/heads/$BRANCH")
            branchReady = true
            return
        } catch (e: QueueException) {
            if (e.kind != QueueException.Kind.NotFound && e.status != 409) throw e
        }
        val tree = try {
            createTree()
        } catch (e: QueueException) {
            if (e.status != 409) throw e
            // 空仓库没法建 git 对象,先放一个 README 让它有默认分支
            api.put("$base/contents/README.md", buildJsonObject {
                put("message", "Initialize repository for Mika artifacts")
                put("content", Base64.getEncoder().encodeToString("# Mika artifacts\n".toByteArray()))
            })
            createTree()
        }
        val commit = api.post("$base/git/commits", buildJsonObject {
            put("message", "Mika screenshots branch")
            put("tree", tree)
            put("parents", JsonArray(emptyList()))
        }).json.jsonObject.getValue("sha").jsonPrimitive.content
        try {
            api.post("$base/git/refs", buildJsonObject { put("ref", "refs/heads/$BRANCH"); put("sha", commit) })
        } catch (e: QueueException) {
            // 422:别的设备刚建好
            if (e.status != 422) throw e
        }
        branchReady = true
    }

    private suspend fun createTree(): String = api.post("$base/git/trees", buildJsonObject {
        put("tree", JsonArray(listOf(buildJsonObject {
            put("path", "README.md"); put("mode", "100644"); put("type", "blob")
            put("content", "# Mika screenshots\n\nScreenshots uploaded by Mika for task-board results. Safe to prune.\n")
        })))
    }).json.jsonObject.getValue("sha").jsonPrimitive.content

    /** 「测试连接」用:截图仓库读得到、令牌能推。 */
    suspend fun check(): Boolean? {
        val o = api.get(base).json.jsonObject
        return ((o["permissions"] as? JsonObject)?.get("push") as? JsonPrimitive)?.content?.toBooleanStrictOrNull()
    }

    companion object {
        const val BRANCH = "mika-artifacts"
        private fun safe(s: String) = s.replace(Regex("[^A-Za-z0-9._-]"), "_").take(64)
    }
}
