package com.friday.assistant.commands

import java.text.DateFormat
import java.util.Date
import java.util.Locale

/** Small offline command router; future AI/chat commands can be added here without changing the UI. */
class FridayCommandProcessor {
    fun process(input: String): FridayResponse {
        val command = input.trim().lowercase(Locale.ROOT)
        return when {
            command.contains("hello friday") || command == "hello" || command.contains("नमस्ते") ->
                FridayResponse("Hello. Main Friday hoon. Bataiye.")
            command.contains("what time") || command.contains("time is it") || command.contains("समय") || command.contains("टाइम") ->
                FridayResponse("Abhi ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date())} baj rahe hain.")
            command.contains("today's date") || command.contains("todays date") || command.contains("what is the date") || command.contains("तारीख") || command.contains("डेट") ->
                FridayResponse("Aaj ${DateFormat.getDateInstance(DateFormat.LONG).format(Date())} hai.")
            command.contains("open youtube") || command.contains("youtube kholo") || command.contains("यूट्यूब") ->
                FridayResponse("YouTube khol rahi hoon.", FridayAction.YOUTUBE)
            command.contains("open calculator") || command.contains("calculator kholo") || command.contains("कैलकुलेटर") ->
                FridayResponse("Calculator khol rahi hoon.", FridayAction.CALCULATOR)
            command.contains("open settings") || command.contains("settings kholo") || command.contains("सेटिंग") ->
                FridayResponse("Settings khol rahi hoon.", FridayAction.SETTINGS)
            command.contains("open camera") || command.contains("camera kholo") || command.contains("कैमरा") ->
                FridayResponse("Camera khol rahi hoon.", FridayAction.CAMERA)
            command.contains("who are you") || command.contains("tum kaun") || command.contains("आप कौन") ->
                FridayResponse("Main Friday hoon, aapki personal Android assistant.")
            command.contains("good morning") || command.contains("सुप्रभात") ->
                FridayResponse("Good morning. Aaj ka din shandaar banate hain.")
            command.contains("good night") || command.contains("शुभ रात्रि") ->
                FridayResponse("Good night. Aaraam se soyiye, main yahin hoon.")
            else -> FridayResponse("Mujhe abhi sirf basic offline commands aate hain. Aap hello, time, date, ya kisi app ko open karne ke liye keh sakte hain.")
        }
    }
}
