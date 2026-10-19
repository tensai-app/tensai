package io.github.tensaiapp.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The stack rules of TEN-168, as [NavOp] values run through [applyNavOp]: a stack is the list of
 * routes, oldest first, the first one being the root conversation.
 */
class NavigationRulesTest {

    private val draft = "chat/new"
    private val models = TensaiDestination.Models.route
    private val chats = TensaiDestination.Chats.route

    private fun conversation(id: String) = "chat/$id"

    @Test
    fun drawerConversationReplacesTheRootInsteadOfStacking() {
        val op = openConversationOp(openConversationId = "a", targetId = "b")
        assertEquals(listOf(conversation("b")), applyNavOp(listOf(conversation("a")), op))
    }

    @Test
    fun drawerNewChatReplacesTheRoot() {
        val op = openConversationOp(openConversationId = "a", targetId = TensaiDestination.Chat.NEW_CONVERSATION_ID)
        assertEquals(listOf(draft), applyNavOp(listOf(conversation("a")), op))
    }

    @Test
    fun retappingTheOpenConversationDoesNothing() {
        val stack = listOf(conversation("a"))
        assertEquals(NavOp.None, openConversationOp(openConversationId = "a", targetId = "a"))
        assertEquals(stack, applyNavOp(stack, openConversationOp("a", "a")))
    }

    @Test
    fun newChatOnAnEmptyDraftDoesNothing() {
        val new = TensaiDestination.Chat.NEW_CONVERSATION_ID
        assertEquals(NavOp.None, openConversationOp(openConversationId = new, targetId = new))
    }

    @Test
    fun drawerSecondaryScreenPushesAndBackReturnsToTheConversation() {
        val root = listOf(conversation("a"))
        val onModels = applyNavOp(root, openSecondaryOp(models))
        assertEquals(listOf(conversation("a"), models), onModels)
        assertEquals(root, applyNavOp(onModels, NavOp.Pop))
    }

    @Test
    fun secondaryScreensStackAndBackPopsOneAtATime() {
        var stack = listOf(conversation("a"))
        stack = applyNavOp(stack, openSecondaryOp(models))
        stack = applyNavOp(stack, NavOp.Push("model/m1"))
        stack = applyNavOp(stack, NavOp.Push("repo?id=x"))
        assertEquals(listOf(conversation("a"), models, "model/m1", "repo?id=x"), stack)
        assertEquals(listOf(conversation("a"), models, "model/m1"), applyNavOp(stack, NavOp.Pop))
    }

    @Test
    fun pushingTheScreenAlreadyOnTopDoesNotDuplicateIt() {
        val stack = listOf(conversation("a"), models)
        assertEquals(stack, applyNavOp(stack, openSecondaryOp(models)))
    }

    @Test
    fun loadFromModelsLandsOnAFreshDraftWithNothingUnderneath() {
        val stack = listOf(conversation("a"), models, "model/m1", "loading/m1")
        assertEquals(listOf(draft), applyNavOp(stack, loadFinishedOp(returnToChat = false)))
    }

    @Test
    fun loadFromTheNotificationReplacesTheRootToo() {
        val stack = listOf(conversation("a"), "loading/m1")
        assertEquals(listOf(draft), applyNavOp(stack, loadFinishedOp(returnToChat = false)))
    }

    @Test
    fun loadFromTheOnboardingLandsOnTheDraftAlone() {
        val stack = listOf(draft, "loading/m1")
        assertEquals(listOf(draft), applyNavOp(stack, loadFinishedOp(returnToChat = false)))
    }

    @Test
    fun loadFromTheChatPickerGoesBackToThatConversation() {
        val stack = listOf(conversation("a"), "loading/m1?returnToChat=true")
        assertEquals(listOf(conversation("a")), applyNavOp(stack, loadFinishedOp(returnToChat = true)))
    }

    @Test
    fun cancellingALoadIsASimplePop() {
        val stack = listOf(conversation("a"), models, "loading/m1")
        assertEquals(listOf(conversation("a"), models), applyNavOp(stack, NavOp.Pop))
    }

    @Test
    fun conversationOpenedFromChatsStacksOnTopOfTheList() {
        var stack = listOf(conversation("a"))
        stack = applyNavOp(stack, openSecondaryOp(chats))
        stack = applyNavOp(stack, NavOp.Push(conversation("b")))
        assertEquals(listOf(conversation("a"), chats, conversation("b")), stack)
        assertEquals(listOf(conversation("a"), chats), applyNavOp(stack, NavOp.Pop))
    }

    @Test
    fun chatsToTheRootConversationPopsBackInsteadOfDuplicatingIt() {
        val stack = listOf(conversation("a"), chats)
        assertEquals(listOf(conversation("a")), applyNavOp(stack, openFromChatsOp("a", "a")))
    }

    @Test
    fun chatsToAnotherConversationPushesOnTop() {
        val stack = listOf(conversation("a"), chats)
        assertEquals(stack + conversation("b"), applyNavOp(stack, openFromChatsOp("a", "b")))
        assertEquals(stack + conversation("b"), applyNavOp(stack, openFromChatsOp(TensaiDestination.Chat.NEW_CONVERSATION_ID, "b")))
    }

    @Test
    fun drawerFromAConversationOpenedViaChatsCollapsesToTheNewRootAlone() {
        val stack = listOf(conversation("a"), chats, conversation("b"))
        assertEquals(
            listOf(conversation("c")),
            applyNavOp(stack, openConversationOp(openConversationId = "b", targetId = "c")),
        )
        assertEquals(
            listOf(draft),
            applyNavOp(stack, openConversationOp(openConversationId = "b", targetId = TensaiDestination.Chat.NEW_CONVERSATION_ID)),
        )
    }
}

/** [op] applied to [stack] (oldest first, routes with their arguments filled in): the expected result. */
private fun applyNavOp(stack: List<String>, op: NavOp): List<String> = when (op) {
    NavOp.None -> stack
    is NavOp.Push -> if (stack.lastOrNull() == op.route) stack else stack + op.route
    NavOp.Pop -> stack.dropLast(1)
    is NavOp.ReplaceRoot -> listOf(op.route)
}
