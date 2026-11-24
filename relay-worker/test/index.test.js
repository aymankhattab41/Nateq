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
  sendWithParseFallback,
  checkAndMarkUpdateId,
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

test("forwardMessage: فشل sendDocument HTTP500 وعدم إرسال نجاح", async () => {
  const env = {
    TELEGRAM_BOT_TOKEN: "dummy_token",
    SUPPORT_CHAT_ID: "999",
  };
  const calls = [];
  const realFetch = globalThis.fetch;
  globalThis.fetch = async (url, options) => {
    calls.push({ url, options });
    if (url.includes("/sendDocument")) {
      return new Response(JSON.stringify({ ok: false, error_code: 500 }), {
        status: 500,
      });
    }
    return new Response(JSON.stringify({ ok: true }), { status: 200 });
  };
  try {
    const result = await forwardMessage(env, {
      message: {
        document: {
          file_id: "FAIL_DOC_ID",
          file_name: "crash_log.txt",
          file_size: 1024,
        },
        from: { id: 104, first_name: "مستخدم" },
        chat: { id: 104 },
        date: 400,
      },
    });
    assert.equal(result, "document_send_failed");
    const hasConfirm = calls.some(
      (c) =>
        c.url.includes("/sendMessage") &&
        c.options.body.toString().includes("chat_id=104") &&
        c.options.body.toString().includes("بنجاح")
    );
    assert.equal(hasConfirm, false, "لا يجب إرسال تأكيد نجاح عند فشل sendDocument");
  } finally {
    globalThis.fetch = realFetch;
  }
});

test("forwardMessage: /start مع حصة ممتلئة يُرفض بـ rate_limited", async () => {
  const kvMap = new Map();
  kvMap.set("rate:200", JSON.stringify({ windowStart: Math.floor(Date.now() / 1000), count: 25 }));
  const env = {
    TELEGRAM_BOT_TOKEN: "dummy_token",
    SUPPORT_CHAT_ID: "999",
    RATE_LIMITS: {
      get: async (k) => (kvMap.has(k) ? JSON.parse(kvMap.get(k)) : null),
      put: async (k, v) => kvMap.set(k, v),
    },
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
        text: "/start",
        from: { id: 200, first_name: "مهاجم" },
        chat: { id: 200 },
        date: 500,
      },
    });
    assert.equal(result, "rate_limited");
    assert.equal(calls.length, 0, "لا يجب إرسال أي رسالة ترحيبية عند تجاوز الحصة");
  } finally {
    globalThis.fetch = realFetch;
  }
});

test("forwardMessage: تكرار update_id يعيد duplicate_update دون تكرار الإرسال", async () => {
  const kvMap = new Map();
  const env = {
    TELEGRAM_BOT_TOKEN: "dummy_token",
    SUPPORT_CHAT_ID: "999",
    RATE_LIMITS: {
      get: async (k) => (kvMap.has(k) ? kvMap.get(k) : null),
      put: async (k, v) => kvMap.set(k, v),
    },
  };
  let sendCount = 0;
  const realFetch = globalThis.fetch;
  globalThis.fetch = async () => {
    sendCount++;
    return new Response(JSON.stringify({ ok: true }), { status: 200 });
  };
  try {
    const update = {
      update_id: 123456,
      message: {
        text: "مرحبا بالدعم",
        from: { id: 300, first_name: "علي" },
        chat: { id: 300 },
        date: 600,
      },
    };
    const res1 = await forwardMessage(env, update);
    assert.equal(res1, "forwarded");
    assert.ok(sendCount > 0);

    const prevSendCount = sendCount;
    const res2 = await forwardMessage(env, update);
    assert.equal(res2, "duplicate_update");
    assert.equal(sendCount, prevSendCount, "لا يجب تكرار الإرسال للـ update_id المكرر");
  } finally {
    globalThis.fetch = realFetch;
  }
});

test("forwardMessage: فشل إرسال صورة HTTP500 يعيد photo_send_failed ولا يعلمها delivered", async () => {
  const kvMap = new Map();
  const env = {
    TELEGRAM_BOT_TOKEN: "dummy_token",
    SUPPORT_CHAT_ID: "999",
    RATE_LIMITS: {
      get: async (k) => (kvMap.has(k) ? kvMap.get(k) : null),
      put: async (k, v) => kvMap.set(k, v),
    },
  };
  const realFetch = globalThis.fetch;
  globalThis.fetch = async (url) => {
    if (url.includes("/sendPhoto")) {
      return new Response(JSON.stringify({ ok: false, error_code: 500 }), {
        status: 500,
      });
    }
    return new Response(JSON.stringify({ ok: true }), { status: 200 });
  };
  try {
    const update = {
      update_id: 2001,
      message: {
        photo: [{ file_id: "FAIL_PHOTO_ID" }],
        from: { id: 401, first_name: "سامي" },
        chat: { id: 401 },
        date: 700,
      },
    };
    const res = await forwardMessage(env, update);
    assert.equal(res, "photo_send_failed");
    const rawState = kvMap.get("update:2001");
    assert.ok(rawState);
    const parsed = JSON.parse(rawState);
    assert.notEqual(parsed.state, "delivered", "لا يجب وسمها delivered عند فشل الصورة");
  } finally {
    globalThis.fetch = realFetch;
  }
});

