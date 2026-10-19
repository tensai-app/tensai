package io.github.tensaiapp.engine

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class BlankPromptGuardTest {

    @Test
    fun aBlankPromptForANonEmptyConversationIsAnError() {
        assertNotNull(BlankPromptGuard.check(messageCount = 2, prompt = ""))
        assertNotNull(BlankPromptGuard.check(messageCount = 1, prompt = " \n"))
    }

    @Test
    fun aRealPromptOrNothingToRenderIsNotAnError() {
        assertNull(BlankPromptGuard.check(messageCount = 2, prompt = "<|user|>hi"))
        assertNull(BlankPromptGuard.check(messageCount = 0, prompt = ""))
    }
}
