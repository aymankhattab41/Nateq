import { test } from "node:test";
import assert from "node:assert/strict";
import {
  escapeHtml,
  unescapeHtml,
  stripHtml,
  splitIntoChunks,
  rateLimitDecision,
  buildRelayMessages,
  extractSenderContent,
  forwardMessage,
} from "../src/index.js";

// ── escapeHtml / stripHtml ───────────────────────────────────────────────

test("escapeHtml يهرب & < >", () => {
  assert.equal(escapeHtml(`أ & ب < ج > د`), "أ &amp; ب &lt; ج &gt; د");
});

test("unescapeHtml يفك التهريب استرداداً", () => {
  assert.equal(unescapeHtml("&amp; &lt; &gt;"), "& < >");
});

test("stripHtml يزيل الوسوم ويفكّ الكيانات", () => {
  assert.equal(stripHtml("<b>مرحبا</b> &amp; خير"), "مرحبا & خير");
});

// ── splitIntoChunks ──────────────────────────────────────────────────────

test("splitIntoChunks: النص القصير يبقى جزءاً واحداً", () => {
  const chunks = splitIntoChunks("نص قصير");
  assert.equal(chunks.length, 1);
  assert.equal(chunks[0], "نص قصير");
});

test("splitIntoChunks: لا يتجاوز أي مقطع الحد وتعيد التطابق التام", () => {
  const long =
    "مرحباً بالعالم! هذه رسالة دعم طويلة جداً. ".repeat(100) + "النهاية 🎉.";
  const chunks = splitIntoChunks(long, 4000);
  assert.ok(chunks.length >= 2, "يجب أن يتقسم النص الطويل");
  for (const c of chunks) {
    assert.ok(c.length <= 4000, `مقطع تجاوز الحد: ${c.length}`);
  }
  assert.equal(chunks.join(""), long, "إعادة التطابق التام مع الأصل");
});

test("splitIntoChunks: لا يقطع كيان HTML", () => {
  const text = "&amp;".repeat(1200); // 6000 حرفاً — حدود القطع داخل حدود الكيانات
  const chunks = splitIntoChunks(text, 4000);
  for (const c of chunks) {
    let i = c.indexOf("&");
    while (i !== -1) {
      const semi = c.indexOf(";", i);
      assert.ok(semi !== -1, `كيان مقطوع في مقطع: ${c}`);
      i = c.indexOf("&", semi + 1);
    }
  }
  assert.equal(chunks.join(""), text);
});

test("splitIntoChunks: لا يقطع زوج سيروغيت (إيموجي)", () => {
  // 800 إيموجي (كلٌّ منه زوج سيروغيت = حرفان) → 1600 وحدة + فواصل
  const emoji = "😀".repeat(800);
  const text = "قبل ".repeat(400) + emoji + " بعد".repeat(400);
  const chunks = splitIntoChunks(text, 400);
  let index = 0;
  for (const c of chunks) {
    // لاحقاً يجب ألا يبدأ المقطع بزميل سيروغيت منخفض مقطوع
    if (index > 0) {
      const first = text.charCodeAt(index);
      assert.ok(
        !(first >= 0xdc00 && first <= 0xdfff),
        "مقطع يبدأ بنصف سيروغيت منخفض"
      );
    }
    index += c.length;
  }
  assert.equal(chunks.join(""), text);
});

test("splitIntoChunks: يُفضّل كسر نهاية السطر الكاملة", () => {
  // 40 سطراً طول كلٌّ منها 100 حرفاً ثم '\n' — النافذة 400 يوجد بها سطرُ
  // فتُقصّ عند نهايته التامة، وكل مقطع وسيط ينتهي بـ '\n'.
  const text = ("أ".repeat(100) + "\n").repeat(40);
  const chunks = splitIntoChunks(text, 400);
  for (const [i, c] of chunks.entries()) {
    if (i < chunks.length - 1) {
      assert.equal(c[c.length - 1], "\n", `مقطع ${i} لم يُقصّ عند نهاية سطر`);
    }
  }
  assert.equal(chunks.join(""), text);
});

