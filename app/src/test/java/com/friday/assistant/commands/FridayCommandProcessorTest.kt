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

    @Test fun timerIsNotCapturedByTimeCommand() {
        val result = processor.process("Friday 5 minute ka timer")
        assertTrue(result.action is FridayAction.Timer)
        assertEquals(300, (result.action as FridayAction.Timer).seconds)
    }

    @Test fun hindiTimerCommandProducesSeconds() {
        val result = processor.process("5 मिनट का टाइमर")
        assertTrue(result.action is FridayAction.Timer)
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

    @Test fun relativeAlarmDoesNotBecomeFiveAm() {
        val result = processor.process("alarm after 5 minutes")
        assertTrue(result.action is FridayAction.AlarmAfter)
        assertEquals(300, (result.action as FridayAction.AlarmAfter).seconds)
    }

    @Test fun hindiRelativeAlarmIsRecognized() {
        val result = processor.process("अलार्म 10 मिनट बाद")
        assertTrue(result.action is FridayAction.AlarmAfter)
        assertEquals(600, (result.action as FridayAction.AlarmAfter).seconds)
    }

    @Test fun flashlightNaturalSpacingIsLocal() {
        val result = processor.process("Friday flash light on kar do")
        assertTrue(result.handledLocally)
        assertEquals(FridayAction.FlashlightOn, result.action)
    }

    @Test fun youtubeSearchCreatesSearchAction() {
        val result = processor.process("Friday YouTube par search Arijit Singh songs karo")
        assertTrue(result.handledLocally)
        assertEquals(FridayAction.YouTubeSearch("arijit singh songs"), result.action)
    }

    @Test fun youtubeOpenStillOpensApp() {
        val result = processor.process("Friday YouTube kholo")
        assertTrue(result.handledLocally)
        assertEquals(FridayAction.YouTube, result.action)
    }

    @Test fun calculatorIsLocal() {
        val result = processor.process("Friday calculator kholo")
        assertTrue(result.handledLocally)
        assertEquals(FridayAction.Calculator, result.action)
    }

    @Test fun callCommandRequiresConfirmationAndOpensDialerAction() {
        val result = processor.process("call Rahul")
        assertTrue(result.needsConfirmation)
        assertTrue(result.action is FridayAction.DialContact)
        assertEquals("rahul", (result.action as FridayAction.DialContact).name)
    }

    @Test fun incompleteCallCommandDoesNotInventContact() {
        val result = processor.process("call karo")
        assertFalse(result.handledLocally)
    }

    @Test fun naturalHindiCallCommandRequiresConfirmation() {
        val result = processor.process("Friday mummy ko call karo")
        assertTrue(result.needsConfirmation)
        assertTrue(result.action is FridayAction.DialContact)
        assertEquals("mummy", (result.action as FridayAction.DialContact).name)
    }

    @Test fun smsCommandRequiresConfirmationAndKeepsMessage() {
        val result = processor.process("message karo to Rahul ki main 10 minute late hoon")
        assertTrue(result.needsConfirmation)
        assertTrue(result.action is FridayAction.SmsContact)
        val sms = result.action as FridayAction.SmsContact
        assertEquals("Rahul", sms.name)
        assertEquals("main 10 minute late hoon", sms.message)
    }

    @Test fun naturalHindiSmsCommandRequiresConfirmationAndKeepsMessage() {
        val result = processor.process("Friday Rahul ko message karo ki main 10 minute late hoon")
        assertTrue(result.needsConfirmation)
        assertTrue(result.action is FridayAction.SmsContact)
        val sms = result.action as FridayAction.SmsContact
        assertEquals("Rahul", sms.name)
        assertEquals("main 10 minute late hoon", sms.message)
    }

    @Test fun sendMessageNaturalFormRequiresConfirmation() {
        val result = processor.process("send a message to Rahul that I am late")
        assertTrue(result.needsConfirmation)
        assertTrue(result.action is FridayAction.SmsContact)
        val sms = result.action as FridayAction.SmsContact
        assertEquals("Rahul", sms.name)
        assertEquals("I am late", sms.message)
    }

    @Test fun unicodeSmsContactNameIsPreserved() {
        val result = processor.process("राहुल को message करो कि मैं 10 minute late hoon")
        assertTrue(result.needsConfirmation)
        assertTrue(result.action is FridayAction.SmsContact)
        val sms = result.action as FridayAction.SmsContact
        assertEquals("राहुल", sms.name)
        assertEquals("मैं 10 minute late hoon", sms.message)
    }

    @Test fun accessibilityHomeCommandIsLocalAndGated() {
        val result = processor.process("Friday go home")
        assertTrue(result.handledLocally)
        assertTrue(result.needsConfirmation)
        assertEquals(FridayAction.AccessibilityCommand("home"), result.action)
    }

    @Test fun accessibilityClickCommandIsGated() {
        val result = processor.process("click Send")
        assertTrue(result.handledLocally)
        assertTrue(result.needsConfirmation)
        assertEquals(FridayAction.AccessibilityCommand("click:send"), result.action)
    }

    @Test fun accessibilityTapCommandIsGated() {
        val result = processor.process("tap 100 200")
        assertTrue(result.handledLocally)
        assertTrue(result.needsConfirmation)
        assertEquals(FridayAction.AccessibilityCommand("tap 100 200"), result.action)
    }

    @Test fun alarmAndSmsBecomeOneOrderedPlan() {
        val result = processor.process("alarm after 5 minutes aur Rahul ko message karo ki main late hoon")
        assertTrue(result.handledLocally)
        assertTrue(result.needsConfirmation)
        assertTrue(result.action is FridayAction.Sequence)
        val actions = (result.action as FridayAction.Sequence).actions
        assertEquals(2, actions.size)
        assertTrue(actions[0] is FridayAction.AlarmAfter)
        assertTrue(actions[1] is FridayAction.SmsContact)
    }

    @Test fun emergencyCommandRequiresConfirmation() {
        val result = processor.process("Friday call 112")
        assertTrue(result.handledLocally)
        assertTrue(result.needsConfirmation)
        assertTrue(result.action is FridayAction.EmergencySos)
    }

    @Test fun emergencyAliasesAreExplicitOnly() {
        assertTrue(processor.process("sos").action is FridayAction.EmergencySos)
        assertTrue(processor.process("emergency help").action is FridayAction.EmergencySos)
        assertFalse(processor.process("tell me about emergency procedures").handledLocally)
    }

    @Test fun compoundParserDoesNotSplitUnknownNaturalLanguage() {
        val result = processor.process("tell me something about alarm and messages")
        assertFalse(result.handledLocally)
    }

    @Test fun unknownTaskHandsOffToAi() {
        val result = processor.process("Germany shift hone ke options research karke batao")
        assertTrue(!result.handledLocally)
    }
}
