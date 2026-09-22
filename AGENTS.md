# AGENTS.md — قواعد مشروع Nateq (تطبيق Lord TTS)

> المرجع العام الكامل: `~/.config/opencode/AGENTS.md` (القاعدة الذهبية، إصلاح الأخطاء،
> معالجة الأخطاء فوراً بلا انتظار سؤال، اللغة العربية، هيكل التقارير، سير العمل).

## قواعد ثابتة

### بناء Release دائماً
**التطبيق يُبنى دائماً Release فقط:** `.\gradlew.bat :app:assembleRelease --console=plain`.
ممنوع بناء Debug إلا بطلب صريح. الـ unit tests تعمل عبر `testDebugUnitTest` دون إنتاج APK.

### اسم التطبيق ثابت
**`app_name` ثابت: "Lord TTS".** لا يتغير لأي سبب في أي ملف -strings أو Manifest.
**يُحظَر قطعياً تغيير الاسم على الشاشة الرئيسية (أيقونة المشغّل Launcher)**، ويبقى
دائماً وأبداً **"Lord TTS"** حصراً ومفصولاً بمسافة (لا «إعدادات ناطق» ولا «إعدادات Lord TTS»
أو أي بادئة/لاحقة أخرى)، وتكون تسمية نشاط الإطلاق في الـ Manifest دوماً `@string/app_name`.

### سلسلة بناء أندرويد 17 (API 37)
- AGP **9.2.0+**، Gradle wrapper **9.4.1**، `compileSdk/targetSdk **37**`.
- Kotlin مدمج عبر AGP 9. إعادة تسمية الـ APK عبر `androidComponents.onVariants`.
- الحالي: `versionCode=36` و `versionName="0.36.0"` وناتج `nateq.apk`.

### نشر الإصدارات (scripts/release.ps1)
**رفع رقم الإصدار يتم حصراً عبر السكربت `.\scripts\release.ps1`** (لا يدوياً):
- الآلية: يأخذ أعلى وسم `vN` على الـ remote ويرفع `versionCode` و`versionName`
  (وسم `vN` يعني `0.N.0`، متوافق مع `alignZeroRelease`)، ثم يشغّل
  `:app:testDebugUnitTest` و`:app:assembleRelease`، يلتزم `app/build.gradle.kts`
  فقط (رسالة عربية)، يضع الوسم `vN`، يدفع `master` والوسم، وينشئ Release على
  GitHub بمرفق `nateq.apk` (يحتاج `gh` موثّقاً؛ بدونه يدفع ويحذّر).
- أوامر: `.\scripts\release.ps1` (تنفيذ كامل) أو `.\scripts\release.ps1 -DryRun`
  (عرض الخطة بلا تغيير). يرفض النشر المتكرر لنفس الوسم (محلياً أو على الـ remote).

### الدفع دائماً بالإسكربت
**أي دفع إلى الـ remote في هذا المشروع يمرّ حصراً عبر `.\scripts\release.ps1`** —
ممنوع `git push` يدوي مهما كانت المهمة. التسلسل الإلزامي قبل كل دفع:
1. حدّث `changelog_text` (عربي + إنجليزي) ببند الجديد الحالي (انظر أدناه).
2. التزم الكود والمستجدات بـ commit عربي وصفياً (التزام محلي).
3. شغّل `.\scripts\release.ps1` — يرفع `versionCode/versionName`، يختبر، يبني
   Release، يلتزم الترقيم، يضع الوسم `vN`، يدفع `master` والوسم (فيلحق بهما
   كل ما سُبِقَ التزامه محلياً)، وينشئ Release على GitHub بملاحظات المستجدات.
- السكربت يعمل دائماً على كل الـ master المحلي، لذا تُلتزم تغييرات الكود كاملة
  مسبقاً (خطوة 2) ولا يُترك أي تغيير غير مرحّج خارجها.
- في الحالات التي لا تبرّر إصداراً جديداً: لا دفع — يعرض الوكيل ذلك في تقريره
  وينتظر قرار المدير.

### آخر التحديثات (changelog)
- شاشة «آخر التحديثات» (زر في منطقة المساعدة في الإعدادات) تعرض
  `changelog_text` (عربي + إنجليزي) مدمجاً به رقم الإصدار عبر `%1$s`؛ النص
  في `feature/settings/src/main/res/values/strings.xml` و`values-en/strings.xml`.
