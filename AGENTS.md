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
- الحالي: `versionCode=4` و `versionName="0.4.0"` وناتج `lord_tts.apk`.

### الاختبارات الآلية (JUnit + Robolectric)
- **مطلوبة قبل أي commit:** بعد تعديل المنطق شغّل
  `.\gradlew.bat :app:testDebugUnitTest --console=plain`.
- الاعتماديات: `junit:4.13.2`، `robolectric:4.14.1`، `androidx.test:core:1.6.1` (`testImplementation`).
- الاختبارات: `NumberSpeechTest` (نقي)، `TextProcessorTest` و `SettingsRepositoryTest`
  (Robolectric مع `@Config(sdk=[35])`).
- أي اختبار يكتشف خطأً حقيقياً = أصلح الخطأ مع إبقاء الاختبار (لا تجمّده ولا تحذفه).

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