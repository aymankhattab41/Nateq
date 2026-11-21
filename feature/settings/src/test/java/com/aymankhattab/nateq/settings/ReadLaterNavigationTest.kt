package com.aymankhattab.nateq.settings

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.test.core.app.ApplicationProvider
import com.aymankhattab.nateq.core.data.SettingsRepository
import com.aymankhattab.nateq.feature.settings.R
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class ReadLaterNavigationTest {

    private lateinit var actController:
        org.robolectric.android.controller.ActivityController<FragmentActivity>
    private lateinit var activity: FragmentActivity
    private lateinit var fragment: Fragment
    private lateinit var rootView: View
    private lateinit var controller: SettingsAccordionController
    private lateinit var repo: SettingsRepository
    private lateinit var readLaterCard: View

    class TestSettingsFragment(private val viewToReturn: View) : Fragment() {
        override fun onCreateView(
            inflater: LayoutInflater,
            container: android.view.ViewGroup?,
            savedInstanceState: android.os.Bundle?
        ): View = viewToReturn
    }

    @Before
    fun setUp() {
        val appCtx = ApplicationProvider
            .getApplicationContext<Context>()
        SettingsRepository.resetInstanceForTesting()
        appCtx.getSharedPreferences("nateq_settings", Context.MODE_PRIVATE)
            .edit().clear().commit()
        repo = SettingsRepository(appCtx)

        actController =
            Robolectric.buildActivity(FragmentActivity::class.java)
        activity = actController.get()
        activity.setTheme(
            com.google.android.material.R.style
                .Theme_MaterialComponents_DayNight_NoActionBar
        )
        actController.create().start().resume()

        rootView = LayoutInflater.from(activity).inflate(
            R.layout.fragment_voice_selection,
            null
        )
        readLaterCard = rootView.findViewById(R.id.ll_read_later_card)
        assertNotNull("ll_read_later_card must exist in layout", readLaterCard)

        fragment = TestSettingsFragment(rootView)
        activity.supportFragmentManager.beginTransaction()
            .add(fragment, "test_frag")
            .commitNow()

        val catalog = EngineVoicesCatalog(
            initial = emptyMap(),
            arabicVoiceLabel = "صوت عربي",
            englishVoiceLabel = "English Voice"
        )
        controller = SettingsAccordionController(
            fragment = fragment,
            settings = repo,
            voices = emptyList(),
            engineCatalog = catalog
        )
        controller.setup(rootView, fragment)
    }

    @After
    fun tearDown() {
        controller.cleanup()
        repo.unregister()
        SettingsRepository.resetInstanceForTesting()
        actController.pause().stop().destroy()
    }

    @Test
    fun home_readLaterCardIsGone() {
        controller.showHome()
        assertEquals(View.GONE, readLaterCard.visibility)
    }

    @Test
    fun group0_engineVoices_readLaterCardIsGone() {
        controller.openGroupByIndex(0)
        assertEquals(View.GONE, readLaterCard.visibility)
    }

    @Test
    fun group1_announcements_readLaterCardIsGone() {
        controller.openGroupByIndex(1)
        assertEquals(View.GONE, readLaterCard.visibility)
    }

    @Test
    fun group3_system_readLaterCardIsGone() {
        controller.openGroupByIndex(3)
        assertEquals(View.GONE, readLaterCard.visibility)
    }

    @Test
    fun group4_embeddedEngines_readLaterCardIsGone() {
        controller.openGroupByIndex(4)
        assertEquals(View.GONE, readLaterCard.visibility)
    }

    @Test
    fun group2_textAndNumbers_readLaterCardIsVisible() {
        controller.openGroupByIndex(2)
        assertEquals(View.VISIBLE, readLaterCard.visibility)
    }

    @Test
    fun sectionInsideGroup2_readLaterCardIsGone() {
        controller.openGroupByIndex(2)
        assertEquals(View.VISIBLE, readLaterCard.visibility)

        // فتح قسم داخل النصوص والأرقام (مثلاً قراءة الأرقام)
        controller.navigateToSection(R.id.ll_numbers_content)
        assertEquals(View.GONE, readLaterCard.visibility)

        // فتح قسم آخر (قراءة النصوص)
        controller.navigateToSection(R.id.ll_text_reading_settings)
        assertEquals(View.GONE, readLaterCard.visibility)
    }

    @Test
    fun backFromSectionToGroup2_restoresReadLaterCardVisibility() {
        controller.openGroupByIndex(2)
        assertEquals(View.VISIBLE, readLaterCard.visibility)

        controller.navigateToSection(R.id.ll_numbers_content)
        assertEquals(View.GONE, readLaterCard.visibility)

        // النقر على زر العودة
        rootView.findViewById<View>(R.id.btn_back_to_list).performClick()
        assertEquals(View.VISIBLE, readLaterCard.visibility)

        // العودة للرئيسية
        rootView.findViewById<View>(R.id.btn_back_to_list).performClick()
        assertEquals(View.GONE, readLaterCard.visibility)
    }

    @Test
    fun stateSaveAndRestore_preservesVisibilityAcrossRotations() {
        // في المجموعة 2
        controller.openGroupByIndex(2)
        assertEquals(View.VISIBLE, readLaterCard.visibility)

        val bundleGroup2 = controller.saveState()
        controller.showHome()
        assertEquals(View.GONE, readLaterCard.visibility)

        controller.restoreState(bundleGroup2)
        assertEquals(View.VISIBLE, readLaterCard.visibility)

        // في قسم فرعي
        controller.navigateToSection(R.id.ll_numbers_content)
        assertEquals(View.GONE, readLaterCard.visibility)

        val bundleSection = controller.saveState()
        controller.showHome()
        controller.restoreState(bundleSection)
        assertEquals(View.GONE, readLaterCard.visibility)
    }
}
