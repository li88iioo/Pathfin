package dev.pathfin.android.update

import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class UpgradeIdentityTest {
    private val installed = ApkIdentity("dev.pathfin.android", 31, setOf("release-cert"))

    @Test fun newerBuildWithSameSignatureIsAccepted() {
        checkUpgrade(installed, installed.copy(versionCode = 32))
    }

    @Test fun mismatchedPackagesSignaturesAndVersionsAreRefused() {
        val refused = listOf(
            installed.copy(packageName = "dev.pathfin.android.debug", versionCode = 32),
            installed.copy(versionCode = 32, signers = setOf("debug-cert")),
            installed.copy(versionCode = 32, signers = setOf("release-cert", "extra-cert")),
            installed.copy(versionCode = 32, signers = emptySet()),
            installed,
            installed.copy(versionCode = 30),
        )
        refused.forEach { candidate ->
            val error = assertThrows(IllegalStateException::class.java) { checkUpgrade(installed, candidate) }
            assertTrue(error.message.orEmpty().contains("已拒绝安装"))
        }
    }

    @Test fun emptyInstalledSignersNeverMatch() {
        val unsigned = installed.copy(signers = emptySet())
        assertThrows(IllegalStateException::class.java) { checkUpgrade(unsigned, unsigned.copy(versionCode = 32)) }
    }
}
