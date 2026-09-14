package com.friday.assistant.commands

/**
 * Finds a two-part compound command without blindly splitting message bodies.
 * A split is accepted only when both complete parts are understood local commands.
 */
internal object CompoundCommandParser {
    private val separator = Regex("\\s+(?:aur|and|then|phir|fir)\\s+", RegexOption.IGNORE_CASE)

    fun parse(input: String, processor: FridayCommandProcessor): FridayResponse? {
        val matches = separator.findAll(input).toList()
        if (matches.isEmpty()) return null
        for (match in matches) {
            val left = input.substring(0, match.range.first).trim()
            val right = input.substring(match.range.last + 1).trim()
            if (left.isBlank() || right.isBlank()) continue
            val first = processor.processWithoutCompound(left)
            val second = processor.processWithoutCompound(right)
            if (!first.handledLocally || !second.handledLocally) continue
            val actions = listOfNotNull(first.action, second.action)
            if (actions.size != 2) continue
            val needsConfirmation = first.needsConfirmation || second.needsConfirmation
            val text = listOf(first.text, second.text).filter { it.isNotBlank() }.joinToString(" Then ")
            return FridayResponse(text, FridayAction.Sequence(actions), needsConfirmation)
        }
        return null
    }
}
