package io.github.tensaiapp.hf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

internal fun fixture(name: String): String =
    HfParsingTest::class.java.getResource("/hf/$name")!!.readText()

class HfParsingTest {

    @Test
    fun `search page fixture parses with counters, tags and licence`() {
        val items = HfParser.parseSearch(fixture("search_page.json"))
        assertEquals(20, items.size)
        val first = items.first()
        assertEquals("unsloth/Qwen3.8-27B-GGUF", first.id)
        assertEquals("unsloth", first.author)
        assertEquals("Qwen3.8-27B-GGUF", first.name)
        assertEquals(6_634_241, first.downloads)
        assertEquals(5084, first.likes)
        assertEquals("apache-2.0", first.license)
        assertEquals(HfGate.None, first.gate)
        assertNotNull(first.lastModified)
    }

    @Test
    fun `detail fixture with blobs and gguf parses`() {
        val d = HfParser.parseDetail(fixture("detail_qwen3_1_7b.json"))
        assertEquals("bartowski/Qwen_Qwen3-1.7B-GGUF", d.summary.id)
        assertEquals(2_031_739_904, d.paramCount)
        assertEquals("qwen3", d.architecture)
        assertEquals(32_768, d.contextLength)
        assertTrue(d.supportsTools)
        assertFalse(d.hasVision)
        assertFalse(d.summary.isGated)
        assertTrue(d.quants.isNotEmpty())
        // Sorted smallest first, all with a size and an LFS hash, none a non-gguf file.
        assertEquals(d.quants.sortedBy { it.sizeBytes }, d.quants)
        assertTrue(d.quants.all { it.fileName.endsWith(".gguf") && it.sizeBytes > 0 && it.sha256?.length == 64 })
        val q4 = d.quants.single { it.quant == "Q4_K_M" }
        assertEquals("Qwen_Qwen3-1.7B-Q4_K_M.gguf", q4.fileName)
        assertTrue(d.quants.any { it.quant == "IQ2_M" })
    }

    @Test
    fun `vision repo fixture flags vision and keeps mmproj out of the quants`() {
        val d = HfParser.parseDetail(fixture("detail_vision.json"))
        assertTrue(d.hasVision)
        assertEquals(listOf("Q4_K_M", "Q8_0", "F16"), d.quants.map { it.quant })
        assertTrue(d.quants.none { it.fileName.contains("mmproj") })
        assertEquals("apache-2.0", d.summary.license)
        // No chat template in this fixture: no tools claim.
        assertFalse(d.supportsTools)
    }

    @Test
    fun `split parts are not quants`() {
        val d = HfParser.parseDetail(
            """{"id":"a/b","siblings":[
              {"rfilename":"m-Q4_K_M.gguf","size":10},
              {"rfilename":"m-Q8_0-00001-of-00003.gguf","size":99},
              {"rfilename":"Q8_0/m-Q8_0-00002-of-00003.gguf","size":99},
              {"rfilename":"README.md","size":1}]}""",
        )
        assertEquals(listOf("m-Q4_K_M.gguf"), d.quants.map { it.fileName })
    }

    @Test
    fun `gated is false, auto, manual or true`() {
        fun gate(json: String) = HfParser.parseDetail("""{"id":"a/b","gated":$json}""").summary.gate
        assertEquals(HfGate.None, gate("false"))
        assertEquals(HfGate.Auto, gate("\"auto\""))
        assertEquals(HfGate.Manual, gate("\"manual\""))
        assertEquals(HfGate.Manual, gate("true"))
        assertEquals(HfGate.None, HfParser.parseDetail("""{"id":"a/b"}""").summary.gate)
    }

    @Test
    fun `licence comes from card data, string or list, else the tag`() {
        fun lic(json: String) = HfParser.parseDetail(json).summary.license
        assertEquals("mit", lic("""{"id":"a/b","cardData":{"license":"mit"}}"""))
        assertEquals("mit", lic("""{"id":"a/b","cardData":{"license":["mit","other"]}}"""))
        assertEquals("gemma", lic("""{"id":"a/b","tags":["license:gemma"]}"""))
        assertNull(lic("""{"id":"a/b"}"""))
    }

