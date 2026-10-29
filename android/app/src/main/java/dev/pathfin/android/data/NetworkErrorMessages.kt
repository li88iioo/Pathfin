package dev.pathfin.android.data

import dev.pathfin.android.i18n.tr
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLPeerUnverifiedException

/** 只输出固定诊断，不显示底层异常中可能包含的地址、密码或签名参数。 */
internal object NetworkErrorMessages {
    fun connection(error: IOException): String {
        val causes = generateSequence<Throwable>(error) { it.cause }.take(12).toList()
        if (causes.any { it.message.orEmpty().contains("EACCES", true) || it.message.orEmpty().contains("EPERM", true) || it.message.orEmpty().contains("Permission denied", true) })
            return tr("连接被系统权限阻止，请检查 Pathfin 的局域网访问权限。")
        return when {
            causes.any { it is UnknownHostException } -> tr("无法解析服务器地址，请检查 IP 或域名。")
            causes.any { it is SSLPeerUnverifiedException } -> tr("服务器证书校验失败，请检查域名和证书配置。")
            causes.any { it is SSLException } -> tr("HTTPS 连接失败，请确认服务器启用了 HTTPS，并核对协议和端口。")
            causes.any { it is SocketTimeoutException } -> tr("连接服务器超时，请确认手机与服务器网络互通。")
            causes.any { it is ConnectException } -> tr("无法连接此地址和端口，请确认服务已启动且手机能够访问。")
            else -> tr("无法连接服务器，请检查地址和网络。")
        }
    }
}
