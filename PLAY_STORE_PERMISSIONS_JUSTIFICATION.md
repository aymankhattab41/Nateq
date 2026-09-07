# مبررات أذونات Google Play — تطبيق Lord TTS (ناطق)

> هذا الملف هو المرجع الرسمي لتعبئة **Permissions Declaration Form** في Google Play Console
> عند رفع أي إصدار يحتوي على الأذونات المقيدة أدناه. استخدم النصوص العربية أو الإنجليزية
> حسب لغة الاستمارة، وأرفق فيديو استخدام لكل ميزة كما هو موضح.

---

## تطبيق إتاحة (Accessibility) لذوي الاحتياجات البصرية

**Lord TTS (ناطق)** هو محرك تحويل النص إلى كلام (TTS) وتطبيق إتاحة متخصص
للمكفوفين وضعاف البصر. وظيفته الأساسية نطق المعلومات بصوت عالٍ وواضح فور وصولها،
حتى لا يضطر المستخدم لقراءة الشاشة. الميزات المذكورة أدناه كلها **خيارية ومدفوع
بها من المستخدم** ولا تُفعَّل إلا بطلب صريح من داخل التطبيق، ولا تُرفع أي بيانات
إلى أي خادم (التطبيق يعمل محلياً 100% — الإنترنت يُستخدم للفحص الذاتي لتحديثات
GitHub Releases فقط ولا يخرج منه أي محتوى مستخدم).

---

## قائمة الأذونات المقيّدة والتبريرات

### 1. `READ_CONTACTS` — قراءة جهات الاتصال

| البند | التفاصيل |
|-------|----------|
| **الغرض** | البحث عن اسم المتصل في دفتر الهاتف لنطقه بصوت عالٍ عند وصول مكالمة («اتصال وارِد من فلان»)، للمكفوفين الذين لا يرون اسم المتصل على الشاشة. |
| **لماذا الإذن ضروري؟** | لا بديل برمجي يسمح بقراءة اسم جهة اتصال من الرقم دون هذا الإذن مع `READ_CALL_LOG`. التطبيق ليس تطبيق الاتصال الافتراضي على الجهاز، لكنه يحتاج إلى مطابقة الرقم الوارد مع دفتر الاتصالات لنطق الاسم. |
| **الحد الأدنى** | لا نقرأ سوى عمود `DISPLAY_NAME` المرتبط بالرقم الوارد عبر `ContactsContract.PhoneLookup` ولا نمسح دفتر الاتصالات ولا نقرأ بيانات غير ذات صلة. |
| **البيانات المعالجة** | الاسم المرتبط بالرقم فقط، ويُنطق صوتياً فوراً دون تخزين أو إرسال. |
| **فيديو الاستخدام** | أظهِر: وصول مكالمة هاتفية على جهاز مؤمَّن من شاشة القفل مع قفل خاص بالمكالمات الواردة، ثم سماع اسم المتصل المنطوق. |
| **خصوصية المستخدم** | تحكم كامل: الميزة غير مفعّلة حتى يسأل التطبيق الإذن وقت التفعيل، ويمكن إيقافها من قسم «إعلان المتصل». |

### 2. `READ_CALL_LOG` — قراءة سجل المكالمات

| البند | التفاصيل |
|-------|----------|
| **الغرض** | الحصول على رقم المتصل الوارد على أندرويد 12+ (بدونه لا يُسلَّم النظام رقمَ المتصل عبر `EXTRA_INCOMING_NUMBER` لغير تطبيق الاتصال الافتراضي)، والبحث عن الاسم في بيانات المكالمة المخزنة في السجل. |
| **لماذا الإذن ضروري؟** | منذ أندرويد 12، يُلغي النظام إيصال رقم المتصل لغير تطبيقات الاتصال الافتراضية ما لم يملك التطبيق `READ_CALL_LOG`. الإذنان معاً يمكّنان الإعلان الصوتي لاسم المتصل ورقمه للمكفوفين. |
| **الحد الأدنى** | نقرأ `CACHED_NAME` و `NUMBER` فقط في صف واحد مطابق للرقم الوارد؛ لا نقرأ الحوارات الصوتية ولا نعالج بيانات الاتصال السابقة. |
| **البيانات المعالجة** | اسم المتصل والرقم المرتبط بالمكالمة الحالية فقط، يُنطقان صوتياً دون تخزين أو إرسال. |
| **فيديو الاستخدام** | أظهِر نفس سيناريو فيديو READ_CONTACTS: مكالمة واردة + نطق الاسم/الرقم من شاشة القفل ولوحة الإعلان. |
| **خصوصية المستخدم** | يحتاج الإذن لإعلان المتصل؛ يمكن إيقافه من الإعدادات وحذفه من النظام، والميزة تتوقف فوراً. |

### 3. `RECEIVE_SMS` — استقبال الرسائل النصية الواردة

