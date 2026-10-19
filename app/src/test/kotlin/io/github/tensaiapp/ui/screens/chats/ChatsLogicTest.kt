package io.github.tensaiapp.ui.screens.chats

import io.github.tensaiapp.data.chat.ConversationEntity
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** JVM tests for the Chats page's pure logic (TEN-165). */
class ChatsLogicTest {

    private val zone = ZoneId.of("Europe/Paris")

    // Thursday 2026-10-15, 10:00 local.
    private val now: Instant = at(2026, 10, 15, 10, 0)

    private fun at(y: Int, m: Int, d: Int, h: Int = 12, min: Int = 0): Instant =
        LocalDateTime.of(y, m, d, h, min).atZone(zone).toInstant()

    private fun chat(id: String, updated: Instant, title: String = id, modelId: String? = null) =
        ConversationEntity(id, title, modelId, null, null, false, updated.toEpochMilli(), updated.toEpochMilli())

    private fun groupOf(updated: Instant): ChatGroup = groupChats(listOf(chat("a", updated)), now, zone).single().group

    @Test
    fun midnightSplitsTodayFromYesterday() {
        assertEquals(ChatGroup.Today, groupOf(at(2026, 10, 15, 0, 0)))
        assertEquals(ChatGroup.Yesterday, groupOf(at(2026, 10, 14, 23, 59)))
    }

    @Test
    fun yesterdayIsTheCalendarDayNotTwentyFourHours() {
        // 23h before "now" is still yesterday's date.
        assertEquals(ChatGroup.Yesterday, groupOf(now.minusSeconds(23 * 3600)))
    }

    @Test
    fun futureTimestampCountsAsToday() {
        assertEquals(ChatGroup.Today, groupOf(at(2026, 10, 16, 8, 0)))
    }

    @Test
    fun previousSevenDaysCoversTwoToSevenDaysBack() {
        assertEquals(ChatGroup.Previous7Days, groupOf(at(2026, 10, 13)))
        assertEquals(ChatGroup.Previous7Days, groupOf(at(2026, 10, 8)))
    }

    @Test
    fun eightDaysBackIsThisMonth() {
        assertEquals(ChatGroup.ThisMonth, groupOf(at(2026, 10, 7)))
        assertEquals(ChatGroup.ThisMonth, groupOf(at(2026, 10, 1, 0, 0)))
    }

    @Test
    fun monthStartBoundaryWhenTodayIsEarlyInTheMonth() {
        val early = at(2026, 10, 3, 9, 0)
        // 3 Oct: 2 Oct is yesterday, 26 Sep is 7 days back (previous 7 days, though last month).
        assertEquals(ChatGroup.Previous7Days, chatGroupOf(LocalDate.of(2026, 9, 26), early.atZone(zone).toLocalDate()))
        assertEquals(ChatGroup.Month(YearMonth.of(2026, 9)), chatGroupOf(LocalDate.of(2026, 9, 25), early.atZone(zone).toLocalDate()))
    }

    @Test
    fun previousMonthGetsItsOwnGroup() {
        assertEquals(ChatGroup.Month(YearMonth.of(2026, 9)), groupOf(at(2026, 9, 30)))
    }

    @Test
    fun previousYearGetsItsOwnGroup() {
        assertEquals(ChatGroup.Month(YearMonth.of(2025, 10)), groupOf(at(2025, 10, 15)))
    }

    @Test
    fun localZoneDecidesTheDay() {
        // 23:30 UTC on the 14th is already the 15th in Paris (UTC+2).
        val utc = LocalDateTime.of(2026, 10, 14, 23, 30).atZone(ZoneId.of("UTC")).toInstant()
        assertEquals(ChatGroup.Today, groupOf(utc))
        assertEquals(
            ChatGroup.Yesterday,
            groupChats(listOf(chat("a", utc)), now, ZoneId.of("UTC")).single().group,
        )
    }

    @Test
    fun sectionsKeepOrderAndSkipEmptyBuckets() {
        val chats = listOf(
            chat("t", at(2026, 10, 15, 9)),
            chat("y", at(2026, 10, 14)),
            chat("sep1", at(2026, 9, 20)),
            chat("sep2", at(2026, 9, 2)),
            chat("old", at(2025, 3, 1)),
        )
        val sections = groupChats(chats, now, zone)
        assertEquals(
            listOf(
                ChatGroup.Today,
                ChatGroup.Yesterday,
                ChatGroup.Month(YearMonth.of(2026, 9)),
                ChatGroup.Month(YearMonth.of(2025, 3)),
            ),
            sections.map { it.group },
        )
        assertEquals(listOf("sep1", "sep2"), sections[2].chats.map { it.id })
    }

    @Test
    fun whenStyleByAge() {
        assertEquals(WhenStyle.Time, whenStyle(at(2026, 10, 15, 0, 1).toEpochMilli(), now, zone))
        assertEquals(WhenStyle.Weekday, whenStyle(at(2026, 10, 14).toEpochMilli(), now, zone))
        assertEquals(WhenStyle.Weekday, whenStyle(at(2026, 10, 9).toEpochMilli(), now, zone))
        assertEquals(WhenStyle.DayMonth, whenStyle(at(2026, 10, 8).toEpochMilli(), now, zone))
        assertEquals(WhenStyle.DayMonth, whenStyle(at(2025, 1, 8).toEpochMilli(), now, zone))
    }

    @Test
    fun filterIsCaseInsensitiveAndTrimmed() {
        val chats = listOf(chat("1", now, "Parse a GGUF header"), chat("2", now, "Recette de crepes"))
        assertEquals(listOf("1"), filterChats(chats, "  gguf ", "New chat").map { it.id })
        assertEquals(listOf("2"), filterChats(chats, "CREPES", "New chat").map { it.id })
        assertTrue(filterChats(chats, "zzz", "New chat").isEmpty())
    }

    @Test
    fun blankQueryKeepsEverything() {
        val chats = listOf(chat("1", now, "a"), chat("2", now, "b"))
        assertEquals(chats, filterChats(chats, "   ", "New chat"))
    }

    @Test
    fun untitledChatMatchesItsDisplayedLabel() {
        val chats = listOf(chat("1", now, ""), chat("2", now, "other"))
        assertEquals(listOf("1"), filterChats(chats, "new", "New chat").map { it.id })
    }

    @Test
    fun selectAllThenNone() {
        val ids = listOf("a", "b", "c")
        val all = toggleSelectAll(setOf("a"), ids)
        assertEquals(setOf("a", "b", "c"), all)
        assertTrue(allSelected(all, ids))
        assertEquals(emptySet<String>(), toggleSelectAll(all, ids))
    }

    @Test
    fun selectAllWithNothingVisibleIsNotAllSelected() {
        assertFalse(allSelected(emptySet(), emptyList()))
        assertEquals(emptySet<String>(), toggleSelectAll(emptySet(), emptyList()))
    }

    @Test
    fun toggleSelectedAddsAndRemoves() {
        assertEquals(setOf("a"), toggleSelected(emptySet(), "a"))
        assertEquals(emptySet<String>(), toggleSelected(setOf("a"), "a"))
    }

    @Test
    fun modelLabelPrefersLibraryNameThenStoredIdThenNothing() {
        val names = mapOf("m1" to "Gemma 3 4B")
        assertEquals("Gemma 3 4B", modelLabel("m1", names))
        assertEquals("gone", modelLabel("gone", names))
        assertNull(modelLabel(null, names))
    }
}
