package io.github.hokanosekai.tensai.ui.components

import io.github.hokanosekai.tensai.ui.screens.chat.MessageStatsUi
import io.github.hokanosekai.tensai.ui.screens.chat.TruncationReason
import org.junit.Assert.assertEquals
import org.junit.Test

class StatsFiguresTest {
    private fun ui(tps: Float) = MessageStatsUi(tps, 232, 92_567L, TruncationReason.None)

    @Test
    fun `shows tok per sec when measured`() {
        assertEquals("5.0 tok/s · 232 tok · TTFT 92567ms", statsFigures(ui(5.04f)))
    }

    @Test
    fun `omits tok per sec when zero`() {
        assertEquals("232 tok · TTFT 92567ms", statsFigures(ui(0f)))
    }
}
