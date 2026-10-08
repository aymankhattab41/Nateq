# عقد JNI لمحرك Nuance Vocalizer (الأمر ع٢٩-1)

> المصدر الوحيد للحقيقة: `libNuanceVocalizer.so` (الأصل بـ `core/audio/
> src/main/jniLibs/arm64-v8a/` — و`v7a` بنفس الأسماء). الكود الجاوي
> يجب أن يطابق هذا العقد حرفياً وإلا كسر JNI (تعطّل/انهيار) كما حدث
> بغياب `mNativeEnginePtr`.

## سبب الانهيار المُثبَت

`Java_com_nuance_android_vocalizer_VocalizerEngine_createNativeEngine`
(دالة .dynsym عند `0x13202c`) تُنفّذ:

1. `GetFieldID` في مكان ما وتحفظ الناتج عالمياً عند `[0x777000+0x198]`
   (الحقل/الاسم `mNativeEnginePtr`/`J`).
2. بإنشاء `CVocalizerEngine` عن طريق malloc+cctor ثم:
3. `ldr x4, [x9, #0x370]` = **SetLongField** ثم
   `br x4` أي `SetLongField(env, thiz, fid, pointer)`.

حين يكون الحقل غائباً من الجافا: `fid == null` → ART تُرمي
`JNI DETECTED ERROR: fid == null in call to SetLongField` — وهو الانهيار
القابل للإعادة الذي كان يظهر عند تهيئة محرك Vocalizer.

## الأصناف السبع (FindClass)

| الصنف | المسار |
|---|---|
| VocalizerEngine | `com.nuance.android.vocalizer.VocalizerEngine` |
| VocalizerSpeechMark | `com.nuance.android.vocalizer.VocalizerSpeechMark` |
| VocalizerVersion | `com.nuance.android.vocalizer.VocalizerVersion` |
| VocalizerVoice | `com.nuance.android.vocalizer.VocalizerVoice` |
| VocalizerFileInfo | `com.nuance.android.vocalizer.internal.VocalizerFileInfo` |
| VocalizerResourceInfo | `com.nuance.android.vocalizer.internal.VocalizerResourceInfo` |
| VocalizerStatusInfo | `com.nuance.android.vocalizer.internal.VocalizerStatusInfo` |

## الحقول الأحد والأربعون (41)

### VocalizerEngine
| الحقل | التوقيع |
|---|---|
| mNativeEnginePtr | `J` ← **أُضيف (سبب الانهيار)** |

### VocalizerSpeechMark
| الحقل | التوقيع |
|---|---|
| mMarkType | `I` |
| mSrcPos | `I` |
| mSrcTextLen | `I` |
| mDestPos | `I` |
| mDestLen | `I` |
| mPhonemeId | `I` |
| mMarkId | `I` |
| mJawOpen | `I` |
| mTeethUpVisible | `I` |
| mTeethLoVisible | `I` |
| mMouthHeight | `I` |
| mMouthWidth | `I` |
| mMouthUpturn | `I` |
| mLipTension | `I` |
| mTonguePos | `I` |
| mLHPhoneme | `Ljava/lang/String;` ← **أُضيف** |

### VocalizerVersion
| الحقل | التوقيع |
|---|---|
| mMajor | `I` |
| mMinor | `I` |
| mBuild | `I` |
| mBuildYear | `I` |
| mBuildMonth | `I` |
| mBuildDay | `I` |
| mBuildInfo | `Ljava/lang/String;` |

### VocalizerVoice
| الحقل | التوقيع |
|---|---|
| mVoiceName | `Ljava/lang/String;` |
| mSampleRate | `I` |
| mLanguage | `Ljava/lang/String;` |
| mVoiceOperatingPoint | `Ljava/lang/String;` |
| mLanguageVersion | `Ljava/lang/String;` |
| mLanguageTLW | `Ljava/lang/String;` |
| mVoiceAge | `Ljava/lang/String;` |
| mVoiceType | `Ljava/lang/String;` |
| mVoiceVersion | `Ljava/lang/String;` |
| mLangId | `I` |

