# Original Vocalizer Wrapper & Initialization Flow Analysis

تم تحليل حزمة **`SmartVoice-v4.1.16-FIX3-9f9b8042.apk`** الموجودة في جذر المشروع،
وتفكيك `classes.dex` وفحص أصناف:
- `com.nuance.android.vocalizer.VocalizerEngine`
- `com.nuance.android.vocalizer.internal.VocalizerAssetManager`
- `com.nuance.android.vocalizer.VocalizerExternalDataProvider`
- `com.nuance.vocalizer.b` (تنفيذ مزود البيانات الأصلي)
- `com.nuance.vocalizer.f` (متحكم خدمة الصوت الأصلية)

---

## 1. ما السلسلة التي تُمرَّر إلى initialize / initializeNative؟

- دالة JNI الأصلية:
  `private native boolean initializeNative(String dataDirectory)`
- الدالة الجافا الوسيطة في `VocalizerEngine$InitThread.run()`:
  ```java
  mAssetManager.initialize();
  String pipelineHeaders = mAssetManager.getPipelineHeaders();
  boolean success = initializeNative(pipelineHeaders);
  ```
- **الحقيقة القاطعة**:
  المكتبة **لا تنتظر مسار مجلد ولا مسار ملف على القرص في `initializeNative`!**
  بل تنتظر **محتوى نصوص ترويسات خط المعالجة (Pipeline Headers)** المُجمعة
  من ملفات `.hdr` (مثل `ve_pipeline_*.hdr`) مجتمعة في سلسلة نصية واحدة
  (`String pipelineHeaders`).
  - عند قراءة ملفات `.hdr` يتم دمج نصوص XML الترويسية الخاصة بها
    (`ve_pipeline_*.hdr` أو الملفات التي تنتهي بـ `.hdr` أو `.jet`).
  - تمرير مسار مجلد نصي مثل `"/data/user/0/.../VocalizerEx2"` يجعل المحرك
    الأصلي يحاول تحليل المسار كنص XML/Header، فيفشل فوراً بالرمز
    `80000004` (تعذّر فتح ملف / مورد غير صالح).

---

## 2. ما الدوال التي تُستدعى قبل التهيئة؟

في الصنف `com.nuance.vocalizer.f`:
1. إنشاء محرك Vocalizer وتزويده بـ `VocalizerExternalDataProvider`:
   ```java
   VocalizerEngine engine = new VocalizerEngine(context, externalDataProvider);
   ```
2. ضبط المستمعين:
   ```java
   engine.setListener(engineListener);
   engine.setAudioOutputListener(audioOutputListener, bypassPlayback);
   ```
3. استدعاء `engine.initialize()` الذي يطلق `InitThread`.
4. داخل `InitThread`:
   - استدعاء `mAssetManager.initialize()` الذي يقوم بمسح حزم الأصول ومسح مجلد
     الصوت عبر `externalDataProvider.getHeaders()`.
   - استدعاء `initializeNative(pipelineHeaders)`.
   - استدعاء `getAvailableVoiceListNative()`.
   - إخطار المستمعين `onInitialized`.

---

## 3. هل تُنسخ ملفات (xml أو إعدادات) من الأصول قبل التهيئة؟ وإلى أين؟

- التطبيق الأصلي **لا ينسخ ملفات من الأصول إلى مجلد خارجي**.
- الملفات الأساسية المشتركة تأتي بصيغة `.xmf` داخل `assets/vocalizer/`:
  - `sysdct.xmf`
  - `synth_med_fxd_bet3f22.xmf`
  - `rex_rur.xmf`
  - `userdct_hei.xmf`
  - `userdct_rur.xmf`
  - `clm.xmf`
  - `lid.xmf`
- الأصوات الإضافية تكون داخل مجلد خارجي:
  `/storage/emulated/0/VocalizerEx2` (أو المجلد المحدد في إعدادات التطبيق).
  ويحوي ملفات الصوت (`.hdr`، `.dat`).

---

## 4. كيف تُنفَّذ openAssetFile الأصلية؟

عندما يستدعي كود JNI الأصلي في `libNuanceVocalizer.so`:
`openAssetFile(String path)`:
1. يستدعي `mAssetManager.openAssetFile(path)`.
2. يبدأ بفحص `externalDataProvider.openDataFile(path)`:
   - يحوّل الاسم إلى أحرف صغيرة ويستبدل `/` بـ `_` ويضيف `.dat`.
   - إذا وجد الملف في جدول ملفات البيانات الخارجية، يفتحه عبر:
     `ParcelFileDescriptor.open(file, MODE_READ_ONLY)`
     (أو `ContentResolver.openFile` في أندرويد 10+).
     ويعيد `AssetFileDescriptor(pfd, 0, statSize)`.
3. إن لم يوجد في المزود الخارجي، يبحث في الأصول الداخلية (`assets/vocalizer/`):
   - يطابق الاسم مع ملفات `.xmf` الممسوحة.
   - يستدعي `AssetManager.openFd(name)`.
4. يستخرج `pfd.getFd()`، و`startOffset`، و`length`، ويملأ كائن
   `com.nuance.android.vocalizer.internal.VocalizerFileInfo` ويعيده إلى JNI.
5. يحتفظ بـ `AssetFileDescriptor` في جدول مفتوح حتى يستدعي C++
   `closeAssetFile(descriptor)`.
