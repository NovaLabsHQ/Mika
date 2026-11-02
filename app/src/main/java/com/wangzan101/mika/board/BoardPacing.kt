package com.wangzan101.mika.board

/**
 * 轮询间隔:空闲按设置的分钟数,有任务在跑一分钟一次,等回答时 20 秒一次;不低于 15 秒。
 * 出错翻倍退避到 30 分钟,限流时至少等到 GitHub 说的时刻。纯计算,单独测。
 */
class BoardPacing(private val idleMs: () -> Long) {
    private var failures = 0

    fun next(busy: Boolean, waiting: Boolean): Long {
        val base = when { waiting -> WAITING_MS; busy -> BUSY_MS; else -> idleMs() }
        return base.coerceAtLeast(FLOOR_MS)
    }

    fun ok() { failures = 0 }

    fun failed(retryAfterMs: Long? = null): Long {
        failures++
        val back = (FLOOR_MS shl (failures.coerceAtMost(12))).coerceAtMost(MAX_BACKOFF_MS)
        return maxOf(back, retryAfterMs ?: 0L).coerceAtMost(MAX_BACKOFF_MS)
    }

    companion object {
        const val FLOOR_MS = 15_000L
        const val WAITING_MS = 20_000L
        const val BUSY_MS = 60_000L
        const val MAX_BACKOFF_MS = 30 * 60_000L
    }
}
