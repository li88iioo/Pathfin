package dev.pathfin.android.data

import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException
import org.junit.Assert.*
import org.junit.Test

class NetworkErrorMessagesTest {
    @Test fun deniedAccessIsNotReportedAsWrongCredentials() {
        val message = NetworkErrorMessages.connection(ConnectException("secret-host EACCES (Permission denied) signed-secret"))
        assertTrue(message.contains("局域网访问权限"))
        assertFalse(message.contains("secret"))
        assertFalse(message.contains("账号或密码"))
    }
    @Test fun networkFailuresRemainDistinctAndRedacted() {
        assertTrue(NetworkErrorMessages.connection(UnknownHostException("secret-token")).contains("解析"))
        assertTrue(NetworkErrorMessages.connection(SSLHandshakeException("secret-token")).contains("HTTPS"))
        assertTrue(NetworkErrorMessages.connection(SocketTimeoutException("secret-token")).contains("超时"))
        assertTrue(NetworkErrorMessages.connection(ConnectException("secret-token")).contains("端口"))
        assertFalse(NetworkErrorMessages.connection(IOException("signed-secret")).contains("secret"))
    }
}
