// ناقل رسائل دعم Lord TTS على Cloudflare Workers.
//
// الوظيفة: يستقبل webhook من بوت تليجرام (عبر setWebhook) ويُعيد توجيه كل
// رسالة واردة إلى محادثتك الشخصية (SUPPORT_CHAT_ID) بصيغة مقروءة، دون
// الكشف عن أي وسيلة تواصل شخصية للمستخدم.
//
// المتغيرات السرّية (تُضبط عبر `wrangler secret put`، لا هنا ولا في git):
//   TELEGRAM_BOT_TOKEN — رمز البوت الذي حصلت عليه من @BotFather
//   SUPPORT_CHAT_ID    — معرّفك الرقمي في تليجرام (من @userinfobot)
//   WEBHOOK_SECRET     — سر عشوائي طويل تُمرّره عند ضبط setWebhook عبر
//                        secret_token ليُرسله تليجرام في ترويسة
//                        X-Telegram-Bot-Api-Secret-Token على كل طلب؛ يمنع
//                        رسائل مزيفة/انتحال/إغراق ناقل الدعم.
//
// التحصينات المضمّنة في هذا الملف:
//   1) تقطيع النصوص الطويلة: حدّ sendMessage في تليجرام هو 4096 حرفاً،
//      فالرسائل الأطول تُقسَّم إلى أجزاء لا تتجاوز [TELEGRAM_MAX_TEXT_LENGTH]
//      مع إشعار «طويل — N أجزاء»، دون قطع كيان HTML أو زوج سيروغيت (إيموجي).
//   2) قيود معدل: عداد لكل مرسل عبر Cloudflare KV (نافذة 60 ث / حدّ 20
//      رسالة) يمنع إغراق الناقل واستهلاك حصة Cloudflare أو حظْر البوت
//      من تليجرام. يتطلب ربط namespace باسم RATE_LIMITS في wrangler.toml
//      (انظر README)؛ وإن لم يُربط لا يُوقف الناقل ويُسمح للجميع.
//   3) شرح المرفقات (msg.caption): يُقرأ مع النص المباشر فلا يضيع الشرح
//      المكتوب مع الصورة/الملف/الصوت، ويُصرَّح بنوع المرفق إضافةً لنصه.
//   4) تراجع تلقائي للنص العادي: إن اعترض تليجرام على تنسيق HTML (Parse
//      Error) تُعاد الرسالة بلا parse_mode بدل فقدان رسالة الدعم.

const TELEGRAM_API = "https://api.telegram.org";

// حدّ حروف رسالة sendMessage لدى تليجرام هو 4096 فعلياً؛ نعمل بهامش أمان أدنى.
const TELEGRAM_MAX_TEXT_LENGTH = 4000;

// نافذة قيود المعدل (ثوانٍ) وأقصى عدد رسائل مسموح لكل مرسل داخل النافذة.
const RATE_LIMIT_WINDOW_SECONDS = 60;
const RATE_LIMIT_MAX_MESSAGES = 20;

// رسالة تُردّ للمستخدم عند تجاوز معدل الإرسال (تُرسل كخصٍص بلا تنسيق).
const RATE_LIMITED_TEXT =
  "تم تحديد معدل إرسال رسائلك مؤقتاً — انتظر قليلاً ثم أعد المحاولة.";

// الهروب من أحرف HTML قبل تمرير النص لتجنّب كسر تنسيق sendMessage.
function escapeHtml(text) {
  return String(text)
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;");
}

// فكّ تهريب [escapeHtml] — يُستخدم عند التراجع إلى النص العادي.
function unescapeHtml(text) {
  return String(text)
    .replace(/&lt;/g, "<")
    .replace(/&gt;/g, ">")
    .replace(/&amp;/g, "&");
}

// إزالة وسوم HTML وفكّ الكيانات (تحويل الرجوّع إلى نص عادي قابل للعرض).
function stripHtml(html) {
  return unescapeHtml(String(html).replace(/<[^>]*>/g, ""));
}

/**
 * تقسيم نص (غالباً مُهرَّب HTML بلا وسوم) إلى مقاطع لا يتجاوز كلٌّ منها
 * [maxLen] حرفاً. يُفضَّل الكسر عند نهاية سطر كامل ثم عند فراغ، ولا يقطع
 * أبداً كيان هروب (&…;) ولا زوج سيروغيت؛ ضماناً لإعادة التطابق التام
 * (chunks.join("") === النص الأصلي).
 */
