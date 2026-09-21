package com.friday.assistant.commands

import java.text.DateFormat
import java.util.Date
import java.util.Locale

class FridayCommandProcessor {
    fun process(input: String): FridayResponse {
        val raw = input.trim()
        if (raw.isBlank()) return FridayResponse("I didn't catch that. Please say it again.")
        parseAppThenSearch(raw)?.let { return it }
        parseWhatsappMessage(raw)?.let { (name, message) ->
            return FridayResponse("${name.trim()} ko WhatsApp message bhej rahi hoon.", FridayAction.AccessibilityCommand("whatsapp_ui|${name.trim()}|${message.trim()}"), needsConfirmation = false)
        }
        parseWhatsappQuickMessage(raw)?.let { (name, message) ->
            return FridayResponse("${name.trim()} ko WhatsApp par “${message.trim()}” bhej rahi hoon.", FridayAction.AccessibilityCommand("whatsapp_message|${name.trim()}|${message.trim()}"), needsConfirmation = false)
        }
        CompoundCommandParser.parse(raw, this)?.let { return it }
        return processWithoutCompound(raw)
    }

    internal fun processWithoutCompound(input: String): FridayResponse {
        val raw = input.trim()
        val command = raw.lowercase(Locale.ROOT)
        if (command.isBlank()) return FridayResponse("I didn't catch that. Please say it again.")
        if (isGreeting(command)) return FridayResponse("Yes Boss. Main Friday hoon. Bataiye.")
        parseMood(command)?.let { return it }
        if (command.contains("who are you") || command.contains("tum kaun") || command.contains("aap kaun")) return FridayResponse("Main Friday hoon, aapki personal Android assistant. Ready when you are, Boss.")

        val normalized = command.replace(Regex("^\\s*(?:hey\\s+)?friday\\b\\s*"), "").trim()
        val hasHindiTimeWord = Regex("(^|\\s)टाइम(\\s|$)").containsMatchIn(normalized)
        if (Regex("(^|\\s)time(\\s|$)").containsMatchIn(normalized) || normalized.contains("kitne baje") || normalized.contains("samay") || hasHindiTimeWord) return FridayResponse("Abhi ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date())} baj rahe hain, Boss.")
        if (Regex("(^|\\s)date(\\s|$)").containsMatchIn(normalized) || normalized.contains("tarikh") || normalized.contains("tariq") || normalized.contains("तारीख") || normalized.contains("डेट")) return FridayResponse("Aaj ${DateFormat.getDateInstance(DateFormat.LONG).format(Date())} hai.")
        if (normalized.contains("standby") || normalized.contains("so jao") || normalized.contains("stop listening")) return FridayResponse("Understood Boss. Standby mode.")

        parseDirectWebSearch(normalized)?.let { return FridayResponse("Google par $it search kar rahi hoon.", FridayAction.OpenApp("__web_search__:$it", "Web search")) }

        if (isEmergencySosCommand(normalized)) return FridayResponse("Emergency dialer mein 112 open karne ke liye confirmation chahiye.", FridayAction.EmergencySos, needsConfirmation = true)

        parseAccessibilityCommand(normalized)?.let { return it }

        parseTimer(normalized)?.let { return FridayResponse("Timer ${prettyDuration(it)} ka set kar rahi hoon.", FridayAction.Timer(it)) }
        parseAlarm(normalized)?.let { alarm ->
            return if (alarm is FridayAction.AlarmAfter) FridayResponse("Alarm ${prettyDuration(alarm.seconds)} baad set kar rahi hoon.", alarm)
            else { val clock = alarm as FridayAction.Alarm; FridayResponse("Alarm ${String.format(Locale.US, "%02d:%02d", clock.hour, clock.minute)} ke liye set kar rahi hoon.", clock) }
        }

        if (normalized.contains("flashlight") || normalized.contains("flash light") || normalized.contains("torch") ||
            normalized.contains("light on") || normalized.contains("light off") ||
            normalized.contains("torch on") || normalized.contains("torch off") ||
            normalized.contains("torch chalu") || normalized.contains("torch band") ||
            normalized.contains("flashlight on") || normalized.contains("flashlight off") ||
            normalized.contains("फ्लैशलाइट") || normalized.contains("टॉर्च")) {
            return if (normalized.contains("off") || normalized.contains("band")) FridayResponse("Torch off kar rahi hoon.", FridayAction.FlashlightOff) else FridayResponse("Torch on kar rahi hoon.", FridayAction.FlashlightOn)
        }
        if (normalized.contains("volume") && (normalized.contains("up") || normalized.contains("increase") || normalized.contains("badha"))) return FridayResponse("Volume badha rahi hoon.", FridayAction.VolumeUp)
        if (normalized.contains("volume") && (normalized.contains("down") || normalized.contains("decrease") || normalized.contains("kam"))) return FridayResponse("Volume kam kar rahi hoon.", FridayAction.VolumeDown)

        parseYoutubeSearch(normalized)?.let { return FridayResponse("YouTube par ${it} search kar rahi hoon.", FridayAction.YouTubeSearch(it)) }
        if (normalized.contains("youtube") || normalized.contains("यूट्यूब") || normalized.contains("यूट्यूब")) return FridayResponse("YouTube khol rahi hoon.", FridayAction.YouTube)
        if (normalized.contains("calculator") || normalized.contains("कैलकुलेटर")) return FridayResponse("Calculator khol rahi hoon.", FridayAction.Calculator)
        if (normalized.contains("settings") || normalized.contains("सेटिंग")) return FridayResponse("Settings khol rahi hoon.", FridayAction.Settings)
        if (normalized.contains("camera") || normalized.contains("कैमरा")) return FridayResponse("Camera khol rahi hoon.", FridayAction.Camera)
        if (normalized.contains("chrome")) return FridayResponse("Chrome khol rahi hoon.", FridayAction.Chrome)
        if (normalized.contains("whatsapp")) return FridayResponse("WhatsApp khol rahi hoon.", FridayAction.WhatsApp)
        if (normalized.contains("instagram")) return FridayResponse("Instagram khol rahi hoon.", FridayAction.Instagram)
        if (isMessagesAppCommand(normalized)) return FridayResponse("Messages khol rahi hoon.", FridayAction.Messages)

        parseMap(normalized)?.let { return FridayResponse(if (it.second) "Maps mein route khol rahi hoon." else "Maps mein location dikha rahi hoon.", FridayAction.MapQuery(it.first, it.second)) }
        parseCall(normalized)?.let { target ->
            val compact = target.filter { it.isDigit() || it == '+' }
            val isNumber = compact.length in 7..15 && target.all { it.isDigit() || it == '+' || it == ' ' || it == '-' }
            val action = if (isNumber) FridayAction.DialNumber(compact) else FridayAction.DialContact(target)
            return FridayResponse("${target.trim()} ko call kar rahi hoon.", action, needsConfirmation = false)
        }
        parseSms(normalized, raw)?.let { (name, message) -> return FridayResponse("${name.trim()} ko message bhejne ke liye confirmation chahiye.", FridayAction.SmsContact(name.trim(), message), needsConfirmation = true) }
        return FridayResponse("", handledLocally = false)
    }

