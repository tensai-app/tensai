package io.github.tensaiapp.ui.screens.chats

import io.github.tensaiapp.data.chat.ConversationEntity
import io.github.tensaiapp.ui.navigation.conversationLabel
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** A date bucket of the Chats list, in the order they are shown (TEN-165). */
sealed interface ChatGroup {
    data object Today : ChatGroup
    data object Yesterday : ChatGroup
    data object Previous7Days : ChatGroup
    data object ThisMonth : ChatGroup
    data class Month(val month: YearMonth) : ChatGroup
}

data class ChatSection(val group: ChatGroup, val chats: List<ConversationEntity>)

/** How a row's "when" is written, see [whenStyle]. */
enum class WhenStyle { Time, Weekday, DayMonth }

private fun ConversationEntity.localDate(zone: ZoneId): LocalDate =
    Instant.ofEpochMilli(updatedAt).atZone(zone).toLocalDate()

private fun daysAgo(date: LocalDate, today: LocalDate): Long = ChronoUnit.DAYS.between(date, today)

/**
 * Which bucket a conversation last touched on [date] falls in, as seen on [today] (local calendar
 * days, not 24h windows). "Previous 7 days" is 2 to 7 days back, "This month" what is left of the
 * current calendar month, then one bucket per month. A date in the future (clock change) is Today.
 */
internal fun chatGroupOf(date: LocalDate, today: LocalDate): ChatGroup {
    val ago = daysAgo(date, today)
    return when {
        ago <= 0 -> ChatGroup.Today
        ago == 1L -> ChatGroup.Yesterday
        ago <= 7 -> ChatGroup.Previous7Days
        YearMonth.from(date) == YearMonth.from(today) -> ChatGroup.ThisMonth
        else -> ChatGroup.Month(YearMonth.from(date))
    }
}

/**
 * [chats] split into date sections, in [zone]'s calendar relative to [now]. Order inside a section
 * and between months follows [chats] (newest first, as the DAO returns them); empty buckets are
 * left out.
 */
fun groupChats(chats: List<ConversationEntity>, now: Instant, zone: ZoneId): List<ChatSection> {
    val today = now.atZone(zone).toLocalDate()
    val sections = LinkedHashMap<ChatGroup, MutableList<ConversationEntity>>()
    chats.forEach { sections.getOrPut(chatGroupOf(it.localDate(zone), today)) { mutableListOf() }.add(it) }
    return sections.map { (group, list) -> ChatSection(group, list) }
}

/**
 * The format of a row's "when": the time for today, the short weekday for the last 6 days (the 7th
 * would repeat today's weekday), day and short month beyond.
 */
fun whenStyle(updatedAt: Long, now: Instant, zone: ZoneId): WhenStyle {
    val today = now.atZone(zone).toLocalDate()
    val ago = daysAgo(Instant.ofEpochMilli(updatedAt).atZone(zone).toLocalDate(), today)
    return when {
        ago <= 0 -> WhenStyle.Time
        ago < 7 -> WhenStyle.Weekday
        else -> WhenStyle.DayMonth
    }
}

/**
 * Chats whose displayed title (blank titles show [untitledLabel], like the drawer) contains [query],
 * ignoring case and surrounding spaces. A blank query keeps everything.
 */
fun filterChats(chats: List<ConversationEntity>, query: String, untitledLabel: String): List<ConversationEntity> {
    val needle = query.trim()
    if (needle.isEmpty()) return chats
    return chats.filter { conversationLabel(it.title, untitledLabel).contains(needle, ignoreCase = true) }
}

/** "Select all" when something visible is unselected, "Select none" once every visible chat is. */
fun allSelected(selected: Set<String>, visibleIds: List<String>): Boolean =
    visibleIds.isNotEmpty() && selected.containsAll(visibleIds)

/** What the select-all action does: clears when [allSelected], else selects every visible chat. */
fun toggleSelectAll(selected: Set<String>, visibleIds: List<String>): Set<String> =
    if (allSelected(selected, visibleIds)) emptySet() else selected + visibleIds

/** Adds [id] to [selected], or removes it when already there. */
fun toggleSelected(selected: Set<String>, id: String): Set<String> =
    if (id in selected) selected - id else selected + id

/**
 * The model line of a row: the library's name for [modelId], else the stored id, null for a
 * conversation started with no model (no line then).
 */
fun modelLabel(modelId: String?, namesById: Map<String, String>): String? =
    modelId?.let { namesById[it] ?: it }
