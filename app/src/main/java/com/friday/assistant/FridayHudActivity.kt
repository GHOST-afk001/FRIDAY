package com.friday.assistant

import android.graphics.Color
import android.os.Bundle
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import androidx.activity.ComponentActivity

/**
 * HUD reintroduction step 1.
 *
 * The safe-mode launcher proved the Activity itself is stable. This step adds only the
 * custom reference HUD. It intentionally keeps voice, permissions, coroutines, runtime
 * state collection, Gemini, and button actions disabled so we can isolate the next layer.
 */
class FridayHudActivity : ComponentActivity() {
    private var hud: FridayReferenceHudView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enterImmersiveHud()

        runCatching {
            FridayReferenceHudView(this).also {
                hud = it
                setContentView(it)
            }
        }.onFailure {
            setContentView(android.widget.TextView(this).apply {
                setBackgroundColor(Color.rgb(4, 2, 3))
                setTextColor(Color.rgb(255, 137, 48))
                textSize = 22f
                text = "FRIDAY\\n\\nHUD LOAD FAILED"
                gravity = android.view.Gravity.CENTER
            })
        }
    }

    override fun onResume() {
        super.onResume()
        enterImmersiveHud()
    }

    private fun enterImmersiveHud() {
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            window.insetsController?.let { controller ->
                controller.hide(
                    WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars()
                )
                controller.systemBarsBehavior =
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility =
                android.view.View.SYSTEM_UI_FLAG_FULLSCREEN or
                    android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                    android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                    android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        }
    }

    override fun onDestroy() {
        hud = null
        super.onDestroy()
    }
}
