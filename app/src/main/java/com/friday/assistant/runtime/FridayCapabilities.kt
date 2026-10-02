package com.friday.assistant.runtime

import android.content.Context
import com.friday.assistant.commands.FridayResponse
import java.util.Locale

class FridayCapabilities(context: Context) {
    private val appContext = context.applicationContext
    private val memory = FridayMemory(appContext)

    fun handle(input: String): FridayResponse? {
        val raw = input.trim()
        val lower = raw.lowercase(Locale.ROOT)

        parseMode(lower)?.let { mode ->
            memory.setMode(mode)
            return FridayResponse("Mode ${mode.replaceFirstChar { it.uppercase() }} active hai, Boss.")
        }
        if (lower in setOf("what mode am i in", "current mode", "mode kya hai", "kaunsa mode hai")) {
            return FridayResponse("Boss, abhi ${memory.mode()} mode active hai.")
        }

        if (lower in setOf("device status", "system status", "phone status", "system check", "status batao", "phone kaisa hai")) {
            val d = DeviceTelemetry.snapshot(appContext)
            return FridayResponse(
                "System status: battery ${d.batteryPercent} percent" +
                    if (d.charging) ", charging" else "" +
                    ", temperature ${String.format(Locale.US, "%.1f", d.batteryTempC)}°C, " +
                    "storage ${d.storageUsedGb}/${d.storageTotalGb} GB, RAM ${d.ramUsedGb}/${d.ramTotalGb} GB, " +
                    "network ${d.network}."
            )
        }

        if (lower in setOf("battery", "battery status", "battery kitni hai", "battery batao", "battery percentage")) {
            val d = DeviceTelemetry.snapshot(appContext)
            return FridayResponse("Boss, battery ${d.batteryPercent}% hai" + if (d.charging) " aur phone charging par hai." else ".")
        }

        val note = Regex("^(?:take|make|save|add)\\s+(?:a\\s+)?note\\s*(?:that|:|-)?\\s*(.+)$", RegexOption.IGNORE_CASE)
            .find(raw)?.groupValues?.getOrNull(1)?.trim()
            ?: Regex("^(?:note|yaad ke liye note)\\s*[:,-]?\\s*(.+)$", RegexOption.IGNORE_CASE)
                .find(raw)?.groupValues?.getOrNull(1)?.trim()
        if (!note.isNullOrBlank()) {
            return if (memory.addNote(note)) FridayResponse("Note save kar diya, Boss.") else FridayResponse("Boss, main is note ko safely save nahi kar paayi.")
        }

        if (lower in setOf("show my notes", "my notes", "notes dikhao", "notes batao", "mere notes")) {
            val notes = memory.notes()
            return if (notes.isEmpty()) FridayResponse("Boss, abhi koi saved notes nahi hain.")
            else FridayResponse("Aapke latest notes: " + notes.takeLast(8).joinToString(" | "))
        }

        if (lower in setOf("clear my notes", "delete my notes", "notes delete karo", "notes hatao")) {
            memory.clearNotes()
            return FridayResponse("Boss, saved notes clear kar diye.")
        }

        if (lower in setOf("forget everything", "forget my memory", "meri memory bhool jao", "sab kuch bhool jao")) {
            memory.forgetFacts()
            memory.clearConversationHistory()
            memory.clearNotes()
            return FridayResponse("Boss, persistent memory, conversation history aur notes clear kar diye.")
        }
        return null
    }

    private fun parseMode(lower: String): String? {
        val clean = lower.replace(Regex("[.!?]+$"), "").trim()
        val direct = mapOf(
            "normal" to "normal", "normal mode" to "normal", "normal mode on" to "normal",
            "driving" to "driving", "driving mode" to "driving", "drive mode" to "driving",
            "work" to "work", "work mode" to "work",
            "focus" to "focus", "focus mode" to "focus",
            "night" to "night", "night mode" to "night",
            "sleep" to "night", "sleep mode" to "night"
        )
        direct[clean]?.let { return it }
        Regex("^(?:switch to|enable|activate|set)\\s+(.+?)(?:\\s+mode)?$", RegexOption.IGNORE_CASE)
            .find(clean)?.groupValues?.getOrNull(1)?.trim()?.let {
                return when (it) {
                    "normal" -> "normal"
                    "driving", "drive" -> "driving"
                    "work" -> "work"
                    "focus" -> "focus"
                    "night", "sleep" -> "night"
                    else -> null
                }
            }
        return null
    }
}
