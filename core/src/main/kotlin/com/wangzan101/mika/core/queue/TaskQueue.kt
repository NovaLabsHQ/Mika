package com.wangzan101.mika.core.queue

/** 看板上的人;[id] 是数字编号(改名也不变),只按它认人。 */
data class QueueUser(val id: Long, val login: String, val isBot: Boolean = false)

data class QueueItem(
    val number: Int,
    val title: String,
    val body: String,
    val author: QueueUser,
    val labels: Set<String>,
    val open: Boolean = true,
)

data class QueueComment(val id: Long, val author: QueueUser, val body: String, val createdAtMs: Long)

/** 带条件请求的读:没变时不重新给内容(GitHub 的 304 不算请求额度)。 */
sealed interface Fetched<out T> {
    data class Changed<T>(val value: T) : Fetched<T>
    data object Unchanged : Fetched<Nothing>
}

class QueueException(
    val kind: Kind, message: String, val retryAfterMs: Long? = null, cause: Throwable? = null,
    /** [Kind.Http] 时的状态码(409 空仓库、422 已存在等)。 */
    val status: Int? = null,
) : Exception(message, cause) {
    enum class Kind {
        /** 令牌无效或过期:停止轮询,等用户重新填。 */
        Auth,
        /** 没有权限(或仓库对这个令牌不可见)。 */
        Forbidden,
        NotFound,
        /** 限流:[retryAfterMs] 之后再来。 */
        RateLimited,
        Network,
        Http,
    }
}

/** 看板用到的标签和意义见 [docs/openbot-guide.md]。 */
object BoardLabels {
    const val TASK = "mika-task"
    const val IN_PROGRESS = "mika-in-progress"
    const val NEEDS_INPUT = "mika-needs-input"
    const val DONE = "mika-done"
    const val FAILED = "mika-failed"

    /** 名字、颜色(不带 #)、说明;手机缺哪个建哪个。 */
    val ALL = listOf(
        Triple(TASK, "1d76db", "Queued for Mika on the test phone"),
        Triple(IN_PROGRESS, "fbca04", "Mika is running this task"),
        Triple(NEEDS_INPUT, "d93f0b", "Mika asked a question; reply in a comment"),
        Triple(DONE, "0e8a16", "Mika finished: PASS"),
        Triple(FAILED, "b60205", "Mika finished: not PASS (fail, unverified, timeout or error)"),
    )
}

/**
 * 一个任务看板(今天只有 GitHub issues):列排队的、读评论、发评论、换标签。
 * 只管收发,不决定谁能交任务([BoardOwners])和怎么跑([QueueChannel])。
 */
interface TaskQueue {
    /** 例如 `github:owner/repo`。 */
    val id: String

    /** 带 [BoardLabels.TASK] 的打开的任务,按建立时间从早到晚;拉取请求不算。 */
    suspend fun queued(): Fetched<List<QueueItem>>
    suspend fun inProgress(): List<QueueItem>
    suspend fun item(number: Int): Fetched<QueueItem>
    /** 按时间从早到晚;[sinceMs] 给了时只要这之后更新过的。 */
    suspend fun comments(number: Int, sinceMs: Long? = null): List<QueueComment>
    suspend fun addComment(number: Int, body: String): Long
    suspend fun editComment(commentId: Long, body: String)
    suspend fun addLabels(number: Int, labels: Set<String>)
    /** 本来就没有这个标签不算错。 */
    suspend fun removeLabel(number: Int, label: String)
    suspend fun ensureLabels()
    fun itemUrl(number: Int): String
}
