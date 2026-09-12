# FRIDAY V5 device test plan

## Hands-free
1. Set FRIDAY as the default Android Assistant.
2. Keep the phone locked and unlocked and say `Hey Friday`.
3. Speak two commands back-to-back without touching the screen.
4. Say `stop` or `standby` to end the active conversation.

## Voice
- Test Hindi, English, and mixed Hindi/English sentences.
- Verify that English fragments are not pronounced with the Hindi voice.
- Verify that FRIDAY no longer repeats the fixed wake acknowledgement.

## AI
- Configure a Gemini API key from AI BRAIN.
- Ask a non-local question and confirm the UI reports AI BRAIN rather than LOCAL CORE.

## Messaging
- Grant Contacts permission.
- Test `Rahul ko message karo ki main 10 minute mein aa raha hoon`.
- FRIDAY should open the user-controlled SMS composer with the intended recipient/body; it must not silently send an SMS.

## Safety
- Test call/SMS confirmation and ambiguous confirmation responses.
- Confirm no action executes when policy validation rejects it.
