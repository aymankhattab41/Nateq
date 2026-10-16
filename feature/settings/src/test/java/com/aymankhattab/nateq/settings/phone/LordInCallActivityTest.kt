package com.aymankhattab.nateq.settings.phone

import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.aymankhattab.nateq.feature.settings.R
import com.google.android.material.button.MaterialButton
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * اختبارات واجهة شاشة إدارة المكالمات LordInCallActivity.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class LordInCallActivityTest {

    @Test
    fun inCallActivity_initialUiDefaults_hideIncomingActionsWhenNoCall() {
        val controller =
            Robolectric.buildActivity(LordInCallActivity::class.java).setup()
        val activity = controller.get()
        val llIncoming =
            activity.findViewById<View>(R.id.ll_incoming_call_actions)
        // في حالة عدم وجود مكالمة رنين،
        // الواجهة تغلق أو تختفي إجراءات الرنين
        assertEquals(View.GONE, llIncoming.visibility)
        controller.destroy()
    }

    @Test
    fun inCallActivity_answerClick_immediatelyHidesIncomingActions() {
        val controller =
            Robolectric.buildActivity(LordInCallActivity::class.java).setup()
        val activity = controller.get()
        val llIncoming =
            activity.findViewById<View>(R.id.ll_incoming_call_actions)
        val btnAnswer =
            activity.findViewById<MaterialButton>(R.id.btn_incall_answer)

        // محاكاة حالة الرنين بجعل الأزرار مرئية
        llIncoming.visibility = View.VISIBLE

        // النقر على زر الرد
        btnAnswer.performClick()

        // التأكد من الاختفاء الفوري لأزرار الرد والرفض
        assertEquals(View.GONE, llIncoming.visibility)
        controller.destroy()
    }
}
