package pro.potoki.bekon.agent.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import pro.potoki.bekon.agent.SetupStep

class StepEngineTest {
    private fun allReady(): Map<SetupStep, () -> Boolean> = SetupStep.entries.associateWith { { true } }

    private fun notReady(vararg steps: SetupStep): Map<SetupStep, () -> Boolean> {
        val base = allReady().toMutableMap()
        steps.forEach { base[it] = { false } }
        return base
    }

    @Test
    fun everythingReadyHasNoMissingStep() {
        val e = StepEngine(allReady())
        assertNull(e.nextMissing())
    }

    @Test
    fun captureMissingNextStepIsScreen() {
        val e = StepEngine(notReady(SetupStep.SCREEN))
        assertEquals(SetupStep.SCREEN, e.nextMissing())
    }

    @Test
    fun advanceJumpsToFirstMissing() {
        val e = StepEngine(notReady(SetupStep.KEYBOARD))
        e.advance()
        assertEquals(SetupStep.KEYBOARD.ordinal, e.stepIndex)
    }

    @Test
    fun reconcileStepsBackWhenEarlierBlocked() {
        val e = StepEngine(notReady(SetupStep.GESTURES))
        val missing = e.reconcile(from = 0)
        assertEquals(SetupStep.GESTURES, missing)
        assertEquals(SetupStep.GESTURES.ordinal, e.stepIndex)
    }

    @Test
    fun reconcileClearsToDoneWhenNothingMissing() {
        val e = StepEngine(notReady())
        val missing = e.reconcile(from = SetupStep.SCREEN.ordinal)
        assertEquals(null, missing)
        assertEquals(SetupStep.DONE.ordinal, e.stepIndex)
    }
}
