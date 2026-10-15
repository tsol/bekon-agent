package pro.potoki.bekon.agent.mcp

import pro.potoki.bekon.agent.coach.CoachScript
import pro.potoki.bekon.agent.coach.CoachSurface
import pro.potoki.bekon.agent.core.ActionExecutor

/** Runs one CoachScript at a time on a background thread (phone-local loop, no agent round-trip). */
class CoachScriptRunner(private val executor: ActionExecutor, private val coach: CoachSurface?) {
    data class Event(val ruleId: String, val atMs: Long, val pkg: String?)
    class Run(val rules: List<CoachScript.Rule>, val timeoutMs: Long, val intervalMs: Long) {
        val t0 = System.currentTimeMillis()
        @Volatile var status = "running" // running | done | timeout | stopped | error
        @Volatile var error: String? = null
        @Volatile var current: String? = null
        @Volatile var doneRule: String? = null
        @Volatile var actions = 0
        val events = java.util.Collections.synchronizedList(ArrayList<Event>())
        val lock = Object()
        fun elapsed() = System.currentTimeMillis() - t0
        fun finish(s: String) { synchronized(lock) { if (status == "running") status = s; lock.notifyAll() } }
    }

    @Volatile var run: Run? = null; private set
    companion object { const val MAX_ACTIONS = 40 }

    fun start(rules: List<CoachScript.Rule>, timeoutMs: Long, intervalMs: Long): Run {
        stop()
        val r = Run(rules, timeoutMs, intervalMs)
        run = r
        Thread({ loop(r) }, "coach-script").apply { isDaemon = true }.start()
        return r
    }

    fun stop() { run?.let { it.finish("stopped"); try { coach?.clearScreen() } catch (_: Exception) {} } }

    private fun act(a: CoachScript.Action, f: CoachScript.Fired, w: Int, h: Int) {
        when (a.type) {
            "tap" -> {
                val b = f.targetBounds ?: throw IllegalStateException("tap: target of rule '${f.rule.id}' not on screen")
                executor.tap((b[0] + b[2]) / 2, (b[1] + b[3]) / 2)
            }
            "scroll_down" -> executor.swipe(w / 2, (h * 0.72).toInt(), w / 2, (h * 0.32).toInt(), 350)
            "scroll_up" -> executor.swipe(w / 2, (h * 0.32).toInt(), w / 2, (h * 0.72).toInt(), 350)
            "back", "home" -> executor.nav(a.type)
        }
    }

    private fun loop(r: Run) {
        var lastKey: String? = null
        var lastAct: String? = null; var sameAct = 0
        try {
            while (r.status == "running") {
                if (r.elapsed() >= r.timeoutMs) { r.finish("timeout"); break }
                val snap = executor.snapshot()
                val pkg = executor.foreground()["package"]
                val f = CoachScript.evaluate(r.rules, snap.items, pkg, snap.screenW, snap.screenH)
                val id = f?.rule?.id
                if (id != r.current) { r.current = id; if (id != null) r.events += Event(id, r.elapsed(), pkg) }
                val key = f?.let { "${it.rule.id}|${it.targetBounds?.joinToString(",")}" }
                if (f != null && f.rule.done) {
                    r.doneRule = f.rule.id
                    if (f.shapes.isNotEmpty()) coach?.drawOnScreen(f.shapes, false, 1500) else coach?.clearScreen()
                    r.finish("done"); break
                }
                val a = f?.rule?.action
                if (f != null && a != null) {
                    if (++r.actions > MAX_ACTIONS) throw IllegalStateException("more than $MAX_ACTIONS actions; script looks stuck at rule '${f.rule.id}'")
                    val sig = "${f.rule.id}|${f.targetBounds?.joinToString(",")}|${CoachScript.screenKey(snap.items)}"
                    sameAct = if (sig == lastAct) sameAct + 1 else 0; lastAct = sig
                    if (a.type == "tap" && sameAct >= 2) throw IllegalStateException("rule '${f.rule.id}' tapped the same node 3x and the screen did not change")
                    act(a, f, snap.screenW, snap.screenH)
                    r.events += Event("${f.rule.id}:${a.type}", r.elapsed(), pkg)
                    lastKey = null
                    synchronized(r.lock) { if (r.status == "running") r.lock.wait(a.settleMs) }
                    continue
                }
                if (key != lastKey) {
                    lastKey = key
                    if (f == null || f.shapes.isEmpty()) coach?.clearScreen() else coach?.drawOnScreen(f.shapes, false, 0)
                }
                synchronized(r.lock) { if (r.status == "running") r.lock.wait(r.intervalMs) }
            }
        } catch (e: Exception) {
            r.error = e.message ?: e.javaClass.simpleName
            r.finish("error")
        }
        if (r.status != "done") try { coach?.clearScreen() } catch (_: Exception) {}
    }

    /** Block up to [timeoutMs] for the script to end or (if [untilRule]) a rule change. */
    fun await(r: Run, timeoutMs: Long, onRuleChange: Boolean): Unit {
        val end = System.currentTimeMillis() + timeoutMs
        val startEvents = r.events.size
        synchronized(r.lock) {
            while (r.status == "running" && System.currentTimeMillis() < end && !(onRuleChange && r.events.size > startEvents)) {
                r.lock.wait(minOf(100L, end - System.currentTimeMillis()).coerceAtLeast(1))
            }
        }
    }
}