### VocalizerFileInfo (internal)
| الحقل | التوقيع |
|---|---|
| descriptor | `I` |
| startOffset | `J` |
| length | `J` |

### VocalizerResourceInfo (internal)
| الحقل | التوقيع |
|---|---|
| mHandle | `J` |

### VocalizerStatusInfo (internal)
| الحقل | التوقيع |
|---|---|
| mHeapBlocks | `I` |
| mSystemFileHandles | `I` |
| mAssetFileHandles | `I` |

## الطرائق (GetMethodID)

| الصنف | الطريقة | التوقيع | العنوان | الحالة |
|---|---|---|---|---|
| VocalizerEngine | speechMarksReceived | `([Lcom/nuance/android/vocalizer/VocalizerSpeechMark;)V` | 0x132d70 | موجودة (private) |
| VocalizerEngine | audioSamplesReceived | `([S)Z` | 0x132df4 | موجودة (private) |
| VocalizerEngine | openAssetFile | `(Ljava/lang/String;)Lcom/nuance/android/vocalizer/internal/VocalizerFileInfo;` | 0x134b1c | **أُضيفت** |
| VocalizerEngine | closeAssetFile | `(I)V` | 0x134e38 | **أُضيفت** |

الدالتان المفقودتان استدعاء **instance** على الكائن نفسه
(`openAssetFile`/`closeAssetFile` تُسمى CallObjectMethod / تطبيقُ العلامة لاحقاً على الـ object في `this+0x18`). إثباتُ الملكية:

- `CVocalizerEngine::CVocalizerEngine(JNIEnv*, jobject)` (`_ZN16CVocalizerEngineC1EP7_JNIEnvP8_jobject`):
  `ldr x8, [x8, #0xa8]` = **NewGlobalRef** ثم `str x0, [x19, #0x18]`
  أي أن `this+0x18` = الكائن الجاوي `VocalizerEngine` (thiz).
- `GetObjectClass(env, [self,#0x18])` = `VocalizerEngine`.
- إزاحة GetObjectClass = `0xf8` (index 31).

وبذلك: **instancer** على `VocalizerEngine` — لا حاجة لإضافة بناءه.

## إثبات «الحقول الناقصة تعطل البناء» دائماً

توجد دالة `_ZN16CVocalizerEngine22DeleteGlobalReferencesEP7_JNIEnv`
(حذف مرجعية الكائن الجاوي)، و`Release` تُغلق، أي أن البنية الأصلية
تريد التواصل مع الكائن الجاوي وستفشل كل مكالمة عنها بمهلة أو غياب
الحقل المطلوب.

## أدوات الاستخراج وإعادة التحقق

- الماسح: `tools/vocalizer_jni_scan.py` → `C:\WINDOWS\TEMP\jnis.json`
- إزاحات JNI المستخدمة في الماسح (arm64، JNINativeInterface):
  FindClass=0x30، GetFieldID=0x2f0، GetMethodID=0x108،
  GetStaticMethodID، GetStaticFieldID=0x480، SetLongField=0x370،
  SetIntField=0x368، SetObjectField=0x340، SetBooleanField=0x348،
  GetObjectClass=0xf8، ReleaseStringUTFChars=0xb8.
- حارس الاختبار: `VocalizerJniContractTest` (انعكاس) يمسك أي حقل/طريقة
  يُحذف أو يُعاد تسميته مستقبلاً.
- حارس R8: `core/audio/consumer-rules.pro` (يُطبَّق تلقائياً على :app).

## قواعد صارمة

- **ممنوع** تغيير اسم/نوع أي حقل وارد أعلاه، وممنوع تحويل
  `mNativeEnginePtr` إلى static/final، وممنوع حذف `mLHPhoneme`.
- **ممنوع** تعديل أي معادلة/سلوك لضبط الواجهة الحسية حول المحرك؛ أي
  أمر صوتي يُعرض على المدير أولاً (قاعدة ملفات الأصوات).
- التسجيل بالاسم (`Java_...`) لبقية الطرائق: أسماء `Java_com_nuance_
  android_vocalizer_VocalizerEngine_*` كلها حاضرة في `.dynsym`.