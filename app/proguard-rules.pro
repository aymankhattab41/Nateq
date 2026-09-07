# ============================================================
#  قواعد تشفير وتحصين كود Lord TTS ضد الهندسة العكسية
#  (ProGuard / R8) — تُستخدم في كل build من نوع release
# ============================================================

# ضغط أكواد الأصناف وربطها (هل يجب)، إزالة معلومات السطر/الملف
-keepattributes Signature
-keepattributes *Annotation*
-keepattributes !SourceFile, !LineNumberTable

# لا تُعدد الأصناف المطلوب لمفتاحها أبداً؛ فهذا يزيد صعوبة الفهم
-allowaccessmodification
-mergeinterfacesaggressively
-optimizationpasses 5

# إعادة العناصر إلى حزمة مسطحة فلتردد أسماء أقصر وأقل دلالة (تحسين إعاقه الفهم)
-repackageclasses ''

# ============================================================
#  قواعد keep الضرورية (كي لا ينكسر التطبيق أو اتصالات النظام)
# ============================================================

# مكونات النظام المدرجة في Manifest يجب أن تبقى بأسمائها كما هي،
# لأن النظام ينشئها بهذه الأسماء (بقية الأصناف تُشفّر وتُعبّب بحرية).
# (يضيف AGP الـ keep تلقائياً لمكونات الـ Manifest؛ هذه صراحة وقايةً مستقبلية)
-keep public class com.aymankhattab.nateq.NateqApplication
-keep public class com.aymankhattab.nateq.engine.NateqTtsService
-keep public class com.aymankhattab.nateq.settings.SettingsActivity
-keep public class com.aymankhattab.nateq.settings.CheckTtsDataActivity
-keep public class com.aymankhattab.nateq.settings.GetSampleTextActivity
-keep public class com.aymankhattab.nateq.receivers.**

# المكونات المنشأة خارج حزمة receivers — مذكورة في Manifest (أداة/بلاطة/خدمة جدولة/إقلاع)
-keep public class com.aymankhattab.nateq.engine.AnnouncementBootReceiver
-keep public class com.aymankhattab.nateq.engine.AnnouncementSchedulerService
-keep public class com.aymankhattab.nateq.settings.AnnouncementTileService
-keep public class com.aymankhattab.nateq.widget.SpeakingClockWidget
-keep public class com.aymankhattab.nateq.receivers.NateqNotificationListener

# خدمة المحرك TTS (مذكورة في Manifest) + الأنواع التي يطلبها النظام عبر TTS Service
-keep class com.aymankhattab.nateq.engine.NateqTtsService { *; }
-keep class * extends android.speech.tts.TextToSpeechService { *; }
-keep class * extends android.speech.tts.TextToSpeech$UtteranceProgressListener { *; }
# أصناف android.speech.tts التي يستدعيها نظام TTS نفسه عبر انعكاس (SynthesisCallback,
# SynthesisRequest, Voice, TextToSpeech, UtteranceProgressListener …) — تُستخدم
# في توقيعات NateqTtsService وتنفيذات مخصّصة من الكود، فتبقى بأسمائها كاملة
-keep class android.speech.tts.* { *; }

# المستقبلات (receiver) التي تسجّلها بأسمائها في Manifest
-keep class com.aymankhattab.nateq.receivers.** { *; }

# الوصول عبر انعكاس من أطراف المساعدة (AccessibilityService)
-keep class * extends android.accessibilityservice.AccessibilityService { *; }

# حافظ على أصناف تمثل جافا الثوابت/Parcelable/native للسلامة
-keep class * implements android.os.Parcelable { *; }
-keepclassmembers class * implements java.io.Serializable { *; }
-keepclassmembers enum * { *; }

# Gson: يجب الحفاظ على التوقيعات العامة (generic) لأن R8 يزيلها
# فيفقد TypeToken وسم_TYPE ويرمي IllegalStateException عند استعادة الحالة
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken { *; }
-keepclassmembers class * extends com.google.gson.reflect.TypeToken { *; }

