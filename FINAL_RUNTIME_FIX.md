# FRIDAY final runtime fix

This line keeps the existing architecture and reference HUD concept while fixing two concrete runtime problems:

- the foreground hands-free engine now uses a resilient continuous Android SpeechRecognizer command loop instead of depending exclusively on the bundled wake-word classifier;
- the HUD uses center-crop full-screen scaling so the 768x1376 reference composition fills modern tall phone displays, with live battery/RAM/storage/CPU/temperature and voice-state animation.

The app still uses the local command/action boundary and Gemini only for open-ended intelligence. Android permissions, Assistant role, Accessibility, and OEM background restrictions remain device-level requirements.
