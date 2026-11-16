package com.aymankhattab.nateq.core.audio.providers

import java.io.File
import java.io.FileInputStream
import java.util.Properties

/**
 * مصدر ترخيص Acapela المستخرج من ملف إعدادات محلي آمن غير متتبع في git
 * (انظر acapela_license.properties المضاف إلى .gitignore).
 */
object AcapelaLicense {

    private const val PROP_FILE_NAME = "acapela_license.properties"

    private val properties: Properties by lazy {
        loadProperties()
    }

    private fun loadProperties(): Properties {
        val props = Properties()
        val candidateFiles = listOf(
            File(PROP_FILE_NAME),
            File("..", PROP_FILE_NAME),
            File("../..", PROP_FILE_NAME),
            File("local.properties"),
            File("..", "local.properties")
        )
        for (file in candidateFiles) {
            if (file.exists() && file.isFile) {
                runCatching {
                    FileInputStream(file).use { props.load(it) }
                }
                if (props.containsKey("acapela.user_id") ||
                    props.containsKey("acapela.license")
                ) {
                    break
                }
            }
        }
        return props
    }

    val USER_ID: Long by lazy {
        val prop = properties.getProperty("acapela.user_id")
            ?: System.getProperty("acapela.user_id")
            ?: System.getenv("ACAPELA_USER_ID")
        prop?.trim()?.removePrefix("0x")?.toLongOrNull(16)
            ?: prop?.trim()?.toLongOrNull()
            ?: 0L
    }

    val PASSWORD: Long by lazy {
        val prop = properties.getProperty("acapela.password")
            ?: System.getProperty("acapela.password")
            ?: System.getenv("ACAPELA_PASSWORD")
        prop?.trim()?.removePrefix("0x")?.toLongOrNull(16)
            ?: prop?.trim()?.toLongOrNull()
            ?: 0L
    }

    val LICENSE: String by lazy {
        val raw = properties.getProperty("acapela.license")
            ?: System.getProperty("acapela.license")
            ?: System.getenv("ACAPELA_LICENSE")
            ?: ""
        raw.replace("\\n", "\n")
    }
}
