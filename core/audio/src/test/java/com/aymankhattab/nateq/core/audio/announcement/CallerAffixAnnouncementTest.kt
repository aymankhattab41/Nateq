package com.aymankhattab.nateq.core.audio.announcement

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.aymankhattab.nateq.core.data.SettingsRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * حارس جملتَي «قبل» و«بعد» بعد حذف القالب الواحد وعبارة «اتصال وارد».
 *
 * الطلبُ الحاكم: مربعٌ قبل ومربعٌ بعد، والمستخدم هو مَن يحدّد ماذا
 * يُنطق في كلٍّ منهما — فالهوية (الاسم/الرقم) تبقى الوسط، والجملتان
 * زائدتان يبتلعهما المربّعان. ولا تفرض التطبيقُ جملةً افتراضيةً واحدة.
 *
 * كلُّ اسمٍ هنا = الكسرُ الذي يحرسه:
 * - `prefix and suffix surround the identity`.
 * - `a box with no text is ignored` (لا جملةَ فارغةَ تسبق الهوية).
 * - `placeholders` (المستخدم قد يكتب {name} فيحصل على الاسم).
 * - `no identity` و`privacy locked` (لا نصَّ بلا هوية).
 * - `the saved name is spoken alone` و`no default phrase is ever spoken`
 *   (حذفُ «اتصال وارد» نهائياً).
 * - `an unsaved number keeps its description` («رقم غير محفوظ» معلومةٌ
 *   لا عبارةٌ افتراضية، فبقيت بقرار المدير).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class CallerAffixAnnouncementTest {

    private val context: Context
        get() = ApplicationProvider.getApplicationContext<Context>()

    /**
     * مخزنٌ نظيف لكل اختبار: Robolectric يُبقي SharedPreferences بين
     * اختبارات الصفّ الواحد، فمربّعٌ مفعّلٌ في اختبارٍ يسرّب نفسه إلى
     * التالي فيفشل اختبارُ «بلا مربّعين» وهو يقرأ إعدادات غيره.
     */
    private fun settings(): SettingsRepository {
        context.getSharedPreferences("nateq_settings", Context.MODE_PRIVATE)
            .edit().clear().commit()
        return SettingsRepository.create(context)
    }

    /** يستدعي التركيب الخاصّ (private) مثل مسارات النطق الحقيقية. */
    private fun announce(
        settings: SettingsRepository,
        number: String? = "0501234567",
        contactName: String? = null,
        privacyLocked: Boolean = false
    ): String {
        val method = CallerAnnouncementReceiver::class.java
            .getDeclaredMethod(
                "buildAnnouncementText",
                Context::class.java,
                String::class.java,
                String::class.java,
                SettingsRepository::class.java,
                Boolean::class.javaPrimitiveType,
                Int::class.javaPrimitiveType
            )
        method.isAccessible = true
        return method.invoke(
            CallerAnnouncementReceiver(),
            context, number, contactName, settings, privacyLocked, 1
        ) as String
    }

    @Test
    fun `both boxes off leave the identity alone`() {
        val repo = settings()
        // بلا مربّعين يُنطق الاسمُ وحده: **لا عبارةَ افتراضيةَ قبله**
        // («اتصال وارد» حُذفت نهائياً).
        assertEquals(
            "بلا مربّعين يُنطق الاسم وحده",
            "سالم",
            announce(repo, contactName = "سالم")
        )
    }

    @Test
    fun `no default phrase is ever spoken`() {
        // **حارسُ الحذف النهائي:** لا «اتصال وارد» ولا «وارد» في نصّ
        // الإعلان مهما كانت الإعدادات — فعبارةٌ تفرضها التطبيق تناقضُ
        // اختيارَ المستخدم وتُنطق مرّتين إن كتبها في مربّعه.
        val repo = settings()
        repo.setCallerPrefixEnabled(true)
        repo.setCallerPrefixText("انتبه")
        repo.setCallerSuffixEnabled(true)
        repo.setCallerSuffixText("على الخط")
        val withName = announce(repo, contactName = "سالم")
        assertTrue(
            "بلا «اتصال وارد» مع اسم: $withName",
            !withName.contains("اتصال وارد")
        )
        val withNumber = announce(repo, contactName = null)
        assertTrue(
            "بلا «اتصال وارد» مع رقم: $withNumber",
            !withNumber.contains("اتصال وارد")
        )
    }

    @Test
    fun `the saved name is spoken alone`() {
        assertEquals(
            "الاسم المحفوظ يعرّف بنفسه",
            "سالم",
            announce(settings(), contactName = "سالم")
        )
    }

    @Test
    fun `an unsaved number keeps its description`() {
        // **بقيت «رقم غير محفوظ» بقرار المدير:** هي معلومةٌ (المتصلُ غير
        // محفوظ) لا جملةٌ افتراضية، ومنعها يفقد المستخدمَ تمييزَ الرقمِ
        // المقروء عن اسمٍ محفوظ.
        val spoken = announce(settings(), contactName = null)
        assertTrue(
            "وصفُ الرقم يبقى: $spoken",
            spoken.startsWith("رقم غير محفوظ")
        )
        // والرقمُ يُنطق بصيغته المنطوقة (خاناتٍ خانةً، لا نصاً حرفياً).
        assertTrue(
            "ثم يُنطق الرقم خانةً خانة: $spoken",
            spoken.contains("صِفْرْ") && spoken.contains("سبعة")
        )
        assertTrue(
            "ولا يظهر الرقم مجرّداً إلى جانب الوصف: $spoken",
            !spoken.contains("0501234567")
        )
    }

    @Test
    fun `prefix and suffix surround the identity`() {
        val repo = settings()
        val identity = announce(repo, contactName = "سالم")
        repo.setCallerPrefixEnabled(true)
        repo.setCallerPrefixText("  انتبه  ")
        repo.setCallerSuffixEnabled(true)
        repo.setCallerSuffixText("  على الخط  ")
        assertEquals(
            "الوسط هو الهوية والجملتان حوله",
            "انتبه " + identity + " على الخط",
            announce(repo, contactName = "سالم")
        )
    }

    @Test
    fun `one box alone affects only its own side`() {
        val identity = announce(settings(), contactName = "سالم")
        val prefixOnly = settings()
        prefixOnly.setCallerPrefixEnabled(true)
        prefixOnly.setCallerPrefixText("انتبه")
        assertEquals(
            "المربّع الأول لا يفتح ما بعده",
            "انتبه " + identity,
            announce(prefixOnly, contactName = "سالم")
        )
        val suffixOnly = settings()
        suffixOnly.setCallerSuffixEnabled(true)
        suffixOnly.setCallerSuffixText("على الخط")
        assertEquals(
            "والمربّع الثاني لا يفتح ما قبله",
            identity + " على الخط",
            announce(suffixOnly, contactName = "سالم")
        )
    }

    @Test
    fun `a box with no text is ignored`() {
        val repo = settings()
        val identity = announce(repo, contactName = "سالم")
        repo.setCallerPrefixEnabled(true)
        repo.setCallerPrefixText("   ")
        repo.setCallerSuffixEnabled(true)
        repo.setCallerSuffixText("")
        assertEquals(
            "مربّعٌ على حقلٍ فارغ لا ينطق فراغاً قبل الاسم",
            identity,
            announce(repo, contactName = "سالم")
        )
    }

    @Test
    fun `text is ignored while its box is off`() {
        val repo = settings()
        val identity = announce(repo, contactName = "سالم")
        // الحقل ممتلئ والمربع مطفي: لا يُنطق ما لم يطلبه المربع.
        repo.setCallerPrefixText("انتبه")
        repo.setCallerSuffixText("على الخط")
        assertEquals(
            "نصٌّ بلا مربّع لا يُنطق",
            identity,
            announce(repo, contactName = "سالم")
        )
    }

    @Test
    fun `placeholders in the user text resolve to the identity`() {
        val repo = settings()
        repo.setCallerPrefixEnabled(true)
        repo.setCallerPrefixText("اتصال من {name}")
        repo.setCallerSuffixEnabled(true)
        repo.setCallerSuffixText("رقمه {number}")
        val spoken = announce(repo, contactName = "سالم")
        assertTrue(
            "الاسم يُستبدل في جملة المستخدم: $spoken",
            spoken.contains("اتصال من سالم")
        )
        assertTrue(
            "والرقم كذلك: $spoken",
            spoken.contains("رقمه 0501234567")
        )
        assertTrue(
            "ولا يبقى أي عنصر ناطق: $spoken",
            !spoken.contains("{") && !spoken.contains("}")
        )
    }

    @Test
    fun `no identity yields no text even with both boxes on`() {
        val repo = settings()
        repo.setCallerPrefixEnabled(true)
        repo.setCallerPrefixText("انتبه")
        repo.setCallerSuffixEnabled(true)
        repo.setCallerSuffixText("على الخط")
        assertEquals(
            "بلا هوية لا نطق البتّة",
            "",
            announce(repo, number = null, contactName = null)
        )
    }

    @Test
    fun `privacy locked yields no text even with both boxes on`() {
        val repo = settings()
        repo.setCallerPrefixEnabled(true)
        repo.setCallerPrefixText("انتبه")
        repo.setCallerSuffixEnabled(true)
        repo.setCallerSuffixText("على الخط")
        assertEquals(
            "القفل صمتٌ تام لا جملةَ عامة",
            "",
            announce(repo, contactName = "سالم", privacyLocked = true)
        )
    }
}