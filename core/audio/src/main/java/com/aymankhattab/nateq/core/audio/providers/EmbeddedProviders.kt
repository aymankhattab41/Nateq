package com.aymankhattab.nateq.core.audio.providers

import android.content.Context
import java.util.concurrent.atomic.AtomicReference

/**
 * سجل مزوّدات مدمجة مفرد (Singleton) مع تهيئة كسولة.
 * يمنع إنشاء نسخ متعددة لكل محرك طوال عمر العملية.
 */
object EmbeddedProviders {
    private val vocalizerRef = AtomicReference<VocalizerEmbeddedVoiceProvider?>(
        null
    )
    private val acapelaRef = AtomicReference<AcapelaEmbeddedVoiceProvider?>(
        null
    )
    private val sherpaRef = AtomicReference<SherpaOnnxVoiceProvider?>(
        null
    )

    @Synchronized
    fun vocalizer(context: Context): VocalizerEmbeddedVoiceProvider {
        val app = context.applicationContext
        vocalizerRef.get()?.let { return it }
        val inst = VocalizerEmbeddedVoiceProvider(app)
        vocalizerRef.set(inst)
        return inst
    }

    @Synchronized
    fun acapela(context: Context): AcapelaEmbeddedVoiceProvider {
        val app = context.applicationContext
        acapelaRef.get()?.let { return it }
        val inst = AcapelaEmbeddedVoiceProvider(app)
        acapelaRef.set(inst)
        return inst
    }

    @Synchronized
    fun sherpa(context: Context): SherpaOnnxVoiceProvider {
        val app = context.applicationContext
        sherpaRef.get()?.let { return it }
        val inst = SherpaOnnxVoiceProvider(app)
        sherpaRef.set(inst)
        return inst
    }

    @Synchronized
    fun shutdownAll() {
        vocalizerRef.getAndSet(null)?.let {
            runCatching { it.shutdown() }
        }
        acapelaRef.getAndSet(null)?.let {
            runCatching { it.shutdown() }
        }
        sherpaRef.getAndSet(null)?.let {
            runCatching { it.shutdown() }
        }
    }
}