test("forwardMessage: فشل إرسال صوت HTTP500 يعيد audio_send_failed", async () => {
  const kvMap = new Map();
  const env = {
    TELEGRAM_BOT_TOKEN: "dummy_token",
    SUPPORT_CHAT_ID: "999",
    RATE_LIMITS: {
      get: async (k) => (kvMap.has(k) ? kvMap.get(k) : null),
      put: async (k, v) => kvMap.set(k, v),
    },
  };
  const realFetch = globalThis.fetch;
  globalThis.fetch = async (url) => {
    if (url.includes("/sendAudio")) {
      return new Response(JSON.stringify({ ok: false, error_code: 500 }), {
        status: 500,
      });
    }
    return new Response(JSON.stringify({ ok: true }), { status: 200 });
  };
  try {
    const update = {
      update_id: 2002,
      message: {
        audio: { file_id: "FAIL_AUDIO_ID", title: "مقطع" },
        from: { id: 402, first_name: "مروان" },
        chat: { id: 402 },
        date: 710,
      },
    };
    const res = await forwardMessage(env, update);
    assert.equal(res, "audio_send_failed");
  } finally {
    globalThis.fetch = realFetch;
  }
});

test("forwardMessage: فشل إرسال رسالة صوتية HTTP500 يعيد voice_send_failed", async () => {
  const kvMap = new Map();
  const env = {
    TELEGRAM_BOT_TOKEN: "dummy_token",
    SUPPORT_CHAT_ID: "999",
    RATE_LIMITS: {
      get: async (k) => (kvMap.has(k) ? kvMap.get(k) : null),
      put: async (k, v) => kvMap.set(k, v),
    },
  };
  const realFetch = globalThis.fetch;
  globalThis.fetch = async (url) => {
    if (url.includes("/sendVoice")) {
      return new Response(JSON.stringify({ ok: false, error_code: 500 }), {
        status: 500,
      });
    }
    return new Response(JSON.stringify({ ok: true }), { status: 200 });
  };
  try {
    const update = {
      update_id: 2003,
      message: {
        voice: { file_id: "FAIL_VOICE_ID" },
        from: { id: 403, first_name: "يوسف" },
        chat: { id: 403 },
        date: 720,
      },
    };
    const res = await forwardMessage(env, update);
    assert.equal(res, "voice_send_failed");
  } finally {
    globalThis.fetch = realFetch;
  }
});

test("forwardMessage: تتبع التسليم الجزئي للأجزاء المتعددة عند فشل جزء وإعادة المحاولة", async () => {
  const kvMap = new Map();
  const env = {
    TELEGRAM_BOT_TOKEN: "dummy_token",
    SUPPORT_CHAT_ID: "999",
    RATE_LIMITS: {
      get: async (k) => (kvMap.has(k) ? kvMap.get(k) : null),
      put: async (k, v) => kvMap.set(k, v),
    },
  };
  const calls = [];
  const realFetch = globalThis.fetch;
  let failSecond = true;
  globalThis.fetch = async (url, options) => {
    const bodyStr = options.body ? options.body.toString() : "";
    calls.push({ url, body: bodyStr });
    // فشل في الرسالة الثانية (index 1) في المحاولة الأولى
    if (failSecond && calls.length === 2) {
      return new Response(JSON.stringify({ ok: false, error_code: 500 }), {
        status: 500,
      });
    }
    return new Response(JSON.stringify({ ok: true }), { status: 200 });
  };
  try {
    const update = {
      update_id: 3001,
      message: {
        text: "كلمة ".repeat(800),
        from: { id: 501, first_name: "طارق" },
        chat: { id: 501 },
        date: 800,
      },
    };
    // محاولة أولى: ترسل الجزء الأول وتفشل في الثاني
    const res1 = await forwardMessage(env, update);
    assert.ok(res1 === "forward_failed_500" || res1 === "forward_failed");
    assert.equal(calls.length, 2);

    // محاكاة انتهاء مهلة القفل التزامني لإعادة المحاولة من تليجرام
    const stateRaw = kvMap.get("update:3001");
    assert.ok(stateRaw);
    const stateObj = JSON.parse(stateRaw);
    stateObj.timestamp = Math.floor(Date.now() / 1000) - 35; // مضى 35 ثانية
    kvMap.set("update:3001", JSON.stringify(stateObj));

    // محاولة ثانية: لا يعيد إرسال الجزء 0 الذي نجح، ويرسل الجزء المتبقي فقط
    failSecond = false;
    calls.length = 0;
    const res2 = await forwardMessage(env, update);
    assert.equal(res2, "forwarded");
    assert.equal(calls.length, 1, "يجب إرسال الجزء المتبقي فقط دون تكرار الأول");

    const finalState = JSON.parse(kvMap.get("update:3001"));
    assert.equal(finalState.state, "delivered", "يكتمل التسليم بنجاح");
  } finally {
    globalThis.fetch = realFetch;
  }
});

