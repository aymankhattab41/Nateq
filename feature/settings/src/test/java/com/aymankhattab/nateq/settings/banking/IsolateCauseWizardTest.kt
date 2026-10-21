package com.aymankhattab.nateq.settings.banking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * حارسُ معالج «اعزل السبب» — العقد الحاكم: **لا سببٌ بلا قفزةٍ قبلية/بعدية
 * على نفس الخطوة، ولا خطوةُ استعادةٍ تُخرِج سبباً.**
 *
 * كل اختبارٍ هنا يحرس كسراً حقيقياً محدَّداً (ليست حالةً طبيعية
 * وحسب)، لأن الكسر المتكرَّر في تشخيصات كهذه: الاستنتاج بغياب الأساس،
 * أو القفز بالترتيب بدل المقارنة، أو الاعتماد على ترتيب الملف.
 */
class IsolateCauseWizardTest {

    private fun stepResults(vararg pairs: Pair<IsolateStep, Boolean>) =
        pairs.map { IsolateResult(it.first, it.second) }

    @Test
    fun missingBaseline_yieldsNoCauseNeverAGuess() {
        val summary = IsolateCauseWizard.summarize(
            stepResults(
                IsolateStep.DISABLE_NOTIFICATION_LISTENER to true
            )
        )
        assertEquals(IsolateCause.OTHER_APP, summary.cause)
        assertNull(summary.causeStep)
    }

    @Test
    fun baselineOpenMeansNoConflict_evenIfLaterStepsFail() {
        val summary = IsolateCauseWizard.summarize(
            stepResults(
                IsolateStep.BASELINE to true,
                IsolateStep.DISABLE_NOTIFICATION_LISTENER to false,
                IsolateStep.DISABLE_ACCESSIBILITY to false,
                IsolateStep.REVOKE_OVERLAY to false
            )
        )
        assertEquals(IsolateCause.NONE, summary.cause)
        assertEquals(IsolateStep.BASELINE, summary.causeStep)
    }

    @Test
    fun baselineClosedAndNotificationRevokedOpens_pointsToNotification() {
        val summary = IsolateCauseWizard.summarize(
            stepResults(
                IsolateStep.BASELINE to false,
                IsolateStep.DISABLE_NOTIFICATION_LISTENER to true
            )
        )
        assertEquals(
            IsolateCause.NOTIFICATION_LISTENER, summary.cause
        )
        assertEquals(
            IsolateStep.DISABLE_NOTIFICATION_LISTENER,
            summary.causeStep
        )
    }

    @Test
    fun accessibilityOpensOnlyAfterNotificationStepKeptClosed_pointsToIt() {
        val summary = IsolateCauseWizard.summarize(
            stepResults(
                IsolateStep.BASELINE to false,
                IsolateStep.DISABLE_NOTIFICATION_LISTENER to false,
                IsolateStep.DISABLE_ACCESSIBILITY to true
            )
        )
        assertEquals(IsolateCause.ACCESSIBILITY, summary.cause)
        assertEquals(
            IsolateStep.DISABLE_ACCESSIBILITY, summary.causeStep
        )
    }

    @Test
    fun overlayIsOnlyCauseWhenAllEarlierStepsStayClosed() {
        val summary = IsolateCauseWizard.summarize(
            stepResults(
                IsolateStep.BASELINE to false,
                IsolateStep.DISABLE_NOTIFICATION_LISTENER to false,
                IsolateStep.DISABLE_ACCESSIBILITY to false,
                IsolateStep.REVOKE_OVERLAY to true
            )
        )
        assertEquals(IsolateCause.OVERLAY, summary.cause)
        assertEquals(IsolateStep.REVOKE_OVERLAY, summary.causeStep)
    }

    @Test
    fun stillClosedAfterEveryRevocation_pointsOutsideLord() {
        val summary = IsolateCauseWizard.summarize(
            stepResults(
                IsolateStep.BASELINE to false,
                IsolateStep.DISABLE_NOTIFICATION_LISTENER to false,
                IsolateStep.DISABLE_ACCESSIBILITY to false,
                IsolateStep.REVOKE_OVERLAY to false,
                IsolateStep.RESTORE_SERVICES to true
            )
        )
        assertEquals(IsolateCause.OTHER_APP, summary.cause)
        assertNull(summary.causeStep)
    }

    @Test
    fun restoreStep_aloneCanNeverNameACause() {
        // خطوة الاستعادة لا تُغيّر متغيّراً؛ فإن عُيِّنت وحدها مع
        // فشل الأساس فلا يجوز اختراع سبب منها.
        val summary = IsolateCauseWizard.summarize(
            stepResults(
                IsolateStep.BASELINE to false,
                IsolateStep.RESTORE_SERVICES to true
            )
        )
        assertEquals(IsolateCause.OTHER_APP, summary.cause)
        assertTrue(
            !IsolateCauseWizard.causeSteps
                .contains(IsolateStep.RESTORE_SERVICES)
        )
    }

