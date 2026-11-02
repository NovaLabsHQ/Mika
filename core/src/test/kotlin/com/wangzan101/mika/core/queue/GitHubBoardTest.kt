package com.wangzan101.mika.core.queue

import com.wangzan101.mika.core.queue.github.GitHubApi
import com.wangzan101.mika.core.queue.github.GitHubArtifacts
import com.wangzan101.mika.core.queue.github.GitHubBoard
import com.wangzan101.mika.core.queue.github.ImageShrinker
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class GitHubBoardTest {
    private val server = MockWebServer().also { it.start() }
    private var now = 1_700_000_000_000L
    private val api = GitHubApi({ "test-token" }, baseUrl = server.url("/").toString().trimEnd('/'), clock = { now }, mutationGapMs = 0)
    private val board = GitHubBoard(api, "o", "r")

    @After fun stop() = server.shutdown()

    private fun json(body: String, code: Int = 200) = MockResponse().setResponseCode(code).setBody(body).setHeader("Content-Type", "application/json")

    private val issues = """[
        {"number":3,"title":"T","body":"b","state":"open","user":{"id":1,"login":"me","type":"User"},"labels":[{"name":"mika-task"}]},
        {"number":4,"title":"PR","body":"","state":"open","user":{"id":1,"login":"me"},"labels":[],"pull_request":{}}
    ]"""

    @Test fun etagAndPullRequestsSkipped() = runBlocking {
        server.enqueue(json(issues).setHeader("ETag", "\"abc\""))
        server.enqueue(MockResponse().setResponseCode(304))
        val first = board.queued() as Fetched.Changed
        assertEquals(listOf(3), first.value.map { it.number })
        assertEquals(setOf("mika-task"), first.value.single().labels)
        assertEquals(Fetched.Unchanged, board.queued())
        val r1 = server.takeRequest(); val r2 = server.takeRequest()
        assertTrue(r1.path!!.contains("labels=mika-task"))
        assertEquals("Bearer test-token", r1.getHeader("Authorization"))
        assertEquals("2022-11-28", r1.getHeader("X-GitHub-Api-Version"))
        assertEquals(null, r1.getHeader("If-None-Match"))
        assertEquals("\"abc\"", r2.getHeader("If-None-Match"))
    }

    @Test fun authErrorDoesNotLeakToken() = runBlocking {
        server.enqueue(json("""{"message":"Bad credentials"}""", 401))
        try { board.queued(); fail() } catch (e: QueueException) {
            assertEquals(QueueException.Kind.Auth, e.kind)
            assertFalse(e.message!!.contains("test-token"))
        }
    }

    @Test fun rateLimitBlocksUntilReset() = runBlocking {
        val reset = now / 1000 + 120
        server.enqueue(json("""{"message":"API rate limit exceeded"}""", 403)
            .setHeader("x-ratelimit-remaining", "0").setHeader("x-ratelimit-reset", reset.toString()))
        try { board.queued(); fail() } catch (e: QueueException) {
            assertEquals(QueueException.Kind.RateLimited, e.kind)
            assertEquals(120_000L, e.retryAfterMs)
        }
        // 等待期间不再发请求
        try { board.queued(); fail() } catch (e: QueueException) { assertEquals(QueueException.Kind.RateLimited, e.kind) }
        assertEquals(1, server.requestCount)
        now += 121_000
        server.enqueue(json("[]"))
        board.queued()
        assertEquals(2, server.requestCount)
    }

    @Test fun secondaryLimitHonoursRetryAfter() = runBlocking {
        server.enqueue(json("""{"message":"secondary rate limit"}""", 403).setHeader("retry-after", "30"))
        try { board.addComment(1, "x"); fail() } catch (e: QueueException) { assertEquals(30_000L, e.retryAfterMs) }
    }

    @Test fun commentsFollowPagination() = runBlocking {
        val next = server.url("/repos/o/r/issues/3/comments?page=2").toString()
        server.enqueue(json("""[{"id":10,"user":{"id":1,"login":"me"},"body":"a","created_at":"2026-11-02T10:00:00Z"}]""")
            .setHeader("Link", "<$next>; rel=\"next\", <$next>; rel=\"last\""))
        server.enqueue(json("""[{"id":11,"user":{"id":2,"login":"x","type":"Bot"},"body":"b","created_at":"2026-11-02T10:01:00Z"}]"""))
        val c = board.comments(3, sinceMs = 0)
        assertEquals(listOf(10L, 11L), c.map { it.id })
        assertTrue(c[1].author.isBot)
        assertTrue(server.takeRequest().path!!.contains("since=1970-01-01T00%3A00%3A00Z"))
    }

    @Test fun labelsAndComments() = runBlocking {
        server.enqueue(json("""{"id":77}""", 201))
        server.enqueue(json("[]"))
        server.enqueue(json("""{"message":"Label does not exist"}""", 404))
        server.enqueue(json("""[{"name":"mika-task"}]"""))
        repeat(BoardLabels.ALL.size - 2) { server.enqueue(json("{}", 201)) }
        server.enqueue(json("""{"message":"Validation Failed"}""", 422))
        assertEquals(77L, board.addComment(3, "hi"))
        board.addLabels(3, setOf(BoardLabels.IN_PROGRESS))
        board.removeLabel(3, BoardLabels.TASK)
        board.ensureLabels()
        assertEquals("POST", server.takeRequest().method)
        assertEquals("{\"labels\":[\"mika-in-progress\"]}", server.takeRequest().body.readUtf8())
        assertEquals("/repos/o/r/issues/3/labels/mika-task", server.takeRequest().path)
        server.takeRequest()
        val created = (1 until BoardLabels.ALL.size).map { server.takeRequest().body.readUtf8() }
        assertFalse(created.any { "\"name\":\"mika-task\"" in it })
    }

    @Test fun artifactsCreateOrphanBranchThenUpload() = runBlocking {
        val shrink = ImageShrinker { b, _, _ -> b }
        val art = GitHubArtifacts(api, "o", "shots", shrink)
        server.enqueue(json("""{"message":"Not Found"}""", 404))
        server.enqueue(json("""{"sha":"tree1"}""", 201))
        server.enqueue(json("""{"sha":"commit1"}""", 201))
        server.enqueue(json("{}", 201))
        server.enqueue(json("{}", 201))
        server.enqueue(json("""{"message":"sha wasn't supplied"}""", 422))
        val up = art.upload(5, "run1", listOf("step 1" to byteArrayOf(1, 2), "final" to byteArrayOf(3)))
        assertEquals(2, up.links.size)
        assertEquals("https://github.com/o/shots/blob/mika-artifacts/runs/5/run1/0001.jpg?raw=true", up.links[0].url)
        server.takeRequest(); server.takeRequest()
        assertTrue(server.takeRequest().body.readUtf8().contains("\"parents\":[]"))
        assertTrue(server.takeRequest().body.readUtf8().contains("refs/heads/mika-artifacts"))
        val put = server.takeRequest()
        assertEquals("PUT", put.method)
        assertEquals("/repos/o/shots/contents/runs/5/run1/0001.jpg", put.path)
        assertTrue(put.body.readUtf8().contains("\"branch\":\"mika-artifacts\""))
    }

    @Test fun resolvesUsersToIds() = runBlocking {
        server.enqueue(json("""{"login":"Alice","id":42}"""))
        assertEquals(listOf(AllowedUser("Alice", 42)), GitHubBoard.resolveUsers(api, listOf("@alice", " ")))
    }
}