    @Test
    fun `tools detection reads the chat template`() {
        fun tools(tpl: String?) = HfParser.parseDetail(
            """{"id":"a/b","gguf":{"total":1,"chat_template":${tpl?.let { "\"$it\"" } ?: "null"}}}""",
        ).supportsTools
        assertTrue(tools("{%- if tools %}x{%- endif %}"))
        assertFalse(tools("<tool_call> {{ m.tool_calls }}"))
        assertFalse(tools("{{ messages }} nothing special"))
        assertFalse(tools("toolset and toolbox only"))
        assertFalse(tools(null))
    }

    @Test
    fun `thinking detection reads the chat template`() {
        fun thinking(tpl: String?) = HfParser.parseDetail(
            """{"id":"a/b","gguf":{"total":1,"chat_template":${tpl?.let { "\"$it\"" } ?: "null"}}}""",
        ).supportsThinking
        assertTrue(thinking("{%- if enable_thinking %}x{%- endif %}"))
        assertTrue(thinking("<|im_start|>assistant\\n<think>"))
        assertFalse(thinking("{{ messages }} nothing special"))
        assertFalse(thinking(null))
    }

    @Test
    fun `no chat template is flagged only when the Hub summarised the header`() {
        fun has(body: String) = HfParser.parseDetail(body).hasChatTemplate
        assertTrue(has("""{"id":"a/b","gguf":{"total":1,"chat_template":"{{ messages }}"}}"""))
        assertFalse(has("""{"id":"a/b","gguf":{"total":1}}"""))
        assertFalse(has("""{"id":"a/b","gguf":{"total":1,"chat_template":""}}"""))
        assertTrue("no gguf block is unknown, not template-less", has("""{"id":"a/b"}"""))
    }

    @Test
    fun `commit sha is kept only when it is 40 hex`() {
        val good = "0123456789abcdef0123456789abcdef01234567"
        assertEquals(good, HfParser.parseDetail("""{"id":"a/b","sha":"$good","siblings":[]}""").sha)
        assertEquals(null, HfParser.parseDetail("""{"id":"a/b","sha":"main","siblings":[]}""").sha)
        assertEquals(null, HfParser.parseDetail("""{"id":"a/b","siblings":[]}""").sha)
    }

    @Test
    fun `repo without gguf block parses with zero params`() {
        val d = HfParser.parseDetail("""{"id":"a/b","siblings":[{"rfilename":"x-Q4_K_M.gguf","size":5}]}""")
        assertEquals(0, d.paramCount)
        assertEquals("", d.architecture)
        assertEquals(1, d.quants.size)
    }

    @Test
    fun `files with no known size are not offered`() {
        val d = HfParser.parseDetail(
            """{"id":"a/b","siblings":[
              {"rfilename":"m-Q4_K_M.gguf"},
              {"rfilename":"m-Q5_K_M.gguf","size":null},
              {"rfilename":"m-Q6_K.gguf","size":0},
              {"rfilename":"m-Q8_0.gguf","size":7}]}""",
        )
        assertEquals(listOf("m-Q8_0.gguf"), d.quants.map { it.fileName })
    }

    @Test
    fun `explicit nulls and one malformed sibling do not kill the detail`() {
        val d = HfParser.parseDetail(
            """{"id":"a/b","downloads":null,"likes":null,"tags":null,
              "gguf":{"total":null,"context_length":null},
              "siblings":[{"rfilename":"m-Q8_0.gguf","size":7},{"rfilename":5,"size":"x"},"junk",
              {"rfilename":"m-Q4_K_M.gguf","size":3}]}""",
        )
        assertEquals(0, d.summary.downloads)
        assertEquals(listOf("m-Q4_K_M.gguf", "m-Q8_0.gguf"), d.quants.map { it.fileName })
    }

    @Test
    fun `slashless ids do not crash`() {
        val s = HfParser.parseDetail("""{"id":"gpt2"}""").summary
        assertEquals("", s.author)
        assertEquals("gpt2", s.name)
    }
}