// ── rateLimitDecision ────────────────────────────────────────────────────

test("rateLimitDecision: أول رسالة في نافذة جديدة مسموحة", () => {
  const d = rateLimitDecision(null, 1000);
  assert.equal(d.allowed, true);
  assert.deepEqual(d.record, { windowStart: 1000, count: 1 });
});

test("rateLimitDecision: نافذة منتهية تُعيد الإحصاء", () => {
  const oldRecord = { windowStart: 900, count: 20 };
  const d = rateLimitDecision(oldRecord, 961); // 61 ثانية لاحقة
  assert.equal(d.allowed, true);
  assert.deepEqual(d.record, { windowStart: 961, count: 1 });
});

test("rateLimitDecision: ضمن النافذة وتحت العتبة يزداد العد", () => {
  const rec = { windowStart: 1000, count: 3 };
  const d = rateLimitDecision(rec, 1010);
  assert.equal(d.allowed, true);
  assert.deepEqual(d.record, { windowStart: 1000, count: 4 });
});

test("rateLimitDecision: بلوغ العتبة ضمن النافذة يُرفض", () => {
  const rec = { windowStart: 1000, count: 20 };
  const d = rateLimitDecision(rec, 1010);
  assert.equal(d.allowed, false);
});

test("rateLimitDecision: تجاوز العتبة ضمن النافذة يُرفض", () => {
  const rec = { windowStart: 1000, count: 21 };
  const d = rateLimitDecision(rec, 1010);
  assert.equal(d.allowed, false);
});

// ── buildRelayMessages ───────────────────────────────────────────────────

test("buildRelayMessages: رسالة واحدة عند قصر المحتوى", () => {
  const msgs = buildRelayMessages(["📨 رأس", ""], "💬 <b>النص:</b>", ["مرحبا"]);
  assert.equal(msgs.length, 1);
  assert.ok(msgs[0].includes("مرحبا"));
});

test("buildRelayMessages: تقسيم لعدة رسائل عند الطول وكل واحدة دون الحد", () => {
  const parts = ["أ".repeat(3000), "ب".repeat(3000)];
  const msgs = buildRelayMessages(["📨 رأس", ""], "💬 <b>النص:</b>", parts);
  assert.equal(msgs.length, 3); // رأس + جزآن
  for (const m of msgs) {
    assert.ok(m.length <= 4000, `رسالة تجاوزت الحد: ${m.length}`);
  }
});

// ── extractSenderContent ─────────────────────────────────────────────────

test("extractSenderContent: نص مباشر فقط", () => {
  const r = extractSenderContent({ text: "السلام عليكم" });
  assert.equal(r.attachment, null);
  assert.equal(r.textLabel, "النص");
  assert.equal(r.bodyParts.length, 1);
  assert.ok(r.bodyParts[0].includes("السلام"));
});

test("extractSenderContent: صورة بشرح msg.caption تُدمج المرفق والنص", () => {
  const r = extractSenderContent({
    photo: [{ file_id: "PHOTO_FILE_ID" }],
    caption: "هذه لقطة الشاشة",
  });
  assert.ok(r.attachment.includes("PHOTO_FILE_ID"), "يُذكر المرفق");
  assert.ok(r.attachment.includes("🖼️"));
  assert.equal(r.textLabel, "الشرح");
  assert.ok(r.bodyParts[0].includes("هذه لقطة الشاشة"));
});

test("extractSenderContent: مرفق بلا نص/شرح يُذكر وحده", () => {
  const r = extractSenderContent({
    document: { file_name: "تقرير.pdf", file_id: "DOC_ID" },
  });
  assert.ok(r.attachment.includes("تقرير.pdf"));
  assert.equal(r.bodyParts.length, 0);
  assert.equal(r.bodyLabel, null);
});