    private fun parseAccessibilityCommand(c: String): FridayResponse? {
        val normalized = c.trim().replace(Regex("\\s+"), " ")
        val command = when {
            normalized in setOf("go home", "home", "ghar jao", "home jao", "होम जाओ") -> "home"
            normalized in setOf("go back", "back", "peeche jao", "वापस जाओ") -> "back"
            normalized in setOf("open recents", "recent apps", "recent kholo", "recent apps kholo", "रीसेंट खोलो") -> "recents"
            normalized in setOf("open notifications", "notifications kholo", "notification kholo", "नोटिफिकेशन खोलो") -> "notifications"
            normalized.startsWith("click_id ") && normalized.length > 9 -> "click_id ${normalized.substringAfter("click_id ").trim()}"
            normalized.startsWith("click ") && normalized.length > 6 -> "click:${normalized.substringAfter("click ").trim()}"
            normalized.startsWith("type ") && normalized.length > 5 -> "type ${normalized.substringAfter("type ").trim()}"
            normalized.startsWith("tap ") && normalized.length > 4 -> "tap ${normalized.substringAfter("tap ").trim()}"
            normalized == "scroll down" || normalized == "scroll" || normalized == "neeche scroll karo" -> "scroll down"
            normalized.startsWith("wait_click_type|") -> normalized
            else -> return null
        }
        return FridayResponse("Visible UI control execute kar rahi hoon.", FridayAction.AccessibilityCommand(command), needsConfirmation = false)
    }

