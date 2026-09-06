// ناقل رسائل دعم Lord TTS على Cloudflare Workers.
//
// الوظيفة: يستقبل webhook من بوت تليجرام (عبر setWebhook) ويُعيد توجيه كل
// رسالة واردة إلى محادثتك الشخصية (SUPPORT_CHAT_ID) بصيغة مقروءة، دون
// الكشف عن أي وسيلة تواصل شخصية للمستخدم.
//
// المتغيرات السرّية (تُضبط عبر `wrangler secret put`، لا هنا ولا في git):
//   TELEGRAM_BOT_TOKEN — رمز البوت الذي حصلت عليه من @BotFather
//   SUPPORT_CHAT_ID    — معرّفك الرقمي في تليجرام (من @userinfobot)

const TELEGRAM_API = "https://api.telegram.org";

// الهروب من أحرف HTML قبل تمرير النص لتجنّب كسر تنسيق sendMessage.
function escapeHtml(text) {
  return String(text)
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;");
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

// توجيه رسالة مستخدم إلى معرّفك الرقمي بصيغة واضحة.
async function forwardMessage(env, update) {
  const msg = update.message;
  if (!msg) return "ignored";

  const from = msg.from || {};
  const senderName = [from.first_name, from.last_name].filter(Boolean).join(" ") || "مستخدِم";
  const handle = from.username ? `@${from.username}` : "ليس له معرّف";
  const senderId = from.id || "؟";

  // أول رسالة: يبدأ المستخدم محادثة البوت
  if (msg.text && msg.text.startsWith("/start")) {
    await sendToTelegram(env, msg.chat.id, WELCOME_HTML, "HTML");
    return "welcomed";
  }

  const lines = [];
  lines.push("📨 <b>رسالة جديدة من مستخدِم</b>");
  lines.push(`👤 <b>الاسم:</b> ${escapeHtml(senderName)}`);
  lines.push(`🪪 <b>المعرّف:</b> ${escapeHtml(handle)} (ر.ق. ${senderId})`);
  lines.push(`🕐 <b>الوقت:</b> ${new Date(msg.date * 1000).toISOString()}`);
  lines.push("");
  if (msg.text) {
    lines.push("💬 <b>النص:</b>");
    lines.push(escapeHtml(msg.text));
  } else if (msg.photo && msg.photo.length) {
    lines.push(`🖼️ أرسل مرفقاً: صورة (File ID: ${msg.photo[msg.photo.length - 1].file_id})`);
  } else if (msg.document) {
    lines.push(`📄 أرفق ملفاً: ${escapeHtml(msg.document.file_name || "بدون اسم")}`);
  } else if (msg.voice) {
    lines.push(`🎙️ أرسل رسالة صوتية (File ID: ${msg.voice.file_id})`);
  } else if (msg.video_note) {
    lines.push(`⏺️ أرسل رسالة فيديو (File ID: ${msg.video_note.file_id})`);
  } else {
    lines.push(`[…] نوع آخر من الرسائل (type: ${msg.type ?? "unknown"})`);
  }

  const forwarded = await sendToTelegram(env, env.SUPPORT_CHAT_ID, lines.join("\n"), "HTML");
  if (!forwarded.ok) {
    console.error("forward failed:", forwarded.status, await forwarded.text());
    return "forward_failed";
  }
  return "forwarded";
}

export default {
  async fetch(request, env) {
    if (request.method !== "POST") {
      return new Response("Lord TTS support relay is running", { status: 200 });
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