test("extractSenderContent: شرح طويل يُقسَّم مع إشعار عدد الأجزاء", () => {
  const r = extractSenderContent({
    voice: { file_id: "VOICE_ID" },
    caption: "كلمة ".repeat(1500), // > 4000 حرفاً
  });
  assert.deepEqual(r.textLabel, "الشرح");
  assert.ok(r.bodyParts.length > 1);
  assert.ok(r.bodyLabel.includes(`طويل — ${r.bodyParts.length} أجزاء`));
  for (const p of r.bodyParts) assert.ok(p.length <= 4000);
});

// ── تضخيم الـ Rate Limit: إسقاط صامت بلا إرسال لتليجرام ────────────────

test("forwardMessage عند التجاوز يُسقط بصمت بلا أي طلب لتليجرام", async () => {
  // KV مقلَّد: يُعيد سجلاً بلغ العتبة ضمن نافذة حالية (غير منتهية) → مرفوض.
  const windowStart = Math.floor(Date.now() / 1000) - 10; // قبل 10 ثوانٍ
  const env = {
    RATE_LIMITS: {
      get: async () => ({ windowStart, count: 20 }),
      put: async () => {},
    },
  };
  // أي استدعاء حقيقي للشبكة (تليجرام) يُفشل الاختبار — الواجب ألا يحدث شيء.
  const realFetch = globalThis.fetch;
  let telegramCalled = false;
  globalThis.fetch = async () => {
    telegramCalled = true;
    return new Response("{}", { status: 200 });
  };
  try {
    const result = await forwardMessage(env, {
      message: {
        text: "رسالة تتجاوز الحد",
        from: { id: 42, first_name: "م" },
        chat: { id: 42 },
        date: 10,
      },
    });
    assert.equal(result, "rate_limited");
    assert.equal(telegramCalled, false, "لا يُرسل أي شيء إلى تليجرام عند التجاوز");
  } finally {
    globalThis.fetch = realFetch;
  }
});

// ── معالج الاستيراد (HTTP fetch) — التسليم 200 عند تجاوز المعدل ─────────

test("fetch: تجاوز المعدل يُسلَّم 200 OK بصمت (بند 8.1) — لا إعادة إرسال DDoS", async () => {
  const mod = await import("../src/index.js");
  const handler = mod.default.fetch;
  const env = {
    WEBHOOK_SECRET: "s3cr3t",
    // سجل يتجاوز العتبة (20 في نافذة 60 ث) — يدفع forwardMessage لفكِّ
    // "rate_limited" دون أي اتصال شبكة بتليجرام.
    RATE_LIMITS: {
      get: async () => ({
        windowStart: Math.floor(Date.now() / 1000) - 10,
        count: 21,
      }),
      put: async () => {},
    },
  };
  // أي استدعاء حقيقي لتليجرام يُفشل الاختبار — الواجب ألا يحدث.
  const realFetch = globalThis.fetch;
  globalThis.fetch = async () => {
    throw new Error("يجب ألا يصل شيء إلى تليجرام عند التجاوز");
  };
  try {
    const request = new Request("https://relay.example/webhook", {
      method: "POST",
      headers: { "X-Telegram-Bot-Api-Secret-Token": "s3cr3t" },
      body: JSON.stringify({
        message: {
          text: "رسالة تتجاوز الحد",
          from: { id: 42, first_name: "م" },
          chat: { id: 42 },
          date: 10,
        },
      }),
    });
    const response = await handler(request, env);
    assert.equal(response.status, 200, "الرد 200 دائماً حتى عند التجاوز");
  } finally {
    globalThis.fetch = realFetch;
  }
});

// ── فحص المستندات والملفات: الحدود والتوجيه ────────────────────────────