    private fun isGreeting(c: String) = c == "hello" || c == "hello friday" || c == "hi friday" || c == "namaste" || c == "नमस्ते"

    private fun parseMood(c: String): FridayResponse? {
        val bad = listOf("mood kharab", "mood off", "mood is bad", "feeling bad", "feeling low", "sad hoon", "dukhi hoon", "मन खराब", "मूड खराब", "मूड ऑफ", "उदास हूं", "उदास हूँ")
        val good = listOf("mood acha", "mood accha", "mood good", "happy hoon", "khush hoon", "मूड अच्छा", "मूड अच्छा है", "खुश हूं", "खुश हूँ")
        return when {
            bad.any { c.contains(it) } -> FridayResponse("Boss, kya hua? Main yahin hoon. Aap chahein toh mujhe bata sakte hain — main sun rahi hoon.")
            good.any { c.contains(it) } -> FridayResponse("Ye sunke achha laga Boss 😄 Bataiye, aaj kya karna hai?")
            else -> null
        }
    }

    private fun isEmergencySosCommand(c: String): Boolean {
        val normalized = c.trim().replace(Regex("\\s+"), " ")
        return normalized in setOf("sos", "emergency", "emergency help", "emergency call", "emergency call 112", "call emergency", "call 112", "112 call karo", "112 dial karo", "sos call karo", "emergency number dial karo", "आपातकाल", "आपातकालीन मदद")
    }

    private fun isMessagesAppCommand(c: String): Boolean {
        val normalized = c.trim().replace(Regex("\\s+"), " ")
        return normalized in setOf("messages", "message app", "open messages", "open message app", "open the messages app", "open the message app", "messages app kholo", "message app kholo", "messages kholo", "messages khol do", "message app khol do", "messages खोलो", "मैसेज खोलो")
    }

    private fun parseDirectWebSearch(c: String): String? {
        val match = Regex(
            "^(?:search|google search|google par search|internet par search|web par search)\\s+(.+)$",
            RegexOption.IGNORE_CASE
        ).find(c) ?: return null
        return match.groupValues[1]
            .trim()
            .replace(Regex("\\s+(?:karo|kar|please|do)$", RegexOption.IGNORE_CASE), "")
            .trim()
            .takeIf { it.isNotBlank() }
    }

    private fun parseYoutubeSearch(c: String): String? {
        if (!c.contains("youtube")) return null
        val direct = Regex("youtube(?:\\s+(?:par|pe|mein|me))?\\s+(?:search|find|khojo|khoj|dhundo|dhoondo|play|chalao|for)\\s+(.+)$", RegexOption.IGNORE_CASE).find(c)
        val openThenSearch = Regex("youtube\\s+(?:khol(?:o|kar|ke)?|open(?:ing)?|launch)\\s+(?:par\\s+)?(?:search|find|khojo|khoj|dhundo|dhoondo)\\s+(.+)$", RegexOption.IGNORE_CASE).find(c)
        val reverse = Regex("(?:search|find|khojo|khoj|dhundo|dhoondo)\\s+(.+?)\\s+(?:on|in|par)\\s+youtube$", RegexOption.IGNORE_CASE).find(c)
        val query = direct?.groupValues?.get(1) ?: openThenSearch?.groupValues?.get(1) ?: reverse?.groupValues?.get(1) ?: return null
        return query.trim().replace(Regex("\\s+(?:karo|kar|please|do)$", RegexOption.IGNORE_CASE), "").trim().takeIf { it.isNotBlank() }
    }

