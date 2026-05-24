package com.wikillm.android.rag

/**
 * Cleans a free-form user question for ZIM full-text search:
 *  - lowercases, drops punctuation
 *  - drops short tokens (<3 chars) and common Russian/English stop-words
 *  - keeps the original token order
 *
 * Result is joined back with spaces — xapian's query parser will OR/AND them
 * itself, but at least it won't have to match useless filler words.
 */
object QueryExtractor {

    private val STOP = setOf(
        // Russian
        "что", "как", "кто", "где", "когда", "почему", "зачем", "какой", "какая", "какие",
        "это", "этот", "эта", "эти", "тот", "та", "то", "те",
        "ты", "вы", "мне", "тебе", "ему", "ей", "нам", "вам", "им",
        "знаешь", "знаете", "знает", "расскажи", "расскажите", "опиши", "опишите",
        "дай", "дайте", "напиши", "напишите", "пожалуйста",
        "про", "о", "об", "обо", "на", "в", "во", "у", "из", "из-за", "под", "над", "за", "по",
        "и", "а", "но", "или", "либо", "ни", "не", "же", "ли", "бы", "да", "нет",
        "есть", "был", "была", "было", "были", "буду", "будет", "будут",
        "его", "её", "их", "мой", "моя", "моё", "мои", "твой", "твоя", "твоё", "твои",
        "может", "могу", "можешь", "хочу", "хочешь",
        // instruction / quantifier / temporal filler (diluted RAG search)
        "перечисли", "перечислите", "назови", "назовите", "список", "спиши",
        "все", "всех", "весь", "вся", "всё", "всего",
        "последние", "последний", "последних", "последнее", "последней",
        "лет", "год", "года", "годов", "годы", "году",
        "сколько", "несколько", "каждый", "каждая", "также", "ещё", "еще",
        // English (just in case)
        "what", "who", "where", "when", "why", "how", "is", "are", "was", "were",
        "the", "a", "an", "of", "on", "in", "at", "to", "for", "with", "and", "or",
        "tell", "me", "you", "about", "give", "please",
    )

    fun extract(question: String): String {
        val cleaned = question.lowercase().replace(Regex("[\\p{Punct}«»“”\"]"), " ")
        val tokens = cleaned.split(Regex("\\s+"))
            .filter { it.length >= 3 && it !in STOP }
        if (tokens.isEmpty()) return question.trim() // fallback — search whole thing
        return tokens.joinToString(" ")
    }
}
