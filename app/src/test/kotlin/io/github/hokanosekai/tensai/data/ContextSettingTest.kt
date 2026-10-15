package io.github.hokanosekai.tensai.data

import io.github.hokanosekai.tensai.data.models.ModelEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** What [ContextSetting] offers a model and how it round-trips through the name that is persisted (TEN-150). */
class ContextSettingTest {

    @Test
    fun aLongContextModelIsOfferedEveryChoice() {
        assertEquals(
            listOf(
                ContextSetting.Auto,
                ContextSetting.K4,
                ContextSetting.K8,
                ContextSetting.K16,
                ContextSetting.K32,
                ContextSetting.Max,
            ),
            ContextSetting.offeredFor(131_072),
        )
    }

    @Test
    fun aFixedSizeAtOrAboveTheTrainedContextIsNotOffered() {
        // 32 768 would be Max under another name, 65 536 is a context the model was never trained for.
        assertEquals(
            listOf(ContextSetting.Auto, ContextSetting.K4, ContextSetting.K8, ContextSetting.K16, ContextSetting.Max),
            ContextSetting.offeredFor(32_768),
        )
        assertEquals(
            listOf(ContextSetting.Auto, ContextSetting.K4, ContextSetting.Max),
            ContextSetting.offeredFor(8_192),
        )
    }

    @Test
    fun aShortContextModelIsOfferedOnlyAutoAndMax() {
        assertEquals(listOf(ContextSetting.Auto, ContextSetting.Max), ContextSetting.offeredFor(2_048))
        assertEquals(listOf(ContextSetting.Auto, ContextSetting.Max), ContextSetting.offeredFor(4_096))
    }

    @Test
    fun everySettingRoundTripsThroughItsName() {
        ContextSetting.entries.forEach { assertEquals(it, ContextSetting.parse(it.name)) }
    }

    @Test
    fun anAbsentOrUnknownNameParsesToNull() {
        assertNull(ContextSetting.parse(null))
        assertNull(ContextSetting.parse("K64"))
    }

    @Test
    fun aModelRowExposesItsOverrideAsASetting() {
        assertNull(entity(contextOverride = null).contextSettingOverride)
        assertEquals(ContextSetting.K8, entity(contextOverride = "K8").contextSettingOverride)
        assertNull("an unknown name is no override", entity(contextOverride = "K64").contextSettingOverride)
    }

    private fun entity(contextOverride: String?) = ModelEntity(
        id = "m",
        displayName = "m",
        fileName = "m.gguf",
        filePath = "/models/m.gguf",
        sizeBytes = 1L,
        architecture = "llama",
        quantLabel = "Q4_K_M",
        paramCount = 1L,
        contextLength = 1L,
        embeddingLength = 1L,
        blockCount = 1L,
        supportsThinking = false,
        importedAt = 0L,
        contextOverride = contextOverride,
    )
}
