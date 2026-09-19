package com.friday.assistant

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import androidx.activity.ComponentActivity

/**
 * HUD isolation step 2.
 *
 * Build #784 showed that attaching the full live HUD still exits immediately.
 * This step keeps the custom View but removes its ticker, telemetry, API-key lookup,
 * touch actions, and complex rendering. It draws only static primitives.
 */
class FridayHudActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enterImmersiveHud()
        setContentView(StaticHudView())
    }

    private fun enterImmersiveHud() {
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            window.insetsController?.let {
                it.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                it.systemBarsBehavior =
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }
    }

    private inner class StaticHudView : View(this) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            canvas.drawColor(Color.rgb(4, 2, 3))

            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2f
            paint.color = Color.rgb(107, 31, 37)
            canvas.drawRoundRect(22f, 20f, width - 22f, 143f, 22f, 22f, paint)

            paint.style = Paint.Style.FILL
            paint.color = Color.rgb(244, 220, 221)
            paint.textSize = 22f
            paint.typeface = android.graphics.Typeface.DEFAULT_BOLD
            canvas.drawText("ULTIMATE", 58f, 68f, paint)

            paint.color = Color.rgb(255, 137, 48)
            paint.textSize = 12f
            canvas.drawText("FRIDAY • J.A.R.V.I.S / ULTRON CORE AI", 58f, 94f, paint)

            paint.color = Color.rgb(229, 72, 78)
            paint.textSize = 30f
            canvas.drawText("FRIDAY", 58f, 132f, paint)

            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 3f
            paint.color = Color.rgb(255, 137, 48)
            val cx = width / 2f
            val cy = height * 0.43f
            canvas.drawCircle(cx, cy, 92f, paint)
            canvas.drawCircle(cx, cy, 62f, paint)

            paint.style = Paint.Style.FILL
            paint.textSize = 18f
            canvas.drawText("GEMINI CORE", cx - 58f, cy + 7f, paint)

            paint.color = Color.rgb(196, 99, 105)
            paint.textSize = 12f
            canvas.drawText("SYSTEM HEALTH", 58f, height - 150f, paint)
            canvas.drawText("HUD DIAGNOSTIC MODE", 58f, height - 112f, paint)
            canvas.drawText("VOICE • OFFLINE   GEMINI • OFFLINE", 58f, height - 74f, paint)
        }
    }
}