    private fun parseAppThenSearch(raw: String): FridayResponse? {
        val source = raw.trim().replace(Regex("^\\s*(?:hey\\s+)?friday\\b\\s*", RegexOption.IGNORE_CASE), "").trim()
        val match = Regex(
            "^(?:open|launch|start|khol(?:o|kar|ke)?|kholo)\\s+(youtube|spotify|chrome|google|instagram)\\s+(?:and|aur|then|phir|fir)\\s+(?:search|find|khojo|dhundo|dhoondo)\\s+(.+)$",
            RegexOption.IGNORE_CASE
        ).find(source) ?: return null
        val app = match.groupValues[1].lowercase(Locale.ROOT)
        val query = match.groupValues[2].trim()
            .replace(Regex("\\s+(?:karo|kar|please|do)$", RegexOption.IGNORE_CASE), "")
            .trim()
            .takeIf { it.isNotBlank() } ?: return null
        return when (app) {
            "youtube" -> FridayResponse("YouTube par $query search kar rahi hoon.", FridayAction.YouTubeSearch(query))
            "spotify" -> FridayResponse("Spotify par $query search kar rahi hoon.", FridayAction.SpotifySearch(query))
            "chrome", "google" -> FridayResponse("Google par $query search kar rahi hoon.", FridayAction.OpenApp("__web_search__:$query", "Web search"))
            "instagram" -> FridayResponse("Instagram khol rahi hoon. Search ke liye Accessibility control chahiye.", FridayAction.Sequence(listOf(FridayAction.Instagram, FridayAction.AccessibilityCommand("click Search"))), needsConfirmation = true)
            else -> null
        }
    }

    private fun parseWhatsappQuickMessage(raw: String): Pair<String, String>? {
        val source = raw.trim()
            .replace(Regex("^\\s*(?:hey\\s+)?friday\\b\\s*", RegexOption.IGNORE_CASE), "")
            .trim()
        val patterns = listOf(
            Regex("^(?:open|launch|start|khol(?:o|kar|ke)?|kholo)\\s+whatsapp\\s+(?:and|aur|then|phir|fir)\\s+(?:say|tell|bolo|bolna)\\s+(?:hi|hello|hey)\\s+(?:to|ko)\\s+([\\p{L}\\p{M}][\\p{L}\\p{M} ]{0,30})$", RegexOption.IGNORE_CASE),
            Regex("^whatsapp\\s+(?:khol(?:o|kar|ke)?|kholo|open)\\s+(?:and|aur|then|phir|fir)\\s+([\\p{L}\\p{M}][\\p{L}\\p{M} ]{0,30}?)\\s+(?:ko|to)\\s+(?:say|tell|bolo|bolna)\\s+(hi|hello|hey)$", RegexOption.IGNORE_CASE),
            Regex("^(?:open|launch|start|khol(?:o|kar|ke)?|kholo)\\s+whatsapp\\s+(?:and|aur|then|phir|fir)\\s+([\\p{L}\\p{M}][\\p{L}\\p{M} ]{0,40}?)\\s+(?:ko|to)\\s+(hi|hello|hey)\\s+(?:bolo|bolna|bhejo|bhej do)$", RegexOption.IGNORE_CASE),
            Regex("^(?:open|launch|start|khol(?:o|kar|ke)?|kholo)\\s+whatsapp\\s+(?:and|aur|then|phir|fir)\\s+([\\p{L}\\p{M}][\\p{L}\\p{M} ]{0,40}?)\\s+(?:ko|to)\\s+(?:bolo|bolna|bhejo|bhej do)\\s+(.+)$", RegexOption.IGNORE_CASE)
        )
        return patterns.firstNotNullOfOrNull { match ->
            val g = match.find(source)?.groupValues ?: return@firstNotNullOfOrNull null
            if (g.size >= 3 && g[2].isNotBlank()) g[1].trim() to g[2].trim()
            else if (g.size >= 2) g[1].trim() to "hi"
            else null
        }?.takeIf { it.first.isNotBlank() }
    }

