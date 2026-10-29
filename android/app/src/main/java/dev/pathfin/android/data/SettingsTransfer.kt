package dev.pathfin.android.data

import android.content.Context
import android.net.Uri
import dev.pathfin.android.domain.AppPreferences
import dev.pathfin.android.i18n.tr
import dev.pathfin.android.ui.theme.ThemePreference
import org.json.JSONObject

internal data class ImportedSettings(val preferences: AppPreferences, val theme: ThemePreference)

/** 设置格式和大小边界；SAF 的读取、写入与取消由唯一公共核心负责。 */
internal object SettingsTransfer {
    private const val MAX_BYTES = 65_536
    private const val FORMAT = "pathfin-settings-v1"

    suspend fun read(context: Context, uri: Uri): ImportedSettings = SafDocumentTransfer.transfer { operation ->
        require(uri.scheme == "content") { tr("请通过系统文件选择器选择设置备份") }
        val bytes = operation.readBytes(context, uri, MAX_BYTES, tr("设置备份不能超过 64 KiB"))
        val json = JSONObject(bytes.toString(Charsets.UTF_8))
        require(json.getString("format") == FORMAT) { tr("不是有效的 Pathfin 设置备份") }
        ImportedSettings(AppPreferencesStore.decode(json.getJSONObject("settings")), ThemePreference.valueOf(json.getString("theme")))
    }

    suspend fun write(context: Context, uri: Uri, preferences: AppPreferences, theme: ThemePreference) {
        SafDocumentTransfer.transfer { operation ->
            require(uri.scheme == "content") { tr("请通过系统文件选择器选择导出位置") }
            val bytes = JSONObject().put("format", FORMAT).put("theme", theme.name)
                .put("settings", AppPreferencesStore.encode(preferences)).toString(2).toByteArray(Charsets.UTF_8)
            require(bytes.size <= MAX_BYTES) { tr("设置备份不能超过 64 KiB") }
            operation.writeBytes(context, uri, bytes)
        }
    }
}