- **كتابة المستجدات واجبة مع كل دفع**: قبل أي دفع يُحدَّث `changelog_text`
  في الملفين (ببنود نقاط مختصرة للجديد الحالي الذي يستحقّه المستخدم) دون حذف
  التحسينات السابقة؛ يبقى البند الأخير دائماً يمثّل أحدث إنجاز. رفع رقم الإصدار
  يبقى حصراً عبر `scripts/release.ps1`.
- **تسجيل منتظم بعد كل جلسة عمل**: مع نهاية كل جلسة (عند أي التزامٍ مركزي أو
  عرضِ تقريرٍ يضمّ تغييراتٍ يستحقّها المستخدم) يُضاف بندٌ موجزٌ في صدارة
  `changelog_text` للملفين يلخّص إنجازات تلك الجلسة وحدها (لا نشرةً تراكمية
  ولا إعادة كتابة للسجل) — بحيث يكون المقطع الأول بعد «الإصدار %1$s» هو آخر
  ما أُنجز دائماً؛ والالتزامُ يكون بأثرِ تلك الجلسة فقط لا بكل السجل.
- **ملاحظات Release على GitHub تأتي من نفس المصدر**: `scripts/release.ps1`
  يقرأ `changelog_text` (عربي) ويعرضه في `gh release create --notes`
  (بعد إزالة سطر العنوان «الإصدار vN») — فلا توليد آلي `--generate-notes`؛
  إن نُشر إصدارٌ لاحقاً دون ذلك فحدّث ملاحظات الـ Release يدوياً بـ
  `gh release edit <tag> --notes-file <ملف>`.

### ميزات نطق النصوص والفوارق المعمارية
- **مستويات نطق علامات الترقيم** (`punctuation_level`، افتراضياً SOME=1):
  `PunctuationLevels` (public في `core:engine`) يعرّف NONE/SOME/ALL؛
  `PunctuationStep` في المسار الثقيل بين `SymbolStep` و`NumberStep` فينطق
  `@ # % & ٪ / + =` المعزولة (SOME) ويضيف `() ; – — …` (ALL)، وNONE يعيد
  النص كما هو. التحويل الرقمي (`NumberStep`) يعمل مستقلاً عن المستوى دائماً.
- **الإسكات الفوري** (`shake_to_stop` / `proximity_silence`، افتراضياً false):
  `InterruptionSensors` في `core:audio` يُسجّل الهزاز/مستشعر التقارب أثناء النطق
  (يبدأ/يتوقف مع `AnnouncementSpeaker`) فيوقف النطق. بطاقة الإعداد في
  `feature/settings/.../InstantSilenceController.kt`.
- **مفاتيح الإعدادات**: `punctuation_level` و`shake_to_stop`
  و`proximity_silence` تُصدَّر تلقائياً في `exportSettings` وتُصفَّر بـ
  `resetAllToDefault`؛ التثبيت في `SettingsRepository.sanitize` (المستشعرات
  توضع false والقيمة غير الصالحة للمستوى تُثبّت على SOME).

### الاختبارات الآلية (JUnit + Robolectric)
- **مطلوبة قبل أي commit:** بعد تعديل المنطق شغّل
  `.\gradlew.bat :app:testDebugUnitTest --console=plain`.
- الاعتماديات: `junit:4.13.2`، `robolectric:4.17`، `androidx.test:core:1.6.1` (`testImplementation`).
- الاختبارات: `NumberSpeechTest` (نقي)، وفئات مصفوفة التوافقية (Robolectric مع
  `@Config(sdk=[24,30,35,37])` — أوسع تغطية عبر النطاق المدعوم)، وفئات
  Robolectric المباشرة على `sdk=[37]` (أحدث بيئة نظام مستهدفة).
- **دعم SDK 36/37 في اختبارات Robolectric 4.17**: يتطلب Java 21 في JVM الاختبارات
  مع إضافة `--add-opens=java.base/jdk.internal.access=ALL-UNNAMED` في `build.gradle.kts`
  (لأن محاكاة `FileDescriptor` لـ `ApplicationSharedMemory` في SDK 37 تستدعي
  `jdk.internal.access.SharedSecrets` عبر Reflection). تم تثبيتها وتعمل بكفاءة 100%.
