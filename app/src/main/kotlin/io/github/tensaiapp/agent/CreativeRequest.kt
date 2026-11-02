package io.github.tensaiapp.agent

import java.text.Normalizer

/**
 * Whether a user message asks for creative writing (a story, a poem, an essay, a song), in English or French: the first
 * turn of a conversation asking for it runs with no tools ([AgentRuntime.run]). Qwen3-1.7B searches the web or
 * Wikipedia before writing a story even when the base prompt tells it not to (TEN-191, on the phone), and a story needs
 * nothing a tool could bring.
 *
 * A heuristic on words, no model: a writing verb and a creative noun, and nothing that names another tool's job (an
 * email, a message, an event, a timer, the news, lyrics), asks for research, holds a link or a digit (a length,
 * "900 words", or an age, "30 ans", is not one: it is left out first). An ask that
 * mentions an email or a message is never one, so `draft_email` and `draft_sms` stay offered for it. A letter is not
 * one of the nouns either.
 */
object CreativeRequest {
    private val verb = Regex("""\b(write|writing|compose|create|make up|tell me|ecri\w*|redige\w*|raconte\w*|invente\w*|compose\w*)\b""")

    private val noun = Regex(
        """\b(story|stories|tales?|poems?|poetry|haikus?|sonnets?|limericks?|essays?|fables?|songs?|raps?|dialogues?|""" +
            """histoires?|contes?|une nouvelle|poemes?|poesies?|essais?|chansons?|couplets?)\b""",
    )

    private val elsewhere = Regex(
        """\b(search\w*|web|internet|look up|wikipedia|sources?|news|latest|today|remind\w*|save|remember\w*|""" +
            """cherche\w*|recherche\w*|actu|actualites?|infos|rappel\w*|enregistre\w*|souviens|aujourd['’]hui|""" +
            """e-?mails?|mails?|sms|textos?|messages?|calendars?|agendas?|events?|evenements?|meetings?|reunions?|""" +
            """timers?|minuteurs?|alarms?|alarmes?|reveils?|reminders?|playlists?|lyrics|paroles|top)\b|https?://|www\.|\d""",
    )

    /** A length the ask sets, or an age ("30 ans", "30th birthday"): the figures a creative ask holds. */
    private val length = Regex(
        """\b\d+\s*(words?|mots?|lines?|lignes?|stanzas?|strophes?|vers|paragraphs?|paragraphes?|ans|years?)\b|""" +
            """\b\d+(st|nd|rd|th|e|eme)\b""",
    )

    private val marks = Regex("""\p{Mn}+""")

    fun matches(text: String): Boolean {
        val plain = length.replace(marks.replace(Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD), ""), "")
        return verb.containsMatchIn(plain) && noun.containsMatchIn(plain) && !elsewhere.containsMatchIn(plain)
    }
}
