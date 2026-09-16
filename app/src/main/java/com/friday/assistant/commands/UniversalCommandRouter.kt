package com.friday.assistant.commands

import java.util.Locale

/**
 * Small deterministic bridge for commands that should never be handed to Gemini as mere chat.
 * It covers installed-app launching and media-search intents while leaving open-ended questions
 * to Gemini. AppLauncher resolves labels against the user's actual installed launcher apps.
 */
object UniversalCommandRouter {
    fun route(input: String): FridayResponse? {
        val raw = input.trim()
        if (raw.isBlank()) return null
        val c = raw
            .replace(Regex("^\\s*(?:hey\\s+)?friday\\b\\s*", RegexOption.IGNORE_CASE), "")
            .trim()
            .replace(Regex("\\s+"), " ")
        val lower = c.lowercase(Locale.ROOT)

        parseSpotify(lower)?.let { return FridayResponse("Spotify par ${it} search kar rahi hoon.", FridayAction.SpotifySearch(it)) }

        val open = Regex("^(?:open|launch|start|khol(?:o|na)?|kholo|chalao|चलाओ|खोलो|खोल)\\s+(.+)$", RegexOption.IGNORE_CASE).find(c)
        if (open != null) {
            val label = cleanAppLabel(open.groupValues[1])
            if (label.isNotBlank() && label.length <= 50) return FridayResponse("${label.replaceFirstChar { it.uppercase() }} khol rahi hoon.", FridayAction.OpenApp(label, label))
        }

        val appFirst = Regex("^(.+?)\\s+(?:app|application)\\s+(?:open|khol(?:o|na)?|kholo)$", RegexOption.IGNORE_CASE).find(c)
        if (appFirst != null) {
            val label = cleanAppLabel(appFirst.groupValues[1])
            if (label.isNotBlank() && label.length <= 50) return FridayResponse("${label.replaceFirstChar { it.uppercase() }} khol rahi hoon.", FridayAction.OpenApp(label, label))
        }

        val genericSearch = Regex("^(?:search|find|google|look up|lookup)\\s+(.+?)(?:\\s+(?:on|in)\\s+(?:google|the web|web))?$", RegexOption.IGNORE_CASE).find(c)
        if (genericSearch != null) {
            val query = genericSearch.groupValues[1].trim()
            if (query.isNotBlank()) return FridayResponse("Web par ${query} search kar rahi hoon.", FridayAction.OpenApp("__web_search__:$query", "Web search"))
        }
        return null
    }

    private fun parseSpotify(c: String): String? {
        if (!c.contains("spotify")) return null
        val patterns = listOf(
            Regex("spotify(?:\\s+(?:par|pe|mein|me))?\\s+(?:search|find|khojo|khoj|dhundo|dhoondo|play|chalao|for)\\s+(.+)$", RegexOption.IGNORE_CASE),
            Regex("(?:search|find|khojo|khoj|dhundo|dhoondo|play|chalao)\\s+(.+?)\\s+(?:on|in|par|pe)\\s+spotify$", RegexOption.IGNORE_CASE)
        )
        val q = patterns.firstNotNullOfOrNull { it.find(c)?.groupValues?.get(1) } ?: return null
        return q.trim().replace(Regex("\\s+(?:karo|kar|please|do)$", RegexOption.IGNORE_CASE), "").trim().takeIf { it.isNotBlank() }
    }

    private fun cleanAppLabel(value: String): String = value
        .trim()
        .replace(Regex("^(?:the|my)\\s+", RegexOption.IGNORE_CASE), "")
        .replace(Regex("\\s+(?:please|please)$", RegexOption.IGNORE_CASE), "")
        .trim()
}
