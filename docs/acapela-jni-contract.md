# عقد JNI لمحرك Acapela

> هذا المستند **مولَّد** ولا يحتوي أي وصف تخميني. مصدراه الوحيدان:
> 1. الرموز الفعلية المُصدَّرة من `libttsacattsandroidextended.so`
>    (استخراج آلي عبر `llvm-nm`).
> 2. التصريحات الفعلية في ملفات Java داخل المشروع.
>
> أي وصف لقيمة إرجاع أو معنى وسيط غير مذكور في المصدرين أعلاه **غير موثّق
> ولا يُفترض**.

---

## 1) المكتبة تحت العقد

| ABI | الملف | الحجم (بايت) | SHA-256 |
|-----|-------|--------------|---------|
| arm64-v8a | `core/audio/src/main/jniLibs/arm64-v8a/libttsacattsandroidextended.so` | 5902224 | `e6bd0799ee2f6aa1b97e2972d22fdf0b6c5d825fe9b57d5377ddcc375e64086b` |
| armeabi-v7a | `core/audio/src/main/jniLibs/armeabi-v7a/libttsacattsandroidextended.so` | 2380348 | `40bee68705be2f2d18df6dccb39b63e45197bbe7e329b100dda6b770091e426e` |

البصمة أعلاه تُستخدم لكشف تغيّر المكتبة. عند تغيّر أحد الـ SHA-256 تُعاد
عملية الاستخراج والتحقق من مطابقة الجدولين (٤ و ٥).

---

## 2) أمر الاستخراج الآلي (قابل للإعادة)

```powershell
$nm = "$env:LOCALAPPDATA\Android\Sdk\ndk\28.2.13676358\toolchains\llvm\prebuilt\windows-x86_64\bin\llvm-nm.exe"
foreach ($abi in @("arm64-v8a", "armeabi-v7a")) {
    $so = "core/audio/src/main/jniLibs/$abi/libttsacattsandroidextended.so"
    & $nm -D --defined-only $so | Select-String "Java_"
}
```

عدد الرموز المستخرجة: **19** في كل ABI. مجموعتا الرمزين **متطابقتان** تمامًا
(الاختلاف في العنوان فقط).

---

## 3) الرموز المُصدَّرة فعليًا من `.so` (JNI exports)

| # | الرمز في `.so` | الصنف المالك |
|---|----------------|---------------|
| 1 | `Java_com_acapelagroup_android_jni_NativeInterface_nGetPhonetic` | `NativeInterface` |
| 2 | `Java_com_acapelagroup_android_jni_NativeInterface_nSetInfo` | `NativeInterface` |
| 3 | `Java_com_acapelagroup_android_tts_AcattsandroidService_nGetClosestVoice` | `AcattsandroidService` |
| 4 | `Java_com_acapelagroup_android_tts_AcattsandroidService_nGetLanguage` | `AcattsandroidService` |
| 5 | `Java_com_acapelagroup_android_tts_AcattsandroidService_nGetPhonetic` | `AcattsandroidService` |
| 6 | `Java_com_acapelagroup_android_tts_AcattsandroidService_nInit` | `AcattsandroidService` |
| 7 | `Java_com_acapelagroup_android_tts_AcattsandroidService_nIsLanguageAvailable` | `AcattsandroidService` |
| 8 | `Java_com_acapelagroup_android_tts_AcattsandroidService_nRefrehEnumeration` | `AcattsandroidService` |
| 9 | `Java_com_acapelagroup_android_tts_AcattsandroidService_nSetCurrentVoice` | `AcattsandroidService` |
| 10 | `Java_com_acapelagroup_android_tts_AcattsandroidService_nSetLanguage` | `AcattsandroidService` |
| 11 | `Java_com_acapelagroup_android_tts_AcattsandroidService_nSetPreferedVoice` | `AcattsandroidService` |
| 12 | `Java_com_acapelagroup_android_tts_AcattsandroidService_nSetProperty` | `AcattsandroidService` |
| 13 | `Java_com_acapelagroup_android_tts_AcattsandroidService_nSetVoiceMode` | `AcattsandroidService` |
| 14 | `Java_com_acapelagroup_android_tts_AcattsandroidService_nShutdown` | `AcattsandroidService` |
| 15 | `Java_com_acapelagroup_android_tts_AcattsandroidService_nSpeak` | `AcattsandroidService` |
| 16 | `Java_com_acapelagroup_android_tts_AcattsandroidService_nStop` | `AcattsandroidService` |
| 17 | `Java_com_acapelagroup_android_tts_AcattsandroidService_nStopSync` | `AcattsandroidService` |
| 18 | `Java_com_acapelagroup_android_tts_AcattsandroidService_nUtf8Mode` | `AcattsandroidService` |
| 19 | `Java_com_acapelagroup_android_tts_AcattsandroidService_nloadLanguage` | `AcattsandroidService` |

