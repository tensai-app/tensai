package io.github.hokanosekai.tensai.data.chat

import io.github.hokanosekai.tensai.engine.SamplingConfig
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Bounds every stored sampling value is held to — shared by [SamplingJson]'s decode (which coerces
 * whatever it reads back off disk) and by the settings sheet's sliders (which can therefore never
 * produce a value the decode would silently move). One source of truth for "what is a legal value",
 * rather than a range in the UI and a different, implicit one in the parser.
 *
 * The floors matter more than the ceilings. A [maxTokens] of zero would mean a generation that
 * cannot produce a token, and [io.github.hokanosekai.tensai.engine.ReasoningBudget.splitting] halves
 * this figure — see its KDoc for why the half must stay at least 1.
 */
object SamplingRanges {
    val Temperature = 0f..2f
    val TopP = 0f..1f
    val TopK = 0..200
    val MinP = 0f..1f
    val RepeatPenalty = 1f..1.5f
    val MaxTokens = 64..8192

    /**
     * Cap on how many stop sequences are kept. llama.cpp scans every one of them against the
     * generated text, so an unbounded list read back off disk is a decode that gets slower for
     * every entry — and there is no sane reason for a conversation to carry dozens.
     */
    const val MaxStopSequences = 8
}

/**
 * Reads and writes [ConversationEntity.samplingJson] — the per-conversation sampling override
 * (TEN-35). The column existed from the start but was written `null` and never read; this is what
 * gives it a meaning.
 *
 * ### What is, and is not, in the blob
 *
 * Exactly the seven knobs a conversation can override: temperature, top-p, top-k, min-p, repeat
 * penalty, stop sequences and max tokens. Deliberately **not**:
 *
 * - [SamplingConfig.enableThinking], which has had its own [ConversationEntity.enableThinking]
 *   column since schema v4 (TEN-33) and is toggled from the chat screen. Writing it here too would
 *   make two rows of the same table disagree about the same setting, and nothing would say which
 *   one wins. [decode] therefore always returns the default `enableThinking`, and the caller is
 *   expected to overlay the column's value — see
 *   [io.github.hokanosekai.tensai.ui.screens.chat.ChatViewModel.generateReply].
 * - [SamplingConfig.reasoningBudget], which is derived from [SamplingConfig.maxTokens] at decode
 *   time and must stay derived — see
 *   [io.github.hokanosekai.tensai.engine.ReasoningBudget.splitting]. Persisting it would let a
 *   stale blob carry two caps that no longer sum to the max-tokens next to them, which is precisely
 *   the invariant this ticket had to avoid breaking.
 *
 * ### Why this format survives the data model changing under it
 *
 * The blob outlives the app version that wrote it: a conversation created today is read back by
 * every build that follows, including ones whose [SamplingConfig] has fields this one has never
 * heard of. So [decode] never fails and never insists on finding anything:
 *
 * - **every key is optional**, resolved against [SamplingConfig]'s own defaults, so a blob written
 *   before a field existed reads as "that field is at its default" rather than as an error;
 * - **unknown keys are ignored**, so a blob written by a *newer* build downgrades cleanly instead
 *   of taking the whole conversation's settings down with it;
 * - **unparseable input falls back whole** — absent, blank, truncated, or not even JSON all return
 *   [SamplingConfig];
 * - **every value is coerced into [SamplingRanges]**, so a nonsense figure (a negative max-tokens,
 *   a NaN temperature) is clamped rather than handed to llama.cpp.
 *
 * [KeyVersion] is written but not currently read: forward compatibility comes from the four rules
 * above, not from a version check. It is there so that a future change those rules *cannot* absorb
 * — renaming a key, changing a unit — has something to dispatch on, which is impossible to add
 * after the fact to blobs already on disk.
 *
 * Uses `org.json` (part of the Android framework, no dependency) rather than a serialization
 * library: the whole schema is seven scalars and a string array, and the only genuinely hard part is
 * escaping stop sequences, which is exactly what a real JSON writer is for.
 */
object SamplingJson {

