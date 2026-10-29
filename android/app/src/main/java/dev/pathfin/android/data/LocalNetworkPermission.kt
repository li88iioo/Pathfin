package dev.pathfin.android.data

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import dev.pathfin.android.domain.LocalNetworkDestination
import dev.pathfin.android.i18n.tr
import java.net.InetAddress
import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object LocalNetworkPermission {
    const val PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"
    val DENIED_MESSAGE: String get() = tr("未允许局域网访问权限。请在系统应用权限中允许 Pathfin 访问附近设备/局域网，然后重新连接。")

    fun isGranted(context: Context): Boolean = Build.VERSION.SDK_INT < 37 ||
        ContextCompat.checkSelfPermission(context, PERMISSION) == PackageManager.PERMISSION_GRANTED

    suspend fun isRequired(url: String): Boolean {
        if (Build.VERSION.SDK_INT < 37) return false
        val host = runCatching { URI(url).host }.getOrNull() ?: return false
        if (LocalNetworkDestination.isLocalHost(host)) return true
        if (host.equals("localhost", true) || host.endsWith(".localhost", true)) return false
        // 兼容指向私网的自有域名，DNS失败交给原有网络错误处理，不猜测成认证失败。
        return withContext(Dispatchers.IO) {
            runCatching { InetAddress.getAllByName(host).any(LocalNetworkDestination::isLocalAddress) }.getOrDefault(false)
        }
    }
}

class LocalNetworkPermissionDenied : IllegalStateException(LocalNetworkPermission.DENIED_MESSAGE)
