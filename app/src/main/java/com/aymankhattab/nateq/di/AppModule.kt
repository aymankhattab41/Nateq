package com.aymankhattab.nateq.di

import android.content.Context
import com.aymankhattab.nateq.engine.PronunciationDictionary
import com.aymankhattab.nateq.core.data.SettingsRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * وحدة الحقن المركزية للكائنات ذات المصير الطويل.
 *
 * تُكشف [SettingsRepository] و[PronunciationDictionary] كسنجلتونات عبر
 * حُقنة واحدة في التطبيق بدل إنشائها من جديد في كل Activity/Fragment/مستقبل —
 * فهما مبنيتان على Context خفيف ولا تحمّلان تكلفة إضافية، وتحسّنان الاتساق
 * (حياة واحدة للمخابئ والقيم المفرّغة). البنود الأخرى كـ [SystemVoiceProvider]
 * مستقلة السياق وتبقى كما هي دون حقن لحين الحاجة.
 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideSettingsRepository(
        @ApplicationContext context: Context
    ): SettingsRepository =
        SettingsRepository.create(context)

    /**
     * **[PronunciationDictionary.shared] لا نسخةً خاصةً بالحُقنة.**
     *
     * كان هذا يخلّق نسخةً مستقلة، فيصير في التطبيق **كائنان لا واحد**:
     * نسخةُ الواجهة (هذه) ونسخةُ نطق الإعلانات ([AnnouncementSpeaker]) —
     * ولا يجمعهما إلا قراءةُ الملف من القرص وهي تنهار صامتاً (Keystore
     * معطوب، أو طابعٌ لم يتغيّر، أو نافذةُ خنق). فصار الحقنُ يطلبُ
     * **المثّل المشترك** فيصير النطقُ والإاجهةُ كائناً واحداً لا يحتاج
     * مزامنةً داخل العملية.
     */
    @Provides
    @Singleton
    fun providePronunciationDictionary(
        @ApplicationContext context: Context
    ): PronunciationDictionary = PronunciationDictionary.shared(context)
}