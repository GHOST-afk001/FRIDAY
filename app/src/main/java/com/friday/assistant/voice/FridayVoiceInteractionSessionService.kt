package com.friday.assistant.voice

import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService

/** Creates the lightweight voice session used after the local wake detector accepts a wake word. */
class FridayVoiceInteractionSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: android.os.Bundle?): VoiceInteractionSession {
        return FridayVoiceInteractionSession(this)
    }
}