    private fun parseWhatsappMessage(raw: String): Pair<String, String>? {
        val source = raw.trim().replace(Regex("^\\s*(?:hey\\s+)?friday\\b\\s*", RegexOption.IGNORE_CASE), "").trim()
        val patterns = listOf(
            Regex("^(?:open|launch|start|khol(?:o|kar|ke)?|kholo)\\s+whatsapp\\s+(?:and|aur|then|phir|fir)\\s+([\\p{L}\\p{M}][\\p{L}\\p{M} ]{0,40}?)\\s+(?:ko|to)\\s+(?:message|msg|text|bolo|bolna|bhejo|bhej do)\\s*(?::|,|-|\\s+ki\\s+|\\s+that\\s+)?(.+)$", RegexOption.IGNORE_CASE),
            Regex("^(?:whatsapp)(?:\\s+(?:par|pe|mein|me))?\\s+(?:message|msg|text|sms)\\s+(?:karo|kar|send|bhejo|bhej do)?\\s*(?:to|ko|mein|par|pe)?\\s*([\\p{L}\\p{M}][\\p{L}\\p{M} ]{0,40}?)\\s+(?:ki|that|message|text|bolo|bolna)\\s+(.+)$", RegexOption.IGNORE_CASE),
            Regex("^(?:message|msg|text)\\s+(?:on\\s+)?whatsapp\\s+(?:to|ko)\\s+([\\p{L}\\p{M}][\\p{L}\\p{M} ]{0,30}?)\\s+(?:ki|that|message|text)\\s+(.+)$", RegexOption.IGNORE_CASE)
        )
        return patterns.firstNotNullOfOrNull { it.find(source)?.groupValues?.let { g -> g[1].trim() to g[2].trim() } }
    }

    private fun parseTimer(c: String): Int? {
        if (!c.contains("timer") && !c.contains("टाइमर")) return null
        val tokens = c.split(Regex("\\s+")).filter { it.isNotBlank() }
        val numberIndex = tokens.indexOfFirst { it.trim(',', '.', ':').toLongOrNull() != null }
        if (numberIndex < 0) return null
        val number = tokens[numberIndex].trim(',', '.', ':').toLongOrNull() ?: return null
        val unit = tokens.getOrNull(numberIndex + 1)?.trim(',', '.', ':')?.lowercase(Locale.ROOT) ?: return null
        val multiplier = when (unit) {
            "hour", "hours", "hr", "hrs", "ghanta", "ghante", "घंटा", "घंटे" -> 3600L
            "minute", "minutes", "min", "mins", "minut", "मिनट" -> 60L
            "second", "seconds", "sec", "secs", "सेकंड" -> 1L
            else -> return null
        }
        return (number * multiplier).takeIf { it in 1L..86400L }?.toInt()
    }