function splitIntoChunks(text, maxLen = TELEGRAM_MAX_TEXT_LENGTH) {
  const s = String(text);
  const chunks = [];
  let rest = s;
  while (rest.length > maxLen) {
    // الكسر الأفضل: نهاية سطر كاملة (التالي للسطر)، ثم فراغ (التالي للفراغ).
    const nl = rest.lastIndexOf("\n", maxLen);
    let cut = nl !== -1 ? nl + 1 : -1;
    if (cut === -1) {
      const sp = rest.lastIndexOf(" ", maxLen);
      cut = sp !== -1 ? sp + 1 : -1;
    }
    if (cut === -1 || cut === 0) cut = maxLen;

    // لا نقطع كيان هروب (&…;): إن وُجد '&' قبل القطع ولا ';' ناصفة بعدُه
    // فالقَصّ يقع قبل '&' ليبدأ المقطع التالي بكيانٍ مكتمل.
    const amp = rest.lastIndexOf("&", cut - 1);
    if (amp !== -1) {
      const semiAfter = rest.indexOf(";", amp);
      if (semiAfter === -1 || semiAfter >= cut) cut = amp;
    }

    // لا نقطع زوج سيروغيت (إيموجي/رموز فوق BMP) في منتصفه.
    while (
      cut > 0 &&
      rest.charCodeAt(cut - 1) >= 0xd800 &&
      rest.charCodeAt(cut - 1) <= 0xdbff &&
      rest.charCodeAt(cut) >= 0xdc00 &&
      rest.charCodeAt(cut) <= 0xdfff
    ) {
      cut--;
    }
    if (cut <= 0) cut = maxLen; // حماية من الدوران اللانهائي

    chunks.push(rest.slice(0, cut));
    rest = rest.slice(cut);
  }
  if (rest.length > 0 || chunks.length === 0) chunks.push(rest);
  return chunks;
}

// رسالة ترحيبية تُردّ تلقائياً على /start لمن يفتح محادثة البوت.
const WELCOME_HTML =
  "مرحباً 👋 أنا بوت الدعم الرسمي لتطبيق <b>Lord TTS</b>.\n\n" +
  "اكتب رسالتك وسيصلني نصها مباشرةً. شكراً لتواصلك!";

async function sendToTelegram(env, chatId, text, parseMode) {
  const body = new URLSearchParams({ chat_id: chatId, text });
  if (parseMode) body.set("parse_mode", parseMode);
  return fetch(`${TELEGRAM_API}/bot${env.TELEGRAM_BOT_TOKEN}/sendMessage`, {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body,
  });
}

/**
 * إرسال نص بتنسيق HTML مع تراجع تلقائي للنص العادي: إن اعترض تليجرام على
 * التنسيق (error_code 400 ووصف يحوي "parse") تُعاد الرسالة نفسها بلا
 * parse_mode — بدل ضياع رسالة الدعم. غير ذلك تُعاد استجابة المحاولة الأولى.
 */
async function sendWithParseFallback(env, chatId, htmlText) {
  const first = await sendToTelegram(env, chatId, htmlText, "HTML");
  if (first.ok) return first;

  let parseError = false;
  try {
    const json = await first.json();
    parseError =
      json && json.error_code === 400 && /parse/i.test(json.description || "");
  } catch (err) {
    // إن لم تكن الاستجابة JSON فلا نستطيع تحديد السبب — نعرضها كما هي.
  }
  if (!parseError) return first;

  const plain = stripHtml(htmlText);
  return sendToTelegram(env, chatId, plain, null);
}

/**
 * قرار قيود المعدل (دالة نقية للاختبار): يُدخل السجلّ الحالي وزمن اللحظة
 * ويُعيد الحالة النهائية {allowed, record}.
 * - لا سجلّ أو نافذة منتهية → سجلّ جديد مسموح.
 * - ضمن النافذة وتحت العتبة → عدّاد+1 مسموح.
 * - تجاوز العتبة ضمن النافذة → مرفوض بلا تحديث.
 */
