package dev.pathfin.ass

/** Public Matroska SSA payload contract; no Media3 private fields or fixed renderer offsets. */
internal data class AssPacket(val data: ByteArray, val startMs: Long, val durationMs: Long, val readOrder: Long) {
    companion object {
        fun parse(sample: ByteArray, timeUs: Long): AssPacket? {
            if (sample.size !in 1..1024 * 1024 || timeUs < 0) return null
            val first = sample.indexOf(','.code.toByte())
            if (first < 0 || first > 64) return null
            val second = (first + 1 until minOf(sample.size, first + 33))
                .firstOrNull { sample[it] == ','.code.toByte() } ?: return null
            val duration = String(sample, first + 1, second - first - 1, Charsets.US_ASCII)
                .split(':', '.')
            if (duration.size != 4) return null
            val parts = duration.map { it.toLongOrNull() ?: return null }
            if (parts.any { it < 0 } || parts[1] > 59 || parts[2] > 59 || parts[3] > 99 || parts[0] > 23) return null
            val durationMs = parts[0] * 3_600_000 + parts[1] * 60_000 + parts[2] * 1000 + parts[3] * 10
            if (durationMs <= 0 || second + 1 == sample.size) return null
            val data = sample.copyOfRange(second + 1, sample.size)
            val orderEnd = data.indexOf(','.code.toByte())
            if (orderEnd !in 1..20) return null
            val readOrder = String(data, 0, orderEnd, Charsets.US_ASCII).toLongOrNull()?.takeIf { it >= 0 } ?: return null
            return AssPacket(data, timeUs / 1000, durationMs, readOrder)
        }
        fun safeFontName(name: String): Boolean = name.length in 1..160 && !name.startsWith('.') &&
            !name.contains("..") && name.none { it == '/' || it == '\\' || it.code < 32 || it.code == 127 } &&
            name.substringAfterLast('.', "").lowercase() in setOf("ttf", "ttc", "otf", "otc")
    }
}
