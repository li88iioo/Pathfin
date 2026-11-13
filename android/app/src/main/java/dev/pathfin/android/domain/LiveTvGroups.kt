package dev.pathfin.android.domain

/**
 * M3U 的 group-title。Jellyfin 解析后不写入频道条目，这里按服务器同样的命名规则（M3uParser.GetChannelName）
 * 把分组对到频道名上；同名频道取第一次出现的分组。entries 保持播放列表顺序，分组与组内频道都按它排列。
 */
data class LiveTvGroups(val entries: List<Pair<String, String>> = emptyList()) {
    val byName: Map<String, String> by lazy { entries.toMap() }
    val order: List<String> by lazy { entries.map { it.second }.distinct() }
    private val rank: Map<String, Int> by lazy { entries.withIndex().associate { (index, entry) -> entry.first to index } }

    fun of(channel: LiveTvChannel): String? = byName[channel.name]

    /** 按分组切成连续分区：分组按播放列表顺序，组内按播放列表顺序；对不上分组的频道收在最后。没有分组时整体一个分区。 */
    fun sections(channels: List<LiveTvChannel>): List<LiveTvSection> {
        if (entries.isEmpty()) return if (channels.isEmpty()) emptyList() else listOf(LiveTvSection(null, channels))
        val grouped = channels.groupBy(::of)
        return order.mapNotNull { group -> grouped[group]?.let { LiveTvSection(group, it.sortedBy { channel -> rank[channel.name] }) } } +
            listOfNotNull(grouped[null]?.let { LiveTvSection(null, it) })
    }

    companion object {
        private const val EXTINF = "#EXTINF:"
        private val ATTRIBUTE = Regex("([a-z0-9\\-_]+)=\"([^\"]+)\"", RegexOption.IGNORE_CASE)

        fun parse(playlist: String): LiveTvGroups {
            val entries = LinkedHashMap<String, String>()
            playlist.lineSequence().map(String::trim).filter { it.startsWith(EXTINF, ignoreCase = true) }.forEach { line ->
                var remaining = line.substring(EXTINF.length).trim()
                val attributes = HashMap<String, String>()
                ATTRIBUTE.findAll(remaining).forEach { match ->
                    val (key, value) = match.destructured
                    attributes[key.lowercase()] = value
                    remaining = remaining.replace("$key=\"$value\"", "", ignoreCase = true)
                }
                val group = attributes["group-title"]?.trim()?.takeIf(String::isNotEmpty) ?: return@forEach
                channelName(remaining, attributes)?.let { entries.putIfAbsent(it, group) }
            }
            return LiveTvGroups(entries.toList())
        }

        private fun channelName(remaining: String, attributes: Map<String, String>): String? {
            val parts = remaining.split(',').filter(String::isNotEmpty)
            var name = if (parts.size > 1) parts.last().trim() else null
            // SatIp 格式“84. VOX”“84.0 - VOX”：服务器去掉前面的数字台号。
            name?.indexOf(' ')?.takeIf { it > 0 }?.let { index ->
                if (name.substring(0, index).trim(' ', '.').toDoubleOrNull() != null)
                    name = name.substring(index + 1).trim(' ', '-')
            }
            return name?.takeIf(String::isNotBlank)
                ?: attributes["tvg-name"]?.takeIf(String::isNotBlank)
                ?: attributes["tvg-id"]?.takeIf(String::isNotBlank)
        }
    }
}

/** 频道列表的一段；group 为 null 表示未分组（或其余频道）。 */
data class LiveTvSection(val group: String?, val channels: List<LiveTvChannel>)