function rateLimitDecision(record, nowSeconds) {
  const windowStart = record !== null && record !== undefined ? record.windowStart : null;
  const count = record !== null && record !== undefined ? record.count : 0;
  if (windowStart === null || nowSeconds - windowStart >= RATE_LIMIT_WINDOW_SECONDS) {
    return { allowed: true, record: { windowStart: nowSeconds, count: 1 } };
  }
  if (count >= RATE_LIMIT_MAX_MESSAGES) {
    return { allowed: false, record };
  }
  return { allowed: true, record: { windowStart, count: count + 1 } };
}

/**
 * تطبيق قيود المعدل عبر Cloudflare KV (مفتاح لكل مرسل). إن لم يُربط
 * namespace (env.RATE_LIMITS غير موجود) أو أخفق KV يُسمح دائماً — لا يُوقف
 * الناقل أبداً وتُسجَّل الأخطاء فحسب.
 */
async function enforceRateLimit(env, senderId) {
  if (!env || !env.RATE_LIMITS) return { allowed: true };
  const now = Math.floor(Date.now() / 1000);
  const key = `rate:${senderId}`;
  let record = null;
  try {
    record = await env.RATE_LIMITS.get(key, "json");
  } catch (err) {
    console.error("rate limit get failed:", err);
    return { allowed: true };
  }
  const decision = rateLimitDecision(record, now);
  if (decision.record !== record) {
    try {
      await env.RATE_LIMITS.put(key, JSON.stringify(decision.record), {
        expirationTtl: RATE_LIMIT_WINDOW_SECONDS + 60,
      });
    } catch (err) {
      console.error("rate limit put failed:", err);
    }
  }
  return { allowed: decision.allowed };
}

/**
 * استخراج محتوى المرسل من التحديث: النص المباشر (msg.text) أو شرح المرفق
 * (msg.caption) — مهما كان المصدر يُهرَّب ويُقسَّم، مع تمييز نوع المرفق
 * إن وُجد (يُذكر المرفق ونصه معاً لا أحدهما بدل الآخر).
 * المرجِع: { attachment, textLabel, bodyLabel, bodyParts }.
 */
function extractSenderContent(msg) {
  const text = typeof msg.text === "string" ? msg.text : null;
  const caption = typeof msg.caption === "string" ? msg.caption : null;

  let attachment = null;
  if (msg.photo && msg.photo.length) {
    attachment = `🖼️ صورة (File ID: ${msg.photo[msg.photo.length - 1].file_id})`;
  } else if (msg.document) {
    attachment = `📄 ملف: ${escapeHtml(msg.document.file_name || "بدون اسم")} (File ID: ${msg.document.file_id})`;
  } else if (msg.voice) {
    attachment = `🎙️ رسالة صوتية (File ID: ${msg.voice.file_id})`;
  } else if (msg.video_note) {
    attachment = `⏺️ رسالة فيديو (File ID: ${msg.video_note.file_id})`;
  }

  const isText = text !== null;
  const isCaption = caption !== null;
  const content = isText ? text : caption;
  if (content === null) {
    return { attachment, textLabel: null, bodyLabel: null, bodyParts: [] };
  }

  const bodyParts = splitIntoChunks(escapeHtml(content));
  const baseLabel = isText ? "💬 <b>النص:</b>" : "📝 <b>الشرح:</b>";
  const bodyLabel =
    bodyParts.length > 1 ? `${baseLabel} (طويل — ${bodyParts.length} أجزاء)` : baseLabel;
  return {
    attachment,
    textLabel: isText ? "النص" : "الشرح",
    bodyLabel,
    bodyParts,
  };
}

/**
 * بناء قائمة الرسائل الجاهزة من رأسٍ ثابت ومقاطع نص مرقمة: إن اتّسع المحتوى
 * في رسالة واحدة أُعيدت رسالة واحدة، وإلا أُرسل الرأس أولاً ثم كل مقطع على
 * حدة. كل رسالة ناتجة لا تتجاوز [TELEGRAM_MAX_TEXT_LENGTH].
 */
function buildRelayMessages(header, bodyLabel, bodyParts) {
  const head = [...header, bodyLabel].join("\n");
  if (
    bodyParts.length === 1 &&
    head.length + 1 + bodyParts[0].length <= TELEGRAM_MAX_TEXT_LENGTH
  ) {
    return [`${head}\n${bodyParts[0]}`];
  }
  const messages = [head];
  for (const part of bodyParts) messages.push(part);
  return messages;
}

