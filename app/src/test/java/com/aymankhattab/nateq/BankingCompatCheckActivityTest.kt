package com.aymankhattab.nateq

import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.aymankhattab.nateq.feature.settings.R
import com.aymankhattab.nateq.settings.banking.BankingCompatCheckActivity
import com.aymankhattab.nateq.settings.banking.IsolateCauseWizard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * اختبارات شاشة «فحص توافق التطبيقات المصرفية» والمعالج المرفق.
 *
 * **لماذا اختبارات الوحدة هنا في `:app` لا في `:feature:settings`؟**
 * لأن الشاشة ترث نمط التطبيق (`Theme.Nateq`) من مانيفست التطبيق، وحزمةُ
 * موارد `:app` وحدها التي تحوي هذا النمط؛ فاختبارها داخل `:feature`
 * يفتقر إلى النمط ويرمي `IllegalStateException` قبل أي سطرٍ من منطقنا.
 *
 * العقود المحرَسة:
 *  - **الشاشة لا تنهار على جهازٍ به أقلُّ إعدادٍ ممكن** على مصفوفة
 *    التوافق `sdk=[24,30,35,37]` — تفتح وتبنِ بنودها كلها.
 *  - **نتيجة الخطوة تبقى بعد العودة من إعدادات النظام**: المستخدم
 *    يغادر الشاشة لإعدادات النظام ثم يعود؛ فإن ضاعت نتائجه ضاع عزلُ
 *    السبب لأن كل خطوةٍ تُقارَن بخطوة الأساس.
 *  - **الختم بلا الأساس يرفض أن يخترع سبباً** ويطلب تسجيل الأساس.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 30, 35, 37])
class BankingCompatCheckActivityTest {

    private fun launch() =
        Robolectric.buildActivity(
            BankingCompatCheckActivity::class.java
        ).create().start().resume()

    @Test
    fun screenBuilds_allCheckItemsAndFirstStepOnEverySdk() {
        val controller = launch()
        val activity = controller.get()

        val items = activity.findViewById<LinearLayout>(R.id.ll_banking_items)
        val stepTitle =
            activity.findViewById<TextView>(R.id.tv_step_title)
        val stepCounter =
            activity.findViewById<TextView>(R.id.tv_step_counter)

        assertTrue(
            "لا بنود = شاشةٌ فارغة على قارئ الشاشة",
            items.childCount > 0
        )
        assertEquals(
            "يبدأ المعالج عند الخطوة 1",
            activity.getString(
                R.string.banking_wizard_step_of,
                1,
                IsolateCauseWizard.stepCount()
            ),
            stepCounter.text
        )
        assertEquals(
            activity.getString(R.string.banking_step_baseline_title),
            stepTitle.text
        )
        // قدرات ناطق الثلاث تُملأ ولا تبقى فارغة.
        val lordA11y = activity.findViewById<TextView>(R.id.tv_lord_a11y)
        assertTrue(lordA11y.text.isNotEmpty())

        controller.pause().stop().destroy()
    }

    @Test
    fun recordedResult_survivesLeavingToSystemSettingsAndBack() {
        val first = launch()
        val activity = first.get()

        // سجّل الأساس: لم يُفتح — ثم ينتقل تلقائياً للخطوة 2.
        activity.findViewById<View>(R.id.btn_record_not_opened)
            .performClick()

        // خرج المستخدم من الشاشة إلى إعدادات النظام ثم عاد.
        first.pause().stop()
        val second = Robolectric
            .buildActivity(
                BankingCompatCheckActivity::class.java
            )
            .create().start().resume()
        val reopened = second.get()

        val counter = reopened.findViewById<TextView>(R.id.tv_step_counter)
        assertEquals(
            "لا بدّ أن يبقى عند الخطوة 2 بعد العودة",
            reopened.getString(
                R.string.banking_wizard_step_of,
                2,
                IsolateCauseWizard.stepCount()
            ),
            counter.text
        )
        val results =
            reopened.findViewById<LinearLayout>(R.id.ll_wizard_results)
        assertEquals(
            "نتيجة الأساس يجب أن تُعرض بعد العودة",
            1, results.childCount
        )

        first.destroy()
        second.pause().stop().destroy()
    }

    @Test
    fun finishWithoutBaseline_refusesToInventACause() {
        val controller = launch()
        val activity = controller.get()

        activity.findViewById<View>(R.id.btn_finish_wizard).performClick()

        val summary =
            activity.findViewById<TextView>(R.id.tv_wizard_summary)
        assertEquals(
            activity.getString(R.string.banking_wizard_need_baseline),
            summary.text
        )

        controller.pause().stop().destroy()
    }

    @Test
    fun fullRunToNotificationRevocation_showsThatCause() {
        val controller = launch()
        val activity = controller.get()

        // الأساس: لم يُفتح (يتقدَّم تلقائياً إلى الخطوة 2).
        activity.findViewById<View>(R.id.btn_record_not_opened)
            .performClick()
        // الخطوة 2: سُحِب الاستماع ثم فُتح التطبيق.
        activity.findViewById<View>(R.id.btn_record_opened)
            .performClick()

        // الخطوتان 3 و4: كلتا النتيجتين «لم يُفتح» حتى لا تنقضيان
        // احتمال الاستماع.
        activity.findViewById<View>(R.id.btn_record_not_opened)
            .performClick()
        activity.findViewById<View>(R.id.btn_record_not_opened)
            .performClick()
        activity.findViewById<View>(R.id.btn_finish_wizard)
            .performClick()

        val summary =
            activity.findViewById<TextView>(R.id.tv_wizard_summary)
        assertEquals(
            activity.getString(
                R.string.banking_wizard_cause_notif_listener
            ),
            summary.text
        )

        controller.pause().stop().destroy()
    }

    @Test
    fun reset_clearsRecordedResultsAndReturnsToStepOne() {
        val controller = launch()
        val activity = controller.get()

        activity.findViewById<View>(R.id.btn_record_not_opened)
            .performClick()
        activity.findViewById<View>(R.id.btn_reset_wizard)
            .performClick()

        val counter = activity.findViewById<TextView>(R.id.tv_step_counter)
        assertEquals(
            activity.getString(
                R.string.banking_wizard_step_of,
                1,
                IsolateCauseWizard.stepCount()
            ),
            counter.text
        )
        val results =
            activity.findViewById<LinearLayout>(R.id.ll_wizard_results)
        assertEquals(0, results.childCount)
        val summary =
            activity.findViewById<TextView>(R.id.tv_wizard_summary)
        assertEquals("", summary.text)

        controller.pause().stop().destroy()
    }
}