> الأخطاء الإملائية في الأسماء (`nRefrehEnumeration`، `nSetPreferedVoice`،
> `nloadLanguage`) **ليست خطأً في هذا المستند**؛ هي أسماء الرموز الفعلية
> المُصدَّرة، ويجب مطابقتها حرفيًا.

---

## 4) الحقول الفعلية (Fields)

### `com.acapelagroup.android.tts.AcattsandroidService`
| الحقل | المُحدِّد | النوع |
|-------|----------|-------|
| `TAG` | `private static final` | `String` |

لا توجد أي حقول أخرى مُعلنة. لا `mNativeHandle` ولا `mContext`.

### `com.acapelagroup.android.jni.NativeInterface`
لا حقول مُعلنة.

---

## 5) التصريحات الفعلية (Java) ومطابقتها للرموز

### 5.1 `core/audio/src/main/java/com/acapelagroup/android/jni/NativeInterface.java`

```java
public class NativeInterface {
    public static native String nGetPhonetic(String text);
    public static native int nSetInfo(String key, String value);
}
```

### 5.2 `core/audio/src/main/java/com/acapelagroup/android/tts/AcattsandroidService.java`

| # | التوقيع الفعلي (كما هو في الملف) | يقابله رمز `.so` |
|---|----------------------------------|-------------------|
| 1 | `private static final String TAG` | — (حقل) |
| 2 | `native int nInit(String path)` | `...AcattsandroidService_nInit` |
| 3 | `native int nShutdown()` | `...AcattsandroidService_nShutdown` |
| 4 | `native int nIsLanguageAvailable(String lang, String country, String variant)` | `...AcattsandroidService_nIsLanguageAvailable` |
| 5 | `native int nloadLanguage(String lang, String country, String variant)` | `...AcattsandroidService_nloadLanguage` |
| 6 | `native int nSetLanguage(String lang, String country, String variant)` | `...AcattsandroidService_nSetLanguage` |
| 7 | `native int nSetCurrentVoice(String voiceName)` | `...AcattsandroidService_nSetCurrentVoice` |
| 8 | `native int nSetPreferedVoice(String lang, String country, String voiceName)` | `...AcattsandroidService_nSetPreferedVoice` |
| 9 | `native String nGetClosestVoice(String lang, String country, String variant)` | `...AcattsandroidService_nGetClosestVoice` |
| 10 | `native String[] nGetLanguage()` | `...AcattsandroidService_nGetLanguage` |
| 11 | `native int nSetProperty(String key, String value)` | `...AcattsandroidService_nSetProperty` |
| 12 | `native int nSetVoiceMode(String mode)` | `...AcattsandroidService_nSetVoiceMode` |
| 13 | `native int nSpeak(String text, SynthesisCallback callback)` | `...AcattsandroidService_nSpeak` |
| 14 | `native int nStop()` | `...AcattsandroidService_nStop` |
| 15 | `native int nStopSync()` | `...AcattsandroidService_nStopSync` |
| 16 | `native int nUtf8Mode(String p1, String p2)` | `...AcattsandroidService_nUtf8Mode` |
| 17 | `native int nRefrehEnumeration()` | `...AcattsandroidService_nRefrehEnumeration` |
| 18 | `native String nGetPhonetic(String text)` | `...AcattsandroidService_nGetPhonetic` |

