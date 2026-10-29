package dev.pathfin.android.ui

import dev.pathfin.android.domain.MediaItem
import java.time.LocalDate
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PersonFactsTest {
    private val today = LocalDate.of(2026, 10, 27)
    private fun person(birth: String? = null, death: String? = null, places: List<String> = emptyList()) =
        MediaItem("p", "人物", "Person", premiereDate = birth, endDate = death, productionLocations = places)

    @Test fun livingPersonShowsBirthDateWithCurrentAgeAndBirthplace() {
        assertEquals(listOf("1976年2月25日 出生 · 50 岁", "美国 · 加利福尼亚州 · 洛杉矶"),
            personFacts(person("1976-02-25", places = listOf("美国,加利福尼亚州, 洛杉矶", "Los Angeles")), today))
    }

    @Test fun ageCountsOnlyCompletedYearsBeforeTheBirthday() {
        assertEquals(listOf("1976年10月28日 出生 · 49 岁"), personFacts(person("1976-10-28"), today))
        assertEquals(listOf("1976年10月27日 出生 · 50 岁"), personFacts(person("1976-10-27"), today))
    }

    @Test fun deceasedPersonShowsLifespanAndAgeAtDeathInsteadOfCurrentAge() {
        assertEquals(listOf("1930年8月25日 – 2020年10月31日 · 享年 90 岁"), personFacts(person("1930-08-25", "2020-10-31"), today))
        assertEquals("只有去世日期时不推算年龄", listOf("2020年10月31日 去世"), personFacts(person(death = "2020-10-31"), today))
    }

    @Test fun missingOrMalformedDatesAndBlankPlacesProduceNoFacts() {
        assertEquals(emptyList<String>(), personFacts(person(), today))
        assertEquals(emptyList<String>(), personFacts(person("unknown", places = listOf(" , ")), today))
        assertNull(serverCalendarDate("0000-01-01"))
        assertNull(serverCalendarDate("2026-02-30T00:00:00Z"))
    }

    @Test fun futureOrReversedDatesNeverInventANegativeOrFutureAge() {
        assertEquals(listOf("2030年1月2日 出生"), personFacts(person("2030-01-02"), today))
        assertEquals(listOf("1976年2月25日 – 1970年1月1日"), personFacts(person("1976-02-25", "1970-01-01"), today))
        assertEquals(listOf("1976年2月25日 – 2030年1月2日"), personFacts(person("1976-02-25", "2030-01-02"), today))
    }

    @Test fun isoCalendarDatesAndUtcSavedDatesDoNotShiftWithDeviceTimeZone() {
        val originalZone = TimeZone.getDefault()
        try {
            listOf("Pacific/Honolulu", "UTC", "Asia/Shanghai").forEach { zone ->
                TimeZone.setDefault(TimeZone.getTimeZone(zone))
                assertEquals("$zone 的保存回显日期", LocalDate.of(1976, 2, 25), serverCalendarDate("1976-02-25T00:00:00Z"))
                assertEquals("$zone 不能将元数据日历日期换日", LocalDate.of(1976, 2, 25), serverCalendarDate("1976-02-25T23:30:00-10:00"))
                assertEquals(listOf("1976年2月25日 出生 · 50 岁"), personFacts(person("1976-02-25T00:00:00Z"), today))
            }
        } finally { TimeZone.setDefault(originalZone) }
    }
}
