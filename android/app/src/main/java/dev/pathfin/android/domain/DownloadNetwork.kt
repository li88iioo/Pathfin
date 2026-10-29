package dev.pathfin.android.domain

import dev.pathfin.android.i18n.tr

/** 计费属性由 Android 判断；不计费网络可能是 Wi-Fi、以太网或运营商提供的不计费网络。 */
enum class DownloadNetwork(private val labelKey: String) {
    ANY("任意网络"),
    UNMETERED("仅不计费网络");

    val label: String get() = tr(labelKey)
}
