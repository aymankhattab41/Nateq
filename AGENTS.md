# AGENTS.md — قواعد مشروع Nateq (تطبيق Lord TTS)

> المرجع العام الكامل: `~/.config/opencode/AGENTS.md` (القاعدة الذهبية، إصلاح الأخطاء،
> معالجة الأخطاء فوراً بلا انتظار سؤال، اللغة العربية، هيكل التقارير، سير العمل).

## قواعد ثابتة

### بناء Release دائماً
**التطبيق يُبنى دائماً Release فقط:** `.\gradlew.bat :app:assembleRelease --console=plain`.
ممنوع بناء Debug إلا بطلب صريح. الـ unit tests تعمل عبر `testDebugUnitTest` دون إنتاج APK.

### اسم التطبيق ثابت
**`app_name` ثابت: "Lord TTS".** لا يتغير لأي سبب في أي ملف -strings أو Manifest.

### سلسلة بناء أندرويد 17 (API 37)
- AGP **9.2.0+**، Gradle wrapper **9.4.1**، `compileSdk/targetSdk **37**`.
- Kotlin مدمج عبر AGP 9. إعادة تسمية الـ APK عبر `androidComponents.onVariants`.
- الحالي: `versionCode=10` و `versionName="0.10.0"` وناتج `lord_tts.apk`.

### نشر الإصدارات (scripts/release.ps1)
**رفع رقم الإصدار يتم حصراً عبر السكربت `.\scripts\release.ps1`** (لا يدوياً):
- الآلية: يأخذ أعلى وسم `vN` على الـ remote ويرفع `versionCode` و`versionName`
  (وسم `vN` يعني `0.N.0`، متوافق مع `alignZeroRelease`)، ثم يشغّل
  `:app:testDebugUnitTest` و`:app:assembleRelease`، يلتزم `app/build.gradle.kts`
  فقط (رسالة عربية)، يضع الوسم `vN`، يدفع `master` والوسم، وينشئ Release على
  GitHub بمرفق `lord_tts.apk` (يحتاج `gh` موثّقاً؛ بدونه يدفع ويحذّر).
- أوامر: `.\scripts\release.ps1` (تنفيذ كامل) أو `.\scripts\release.ps1 -DryRun`
  (عرض الخطة بلا تغيير). يرفض النشر المتكرر لنفس الوسم (محلياً أو على الـ remote).

### الاختبارات الآلية (JUnit + Robolectric)
- **مطلوبة قبل أي commit:** بعد تعديل المنطق شغّل
  `.\gradlew.bat :app:testDebugUnitTest --console=plain`.
- الاعتماديات: `junit:4.13.2`، `robolectric:4.14.1`، `androidx.test:core:1.6.1` (`testImplementation`).
- الاختبارات: `NumberSpeechTest` (نقي)، `TextProcessorTest` و `SettingsRepositoryTest`
  (Robolectric مع `@Config(sdk=[35])`).
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
5. دفع — `git push` (بطلب صريح فقط).
6. إبلاغ المستخدم برقم الـ commit وحجم الـ APK ومساره ومخرجات الاختبارات.