package com.aymankhattab.nateq.core.audio.announcement

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * يغطي فحص إذن الاستماع للإشعارات
 * [NateqNotificationListener.isPermissionGranted]
 * — قراءة «enabled_notification_listeners» من Settings.Secure والتحقق من حضور
 * مكوّن خدمتنا في القائمة الفاصلة بالنقطتين (بند A2). كانت هذه الدالة الحاسمة
 * (بوابة كل قراءة إشعارات) بلا تغطية اختبارية؛ الآن ثلاث حالات: فارغ، حاضرة،
 * وخدمة أخرى فقط — فتُعتمد الخدمة الفعلية عبر Robolectric (sdk 35).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NateqNotificationListenerPermissionTest {

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    /** القيمة الممتدة لمكوّن خدمتنا كما تُكتب في قائمة النظام. */
    private val ownComponent: String
        get() = ComponentName(
            context, NateqNotificationListener::class.java
        ).flattenToString()

    @Test
    fun `permission absent when listeners list is empty`() {
        setEnabledListeners("")
        assertFalse(NateqNotificationListener.isPermissionGranted(context))
    }

    @Test
    fun `permission absent when our component not in list`() {
        setEnabledListeners("com.other.app/com.other.app.OtherListener")
        assertFalse(NateqNotificationListener.isPermissionGranted(context))
    }

    @Test
    fun `permission granted when list contains our component`() {
        setEnabledListeners(ownComponent)
        assertTrue(NateqNotificationListener.isPermissionGranted(context))
    }

    @Test
    fun `permission granted when own component coexists with others`() {
        // القائمة بفاصل النقطتين تُسرد الخدمات الثابتة مجتمعةً — حضورنا
        // مع خدمات أخرى (قارئ شاشة/تطبيق مصدّق) يجب أن يمنح الإذن.
        setEnabledListeners(
            "com.other.app/com.other.app.OtherListener:$ownComponent"
        )
        assertTrue(NateqNotificationListener.isPermissionGranted(context))
    }

    private fun setEnabledListeners(value: String) {
        Settings.Secure.putString(
            context.contentResolver,
            "enabled_notification_listeners",
            value
        )
    }
}