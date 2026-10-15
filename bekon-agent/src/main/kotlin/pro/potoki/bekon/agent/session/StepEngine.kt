package pro.potoki.bekon.agent.session

import pro.potoki.bekon.agent.SetupStep

/**
 * Drives the frictionless onboarding checklist (Part B). Each step asks "is the
 * capability ready?" via [detector]; if not, advances [stepIndex] so the Activity can
 * show ONE big CTA ("Open settings: …") and auto-continue on resume. Pure logic, no
 * Android deps — JVM-testable.
 */
class StepEngine(
    private val detectors: Map<SetupStep, () -> Boolean>,
    initialStep: Int = 0,
) {
    var stepIndex: Int = initialStep
        private set

    /** The first step that is not yet ready, or null when everything is done. */
    fun nextMissing(from: Int = stepIndex): SetupStep? {
        val steps = SetupStep.entries
        for (i in from until steps.size) {
            val step = steps[i]
            if (step == SetupStep.COACH || step == SetupStep.DONE) continue
            if (!detectors[step]!!()) return step
        }
        return null
    }

    /** Advance to the next missing step from current position. */
    fun advance(from: Int = stepIndex): Int {
        val target = nextMissing(from) ?: return stepIndex
        stepIndex = target.ordinal
        return stepIndex
    }

    /** Re-evaluate everything (after returning from settings); step back if a dep re-blocks. */
    fun reconcile(from: Int = 0): SetupStep? {
        stepIndex = from
        val missing = nextMissing(from)
        stepIndex = missing?.ordinal ?: SetupStep.DONE.ordinal
        return missing
    }
}
