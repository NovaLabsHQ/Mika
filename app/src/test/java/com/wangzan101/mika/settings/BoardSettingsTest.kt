package com.wangzan101.mika.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class BoardSettingsTest {
    @get:Rule val tmp = TemporaryFolder()

    private val cipher = object : KeyCipher {
        override fun encrypt(plain: ByteArray) = plain.map { (it.toInt() xor 0x5A).toByte() }.toByteArray()
        override fun decrypt(data: ByteArray) = encrypt(data)
    }

    @Test fun offByDefaultAndRoundTrips() {
        val dir = tmp.newFolder("settings")
        val s = BoardSettings(dir, cipher)
        assertFalse(s.config.value.enabled)
        assertFalse(s.config.value.ready)
        val id = s.config.value.deviceId
        assertTrue(id.isNotBlank())
        s.update { it.copy(owner = "me", repo = "tasks", artifactsRepo = "me/shots", allowedLogins = "@alice, bob",
            allowedUsers = listOf(AllowedUserJson("alice", 42)), pollMinutes = 999, wifiOnly = false) }
        val again = BoardSettings(dir, cipher).config.value
        assertEquals("me", again.owner)
        assertEquals(listOf("alice", "bob"), again.logins())
        assertEquals(42L, again.users.single().id)
        assertEquals(BoardConfig.MAX_POLL_MINUTES, again.pollMinutes)
        assertFalse(again.wifiOnly)
        assertEquals(id, again.deviceId)
        assertEquals("me" to "shots", again.artifactsOwner to again.artifactsName)
        assertTrue(again.ready)
    }

    @Test fun artifactsDefaultToBoardRepo() {
        val c = BoardConfig(owner = "o", repo = "r")
        assertEquals("o" to "r", c.artifactsOwner to c.artifactsName)
    }

    @Test fun tokenIsEncryptedAndRemovable() {
        val dir = tmp.newFolder("settings")
        val s = BoardSettings(dir, cipher)
        assertNull(s.token())
        s.setToken("  ghp_example  ")
        assertEquals("ghp_example", s.token())
        val raw = File(dir, "board-token.bin").readBytes().toString(Charsets.UTF_8)
        assertFalse(raw.contains("ghp_example"))
        assertFalse(File(dir, "board.json").readText().contains("ghp_example"))
        assertEquals("ghp_example", BoardSettings(dir, cipher).token())
        s.setToken("")
        assertFalse(s.hasToken)
        assertNull(BoardSettings(dir, cipher).token())
    }

    @Test fun unreadableFileMeansOff() {
        val dir = tmp.newFolder("settings")
        File(dir, "board.json").writeText("{not json")
        assertFalse(BoardSettings(dir, cipher).config.value.enabled)
    }

    @Test fun namesAreValidated() {
        assertTrue(BoardConfig.validName("mika-tasks"))
        assertFalse(BoardConfig.validName("a/b"))
        assertFalse(BoardConfig.validName(".."))
    }
}
