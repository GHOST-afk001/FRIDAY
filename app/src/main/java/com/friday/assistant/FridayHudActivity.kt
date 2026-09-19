package com.friday.assistant

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.TextView
import androidx.activity.ComponentActivity

/**
 * Minimal launcher diagnostic.
 *
 * This intentionally avoids the custom HUD, coroutines, runtime state, permissions,
 * Gemini, microphone startup and all assistant bridges. If this launches, the crash
 * is inside the richer launcher path and we can reintroduce components incrementally.
 */
class FridayHudActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(
            TextView(this).apply {
                setBackgroundColor(Color.rgb(4, 2, 3))
                setTextColor(Color.rgb(255, 137, 48))
                textSize = 24f
                text = "FRIDAY\n\nLAUNCHER SAFE MODE"
                gravity = Gravity.CENTER
            }
        )
    }
}