// توجيه رسالة مستخدم إلى معرّفك الرقمي بصيغة واضحة.
async function forwardMessage(env, update) {
  const msg = update.message;
  if (!msg) return "ignored";

  const from = msg.from || {};
  const senderName = [from.first_name, from.last_name].filter(Boolean).join(" ") || "مستخدِم";
  const handle = from.username ? `@${from.username}` : "ليس له معرّف";
  const senderId = from.id || "؟";

  // أول رسالة: يبدأ المستخدم محادثة البوت
  if (typeof msg.text === "string" && msg.text.startsWith("/start")) {
    await sendWithParseFallback(env, msg.chat.id, WELCOME_HTML);
    return "welcomed";
  }

  // قيود المعدل تُفحص قبل التوجيه لكل مرسل (نافذة 60 ث / حدّ 20 رسالة).
  if (from.id !== undefined && from.id !== null) {
    const rate = await enforceRateLimit(env, String(from.id));
    if (!rate.allowed) {
      await sendToTelegram(env, msg.chat.id, RATE_LIMITED_TEXT, null);
      return "rate_limited";
    }
  }

  const { attachment, bodyLabel, bodyParts } = extractSenderContent(msg);

  const header = [];
  header.push("📨 <b>رسالة جديدة من مستخدِم</b>");
  header.push(`👤 <b>الاسم:</b> ${escapeHtml(senderName)}`);
  header.push(`🪪 <b>المعرّف:</b> ${escapeHtml(handle)} (ر.ق. ${senderId})`);
  header.push(`🕐 <b>الوقت:</b> ${new Date(msg.date * 1000).toISOString()}`);
  if (attachment) header.push(`📎 <b>المرفق:</b> ${attachment}`);
  header.push("");

  const messages =
    bodyParts.length > 0
      ? buildRelayMessages(header, bodyLabel, bodyParts)
      : [header.join("\n")];

  for (const message of messages) {
    const res = await sendWithParseFallback(env, env.SUPPORT_CHAT_ID, message);
    if (!res.ok) {
      console.error("forward failed:", res.status, await res.text());
      return "forward_failed";
    }
  }
  return "forwarded";
}

// مقارنة زمنية ثابتة (constant-time) لسلسلتين بنفس الطول حول كشف Timing Attack.
// لا تُقصَّر إلا عند اختلاف الطول أو الأعضاء؛ ولا تكشف كم ثنائي مطابق.
function secureEqual(a, b) {
  if (typeof a !== "string" || typeof b !== "string") return false;
  if (a.length === 0 || a.length !== b.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i++) diff |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return diff === 0;
}

export default {
  async fetch(request, env) {
    // فحص GET فقط (صفحة الفحص)
    if (request.method === "GET") {
      return new Response("Lord TTS support relay is running", { status: 200 });
    }
    if (request.method !== "POST") {
      return new Response("Method not allowed", { status: 405 });
    }

    // حماية webhook: نرفض أي POST لا يحمل السر الصحيح. تليجرام يرسل السر في
    // ترويسة X-Telegram-Bot-Api-Secret-Token إن عُيّن بـ secret_token عند
    // setWebhook. نستخدم مقارنة زمنية ثابتة (secureEqual) لتجنّب Timing Attack.
    const supplied = request.headers.get("X-Telegram-Bot-Api-Secret-Token") ?? "";
    if (!secureEqual(env.WEBHOOK_SECRET, supplied)) {
      return new Response(JSON.stringify({ ok: false }), { status: 401 });
    }

    try {
      const update = await request.json();
      const result = await forwardMessage(env, update);
      // نرد 200 دائماً حتى لا يعيد تليجرام إرسال الرسالة مراراً
      return new Response(JSON.stringify({ ok: true, result }), {
        status: 200,
        headers: { "Content-Type": "application/json" },
      });
    } catch (err) {
      console.error("update failed:", err);
      return new Response(JSON.stringify({ ok: false }), { status: 200 });
    }
  },
};

// دوال نقية مستخرَجة لاختبارات Node (node:test) — بلا أي تأثير على النشر.
export {
  escapeHtml,
  unescapeHtml,
  stripHtml,
  splitIntoChunks,
  rateLimitDecision,
  enforceRateLimit,
  extractSenderContent,
  buildRelayMessages,
};