package pro.potoki.bekon.agent.status

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import pro.potoki.bekon.agent.MainActivity
import pro.potoki.bekon.agent.R
import pro.potoki.bekon.agent.session.SessionService

/** Tap on the status pill: tell the agent something without leaving the current app. */
class PillMenuActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setTitle(R.string.pill_menu_title)
        val d = resources.displayMetrics.density
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((20 * d).toInt(), (12 * d).toInt(), (20 * d).toInt(), (16 * d).toInt())
        }
        fun big(text: Int, onClick: () -> Unit) = Button(this).apply {
            setText(text); minHeight = (56 * d).toInt(); textSize = 17f; isAllCaps = false
            setOnClickListener { onClick() }
            col.addView(this, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = (8 * d).toInt() })
        }
        big(R.string.pill_back_to_app) {
            UserEvents.add("back_to_app")
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
            finish()
        }
        big(R.string.pill_changed_mind) {
            clearHints()
            UserEvents.add("changed_mind")
            finish()
        }
        val goal = EditText(this).apply { setHint(R.string.pill_goal_hint); textSize = 17f; visibility = android.view.View.GONE; minLines = 2 }
        val send = Button(this).apply { setText(R.string.pill_send); minHeight = (56 * d).toInt(); isAllCaps = false; visibility = android.view.View.GONE }
        big(R.string.pill_new_goal) { goal.visibility = android.view.View.VISIBLE; send.visibility = android.view.View.VISIBLE; goal.requestFocus() }
        col.addView(goal); col.addView(send)
        send.setOnClickListener {
            val t = goal.text?.toString()?.trim().orEmpty()
            if (t.isNotEmpty()) { clearHints(); UserEvents.add("new_goal", t); finish() }
        }
        val alsoStop = CheckBox(this).apply { setText(R.string.pill_also_stop); textSize = 15f }
        big(R.string.pill_cancel_all) {
            clearHints()
            AgentStatus.clear()
            UserEvents.add("cancel_all", if (alsoStop.isChecked) "session stopped" else "session paused")
            if (alsoStop.isChecked) SessionService.stop(this) else SessionService.setPaused(true)
            finish()
        }
        col.addView(alsoStop)
        big(android.R.string.cancel) { finish() }
        setContentView(col)
    }

    private fun clearHints() {
        try { UserEvents.stopCoaching?.invoke() } catch (_: Exception) {}
        try { pro.potoki.bekon.agent.coach.CoachOverlay.clear() } catch (_: Exception) {}
        try { pro.potoki.bekon.agent.device.touch.GestureOverlay.clearCoach() } catch (_: Exception) {}
    }
}
