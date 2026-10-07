package com.aymankhattab.nateq.core.audio.accessibility

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.aymankhattab.nateq.core.data.SettingsRepository
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 30, 35, 37])
class FeatureFlagsTest {

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `features default to false`() {
        val repo = SettingsRepository.create(context)
        val store = A11yStateStore.getInstance(context)!!
        store.setConnected(true)
        store.setPaused(false)

        FeatureFlags.ALL_FEATURES.forEach { feature ->
            assertFalse(
                "Feature $feature should be disabled by default",
                FeatureFlags.isActive(
                    feature,
                    context = context,
                    settings = repo,
                    stateStore = store
                )
            )
        }
    }

    @Test
    fun `feature is inactive when service is paused`() {
        val repo = SettingsRepository.create(context)
        val store = A11yStateStore.getInstance(context)!!
        store.setConnected(true)
        store.setPaused(true)

        repo.setAccessibilityFeatureEnabled(
            FeatureFlags.FEATURE_READ_MORE_EXPAND,
            true
        )

        assertFalse(
            FeatureFlags.isActive(
                FeatureFlags.FEATURE_READ_MORE_EXPAND,
                context = context,
                settings = repo,
                stateStore = store
            )
        )
    }

    @Test
    fun `feature active only for allowed packages`() {
        val repo = SettingsRepository.create(context)
        val store = A11yStateStore.getInstance(context)!!
        store.setConnected(true)
        store.setPaused(false)

        repo.setAccessibilityFeatureEnabled(
            FeatureFlags.FEATURE_READ_MORE_EXPAND,
            true
        )

        assertTrue(
            FeatureFlags.isActive(
                FeatureFlags.FEATURE_READ_MORE_EXPAND,
                packageName = "com.whatsapp",
                context = context,
                settings = repo,
                stateStore = store
            )
        )

        assertFalse(
            FeatureFlags.isActive(
                FeatureFlags.FEATURE_READ_MORE_EXPAND,
                packageName = "com.android.settings",
                context = context,
                settings = repo,
                stateStore = store
            )
        )
    }
}
