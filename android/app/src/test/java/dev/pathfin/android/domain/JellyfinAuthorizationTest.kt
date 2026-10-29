package dev.pathfin.android.domain

import java.net.URLDecoder
import org.junit.Assert.*
import org.junit.Test

class JellyfinAuthorizationTest {
    @Test fun standardHeaderContainsIdentityAndOptionalToken() {
        val identity = "MediaBrowser Client=\"Pathfin\", Device=\"Android\", DeviceId=\"device\", Version=\"0.1.0\""
        assertEquals(mapOf("Authorization" to identity), JellyfinAuthorization.headers("device"))
        assertEquals(mapOf("Authorization" to "$identity, Token=\"test-token\""), JellyfinAuthorization.headers("device", "test-token"))
    }

    @Test fun tokenCannotInjectAuthorizationParameters() {
        val token = "opaque\",Token=\"other+/%token"
        val value = JellyfinAuthorization.headers("device", token).getValue("Authorization")
        val encoded = value.substringAfter(", Token=\"").removeSuffix("\"")
        assertEquals(token, URLDecoder.decode(encoded, "UTF-8"))
        assertFalse(encoded.contains('"'))
        assertFalse(encoded.contains(','))
    }

    @Test fun invalidIdentityAndHeaderControlsAreRejected() {
        for (device in listOf("", "device\r\nInjected: true", "a\"", "设备", "a".repeat(129))) {
            assertThrows(IllegalArgumentException::class.java) { JellyfinAuthorization.headers(device) }
        }
        for (token in listOf("", " ", "token\r\nInjected: true", "token\tvalue", "秘密")) {
            assertThrows(IllegalArgumentException::class.java) { JellyfinAuthorization.headers("device", token) }
        }
    }
}
