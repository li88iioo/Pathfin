package dev.pathfin.android.domain

import dev.pathfin.android.i18n.tr
import java.net.URLEncoder

/** Jellyfin 12 默认只接受标准 Authorization；身份与令牌必须放在同一个 Header 中。 */
object JellyfinAuthorization {
    fun headers(deviceId: String, accessToken: String? = null): Map<String, String> {
        require(deviceId.matches(Regex("[A-Za-z0-9_-]{1,128}"))) { tr("设备标识无效") }
        val identity = "MediaBrowser Client=\"Pathfin\", Device=\"Android\", DeviceId=\"$deviceId\", Version=\"0.1.0\""
        val authorization = if (accessToken == null) identity else {
            require(accessToken.isNotBlank() && accessToken.all { it.code in 0x21..0x7e }) {
                tr("登录信息无效，请重新登录")
            }
            // 与官方 SDK 一致，对参数做 URL 编码，避免引号或逗号改变认证参数边界。
            "$identity, Token=\"${URLEncoder.encode(accessToken, "UTF-8")}\""
        }
        return mapOf("Authorization" to authorization)
    }
}
