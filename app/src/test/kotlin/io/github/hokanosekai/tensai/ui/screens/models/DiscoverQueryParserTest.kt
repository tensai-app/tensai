package io.github.hokanosekai.tensai.ui.screens.models

import org.junit.Assert.assertEquals
import org.junit.Test

class DiscoverQueryParserTest {

    private fun parse(s: String) = DiscoverQueryParser.parse(s)

    @Test
    fun blankIsTheHome() {
        assertEquals(DiscoverQuery.Empty, parse(""))
        assertEquals(DiscoverQuery.Empty, parse("   "))
    }

    @Test
    fun plainWordsAreASearch() {
        assertEquals(DiscoverQuery.Text("qwen3 1.7b"), parse("  qwen3 1.7b "))
        assertEquals(DiscoverQuery.Text("Qwen3-1.7B"), parse("Qwen3-1.7B"))
        // A version number is not a host.
        assertEquals(DiscoverQuery.Text("llama3.2"), parse("llama3.2"))
        assertEquals(DiscoverQuery.Text("llama 3.2/instruct"), parse("llama 3.2/instruct"))
    }

    @Test
    fun repoLinksOpenTheRepoWithNoFile() {
        val expected = DiscoverQuery.Repo("bartowski/Qwen_Qwen3-1.7B-GGUF", null)
        assertEquals(expected, parse("https://huggingface.co/bartowski/Qwen_Qwen3-1.7B-GGUF"))
        assertEquals(expected, parse("http://www.huggingface.co/bartowski/Qwen_Qwen3-1.7B-GGUF/"))
        assertEquals(expected, parse("hf.co/bartowski/Qwen_Qwen3-1.7B-GGUF"))
        assertEquals(expected, parse("HTTPS://HF.CO/bartowski/Qwen_Qwen3-1.7B-GGUF?foo=1#bar"))
        assertEquals(expected, parse("https://huggingface.co/bartowski/Qwen_Qwen3-1.7B-GGUF/tree/main"))
        assertEquals(expected, parse("https://huggingface.co/bartowski/Qwen_Qwen3-1.7B-GGUF/blob/main/README.md"))
    }

    @Test
    fun theRepoIdKeepsItsCase() {
        assertEquals(DiscoverQuery.Repo("Qwen/Qwen3-4B-GGUF", null), parse("hf.co/Qwen/Qwen3-4B-GGUF"))
    }

    @Test
    fun aQuantTagAfterTheNameIsDropped() {
        assertEquals(DiscoverQuery.Repo("bartowski/Llama-3.2-3B-GGUF", null), parse("hf.co/bartowski/Llama-3.2-3B-GGUF:Q4_K_M"))
    }

    @Test
    fun fileLinksPreselectTheFile() {
        assertEquals(
            DiscoverQuery.Repo("bartowski/Qwen_Qwen3-1.7B-GGUF", "Qwen_Qwen3-1.7B-Q4_K_M.gguf"),
            parse("https://huggingface.co/bartowski/Qwen_Qwen3-1.7B-GGUF/blob/main/Qwen_Qwen3-1.7B-Q4_K_M.gguf"),
        )
        assertEquals(
            DiscoverQuery.Repo("bartowski/Qwen_Qwen3-1.7B-GGUF", "Qwen_Qwen3-1.7B-Q8_0.gguf"),
            parse("https://huggingface.co/bartowski/Qwen_Qwen3-1.7B-GGUF/resolve/main/Qwen_Qwen3-1.7B-Q8_0.gguf?download=true"),
        )
    }

    @Test
    fun aFileInAFolderKeepsItsPathAndEncodedCharactersAreDecoded() {
        assertEquals(
            DiscoverQuery.Repo("a/b", "sub dir/m+x-Q4_K_M.gguf"),
            parse("hf.co/a/b/blob/main/sub%20dir/m+x-Q4_K_M.gguf"),
        )
    }

    @Test
    fun aLinkToANonGgufFileIsARepoLink() {
        assertEquals(DiscoverQuery.Repo("a/b", null), parse("hf.co/a/b/blob/main/model.safetensors"))
    }

    @Test
    fun aPathThatLeavesTheRepoIsNotAFile() {
        assertEquals(DiscoverQuery.Repo("a/b", null), parse("hf.co/a/b/blob/main/../x.gguf"))
    }

    @Test
    fun anyOtherUrlIsForeign() {
        listOf(
            "https://example.com/bartowski/Qwen-GGUF",
            "https://github.com/ggml-org/llama.cpp",
            "example.com/a/b.gguf",
            "www.example.com",
            "ftp://huggingface.co/a/b",
            "https://huggingface.co.evil.com/a/b",
            "https://huggingface.co@evil.com/a/b",
            "https://evil.com/huggingface.co/a/b",
            "https://hf.co.evil.com/a/b",
        ).forEach { assertEquals(it, DiscoverQuery.Foreign, parse(it)) }
    }

    @Test
    fun aHuggingFacePageThatIsNotARepoIsForeign() {
        listOf(
            "https://huggingface.co",
            "https://huggingface.co/models",
            "https://huggingface.co/models?search=qwen",
            "https://huggingface.co/spaces/a/b",
            "https://huggingface.co/datasets/a/b",
            "https://huggingface.co/docs/hub/index",
            "https://huggingface.co/justauser",
        ).forEach { assertEquals(it, DiscoverQuery.Foreign, parse(it)) }
    }
}
