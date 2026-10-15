package io.github.hokanosekai.tensai.data.models

import org.junit.Assert.assertEquals
import org.junit.Test

class ModelNamesTest {

    private fun model(generalName: String, fileName: String, repo: String? = null) = ModelEntity(
        id = "m",
        displayName = generalName,
        fileName = fileName,
        filePath = "/models/m.gguf",
        sizeBytes = 1L,
        architecture = "llama",
        quantLabel = "Q4_K_M",
        paramCount = 1L,
        contextLength = 1L,
        embeddingLength = 1L,
        blockCount = 1L,
        supportsThinking = false,
        importedAt = 1L,
        sourceRepo = repo,
        sourceFile = repo?.let { fileName },
    )

    @Test
    fun aDownloadShowsTheRepoNameWithoutOwnerAndGgufSuffixWhateverTheHeaderSays() {
        val hash = "Ab00687315Bc1298E9D54E9C4B611Dde9867Ccc2"
        assertEquals("LFM2.5-2.6B", model(hash, "LFM2.5-2.6B-Q4_K_M.gguf", "LiquidAI/LFM2.5-2.6B-GGUF").shownName)
        assertEquals(
            "MiniCPM5-2B-Elffuss-Code",
            model("Fused", "MiniCPM5-2B-Elffuss-Code-Q8_0-XL.gguf", "KikoCis/MiniCPM5-2B-Elffuss-Code-GGUF").shownName,
        )
        assertEquals(
            "JiRackUltra_1b",
            model("JiRackUlrta_1", "JiRackUltra_1b.Q8_0.gguf", "CMSManhattan/JiRackUltra_1b").shownName,
        )
        assertEquals("Qwen3-0.6B", model("Qwen3 0.6B Instruct", "Qwen3-0.6B-Q8_0.gguf", "Qwen/Qwen3-0.6B-GGUF").shownName)
    }

    @Test
    fun theGgufSuffixOfARepoIsDroppedWhateverItsSeparatorOrCase() {
        assertEquals("Foo", ModelNames.of("o/Foo_GGUF", "x", "f.gguf"))
        assertEquals("Foo", ModelNames.of("o/Foo.gguf", "x", "f.gguf"))
        assertEquals("GGUF", ModelNames.of("o/GGUF", "x", "f.gguf"))
    }

    @Test
    fun anImportKeepsAUsableHeaderName() {
        val m = model("Llama 3.2 1B Instruct", "Llama-3.2-1B-Instruct-Q4_K_L.gguf")
        assertEquals("Llama 3.2 1B Instruct", m.shownName)
    }

    @Test
    fun anImportWithAHashNameFallsBackToTheFileNameWithoutItsQuant() {
        val hash = "Ab00687315Bc1298E9D54E9C4B611Dde9867Ccc2"
        assertEquals("LFM2.5-2.6B", model(hash, "LFM2.5-2.6B-Q4_K_M.gguf").shownName)
    }

    @Test
    fun placeholdersBlankAndTooShortNamesAreUnusable() {
        for (name in listOf("Fused", "model", "MERGED", "unknown", "Llama", "gguf", "", "  ", "ab")) {
            assertEquals(name, "Foo-Bar", ModelNames.of(null, name, "Foo-Bar-Q4_K_M.gguf"))
        }
    }

    @Test
    fun aPlaceholderIsOnlyRefusedAsAWholeName() {
        assertEquals("Llama 3", ModelNames.of(null, "Llama 3", "x-Q4_K_M.gguf"))
    }

    @Test
    fun aLongHexRunIsAHashButAShortOneIsAName() {
        assertEquals("Foo", ModelNames.of(null, "0123456789abcdef0123456789abcdef", "Foo.Q8_0.gguf"))
        assertEquals("cafe1234", ModelNames.of(null, "cafe1234", "Foo.Q8_0.gguf"))
    }

    @Test
    fun theFileNameFallbackKeepsAMidNameLabelAndANameWithoutOne() {
        assertEquals("Foo-Q4_K_M-imatrix", ModelNames.of(null, "", "Foo-Q4_K_M-imatrix.gguf"))
        assertEquals("Foo-Bar", ModelNames.of(null, "", "Foo-Bar.gguf"))
        assertEquals("Q4_K_M", ModelNames.of(null, "", "Q4_K_M.gguf"))
    }

    @Test
    fun uploaderTagsBeforeTheQuantAreStrippedWithIt() {
        assertEquals("Qwen3-4B", ModelNames.of(null, "", "Qwen3-4B-UD-Q4_K_XL.gguf"))
        assertEquals("Foo", ModelNames.of(null, "", "Foo-i1-Q4_K_M.gguf"))
        assertEquals("Foo", ModelNames.of(null, "", "Foo.imatrix.Q4_K_M.gguf"))
        assertEquals("Foo", ModelNames.of(null, "", "Foo_IMAT_Q4_K_M.gguf"))
        assertEquals("Foo-ud2", ModelNames.of(null, "", "Foo-ud2-Q4_K_M.gguf"))
    }

    @Test
    fun theGgufExtensionIsRemovedWhateverItsCase() {
        assertEquals("Foo", ModelNames.of(null, "", "Foo.GgUf"))
        assertEquals("Foo", ModelNames.of(null, "", "Foo-Q4_K_M.GgUf"))
    }

    @Test
    fun moreGenericNamesAndUuidsAreUnusable() {
        for (name in listOf("untitled", "GGML", "Converted", "default", "None", "test", "New Model")) {
            assertEquals(name, "Foo", ModelNames.of(null, name, "Foo-Q4_K_M.gguf"))
        }
        assertEquals("Foo", ModelNames.of(null, "123e4567-e89b-12d3-a456-426614174000", "Foo-Q4_K_M.gguf"))
    }
}
