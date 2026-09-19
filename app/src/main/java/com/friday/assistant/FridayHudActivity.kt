package com.friday.assistant

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Space
import android.widget.TextView
import androidx.activity.ComponentActivity
import android.graphics.drawable.GradientDrawable

/**
 * Native HUD step 1.
 *
 * This rebuilds the visible FRIDAY HUD using standard Android Views only.
 * No Canvas, custom drawing, animation, telemetry, voice, permissions or Gemini startup.
 * Build #790 proved this View hierarchy is stable on the Samsung device.
 */
class FridayHudActivity : ComponentActivity() {

    private val orange = Color.rgb(255, 137, 48)
    private val red = Color.rgb(229, 72, 78)
    private val pale = Color.rgb(244, 220, 221)
    private val panel = Color.rgb(15, 8, 10)
    private val border = Color.rgb(107, 31, 37)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildHud())
    }

    private fun buildHud(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(18))
            setBackgroundColor(Color.rgb(4, 2, 3))
            layoutParams = ViewGroup.LayoutParams(-1, -1)
        }

        val header = panelLayout()
        header.addView(label("ULTIMATE", 27f, pale, true))
        header.addView(label("FRIDAY • J.A.R.V.I.S / ULTRON CORE AI", 12f, orange, false))
        header.addView(label("FRIDAY", 34f, red, true))
        root.addView(header, matchWrap())

        root.addView(space(14))

        val statusRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val mode = panelLayout()
        mode.addView(label("MODE", 11f, red, true))
        mode.addView(label("STANDBY", 18f, orange, true))
        statusRow.addView(mode, LinearLayout.LayoutParams(0, dp(74), 1f))

        statusRow.addView(space(10))

        val health = panelLayout()
        health.addView(label("SYSTEM HEALTH", 11f, red, true))
        health.addView(label("ONLINE", 18f, orange, true))
        statusRow.addView(health, LinearLayout.LayoutParams(0, dp(74), 1f))

        root.addView(statusRow)

        root.addView(space(18))

        val core = panelLayout().apply {
            gravity = Gravity.CENTER
        }
        core.addView(label("◉", 54f, orange, true))
        core.addView(label("GEMINI CORE", 18f, pale, true))
        core.addView(label("AI ENGINE READY", 11f, orange, false))

        val coreBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 72
            progressTintList = android.content.res.ColorStateList.valueOf(orange)
            layoutParams = LinearLayout.LayoutParams(dp(190), dp(5)).apply {
                topMargin = dp(14)
            }
        }
        core.addView(coreBar)

        root.addView(core, LinearLayout.LayoutParams(-1, 0, 1f))

        root.addView(space(18))

        val bottom = panelLayout()
        bottom.addView(label("HUD STATUS", 11f, red, true))
        bottom.addView(label("VOICE • OFFLINE     GEMINI • OFFLINE", 12f, orange, false))
        bottom.addView(label("WAKE • STANDBY     SYSTEM • NOMINAL", 11f, pale, false))
        root.addView(bottom, matchWrap())

        return root
    }

    private fun panelLayout(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(dp(16), dp(12), dp(16), dp(12))
        background = GradientDrawable().apply {
            setColor(panel)
            setStroke(dp(1), border)
            cornerRadius = dp(16).toFloat()
        }
    }

    private fun label(
        textValue: String,
        size: Float,
        color: Int,
        bold: Boolean
    ): TextView = TextView(this).apply {
        text = textValue
        textSize = size
        setTextColor(color)
        typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        gravity = Gravity.CENTER
        includeFontPadding = true
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
            bottomMargin = dp(3)
        }
    }

    private fun space(value: Int): Space = Space(this).apply {
        layoutParams = LinearLayout.LayoutParams(value, value)
    }

    private fun matchWrap(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(-1, -2)

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
