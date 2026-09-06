# ناقل رسائل دعم Lord TTS (Cloudflare Worker)

يستقبل رسائل بوت تليجرام `@LordTTSBot` ويوجّهها إلى محادثتك الشخصية،
دون كشف أي وسيلة تواصل شخصية للمستخدم. يعمل مجاناً على منصة Workers.

## المتطلبات
- حساب في Cloudflare (مجاني) — أنشئه من https://dash.cloudflare.com
- Node.js مثبّت على جهازك (لتشغيل أوامر wrangler)
- رمز البوت `TELEGRAM_BOT_TOKEN` (من @BotFather) — سرّي
- معرّفك الرقمي `SUPPORT_CHAT_ID` (من @userinfobot) — خاص

## 1) استخراج معرّفك الرقمي (خطوة واحدة على هاتفك)
افتح تليجرام وابدأ محادثة مع `@userinfobot` — سيرد برسالة تبدأ بـ
`Your user ID is: 123456789` — هذا الرقم هو `SUPPORT_CHAT_ID`.

## 2) رفع الكود إلى Cloudflare
من هذا المجلد (`relay-worker`) نفّذ في الطرفية:

```bash
npx wrangler login
npx wrangler deploy
```

سيعرض رابط الـ Worker مثل:
`https://lord-tts-support-relay.<حسابك>.workers.dev`

## 3) ضبط السرّين (مرة واحدة فقط)
```bash
npx wrangler secret put TELEGRAM_BOT_TOKEN
npx wrangler secret put SUPPORT_CHAT_ID
```
أدخل كلاً منهما عند الطلب. **لا يرتفع أي منهما إلى الريبو.**

## 4) ربط البوت بالـ Worker (webhook)
**انتبه:** يجب ضبط رمز البوت قبل هذه الخطوة (أو استخدمه مباشرة هنا —
الرمز يُمرَّر في الحقل `url` ولا يُخزَّن في ملف). نفّذ:

```bash
curl -F "url=https://lord-tts-support-relay.<حسابك>.workers.dev" \
  "https://api.telegram.org/bot<TELEGRAM_BOT_TOKEN>/setWebhook"
```

أو من دون curl — افتح في المتصفح:
```
https://api.telegram.org/bot<رمزك>/setWebhook?url=https://lord-tts-support-relay.<حسابك>.workers.dev
```

سترد: `{"ok":true,...}`.

## 5) الاختبار
- افتح `https://t.me/LordTTSBot` وابدأ المحادثة: سيرد البوت بالترحيب.
- أرسل رسالة: ستصل لك في محادثتك الشخصية بصيغة واضحة (اسم + معرّف + نص).
- جرّب إرفاق صورة/ملف/رسالة صوتية للتأكد من التوجيه.

## سلوك الكود
- `/start` → رد ترحيبي للمستخدم.
- أي رسالة أخرى → تُرسل إلى `SUPPORT_CHAT_ID` بتفاصيل المرسل والنص.
- المرفقات (صورة/ملف/صوت/فيديو) تُمرَّر بمعرّف الملف (File ID) كمرجع.
- يستجيب دائماً بـ 200 فلا يكرر تليجرام إرسال التحديثات.

## الأسئلة الشائعة
- **لماذا التصحيح في فيديو؟** الكود يتجاهل `video` صراحةً (fallback عام يشمل
  `video` حسابياً) — إن أردت إضافة دعم الفيديو كمرفق كامل أضِف `msg.video`.
- **كيف أستبدل البوت لاحقاً؟** عدّل `DEVElOPER_SUPPORT_URL` في
  `VoiceSelectionFragment.kt` ثم أعد البناء.

## أمان
- الرمز والمعرّف **سريّان**: يُضبطان عبر `wrangler secret put` فقط.
- لا يُرفع أي منهما إلى git: `wrangler.toml` لا يحتوي أياً منهما.