- أي اختبار يكتشف خطأً حقيقياً = أصلح الخطأ مع إبقاء الاختبار (لا تجمّده ولا تحذفه).

### لا «لاحقاً» في المهام المحلية أيضاً
**لا يوجد «لاحقاً» لأي خطوة واجبة يمكن عملها الآن** — تغطية الاختبارات الآلية
لاي منطق معَدَّل/جديد تُنفَّذ ضمن نفس الجلسة (ولا يُسجَّل «باقٍ جهد لاحق» في التقرير)،
ونقص بنية الاختبار (مثل حقن `Dispatchers.Main`) يُعالج فوراً عبر
`kotlinx-coroutines-test` و`Dispatchers.setMain`. (التفاصيل في المرجع العام.)

### مقارنة إصدار التحديثات (UpdateChecker.isNewerVersion)
- وسم GitHub الأحادي خلال مرحلة 0.x (مثل «v6») يعني الإصدار «0.6.0» — ليس
  تحديثاً؛ مقرّر ومغطى في `UpdateCheckerSemVerTest` (+ `alignZeroRelease`).
- مُعالَجة تاريخياً: الملاحظة السابقة «isNewerVersion("v6","0.6.0") يرجع true
  رغم تطابق الإصدار» — أُصلحت وسُجّلت هنا حتى لا تتكرر معلّقة.

### الأسطر الطويلة >80 (تُعالج دائماً بلا سؤال)
**كل سطر يتجاوز 80 حرفاً في أي ملف Kotlin/Java (رئيسي أو اختبار) يُقسَّم فور اكتشافه**
دون الرجوع إلى المستخدم أو انتظار طلبه، ولو كان سابقاً للتأسيس — حتى يغدو الفحص
`rg -n '.{81}'` صفراً في مصادر Kotlin. الاستثناءات:
- ملفات XML/الموارد وملفات البناء (build.gradle.kts) والـ README لا تخضع
  (لها اصطلاحاتها الخاصة).
- أسطر `import` في Kotlin **لا تُقسَّم** إطلاقاً — إن تجاوز أحدها 80 (مسار
  استيراد طويل مثل `com.aymankhattab.nateq.core.audio.announcement.AnnouncementSchedulerService`)
  يُترك كما هو؛ لا يُستبدل بـ wildcard ولا يُحوّل إلى مؤهَّل كامل. حالياً بقايا
  هذه العائلة فقط 8 أسطر فيfeature/settings.
- أي سطر آخر >80 (نص، Regex، تعليق، توقيع، تعبير) يُقسَّم فوراً بلا استثناء.

## تحذيرات عامة

### ملفات الأصوات (الصوتيات)
**محظور** تعديل/استبدال/حذف/خفض أي ملف صوتي (.mp3, .wav, .ogg, .m4a) بما فيه
`assets/audio/` إلا بعد تقرير وموافقة صريحة.

### Firebase App Check
**محظور تفعيله** في أي مكان — تجريبي ويسبب أخطاء 401.

### Built-in Kotlin
**معطّل حالياً** — ينتظر دعم `audio_session-0.2.4` و `rive_native-0.1.11` و Flutter 3.47+.

## سير العمل الاعتيادي (مهمة كود مباشرة)
1. تحليل/فحص — `flutter analyze` أو فحص Gradle المناسب للملفات المعدّلة.
2. اختبارات — `.\gradlew.bat :app:testDebugUnitTest --console=plain`.
3. بناء Release — اكتشاف أخطاء فوراً ومعالجتها بلا انتظار سؤال.
4. commit — `git add` للملفات المعدّلة فقط ثم commit برسالة عربية وصفية.
5. مستجدات — تحديث `changelog_text` في الملفين (واجب قبل أي دفع، بلا حذف السابق).
6. دفع — `.\scripts\release.ps1` حصراً (يرفع الإصدار ويختبر ويبني ويدفع وينشر)؛
   ممنوع `git push` يدوي.
7. إبلاغ المستخدم برقم الـ commit وحجم الـ APK ومساره ومخرجات الاختبارات.