# GsonTypes: البنّاء اليدوي لكائنات ParameterizedType (Map<String, X>) —
# يعتمد Gson عليها انعكاسياً (getRawType/getActualTypeArguments)، فلو شُفّر
# اسمها أو أعضاؤها فشل فكّ JSON وفسدت التفضيلات المحفوظة على الجاهز.
-keep class com.aymankhattab.nateq.engine.GsonTypes { *; }
-keep class com.aymankhattab.nateq.engine.ParameterizedTypes { *; }

# ============================================================
#  Hilt / Dagger — المكوّنات والأصناف المولّدة لا تُعاد تسميتها
# ============================================================
# Hilt يجمع المكوّنات (Components) عند الإقلاع عبر انعكاس على أسماء
# الأصناف المولّدة (Dagger*_HiltComponents_*). إعادة التسمية تكسر الحقن
# وتفشل الشاشات/الخدمات بلا رسالة واضحة.
-keep class dagger.hilt.** { *; }
-keep class * extends dagger.hilt.internal.GeneratedComponent { *; }
-keep class * extends dagger.hilt.internal.GeneratedComponentManager { *; }
# أصناف Hilt_* المولّدة (Hilt_NateqTtsService, Hilt_SettingsActivity …)
# هي ما يرثه كل صنف مزيّن بـ @AndroidEntryPoint؛ أسماءها تُحلّ انعكاسياً
-keep class com.aymankhattab.nateq.Hilt_* { *; }
-keep class com.aymankhattab.nateq.**.Hilt_* { *; }
# أصناف Dagger المولّدة (Dagger*_HiltComponents_*): تُبنى عبر Hilt من كود
# النشاط/الخدمة مباشرة؛ حفظها يضمن ثبات تسلسل بناية المكوّنات في كل إصدار
-keep class com.aymankhattab.nateq.**Dagger*_HiltComponents_* { *; }
-keepclassmembers class * {
    @dagger.hilt.android.scopes.* <methods>;
}

# تفضيلات التحويل لكل لغة (LanguageSpeechPrefs): تتسلسل/تتجزأ عبر Gson انعكاسياً
# من SharedPreferences، فلو شُفّر اسمها أو حقولها انكسرت JSON المحفوظة (قراءة فارغة).
# ثبّتها كما هي: الحقول (engine/voiceName/rate/pitch/volume) هي مفاتيح JSON ذاتها.
-keep class com.aymankhattab.nateq.engine.LanguageSpeechPrefs { *; }

# رموز XML/RTL ومعلومات ضرورية للانعكاس
-keepattributes XmlAttribute

# مكتبة security-crypto (مفاتيح) قد تستخدم انعكاساً لا يدعمه R8
-keep class com.google.android.gms.security.** { *; }

# androidx.security.crypto (EncryptedSharedPreferences/MasterKey): واجهة الـ API
# نفسها + كل ما يعتمد عليه MasterKey.holder (حاويات/فورمات Keystore) — تحفظ
# كما هي حتى لا ينكسر فك تشفير التفضيلات/القاموس في أول تشغيل release على جهاز
-keep class androidx.security.crypto.** { *; }

# Tink (تشغّل EncryptedSharedPreferences/Keystore): يتعامل مع تنسيقات مفاتيح
# ومواد مشفّرة عبر انعكاس/تسلسل يسقط مع R8 — إبقاؤه كما هو ضروري لئلا يفقد
# LORD قدرته على فك تشفير تفضيلاته بعد أي تحديث
-keep class com.google.crypto.tink.** { *; }
-dontwarn com.google.crypto.tink.**
-dontwarn org.conscrypt.**

# ============================================================
#  إزالة تحذيرات لا فائدة منها
# ============================================================
-dontwarn com.google.android.gms.**
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**
-dontwarn javax.annotation.concurrent.**

# ============================================================
#  إزالة سجلات التشخيص (Log) من نسخة release —
#  تُزال v/d/i بالكامل؛ w/e تبقى للأخطاء الفعلية فقط (بلا نصوص محتوى)
# ============================================================
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}

# يمكنك إضافة -keep لأي مزود صوت جديد تضيفه مستقبلاً هنا