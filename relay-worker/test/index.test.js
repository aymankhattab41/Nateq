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