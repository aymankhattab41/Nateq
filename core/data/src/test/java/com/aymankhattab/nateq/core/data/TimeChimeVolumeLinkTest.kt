package com.aymankhattab.nateq.core.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * حارسُ عقد **«رنّةُ الساعة تتبعُ نطقَها»** — الكسرُ الذي يحرسه:
 * كانت الرنّةُ المستوى `time_chime_volume` وحدَه، ضابطاً مستقلاً لا
 * صلةَ له بصوت النطق، فرفعَ المستخدمُ صوتَ الساعة ولم ترتفع
 * الرنّةُ، وخفضه فبقيت كما هي. فصارت الرنّةُ تتبعُه افتراضياً
 * ومستوىُه اليدويُّ تجاوزٌ لمن فصلهما.
 */
class TimeChimeVolumeLinkTest {

    /** العقد: التتبّعُ يقرأُ مستوى النطق، لا المستوى اليدويّ. */
    @Test
    fun `follows takes the announcement level not the manual one`() {
        assertEquals(
            0.9f,
            TimeChimeVolumeLink.effective(
                followsAnnouncement = true,
                announcementVolume = 0.9f,
                manualVolume = 0.2f
            ),
            0.0001f
        )
    }

    /** والفصلُ يقرؤ اليدويّ ويُهمل النطق. */
    @Test
    fun `manual overrides the announcement level`() {
        assertEquals(
            0.2f,
            TimeChimeVolumeLink.effective(
                followsAnnouncement = false,
                announcementVolume = 0.9f,
                manualVolume = 0.2f
            ),
            0.0001f
        )
    }

    /** وينزل معها: خفضُ النطق يخفضُ الرنّة. */
    @Test
    fun `lowering the announcement lowers the chime`() {
        assertEquals(
            0.3f,
            TimeChimeVolumeLink.effective(
                followsAnnouncement = true,
                announcementVolume = 0.3f,
                manualVolume = 0.9f
            ),
            0.0001f
        )
    }

    /**
     * **الكسرُ الثاني:** نطقٌ مكتومٌ على 0 — ولا يجوز أن تصمت الرنّةُ،
     * فيبدو رأسُ الساعة معطّلاً، ومدى الرنّة نفسه لا يشمل الصمت.
     * فالقاعُ أدنى حدٍّ في [TimeChimeVolumeLink.MIN].
     */
    @Test
    fun `a muted announcement never mutes the chime to silence`() {
        val level = TimeChimeVolumeLink.effective(
            followsAnnouncement = true,
            announcementVolume = 0f,
            manualVolume = 0.9f
        )
        assertEquals(TimeChimeVolumeLink.MIN, level, 0.0001f)
        assertEquals(true, level > 0f)
    }

    /** والسقفُ لا يُتجاوز حتى لو خرج النطقُ خارجَ المدى. */
    @Test
    fun `the link never leaves the chime range`() {
        val levels = listOf(-1f, 0f, 0.05f, 0.5f, 1f, 5f).map { input ->
            TimeChimeVolumeLink.effective(
                followsAnnouncement = true,
                announcementVolume = input,
                manualVolume = 0.5f
            )
        }
        levels.forEach {
            assertTrue("$it خارج المدى", it in
                TimeChimeVolumeLink.MIN..TimeChimeVolumeLink.MAX)
        }
    }
}