test("forwardMessage: يرفض الملفات التي تتجاوز 10 ميجابايت وينبه المستخدم", async () => {
  const env = {
    TELEGRAM_BOT_TOKEN: "dummy_token",
    SUPPORT_CHAT_ID: "999",
  };
  const calls = [];
  const realFetch = globalThis.fetch;
  globalThis.fetch = async (url, options) => {
    calls.push({ url, options });
    return new Response(JSON.stringify({ ok: true }), { status: 200 });
  };
  try {
    const result = await forwardMessage(env, {
      message: {
        document: {
          file_name: "huge_log.txt",
          file_id: "HUGE_FILE_ID",
          file_size: 15 * 1024 * 1024, // 15MB > 10MB
        },
        from: { id: 101, first_name: "علي" },
        chat: { id: 101 },
        date: 100,
      },
    });
    assert.equal(result, "file_too_large");
    assert.equal(calls.length, 1, "يُرسل رسالة رفض واحدة للمستخدم فقط");
    const bodyText = calls[0].options.body.toString();
    assert.ok(bodyText.includes("chat_id=101"), "الرد يذهب إلى دردشة المستخدم");
    assert.ok(bodyText.includes("10"), "يذكر حد 10 ميجابايت");
  } finally {
    globalThis.fetch = realFetch;
  }
});

test("forwardMessage: يقبل ملف السجل (أقل من 10 ميجابايت) ويوجهه للمطور مع تأكيد", async () => {
  const env = {
    TELEGRAM_BOT_TOKEN: "dummy_token",
    SUPPORT_CHAT_ID: "999",
  };
  const calls = [];
  const realFetch = globalThis.fetch;
  globalThis.fetch = async (url, options) => {
    calls.push({ url, options });
    return new Response(JSON.stringify({ ok: true }), { status: 200 });
  };
  try {
    const result = await forwardMessage(env, {
      message: {
        document: {
          file_name: "nateq_log.txt",
          file_id: "VALID_LOG_FILE_ID",
          file_size: 500 * 1024, // 500KB
        },
        from: { id: 102, first_name: "سارة" },
        chat: { id: 102 },
        date: 200,
      },
    });
    assert.equal(result, "forwarded");
    // يجب أن تشمل الاستدعاءات:
    // 1) إشعار/رأس للمطور (sendMessage)
    // 2) توجيه الملف الفعلي للمطور (sendDocument)
    // 3) رسالة تأكيد للمستخدم (sendMessage)
    assert.ok(calls.length >= 3, `توقع 3 استدعاءات على الأقل، ورد: ${calls.length}`);
    const hasSendDoc = calls.some(
      (c) =>
        c.url.includes("/sendDocument") &&
        c.options.body.toString().includes("document=VALID_LOG_FILE_ID") &&
        c.options.body.toString().includes("chat_id=999")
    );
    assert.ok(hasSendDoc, "يجب استدعاء sendDocument بمعرف الملف ومحادثة الدعم");
    const hasConfirm = calls.some(
      (c) =>
        c.url.includes("/sendMessage") &&
        c.options.body.toString().includes("chat_id=102")
    );
    assert.ok(hasConfirm, "يجب إرسال تأكيد الاستلام لدردشة المستخدم");
  } finally {
    globalThis.fetch = realFetch;
  }
});

test("forwardMessage: يستقبل الرسائل الصوتية (msg.voice) ويوجهها للمطور عبر sendVoice", async () => {
  const env = {
    TELEGRAM_BOT_TOKEN: "dummy_token",
    SUPPORT_CHAT_ID: "999",
  };
  const calls = [];
  const realFetch = globalThis.fetch;
  globalThis.fetch = async (url, options) => {
    calls.push({ url, options });
    return new Response(JSON.stringify({ ok: true }), { status: 200 });
  };
  try {
    const result = await forwardMessage(env, {
      message: {
        voice: {
          file_id: "VOICE_SAMPLE_ID",
          duration: 12,
        },
        caption: "اسمع هذا التقطع في النطق",
        from: { id: 103, first_name: "خالد" },
        chat: { id: 103 },
        date: 300,
      },
    });
    assert.equal(result, "forwarded");
    const hasSendVoice = calls.some(
      (c) =>
        c.url.includes("/sendVoice") &&
        c.options.body.toString().includes("voice=VOICE_SAMPLE_ID") &&
        c.options.body.toString().includes("chat_id=999")
    );
    assert.ok(hasSendVoice, "يجب استدعاء sendVoice وإعادة إرسال الصوت للمطور");
  } finally {
    globalThis.fetch = realFetch;
  }
});