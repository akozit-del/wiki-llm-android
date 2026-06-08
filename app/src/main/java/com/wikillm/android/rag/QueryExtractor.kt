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

    // ---- List-intent detection (build-61) ----

    private val LIST_MARKERS = listOf(
        "перечисли", "перечислите", "список", "спиши", "спишите", "назови всех",
        "назовите всех", "все ", "всех ", "всю ", "какие ", "кто из",
    )

    /** True iff the question asks for a list/enumeration of entities. */
    fun isListIntent(question: String): Boolean {
        val q = question.lowercase()
        return LIST_MARKERS.any { q.contains(it) }
    }

    /**
     * Pull the "main entity" out of the question — the proper-noun-shaped token
     * the list is *about* ("Тольятти" in "перечисли мэров Тольятти"). Heuristic:
     * longest capitalised token in the original question that isn't a generic
     * role word. We work on [question] verbatim to keep original casing.
     */
    fun extractEntity(question: String): String? {
        val roleWords = setOf(
            "Мэр", "Мэры", "Глава", "Главы", "Президент", "Президенты",
            "Губернатор", "Губернаторы", "Министр", "Министры", "Король", "Короли",
            "Премьер", "Премьеры", "Депутат", "Депутаты", "Лидер", "Лидеры",
        )
        // Instruction verbs sitting at the start of the sentence ("Перечисли…",
        // "Назови…") also start capitalised, beating the real entity in length.
        // Drop them by lemma stem (covers all morphology forms).
        val instructionStems = listOf(
            "перечисл", "назов", "расскаж", "опиш", "напиш",
            "скаж", "ответ", "покаж", "помог", "приведи", "приведит",
        )
        fun isInstruction(t: String): Boolean {
            val tl = t.lowercase()
            return instructionStems.any { tl.startsWith(it) }
        }
        val tokens = question
            .replace(Regex("[\\p{Punct}«»“”\"]"), " ")
            .split(Regex("\\s+"))
            .filter {
                it.length >= 4 && it.first().isUpperCase() &&
                    it !in roleWords && !isInstruction(it)
            }
        return tokens.maxByOrNull { it.length }
    }

    /**
     * "Plural role" the question asks about — "Главы" / "Мэры" / "Президенты".
     * Used to template title probes ("Главы X", "Мэры X", "Категория:Главы X").
     */
    fun extractRolePlural(question: String): String? {
        val q = question.lowercase()
        val map = listOf(
            // Government / authority
            "мэр" to "Мэры",
            "глав" to "Главы",
            "градонач" to "Градоначальники",
            "руковод" to "Руководители",
            "президент" to "Президенты",
            "вице-президент" to "Вице-президенты",
            "губернат" to "Губернаторы",
            "министр" to "Министры",
            "премьер" to "Премьер-министры",
            "канцлер" to "Канцлеры",
            "лидер" to "Лидеры",
            "король" to "Короли",
            "корол" to "Короли",
            "император" to "Императоры",
            "царь" to "Цари",
            "хан" to "Ханы",
            "султан" to "Султаны",
            "папа" to "Папы римские",
            "пап рим" to "Папы римские",
            "патриарх" to "Патриархи",
            // Awards / honours / titles
            "лауреат" to "Лауреаты",
            "номинант" to "Номинанты",
            "обладател" to "Обладатели",
            "облада­тел" to "Обладатели",
            // Sport
            "чемпион" to "Чемпионы",
            "победител" to "Победители",
            "призёр" to "Призёры",
            "призер" to "Призёры",
            "финалист" to "Финалисты",
            "олимпийск" to "Олимпийские чемпионы",
            // Other
            "член" to "Члены",
            "директор" to "Директора",
            "ректор" to "Ректоры",
            "редактор" to "Редакторы",
            "режиссёр" to "Режиссёры",
            "режиссер" to "Режиссёры",
            "композитор" to "Композиторы",
            "писател" to "Писатели",
            "поэт" to "Поэты",
            "учён" to "Учёные",
            "учен" to "Учёные",
            "космонавт" to "Космонавты",
            "астронавт" to "Астронавты",
        )
        for ((stem, label) in map) {
            if (q.contains(stem)) return label
        }
        return null
    }
}
