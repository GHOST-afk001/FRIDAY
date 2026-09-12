package com.friday.assistant.commands

import java.text.DateFormat
import java.util.Date
import java.util.Locale

class FridayCommandProcessor {
    fun process(input: String): FridayResponse {
        val raw = input.trim()
        val command = raw.lowercase(Locale.ROOT)
        if (command.isBlank()) return FridayResponse("I didn't catch that. Please say it again.")
        if (isGreeting(command)) return FridayResponse("Yes Boss. Main Friday hoon. Bataiye.")
        if (command.contains("who are you") || command.contains("tum kaun") || command.contains("aap kaun")) return FridayResponse("Main Friday hoon, aapki personal Android assistant. Ready when you are, Boss.")
        // Match the standalone Hindi time word, not the prefix inside "टाइमर".
        val hasHindiTimeWord = Regex("(^|\\s)टाइम(\\s|$)").containsMatchIn(command)
        if (Regex("(^|\\s)time(\\s|$)").containsMatchIn(command) || command.contains("kitne baje") || command.contains("samay") || hasHindiTimeWord) return FridayResponse("Abhi ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date())} baj rahe hain, Boss.")
        if (Regex("(^|\\s)date(\\s|$)").containsMatchIn(command) || command.contains("tarikh") || command.contains("tariq") || command.contains("तारीख") || command.contains("डेट")) return FridayResponse("Aaj ${DateFormat.getDateInstance(DateFormat.LONG).format(Date())} hai.")
        if (command.contains("standby") || command.contains("so jao") || command.contains("stop listening")) return FridayResponse("Understood Boss. Standby mode.")

        parseTimer(command)?.let { return FridayResponse("Timer ${prettyDuration(it)} ka set kar rahi hoon.", FridayAction.Timer(it)) }
        parseAlarm(command)?.let { return FridayResponse("Alarm ${String.format(Locale.US, "%02d:%02d", it.first, it.second)} ke liye set kar rahi hoon.", FridayAction.Alarm(it.first, it.second)) }

        if (command.contains("flashlight") || command.contains("torch") || command.contains("फ्लैशलाइट")) return if (command.contains("off") || command.contains("band")) FridayResponse("Torch off kar rahi hoon.", FridayAction.FlashlightOff) else FridayResponse("Torch on kar rahi hoon.", FridayAction.FlashlightOn)
        if (command.contains("volume") && (command.contains("up") || command.contains("increase") || command.contains("badha"))) return FridayResponse("Volume badha rahi hoon.", FridayAction.VolumeUp)
        if (command.contains("volume") && (command.contains("down") || command.contains("decrease") || command.contains("kam"))) return FridayResponse("Volume kam kar rahi hoon.", FridayAction.VolumeDown)

        if (command.contains("youtube")) return FridayResponse("YouTube khol rahi hoon.", FridayAction.YouTube)
        if (command.contains("calculator") || command.contains("कैलकुलेटर")) return FridayResponse("Calculator khol rahi hoon.", FridayAction.Calculator)
        if (command.contains("settings") || command.contains("सेटिंग")) return FridayResponse("Settings khol rahi hoon.", FridayAction.Settings)
        if (command.contains("camera") || command.contains("कैमरा")) return FridayResponse("Camera khol rahi hoon.", FridayAction.Camera)
        if (command.contains("chrome")) return FridayResponse("Chrome khol rahi hoon.", FridayAction.Chrome)
        if (command.contains("whatsapp")) return FridayResponse("WhatsApp khol rahi hoon.", FridayAction.WhatsApp)
        if (command.contains("instagram")) return FridayResponse("Instagram khol rahi hoon.", FridayAction.Instagram)
        if (command.contains("messages") || command.contains("message app")) return FridayResponse("Messages khol rahi hoon.", FridayAction.Messages)

        parseMap(command)?.let { return FridayResponse(if (it.second) "Maps mein route khol rahi hoon." else "Maps mein location dikha rahi hoon.", FridayAction.MapQuery(it.first, it.second)) }
        val normalized = command.replace(Regex("^\\s*(?:hey\\s+)?friday\\b\\s*"), "").trim()
        parseCall(normalized)?.let { target ->
            val compact = target.filter { it.isDigit() || it == '+' }
            val action = if (compact.length in 7..15 && target.all { it.isDigit() || it == '+' || it == ' ' || it == '-' }) FridayAction.DialNumber(compact) else FridayAction.DialContact(target)
            return FridayResponse("${target.trim()} ke liye dialer khol rahi hoon.", action, needsConfirmation = true)
        }
        parseSms(normalized, raw)?.let { (name, message) -> return FridayResponse("Message ready hai. ${name.trim()} ko bhejne se pehle preview dikhati hoon.", FridayAction.SmsContact(name.trim(), message), needsConfirmation = true) }
        return FridayResponse("", handledLocally = false)
    }

    private fun isGreeting(c: String) = c == "hello" || c.contains("hello friday") || c.contains("hi friday") || c.contains("namaste") || c.contains("नमस्ते")

