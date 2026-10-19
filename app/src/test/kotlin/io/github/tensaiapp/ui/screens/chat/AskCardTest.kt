package io.github.tensaiapp.ui.screens.chat

import org.junit.Assert.assertEquals
import org.junit.Test

/** What "Submit" and the send arrow of the question card answer (TEN-187). */
class AskCardTest {

    private val options = listOf("Kubernetes", "Terraform", "Ansible")

    @Test
    fun multipleKeepsTheCheckedOptionsInTheirOrderThenTheText() {
        assertEquals(
            listOf("Terraform", "Ansible", "Pulumi"),
            composeAskAnswers(options, checked = setOf(2, 1), typed = "  Pulumi ", multiple = true),
        )
    }

    @Test
    fun multipleWithOnlyCheckedOrOnlyTextIsEnough() {
        assertEquals(listOf("Kubernetes"), composeAskAnswers(options, setOf(0), "", multiple = true))
        assertEquals(listOf("Pulumi"), composeAskAnswers(options, emptySet(), "Pulumi", multiple = true))
    }

    @Test
    fun singleAnswersWithTheTypedTextAloneWhateverWasChecked() {
        assertEquals(listOf("my own"), composeAskAnswers(options, setOf(1), "my own", multiple = false))
    }

    @Test
    fun typedTextEqualToACheckedOptionIsAnsweredOnce() {
        assertEquals(listOf("Ansible"), composeAskAnswers(options, setOf(2), "Ansible", multiple = true))
    }

    @Test
    fun nothingCheckedAndBlankTextIsNothingToSubmit() {
        assertEquals(emptyList<String>(), composeAskAnswers(options, emptySet(), "   ", multiple = true))
        assertEquals(emptyList<String>(), composeAskAnswers(options, emptySet(), "", multiple = false))
    }
}