**النتيجة:** كل رمز `.so` من الـ 19 له تصريح Java مطابق، والعكس. لا رمز بلا
تصريح ولا تصريح بلا رمز (مطابقة 1:1).

---

## 6) ما يستدعيه كود التطبيق فعلاً

المصدر: `core/audio/src/main/java/com/aymankhattab/nateq/core/audio/enginehost/AcapelaHostSession.kt`
(تم الحصر بالبحث النصّي عن الاستدعاءات، لا بالتخمين).

| الطريقة | موضع الاستدعاء |
|---------|-----------------|
| `nInit(dataDir)` | `AcapelaHostSession.kt:58` |
| `nSetCurrentVoice(target)` | `AcapelaHostSession.kt:103` |
| `nSetProperty("Rate", ...)` | `AcapelaHostSession.kt:108` |
| `nSetProperty("Pitch", ...)` | `AcapelaHostSession.kt:109` |
| `nSetProperty("Volume", ...)` | `AcapelaHostSession.kt:110` |
| `nSpeak(req.text, callback)` | `AcapelaHostSession.kt:172` |
| `nStop()` | `AcapelaHostSession.kt:174` و `198` |
| `nShutdown()` | `AcapelaHostSession.kt:205` |

باقي الطرق الـ 17 المعرَّفة موجودة في العقد لكن **لا يستدعيها التطبيق حاليًا**.

---

## 7) `SynthesisCallback`

`AcattsandroidService.java` يستورد ويستخدم واجهة Android القياسية:

```java
import android.speech.tts.SynthesisCallback;
```

وهي نوع الوسيط الثاني في `nSpeak(String, SynthesisCallback)`. **لا توجد** فئة
داخلية باسم `SynthesisCallback` في `AcattsandroidService`، ولا توثّق المكتبة
طرقها — فهي واجهة إطار عمل أندرويد لا جزء من هذا العقد.

---

## 8) غير الموثّق (لا يُفترض)

- **قيم إرجاع `int`:** لا تصرّح المكتبة ولا ملفات Java بمعاني رموز الإرجاع
  (نجاح/فشل/أكواد ترخيص). أي جدول لأكواد خطأ غير مذكور هنا لأنه غير موجود في
  المصدرين.
- **معاني وسائط `nUtf8Mode(String, String)` و `nSetVoiceMode(String)`:** غير
  موثّقة في المصدرين.
- **نوع `SynthesisCallback`:** انظر القسم 7.

---

## 9) اختبار الحارس (Contract Test)

الملف:
`core/audio/src/test/java/com/aymankhattab/nateq/core/audio/providers/AcapelaJniContractTest.kt`

يحرس هذا الاختبار التواقيع الفعلية (الاسم + عدد الوسائط + أنواعها + نوع
الإرجاع) لكل من `NativeInterface` و `AcattsandroidService`، فلا ينحرف عقد
الجافا عن الرموز المُصدَّرة دون أن يفشل.

تشغيله:

```powershell
.\gradlew.bat :core:audio:testDebugUnitTest `
  --tests "com.aymankhattab.nateq.core.audio.providers.AcapelaJniContractTest" `
  --console=plain
```

---

## 10) عند تحديث مكتبة Acapela

1. أعد حساب SHA-256 لكل `.so`؛ إن تغيّرت، أعد تنفيذ أمر الاستخراج (القسم 2).
2. حدّث القسمين 3 و 5، ثم جدول البصمات (القسم 1).
3. شغّل اختبار الحارس (القسم 9)؛ إن فشل، طابِق التصريحات أو رموز `.so` قبل
   أي تعديل في Acapela.

---

*مُولَّد آليًا — آخر تحقق: 2026-10-09*
*المصدر: `libttsacattsandroidextended.so` (arm64-v8a و armeabi-v7a) + تصريحات Java*
