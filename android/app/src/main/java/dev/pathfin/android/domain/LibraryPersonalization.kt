package dev.pathfin.android.domain

import java.util.Locale

/** 固定的成人内容分级标记；未知分级保持可见，不从片名或任意数字子串推断。 */
object AdultContentRatings {
    // 只认专用于成人内容的分级。NC-17、TV-MA、18、19、R18+、FSK 18、限制级等是普通年龄分级，
    // 大量犯罪、惊悚剧集与电影都用，模糊它们会误伤正常内容。
    private val adultRatings = setOf("X", "XXX", "AO", "X18", "X18+", "X-18", "X-18+", "成人", "18禁")
    // 成人片刮削器写入的日本分级；日本官方电影分级是 JP-R18+，用于普通电影，不在此列。
    private val scraperAdultRatings = setOf("JP-18+", "JP:18+")
    private val regions = setOf("US", "GB", "UK", "DE", "AU", "CA", "FR", "ES", "IT", "BR", "NL",
        "NZ", "SE", "NO", "FI", "DK", "KR", "JP", "HK", "TW", "CN", "IN", "RU", "PL", "MX")

    fun matches(officialRating: String?): Boolean {
        val raw = officialRating?.trim()?.takeIf { it.isNotEmpty() && it.length <= 64 } ?: return false
        val rating = raw.uppercase(Locale.ROOT).filterNot(Char::isWhitespace).replace('–', '-')
        if (rating in adultRatings || rating in scraperAdultRatings) return true
        val separator = rating.indexOfFirst { it == ':' || it == '-' }
        if (separator <= 0 || rating.substring(0, separator) !in regions) return false
        return rating.substring(separator + 1) in adultRatings
    }
}
