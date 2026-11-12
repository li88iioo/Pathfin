package dev.pathfin.android.domain

import java.io.IOException
import java.util.Collections
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerAddressSelectionTest {
    private val lan = ServerConfig("http://192.168.1.20:8096", allowInsecureHttp = true)
    private val public = ServerConfig("https://media.example.invalid/jellyfin")
    private val mirror = ServerConfig("https://mirror.example.invalid")
    private val session = Session(public, "synthetic-token", "user", "Alice", "device", "server-a")
    private fun profile(current: ServerConfig = public, vararg backups: ServerConfig, auto: Boolean = true) =
        ServerProfile("profile", session.copy(server = current), "Home", addresses = backups.toList(), autoAddress = auto)

    private fun choose(profile: ServerProfile, responses: Map<ServerConfig, suspend () -> String>): ServerConfig? = runBlocking {
        ServerAddressSelection.choose(profile) { address -> responses.getValue(address)() }
    }

    @Test fun prefersAReachableLanAddressOverTheCurrentPublicOne() {
        assertEquals(lan, choose(profile(public, lan), mapOf(public to { "server-a" }, lan to { "server-a" })))
    }

    @Test fun fallsBackToPublicWhenTheLanAddressFailsOrHangs() {
        assertEquals(public, choose(profile(lan, public), mapOf(lan to { throw IOException("unreachable") }, public to { "server-a" })))
        val started = System.nanoTime()
        assertEquals(public, choose(profile(lan, public), mapOf(lan to { awaitCancellation() }, public to { "server-a" })))
        // 前面的地址挂起时只等一个探测超时，不会等到网络层的连接超时。
        assertTrue((System.nanoTime() - started) / 1_000_000 < ServerAddressSelection.PROBE_TIMEOUT_MS + 1_000)
    }

    @Test fun ignoresAnAddressThatAnswersAsAnotherServer() {
        assertEquals(public, choose(profile(public, lan), mapOf(lan to { "someone-else" }, public to { "server-a" })))
    }

    @Test fun keepsTheCurrentAddressAmongEquallyRankedOnes() {
        assertEquals(mirror, choose(profile(mirror, public), mapOf(public to { "server-a" }, mirror to { "server-a" })))
    }

    @Test fun returnsNullWhenNothingAnswers() {
        assertNull(choose(profile(public, lan), mapOf(lan to { throw IOException() }, public to { throw IOException() })))
    }

    @Test fun doesNotProbeWhenManualSingleAddressOrServerIdentityUnknown() {
        val probed = Collections.synchronizedList(mutableListOf<ServerConfig>())
        val cases = listOf(profile(public, lan, auto = false), profile(public),
            profile(public, lan).let { it.copy(session = it.session.copy(serverId = "")) })
        cases.forEach { candidate ->
            assertNull(runBlocking { ServerAddressSelection.choose(candidate) { probed += it; "server-a" } })
        }
        assertTrue(probed.isEmpty())
    }

    @Test fun switchingAddressKeepsThePreviousOneAsABackup() {
        val switched = profile(public, lan, mirror).withSession(session.copy(server = lan))
        assertEquals(lan, switched.session.server)
        assertEquals(listOf(public, mirror), switched.addresses)
        assertEquals(listOf(lan, public, mirror), switched.connectionAddresses)
    }
}
