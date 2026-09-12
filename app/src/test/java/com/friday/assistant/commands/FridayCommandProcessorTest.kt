package com.friday.assistant.commands

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FridayCommandProcessorTest {
    private val processor = FridayCommandProcessor()

    @Test fun timeCommandIsLocal() {
        val result = processor.process("Friday time kya hua")
        assertTrue(result.handledLocally)
        assertTrue(result.text.contains("baj rahe hain"))
    }

    @Test fun mapCommandProducesMapAction() {
        val result = processor.process("Google map par Delhi Yamuna Vihar ki location lagao")
        assertTrue(result.handledLocally)
        assertTrue(result.action is FridayAction.MapQuery)
        assertEquals("delhi yamuna vihar ki location lagao", (result.action as FridayAction.MapQuery).query)
    }

    @Test fun timerCommandProducesSeconds() {
        val result = processor.process("5 minute ka timer")
        assertEquals(300, (result.action as FridayAction.Timer).seconds)
    }

    @Test fun unknownTaskHandsOffToAi() {
        val result = processor.process("Germany shift hone ke options research karke batao")
        assertTrue(!result.handledLocally)
    }
}