| البند | التفاصيل |
|-------|----------|
| **الغرض** | نطق محتوى الرسائل النصية الواردة (اختيارياً اسم المرسل فقط) بصوت عالٍ للمكفوفين وضعاف البصر الذين لا يرون نص الرسالة. |
| **لماذا الإذن ضروري؟** | مستقبل `SmsReadingReceiver` يستقبل بث `SMS_RECEIVED` (الموجود في Manifest) لأنه يعمل لحظة وصول الرسالة حتى مع قفل الشاشة؛ بدون `RECEIVE_SMS` لا يتم تسجيل المستقبل ديناميكياً على أندرويد 19+. |
| **الحد الأدنى** | يستخدم `Telephony.Sms.Intents.getMessagesFromIntent`، ويقرأ نص الرسالة فقط لأغراض النطق. لا نقرأ أرشفة الرسائل القديمة ولا نرسل أي رسائل، ولا نعدّل المحتوى. |
| **البيانات المعالجة** | جسد الرسالة ورقم المرسل – يُنطقان صوتياً ثم يُتجاهلان (لا تخزين، لا إرسال). |
| **خصوصية المستخدم** | تحكم كامل: الوضع «معلّق» افتراضياً؛ لا يُطلب الإذن إلا عند تفعيل المستخدم قراءة الرسائل فعلياً، مع بند خصوصية لشاشة القفل وفلتر كلمات تحقق OTP يمنع نطق الرموز الحساسة في الأماكن العامة. |
| **فيديو الاستخدام** | أظهِر: تفعيل «قراءة الرسائل» من الإعدادات ← منح الإذن ← وصول SMS على شاشة مقفلة/مفتوحة ← نطق المصدر ثم المحتوى (ثم عرض وضع «المصدر فقط» ونطق OTP محجوب). |

---

## ملاحظات Google Play الحرجة

1. **الرفض التلقائي**: أي من الأذونات أعلاه بدون `Permissions Declaration Form` سيرفض
   الـ APK تلقائياً عند الرفع على Play Console (تحقق آلي من سياسة الأذونات).
2. **تطبيقات SMS/هاتف الافتراضية فقط**: يفضل Google منح `RECEIVE_SMS` لتطبيق الرسائل
   الافتراضي؛ للتطبيقات الإتاحية غير الافتراضية يجب تبرير قوي واضح بخصوص الإتاحة.
3. **فيديو الاستخدام إلزامي**: أصدّر فيديو MP4 قصير (مدة ~30-60 ثانية) لكل ميزة
   يوضح الإذن المستخدم والسيناريو الفعلي (دون إظهار بيانات حساسة حقيقية — استخدم
   أرقاماً ورسائل تجريبية).
4. **إبقاء الأذونات**: لا تحذف أي إذن من الـ Manifest؛ الميزات تعمل بها فعلياً
   (إعلان المتصل يعتمد على READ_CONTACTS و READ_CALL_LOG، وقراءة الرسائل على RECEIVE_SMS).
5. **المستند المحدث**: عند كل إصدار جديد، أعد مراجعة هذا الملف وتحديث مدة الفيديو
   وروابطه ليعكس إصدار الأندرويد والإصدار الحالي للتطبيق.

---

## English version — Permissions Justification for Google Play (Lord TTS)

> Official reference for the **Permissions Declaration Form** in Google Play Console.
> Use the Arabic or English text depending on the console language, and attach a
> screen-recording (video) for each feature as described.

### App category
**Lord TTS (ناطق)** is a Text-to-Speech engine and accessibility app for blind and
low-vision users. It speaks incoming information aloud immediately. Every feature
below is **optional, user-enabled**, stores **no data**, and sends **nothing** to any
server (the app is 100% local; Internet is used only for self-update checks against
GitHub Releases and never for user content).

### 1. `READ_CONTACTS`
- **Purpose**: Look up the caller's name in the contact list and speak it aloud
  when a call arrives, so blind users know who is calling.
- **Why required**: Matching an incoming number to a contact name requires
  `READ_CONTACTS` (with `READ_CALL_LOG`). Lord TTS is not the default dialer.
- **Minimal access**: Reads only `DISPLAY_NAME` for the incoming number via
  `ContactsContract.PhoneLookup`. No bulk scan, no storage, no upload.
- **Usage video**: Incoming call on a locked device → caller name is spoken aloud.

### 2. `READ_CALL_LOG`
- **Purpose**: Obtain the incoming caller number on Android 12+ (Android 12 removed
  the receipt of `EXTRA_INCOMING_NUMBER` for non-default dialers unless the app has
  this permission) and resolve the caller name from the call log.
- **Why required**: Together with `READ_CONTACTS`, enables spoken caller ID.
  Without it, the platform withholds the incoming number from non-default dialers.
- **Minimal access**: Reads `NUMBER` and `CACHED_NAME` for the current call only.
  No accessing audio recordings or historical call processing.
- **Usage video**: Same as READ_CONTACTS (call → spoken name/number).

### 3. `RECEIVE_SMS`
- **Purpose**: Speak incoming SMS text (optionally the sender name only) aloud for
  blind/low-vision users.
- **Why required**: `SmsReadingReceiver` (declared in the manifest) listens for the
  `SMS_RECEIVED` broadcast so it works instantly and on a locked screen. On Android
  19+ such receivers must be manifest-declared and therefore require `RECEIVE_SMS`.
- **Minimal access**: `Telephony.Sms.Intents.getMessagesFromIntent` — reads message
  body/sender **for speech only**. Never sends, archives, or modifies messages.
- **Privacy**: Feature is off by default; the permission dialog appears only when the
  user enables SMS reading. Lock-screen privacy suppresses message content, and an
  OTP filter prevents speaking verification codes in public.
- **Usage video**: Enable "SMS reading" → grant → receive an SMS on a locked/open
  screen → hear source + content (then show "source only" mode and a redacted OTP).

### Critical Play-Console notes
1. Without this declaration form these restricted permissions will be
   auto-rejected at upload.
2. Google prefers `RECEIVE_SMS` for the default SMS app; non-default accessibility
   apps must provide strong, clear accessibility justification.
3. A ~30–60s MP4 usage video per feature is mandatory (use fictional numbers/messages).
4. Keep the permissions declared — the features genuinely depend on them.
5. Re-review this file on every release to keep videos current.