    private fun parseTimer(c: String): Int? {
        if (!c.contains("timer") && !c.contains("टाइमर")) return null
        val tokens = c.split(Regex("\\s+")).filter { it.isNotBlank() }
        val numberIndex = tokens.indexOfFirst { it.toLongOrNull() != null }
        if (numberIndex < 0) return null
        val number = tokens[numberIndex].toLongOrNull() ?: return null
        val unit = tokens.getOrNull(numberIndex + 1)?.trim(',', '.', ':')?.lowercase(Locale.ROOT) ?: return null
        val multiplier = when (unit) {
            "hour", "hours", "hr", "hrs", "ghanta", "ghante", "घंटा", "घंटे" -> 3600L
            "minute", "minutes", "min", "mins", "minut", "मिनट" -> 60L
            "second", "seconds", "sec", "secs", "सेकंड" -> 1L
            else -> return null
        }
        return (number * multiplier).takeIf { it in 1L..86400L }?.toInt()
    }

    private fun parseAlarm(c: String): Pair<Int, Int>? {
        if (!(c.contains("alarm") || c.contains("अलार्म") || c.contains("wake me"))) return null
        val timeMatch = Regex("(?<!\\d)(\\d{1,2})(?:[:.](\\d{1,2}))?\\s*(am|pm)?\\b").find(c) ?: return null
        var hour = timeMatch.groupValues[1].toIntOrNull() ?: return null
        val minute = timeMatch.groupValues[2].toIntOrNull() ?: 0
        val meridiem = timeMatch.groupValues[3]
        if (meridiem == "pm" && hour < 12) hour += 12
        if (meridiem == "am" && hour == 12) hour = 0
        if (meridiem.isBlank() && hour !in 0..23) return null
        return if (hour in 0..23 && minute in 0..59) hour to minute else null
    }

    private fun parseMap(c: String): Pair<String, Boolean>? {
        val marker = listOf("google maps", "google map", "maps", "map par", "map pe", "location", "route", "directions", "लोकेशन", "रास्ता").firstOrNull { c.contains(it) } ?: return null
        val route = c.contains("route") || c.contains("directions") || c.contains("rasta") || c.contains("raasta") || c.contains("navigate")
        var q = c.substringAfter(marker, "").trim()
        q = q.replace(Regex("^(par|pe|mein|me|to|ka|ki|for)\\s+"), "").trim()
        q = q.replace(Regex("\\s+(ki )?(location|locaton|jagah)(\\s+lagao|\\s+dikhao|\\s+dikhana)?$", RegexOption.IGNORE_CASE), "").trim()
        q = q.replace(Regex("\\s+(dikhao|dikha do|lagao|khol do)$", RegexOption.IGNORE_CASE), "").trim()
        if (q.isBlank()) q = "current location"
        return q to route
    }

    private fun parseCall(c: String): String? {
        val afterVerb = Regex("^(?:call|phone|dial)\\s+(?:(?:karo|kar|please)\\s+)?(.+)$").find(c)?.groupValues?.get(1)?.trim()
        if (!afterVerb.isNullOrBlank()) return afterVerb
        return Regex("^(.+?)\\s+(?:ko\\s+)?(?:call|phone|dial)\\s+(?:karo|kar|please)?\\s*$", RegexOption.IGNORE_CASE)
            .find(c)?.groupValues?.get(1)?.trim()
    }

    private fun parseSms(c: String, raw: String): Pair<String, String>? {
        val normalizedRaw = raw.trim().replace(Regex("^\\s*(?:hey\\s+)?friday\\b\\s*", RegexOption.IGNORE_CASE), "").trim()
        val forms = listOf(
            Regex("^(?:message|text|sms|msg)\\s+(?:karo|kar)?\\s*(?:to|ko)\\s+([A-Za-z][A-Za-z ]{1,30}?)(?:\\s+(?:that|ki|bolo|bolna|message|text)\\s+|\\s*[:,;-]\\s*)(.+)$", RegexOption.IGNORE_CASE),
            Regex("^([A-Za-z][A-Za-z ]{1,30}?)\\s+ko\\s+(?:message|text|sms|msg)\\s+(?:karo|kar)?(?:\\s+(?:ki|that|bolo|bolna))?\\s+(.+)$", RegexOption.IGNORE_CASE),
            Regex("^(?:message|text|sms|msg)\\s+([A-Za-z][A-Za-z ]{1,30}?)\\s*[:,;-]\\s*(.+)$", RegexOption.IGNORE_CASE)
        )
        val source = if (normalizedRaw.isNotBlank()) normalizedRaw else c
        return forms.firstNotNullOfOrNull { it.find(source)?.groupValues?.let { g -> g[1].trim() to g[2].trim() } }
    }

    private fun prettyDuration(seconds: Int): String = when { seconds % 3600 == 0 -> "${seconds / 3600} hour"; seconds % 60 == 0 -> "${seconds / 60} minute"; else -> "$seconds second" }
}