test("checkAndMarkUpdateId: طلب متزامن لنفس update_id ضمن نافذة القفل يعيد inProgress", async () => {
  const kvMap = new Map();
  const env = {
    RATE_LIMITS: {
      get: async (k) => (kvMap.has(k) ? kvMap.get(k) : null),
      put: async (k, v) => kvMap.set(k, v),
    },
  };
  const res1 = await checkAndMarkUpdateId(env, 4001);
  assert.equal(res1.isDuplicate, false);

  // طلب متزامن فوري
  const res2 = await checkAndMarkUpdateId(env, 4001);
  assert.equal(res2.isDuplicate, true);
  assert.equal(res2.inProgress, true, "يجب تحديد أنه قيد التنفيذ لمنع التزامن المزدوج");
});

test("checkAndMarkUpdateId: تجاوز 5 محاولات فاشلة يثبت failed_permanent لمنع الحلقات اللانهائية", async () => {
  const kvMap = new Map();
  const env = {
    RATE_LIMITS: {
      get: async (k) => (kvMap.has(k) ? kvMap.get(k) : null),
      put: async (k, v) => kvMap.set(k, v),
    },
  };
  const now = Math.floor(Date.now() / 1000);
  kvMap.set(
    "update:5001",
    JSON.stringify({
      state: "processing",
      attempts: 5,
      timestamp: now - 35,
    })
  );

  const res = await checkAndMarkUpdateId(env, 5001);
  assert.equal(res.isDuplicate, true);
  const finalState = JSON.parse(kvMap.get("update:5001"));
  assert.equal(finalState.state, "failed_permanent");
});

test("sendWithParseFallback: خطأ الشبكة يُرمى ولا يُعامل كخطأ تنسيق HTML", async () => {
  const env = { TELEGRAM_BOT_TOKEN: "dummy_token" };
  const realFetch = globalThis.fetch;
  globalThis.fetch = async () => {
    throw new TypeError("fetch failed: connection refused");
  };
  try {
    await assert.rejects(
      async () => {
        await sendWithParseFallback(env, "123", "<b>نص</b>");
      },
      /fetch failed/,
      "يجب رمي خطأ الشبكة الصريح دون محاولة فك التنسيق"
    );
  } finally {
    globalThis.fetch = realFetch;
  }
});

test("fetch: الفشل العابر (مثل فشل إرسال الوسائط) يرد 502 ليعيد تليجرام الإرسال", async () => {
  const mod = await import("../src/index.js");
  const handler = mod.default.fetch;
  const env = {
    WEBHOOK_SECRET: "s3cr3t",
    SUPPORT_CHAT_ID: "999",
    TELEGRAM_BOT_TOKEN: "dummy_token",
    RATE_LIMITS: {
      get: async () => null,
      put: async () => {},
    },
  };
  const realFetch = globalThis.fetch;
  globalThis.fetch = async (url) => {
    if (url.includes("/sendPhoto")) {
      return new Response(JSON.stringify({ ok: false, error_code: 502 }), {
        status: 502,
      });
    }
    return new Response(JSON.stringify({ ok: true }), { status: 200 });
  };
  try {
    const request = new Request("https://relay.example/webhook", {
      method: "POST",
      headers: { "X-Telegram-Bot-Api-Secret-Token": "s3cr3t" },
      body: JSON.stringify({
        update_id: 6001,
        message: {
          photo: [{ file_id: "PHOTO_ID" }],
          from: { id: 601, first_name: "كريم" },
          chat: { id: 601 },
          date: 900,
        },
      }),
    });
    const response = await handler(request, env);
    assert.equal(response.status, 502, "الرد 502 عند الفشل العابر لتحفيز إعادة المحاولة");
  } finally {
    globalThis.fetch = realFetch;
  }
});