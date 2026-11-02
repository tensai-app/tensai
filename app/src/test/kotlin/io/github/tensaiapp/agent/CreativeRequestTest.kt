package io.github.tensaiapp.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The creative-writing heuristic of [CreativeRequest] (TEN-191, songs, dialogues and ages TEN-199). */
class CreativeRequestTest {

    @Test
    fun storiesPoemsAndEssaysAreCreative() {
        listOf(
            "/no_think Write a long story about a lighthouse keeper and a storm, at least 900 words",
            "Write a long story about a lighthouse keeper and a storm, at least 900 words.",
            "écris un poème sur la mer",
            "Écris-moi un HAÏKU sur l'automne",
            "tell me a story about a dragon",
            "Compose a haiku about rain",
            "Can you write me a poem about my grandmother's garden in summer?",
            "Write a short essay on the pros and cons of working from home",
            "raconte-moi une histoire de pirates",
            "Rédige une nouvelle de science-fiction",
            "make up a fable about a fox",
            "Write a poem of 12 lines about the sea",
            "Write a song about summer",
            "Écris une chanson sur l'automne.",
            "Write a rap verse about the city at night.",
            "Write a short dialogue between a cat and a dog arguing over the couch.",
            "Écris un poème pour les 30 ans de ma sœur.",
            "Write a poem for my dad's 60th birthday",
            "Raconte-moi une histoire sur un petit dragon qui a peur du noir.",
        ).forEach { assertTrue(it, CreativeRequest.matches(it)) }
    }

    @Test
    fun lettersResearchAndQuestionsAreNot() {
        listOf(
            "write an email to Bob",
            "write a letter to a friend",
            "écris un SMS à Léa",
            "search the web and write a story",
            "Cherche sur internet et écris un poème",
            "write a story based on today's news",
            "write a poem about https://example.com/page",
            "what is the story of the Eiffel tower",
            "remind me to write my essay tomorrow",
            "write a story and save it to memory",
            "What is 7 times 8?",
            "write a storyboard for the ad",
            "Write an email to my boss about my essay deadline",
            "Écris un SMS à Léa : j'ai de bonnes nouvelles",
            "Raconte-moi les nouvelles d'aujourd'hui",
            "Raconte-moi les nouvelles d’aujourd’hui",
            "Create a calendar event for poetry night Friday 8pm",
            "Make up a story and set a timer for 10 minutes",
            "Tell me the lyrics of Bohemian Rhapsody",
            "I'm writing an essay, what is 15% of 2340?",
            "Tell me the lyrics of the song Yesterday",
            "Écris les paroles d'une chanson de Brel",
            "Write a song and remind me to practice it",
            "Play a song by Daft Punk",
            "Write a poem with 3 rhymes about the sea",
        ).forEach { assertFalse(it, CreativeRequest.matches(it)) }
    }
}
