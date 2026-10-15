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
        val seen = Seen()
        val lock = Object()
        fun elapsed() = System.currentTimeMillis() - t0
        fun finish(s: String) { synchronized(lock) { if (status == "running") status = s; lock.notifyAll() } }
    }

    /** Everything observed while scrolling: union of items (first page seen) + compact page screenshots. */
    class Seen {
        data class Item(val label: String, val checkable: Boolean, val checked: Boolean, val selected: Boolean, val page: Int)
        val items = LinkedHashMap<String, Item>()
        val pages = ArrayList<ByteArray>()
        var pageCount = 0
        @Synchronized fun add(snapItems: List<pro.potoki.bekon.agent.core.UiNode>, shot: ByteArray?) {
            val p = pageCount++
            for (n in snapItems) {
                if (n.statusBar || n.pkg == "com.android.systemui" || n.label.isBlank() || n.label == "clickable") continue
                val k = n.label + "|" + n.checkable
                val prev = items[k]
                if (prev == null || (n.checkable && !prev.checkable)) items[k] = Item(n.label, n.checkable, n.checked, n.selected, p)
            }
            if (shot != null && pages.size < MAX_PAGES) pages += shot
        }
        @Synchronized fun empty() = pageCount == 0
    }

    fun capture(seen: Seen, items: List<pro.potoki.bekon.agent.core.UiNode>) {
        val shot = try { executor.screenshot(0.2f, 45, "jpeg").bytes } catch (_: Exception) { null }
        seen.add(items, shot)
    }

    /** Scroll a list to its end (or [max] pages) collecting every item; then back to the top if [restore]. */
    fun scanList(direction: String, max: Int, restore: Boolean): Seen {
        val seen = Seen()
        var snap = executor.snapshot()
        capture(seen, snap.items)
        val w = snap.screenW; val h = snap.screenH
        val down = direction != "up"
        var moved = 0
        for (i in 0 until max) {
            val before = CoachScript.screenKey(snap.items)
            if (down) executor.swipe(w / 2, (h * 0.72).toInt(), w / 2, (h * 0.32).toInt(), 350)
            else executor.swipe(w / 2, (h * 0.32).toInt(), w / 2, (h * 0.72).toInt(), 350)
            Thread.sleep(700)
            snap = executor.snapshot()
            if (CoachScript.screenKey(snap.items) == before) break
            moved++
            capture(seen, snap.items)
        }
        if (restore) repeat(moved) {
            if (down) executor.swipe(w / 2, (h * 0.32).toInt(), w / 2, (h * 0.72).toInt(), 250)
            else executor.swipe(w / 2, (h * 0.72).toInt(), w / 2, (h * 0.32).toInt(), 250)
            Thread.sleep(350)
        }
        return seen
    }

    @Volatile var run: Run? = null; private set
    companion object { const val MAX_ACTIONS = 40; const val MAX_SCROLLS = 16; const val MAX_PAGES = 10 }

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
        val usedOnce = HashSet<String>()
        var scrolls = 0; var flipped = false
        // Human-scroll watch for arrow hints: a scroll event that leaves the screen unchanged = end of list.
        var hintKey: Int? = null; var hintSeq = executor.changeSignal()?.seq ?: 0L; var endOfList = false
        try {
            while (r.status == "running") {
                if (r.elapsed() >= r.timeoutMs) { r.finish("timeout"); break }
                val snap = executor.snapshot()
                val pkg = executor.foreground()["package"]
                val f = CoachScript.evaluate(r.rules.filter { !(it.once && it.id in usedOnce) }, snap.items, pkg, snap.screenW, snap.screenH)
                val id = f?.rule?.id
                if (id != r.current) { r.current = id; if (id != null) r.events += Event(id, r.elapsed(), pkg) }
                var fd = f
                val isArrow = f?.rule?.draw?.type?.startsWith("arrow_") == true && f.rule.action == null
                if (isArrow) {
                    val k = CoachScript.screenKey(snap.items)
                    val sig = executor.changeSignal()
                    if (hintKey == null || hintKey != k) { if (hintKey != null && hintKey != k) endOfList = false; hintKey = k; hintSeq = sig?.seq ?: 0L }
                    else if (sig != null && sig.seq != hintSeq && sig.lastReason.contains("SCROLLED")) {
                        if (!endOfList) { endOfList = true; r.events += Event("${f!!.rule.id}:end_of_list", r.elapsed(), pkg) }
                        hintSeq = sig.seq
                    }
                    if (endOfList) {
                        val d = f!!.rule.draw!!
                        fd = CoachScript.Fired(f.rule, CoachScript.backHint(CoachScript.Draw("back_hint", null, "Здесь этого нет — нажмите Назад", d.color), snap.items, snap.screenW, snap.screenH), null)
                    }
                } else { hintKey = null; endOfList = false }
                val key = fd?.let { "${it.rule.id}|${it.targetBounds?.joinToString(",")}|$endOfList" }
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
                    val keyBefore = CoachScript.screenKey(snap.items)
                    if (a.type.startsWith("scroll_") && r.seen.empty()) capture(r.seen, snap.items)
                    val eff = if (a.type.startsWith("scroll_") && flipped) CoachScript.Action(if (a.type == "scroll_down") "scroll_up" else "scroll_down", a.target, a.settleMs) else a
                    act(eff, f, snap.screenW, snap.screenH)
                    if (f.rule.once) usedOnce += f.rule.id
                    if (a.type.startsWith("scroll_")) {
                        if (++scrolls > MAX_SCROLLS) throw IllegalStateException("target not found on this screen: $MAX_SCROLLS scrolls without a match (rule '${f.rule.id}')")
                        Thread.sleep(minOf(a.settleMs, 700))
                        val afterSnap = try { executor.snapshot() } catch (_: Exception) { null }
                        val after = afterSnap?.let { CoachScript.screenKey(it.items) }
                        if (afterSnap != null && after != keyBefore) capture(r.seen, afterSnap.items)
                        if (after == keyBefore) {
                            // End of list: try the other direction once, then give up with a clear reason.
                            if (!flipped) { flipped = true; r.events += Event("${f.rule.id}:end_of_list->reverse", r.elapsed(), pkg) }
                            else throw IllegalStateException("target not found on this screen: reached the end of the list in both directions (rule '${f.rule.id}'). Pick another path (Back, another menu, search).")
                        }
                    }
                    r.events += Event("${f.rule.id}:${eff.type}", r.elapsed(), pkg)
                    lastKey = null
                    synchronized(r.lock) { if (r.status == "running") r.lock.wait(a.settleMs) }
                    continue
                }
                if (key != lastKey) {
                    lastKey = key
                    if (fd == null || fd.shapes.isEmpty()) coach?.clearScreen() else coach?.drawOnScreen(fd.shapes, false, 0)
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
