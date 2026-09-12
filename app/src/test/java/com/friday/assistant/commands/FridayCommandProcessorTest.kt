package com.friday.assistant.commands

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
        assertEquals("delhi yamuna vihar", (result.action as FridayAction.MapQuery).query)
    }

    @Test fun timerCommandProducesSeconds() {
        val result = processor.process("5 minute ka timer")
        assertEquals(300, (result.action as FridayAction.Timer).seconds)
    }

    @Test fun timerCommandRejectsOutOfRangeDuration() {
        val result = processor.process("99999 minutes ka timer")
        assertFalse(result.handledLocally)
    }

    @Test fun alarmCommandUsesOnlyTimeNotUnrelatedNumber() {
        val result = processor.process("alarm 7:30 pm")
        assertTrue(result.action is FridayAction.Alarm)
        val alarm = result.action as FridayAction.Alarm
        assertEquals(19, alarm.hour)
        assertEquals(30, alarm.minute)
    }

    @Test fun callCommandRequiresConfirmationAndOpensDialerAction() {
        val result = processor.process("call Rahul")
        assertTrue(result.needsConfirmation)
        assertTrue(result.action is FridayAction.DialContact)
        assertEquals("rahul", (result.action as FridayAction.DialContact).name)
    }

    @Test fun smsCommandRequiresConfirmationAndKeepsMessage() {
        val result = processor.process("message karo to Rahul ki main 10 minute late hoon")
        assertTrue(result.needsConfirmation)
        assertTrue(result.action is FridayAction.SmsContact)
        val sms = result.action as FridayAction.SmsContact
        assertEquals("Rahul", sms.name)
        assertEquals("main 10 minute late hoon", sms.message)
    }

    @Test fun unknownTaskHandsOffToAi() {
        val result = processor.process("Germany shift hone ke options research karke batao")
        assertTrue(!result.handledLocally)
    }
}
