package com.friday.assistant

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity

/**
 * Crash isolation step 3.
 *
 * Build #781 proved a plain TextView launcher is stable, while Build #787 exits with a
 * custom Canvas view. This step removes Canvas and immersive window operations entirely
 * and uses only standard Android Views. If stable, the crash is isolated to the Canvas/
 * graphics path rather than the Activity or application startup.
 */
class FridayHudActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(32, 32, 32, 32)
            setBackgroundColor(Color.rgb(4, 2, 3))
        }

        root.addView(TextView(this).apply {
            text = "ULTIMATE"
            textSize = 28f
            setTextColor(Color.rgb(244, 220, 221))
            gravity = Gravity.CENTER
        })

        root.addView(TextView(this).apply {
            text = "FRIDAY • J.A.R.V.I.S / ULTRON CORE AI"
            textSize = 14f
            setTextColor(Color.rgb(255, 137, 48))
            gravity = Gravity.CENTER
        })

        root.addView(TextView(this).apply {
            text = "FRIDAY"
            textSize = 38f
            setTextColor(Color.rgb(229, 72, 78))
            gravity = Gravity.CENTER
        })

        root.addView(TextView(this).apply {
            text = "GEMINI CORE\n\nSYSTEM HEALTH\nHUD DIAGNOSTIC MODE\n\nVOICE • OFFLINE   GEMINI • OFFLINE"
            textSize = 16f
            setTextColor(Color.rgb(255, 137, 48))
            gravity = Gravity.CENTER
            setPadding(0, 48, 0, 0)
        })

        setContentView(root)
    }
}