    @Test
    fun resultFileRoundTrip_keepsEveryRecordedStep() {
        val original = stepResults(
            IsolateStep.BASELINE to false,
            IsolateStep.DISABLE_NOTIFICATION_LISTENER to true,
            IsolateStep.DISABLE_ACCESSIBILITY to false,
            IsolateStep.REVOKE_OVERLAY to false
        )
        val text = original.joinToString("\n") {
            IsolateCauseWizard.formatResult(it.step, it.opened)
        }
        val parsed = IsolateCauseWizard.parseResults(text)
        assertEquals(original, parsed)
        assertEquals(
            original, IsolateCauseWizard.summarize(parsed).results
        )
    }

    @Test
    fun parseResults_sortsByExecutionOrder_notByFileOrder() {
        val text = listOf(
            IsolateCauseWizard.formatResult(
                IsolateStep.REVOKE_OVERLAY, false
            ),
            IsolateCauseWizard.formatResult(
                IsolateStep.BASELINE, false
            ),
            IsolateCauseWizard.formatResult(
                IsolateStep.DISABLE_NOTIFICATION_LISTENER, true
            )
        ).joinToString("\n")

        val parsed = IsolateCauseWizard.parseResults(text)

        assertEquals(
            IsolateStep.BASELINE, parsed.first().step
        )
        assertEquals(
            IsolateStep.REVOKE_OVERLAY, parsed.last().step
        )
        // والخلاصة نفسها مهما اختلّ ترتيب الملف.
        assertEquals(
            IsolateCause.NOTIFICATION_LISTENER,
            IsolateCauseWizard.summarize(parsed).cause
        )
    }

    @Test
    fun corruptLines_areSkippedInsteadOfBreakingTheSummary() {
        val text = """
            NOT_A_RESULT
            BASELINE=maybe
            =true
            BASELINE=false
            UNKNOWN_STEP=true
            DISABLE_ACCESSIBILITY=true
        """.trimIndent()

        val parsed = IsolateCauseWizard.parseResults(text)

        assertEquals(2, parsed.size)
        assertEquals(IsolateStep.BASELINE, parsed[0].step)
        assertEquals(
            IsolateCause.ACCESSIBILITY,
            IsolateCauseWizard.summarize(parsed).cause
        )
    }

    @Test
    fun eachCauseStep_hasExactlyOneFlipAwayFromBaseline() {
        val mapping = mapOf(
            IsolateStep.DISABLE_NOTIFICATION_LISTENER to
                IsolateCause.NOTIFICATION_LISTENER,
            IsolateStep.DISABLE_ACCESSIBILITY to
                IsolateCause.ACCESSIBILITY,
            IsolateStep.REVOKE_OVERLAY to IsolateCause.OVERLAY
        )
        assertEquals(
            "خطوات الأسباب يجب أن تطابق خريطة الفحوص",
            mapping.keys,
            IsolateCauseWizard.causeSteps.toSet() - IsolateStep.BASELINE
        )
        for ((step, cause) in mapping) {
            val summary = IsolateCauseWizard.summarize(
                stepResults(
                    IsolateStep.BASELINE to false,
                    step to true
                )
            )
            assertEquals("الخطوة $step", cause, summary.cause)
            assertEquals(step, summary.causeStep)
        }
    }

    @Test
    fun evidenceIsCompleteOnlyWhenEveryCauseStepIsRecorded() {
        val partial = stepResults(
            IsolateStep.BASELINE to false,
            IsolateStep.DISABLE_NOTIFICATION_LISTENER to true
        )
        assertFalse(IsolateCauseWizard.hasCauseEvidence(partial))

        val full = partial + stepResults(
            IsolateStep.DISABLE_ACCESSIBILITY to false,
            IsolateStep.REVOKE_OVERLAY to false
        )
        assertTrue(IsolateCauseWizard.hasCauseEvidence(full))
    }

    @Test
    fun stepCountAndFileStayInSync() {
        assertEquals(
            IsolateCauseWizard.steps.size,
            IsolateCauseWizard.stepCount()
        )
        assertEquals(
            IsolateCauseWizard.steps.size - 1,
            IsolateCauseWizard.causeSteps.size
        )
        assertNotNull(IsolateCauseWizard.RESULTS_FILE_NAME)
        for (step in IsolateCauseWizard.steps) {
            val line = IsolateCauseWizard.formatResult(step, true)
            assertEquals(
                step, IsolateCauseWizard.parseResultLine(line)?.step
            )
            assertEquals(
                true,
                IsolateCauseWizard.parseResultLine(line)?.opened
            )
        }
    }
}