    /** Current blob version — see this object's KDoc for what it is (and is not) for. */
    const val Version = 1

    private const val KeyVersion = "v"
    private const val KeyTemperature = "temperature"
    private const val KeyTopP = "topP"
    private const val KeyTopK = "topK"
    private const val KeyMinP = "minP"
    private const val KeyRepeatPenalty = "repeatPenalty"
    private const val KeyStopSequences = "stopSequences"
    private const val KeyMaxTokens = "maxTokens"

    /** Serializes [config]'s persisted subset — see this object's KDoc for what that covers. */
    fun encode(config: SamplingConfig): String =
        JSONObject()
            .put(KeyVersion, Version)
            .put(KeyTemperature, config.temperature.toDouble())
            .put(KeyTopP, config.topP.toDouble())
            .put(KeyTopK, config.topK)
            .put(KeyMinP, config.minP.toDouble())
            .put(KeyRepeatPenalty, config.repeatPenalty.toDouble())
            .put(KeyMaxTokens, config.maxTokens)
            .put(KeyStopSequences, JSONArray(config.stopSequences))
            .toString()

    /**
     * Parses a stored blob back into a [SamplingConfig]. Never throws: anything it cannot make
     * sense of resolves to [SamplingConfig]'s defaults, field by field where possible and wholesale
     * otherwise.
     */
    fun decode(json: String?): SamplingConfig {
        val defaults = SamplingConfig()
        if (json.isNullOrBlank()) return defaults
        val obj = try {
            JSONObject(json)
        } catch (_: JSONException) {
            return defaults
        }
        return SamplingConfig(
            temperature = obj.floatOr(KeyTemperature, defaults.temperature, SamplingRanges.Temperature),
            topP = obj.floatOr(KeyTopP, defaults.topP, SamplingRanges.TopP),
            topK = obj.intOr(KeyTopK, defaults.topK, SamplingRanges.TopK),
            minP = obj.floatOr(KeyMinP, defaults.minP, SamplingRanges.MinP),
            // Blobs written before TEN-114 have no key: they read as the default (1.10), which is the fix.
            repeatPenalty = obj.floatOr(KeyRepeatPenalty, defaults.repeatPenalty, SamplingRanges.RepeatPenalty),
            stopSequences = obj.stopSequencesOr(defaults.stopSequences),
            maxTokens = obj.intOr(KeyMaxTokens, defaults.maxTokens, SamplingRanges.MaxTokens),
        )
    }

    /**
     * NaN and the infinities are checked explicitly rather than left to [coerceIn]: JSON has no
     * literal for them, but `org.json`'s lenient parser will happily hand back a `Double` for a
     * bare `NaN` token, and every comparison a clamp makes against NaN is false — it would come out
     * the other side still NaN.
     */
    private fun JSONObject.floatOr(key: String, default: Float, range: ClosedFloatingPointRange<Float>): Float {
        val raw = optDouble(key, default.toDouble())
        if (raw.isNaN() || raw.isInfinite()) return default
        return raw.toFloat().coerceIn(range.start, range.endInclusive)
    }

    private fun JSONObject.intOr(key: String, default: Int, range: IntRange): Int =
        optInt(key, default).coerceIn(range.first, range.last)

    /**
     * A value that is present but not an array (a string, a number, `null`) is treated as absent —
     * there is nothing sensible to salvage from it, and the defaults are a better answer than half
     * a list. Non-string entries inside an otherwise valid array are skipped individually, for the
     * same reason [decode] resolves per key: one bad entry should not cost the good ones.
     */
    private fun JSONObject.stopSequencesOr(default: List<String>): List<String> {
        val array = optJSONArray(KeyStopSequences) ?: return default
        val out = ArrayList<String>(array.length())
        for (i in 0 until array.length()) {
            val entry = array.opt(i) as? String ?: continue
            // An empty stop sequence matches everywhere: it would end every generation at its
            // first token.
            if (entry.isNotEmpty()) out.add(entry)
        }
        return out.take(SamplingRanges.MaxStopSequences)
    }
}