    private fun parseAlarm(c: String): FridayAction? {
        if (!(c.contains("alarm") || c.contains("अलार्म") || c.contains("wake me"))) return null
        val relative = Regex(
            "(?:(?:alarm|अलार्म)\\s+(?:after|in|me|mein|baad|ke baad|में|बाद)\\s*)?" +
                "(\\d+)\\s*(hour|hours|hr|hrs|h|minute|minutes|min|mins|m|second|seconds|sec|secs|s|घंटा|घंटे|मिनट|सेकंड)" +
                "\\s*(?:baad|later|mein|में|बाद|from now)?\\s*(?:alarm|अलार्म)?",
            RegexOption.IGNORE_CASE
        ).find(c)
        if (relative != null) {
            val value = relative.groupValues[1].toLongOrNull() ?: return null
            val unit = relative.groupValues[2].lowercase(Locale.ROOT)
            val multiplier = when (unit) {
                "hour", "hours", "hr", "hrs", "h", "घंटा", "घंटे" -> 3600L
                "minute", "minutes", "min", "mins", "m", "मिनट" -> 60L
                "second", "seconds", "sec", "secs", "s", "सेकंड" -> 1L
                else -> 1L
            }
            return (value * multiplier).takeIf { it in 60L..86400L }?.toInt()?.let { FridayAction.AlarmAfter(it) }
        }
        val timeMatch = Regex("(?<!\\d)(\\d{1,2})(?:[:.](\\d{1,2}))?\\s*(am|pm)?\\b").find(c) ?: return null
        var hour = timeMatch.groupValues[1].toIntOrNull() ?: return null
        val minute = timeMatch.groupValues[2].toIntOrNull() ?: 0
        val meridiem = timeMatch.groupValues[3]
        if (meridiem == "pm" && hour < 12) hour += 12
        if (meridiem == "am" && hour == 12) hour = 0
        if (meridiem.isBlank() && hour !in 0..23) return null
        return if (hour in 0..23 && minute in 0..59) FridayAction.Alarm(hour, minute) else null
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
        Regex("^(.+?)\\s+(?:ko|को)\\s+(?:call|phone|dial)(?:\\s+(?:karo|kar|please|करो|कर))?\\s*$", RegexOption.IGNORE_CASE).find(c)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotBlank() }?.let { return it }
        val afterVerb = Regex("^(?:call|phone|dial)\\s+(.+)$", RegexOption.IGNORE_CASE).find(c)?.groupValues?.get(1)?.trim()
        if (!afterVerb.isNullOrBlank()) {
            val target = afterVerb.replace(Regex("\\s+(?:karo|kar|please|करो|कर)$", RegexOption.IGNORE_CASE), "").trim()
            if (target.isNotBlank() && target.lowercase(Locale.ROOT) !in setOf("karo", "kar", "please")) return target
        }
        val beforeVerb = Regex("^(.+?)\\s+(?:call|phone|dial)(?:\\s+(?:karo|kar|please|करो|कर))?\\s*$", RegexOption.IGNORE_CASE).find(c)?.groupValues?.get(1)?.trim()
        return beforeVerb?.takeIf { it.isNotBlank() && it.lowercase(Locale.ROOT) !in setOf("karo", "kar", "please") }
    }

    private fun parseSms(c: String, raw: String): Pair<String, String>? {
        val normalizedRaw = raw.trim().replace(Regex("^\\s*(?:hey\\s+)?friday\\b\\s*", RegexOption.IGNORE_CASE), "").trim()
        val forms = listOf(
            Regex("^(?:message|text|sms|msg|send\\s+(?:a\\s+)?message|send\\s+(?:an\\s+)?sms)\\s+(?:(?:karo|kar|करो|कर))?\\s*(?:to|ko|को)\\s+([\\p{L}\\p{M}][\\p{L}\\p{M} ]{1,30}?)(?:\\s+(?:that|ki|कि|bolo|bolna|message|text)\\s+|\\s*[:,;-]\\s*)(.+)$", RegexOption.IGNORE_CASE),
            Regex("^([\\p{L}\\p{M}][\\p{L}\\p{M} ]{1,30}?)\\s+(?:ko|को)\\s+(?:message|text|sms|msg)\\s+(?:(?:karo|kar|करो|कर))?(?:\\s+(?:ki|कि|that|bolo|bolna))?\\s+(.+)$", RegexOption.IGNORE_CASE),
            Regex("^(?:message|text|sms|msg)\\s+([\\p{L}\\p{M}][\\p{L}\\p{M} ]{1,30}?)\\s*[:,;-]\\s*(.+)$", RegexOption.IGNORE_CASE)
        )
        val source = if (normalizedRaw.isNotBlank()) normalizedRaw else c
        return forms.firstNotNullOfOrNull { it.find(source)?.groupValues?.let { g -> g[1].trim() to g[2].trim() } }
    }

    private fun prettyDuration(seconds: Int): String = when {
        seconds % 3600 == 0 -> "${seconds / 3600} hour"
        seconds % 60 == 0 -> "${seconds / 60} minute"
        else -> "$seconds second"
    }
}
