package dev.pathfin.android.domain

import java.util.Locale

/** 固定的成人/18+标记；未知分级保持可见，不从片名或任意数字子串推断。 */
object AdultContentRatings {
    private val adultRatings = setOf("18", "18+", "18A", "18R", "R18", "R18+", "R-18", "R-18+",
        "X18", "X18+", "X-18", "X-18+", "FSK18", "FSK-18", "FSKAB18", "19", "19+",
        "NC-17", "NC17", "TV-MA", "TVMA", "X", "XXX", "成人", "限制级", "18禁")
    private val regions = setOf("US", "GB", "UK", "DE", "AU", "CA", "FR", "ES", "IT", "BR", "NL",
        "NZ", "SE", "NO", "FI", "DK", "KR", "JP", "HK", "TW", "CN", "IN", "RU", "PL", "MX")

    fun matches(officialRating: String?): Boolean {
        val raw = officialRating?.trim()?.takeIf { it.isNotEmpty() && it.length <= 64 } ?: return false
        val rating = raw.uppercase(Locale.ROOT).filterNot(Char::isWhitespace).replace('–', '-')
        if (rating in adultRatings) return true
        val separator = rating.indexOfFirst { it == ':' || it == '-' }
        if (separator <= 0 || rating.substring(0, separator) !in regions) return false
        return rating.substring(separator + 1) in adultRatings
    }
}
