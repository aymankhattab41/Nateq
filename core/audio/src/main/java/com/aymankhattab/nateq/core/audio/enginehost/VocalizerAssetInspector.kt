package com.aymankhattab.nateq.core.audio.enginehost

import android.content.Context
import java.io.IOException

/**
 * ع٣٨-3: فحص وتسجيل أصول assets/vocalizer/ وقيمة noCompress
 * عند بدء المضيف.
 */
object VocalizerAssetInspector {

    /**
     * ملفات .xmf السبعة الأساسية المتوقعة من SmartVoice (ع٣٧-3).
     */
    val EXPECTED_XMF_FILES = listOf(
        "clm.xmf",
        "lid.xmf",
        "rex_rur.xmf",
        "synth_med_fxd_bet3f22.xmf",
        "sysdct.xmf",
        "userdct_hei.xmf",
        "userdct_rur.xmf"
    )

    /**
     * فحص وتسجيل محتوى assets/vocalizer/ وأحجامه والتحقق من فتح
     * كل ملف عبر openFd (عدم الانضغاط).
     */
    fun inspectAndLogAssets(
        context: Context,
        engineId: String = HostProtocol.ENGINE_VOCALIZER
    ) {
        val am = context.assets
        val list = runCatching {
            am.list("vocalizer")?.toList()
        }.getOrNull().orEmpty()

        HostLog.i(
            engineId,
            "=== فحص أصول assets/vocalizer/ (${list.size} ملف) ==="
        )

        for (file in list) {
            val path = "vocalizer/$file"
            try {
                val afd = am.openFd(path)
                val len = afd.length
                afd.close()
                HostLog.i(
                    engineId,
                    "أصل: $file، الحجم=$len بايت، غير مضغوط (openFd نجح)"
                )
            } catch (e: IOException) {
                HostLog.w(
                    engineId,
                    "أصل: $file: فشل openFd (${e.message}) - يبدو مضغوطاً!"
                )
            }
        }

        val missing = EXPECTED_XMF_FILES.filter { it !in list }
        if (missing.isEmpty()) {
            HostLog.i(
                engineId,
                "جميع ملفات .xmf السبعة الأساسية موجودة في الأصول"
            )
        } else {
            val missingStr = missing.joinToString(", ")
            HostLog.w(
                engineId,
                "ملفات .xmf مفقودة من الأصول: $missingStr"
            )
        }
    }
}
