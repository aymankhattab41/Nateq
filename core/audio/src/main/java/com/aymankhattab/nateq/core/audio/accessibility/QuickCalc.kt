package com.aymankhattab.nateq.core.audio.accessibility

import android.content.Context
import com.aymankhattab.nateq.core.audio.R
import com.aymankhattab.nateq.core.data.SettingsRepository
import org.json.JSONObject
import java.util.ArrayDeque
import java.util.Locale
import kotlin.math.pow

/**
 * محوّل العملات والحاسبة السريعة (ع٥٦-أمر 13).
 * يحسب التعابير الحسابية ويحوّل العملات بجدول أسعار محلي بلا اتصال بالإنترنت.
 */
object QuickCalc {

    private val ARABIC_WORD_OPS = mapOf(
        "زائد" to "+",
        "ناقص" to "-",
        "ضرب" to "*",
        "في" to "*",
        "قسمة" to "/",
        "على" to "/",
        "×" to "*",
        "÷" to "/"
    )

    private val KNOWN_CURRENCIES = mapOf(
        "دولار" to "USD",
        "dollars" to "USD",
        "dollar" to "USD",
        "usd" to "USD",
        "$" to "USD",
        "يورو" to "EUR",
        "euro" to "EUR",
        "euros" to "EUR",
        "eur" to "EUR",
        "€" to "EUR",
        "جنيه" to "EGP",
        "egp" to "EGP",
        "ريال" to "SAR",
        "sar" to "SAR",
        "درهم" to "AED",
        "aed" to "AED",
        "دينار" to "KWD",
        "kwd" to "KWD"
    )

    fun evaluate(expression: String): Double? {
        var expr = expression.trim()
        for ((word, op) in ARABIC_WORD_OPS) {
            expr = expr.replace(word, op)
        }
        expr = OtpExtractor.normalizeDigits(expr)

        return try {
            val tokens = tokenize(expr)
            if (tokens.isEmpty()) return null
            val rpn = toRpn(tokens)
            evalRpn(rpn)
        } catch (_: Throwable) {
            null
        }
    }

    private fun tokenize(expr: String): List<String> {
        val tokens = mutableListOf<String>()
        var i = 0
        while (i < expr.length) {
            val ch = expr[i]
            when {
                ch.isWhitespace() -> i++
                ch in "+-*/^()" -> {
                    tokens.add(ch.toString())
                    i++
                }
                ch.isDigit() || ch == '.' -> {
                    val start = i
                    while (i < expr.length &&
                        (expr[i].isDigit() || expr[i] == '.')
                    ) {
                        i++
                    }
                    tokens.add(expr.substring(start, i))
                }
                else -> i++
            }
        }
        return tokens
    }

    private fun precedence(op: String): Int = when (op) {
        "+", "-" -> 1
        "*", "/" -> 2
        "^" -> 3
        else -> 0
    }

    private fun toRpn(tokens: List<String>): List<String> {
        val output = mutableListOf<String>()
        val stack = ArrayDeque<String>()

        for (token in tokens) {
            when {
                token.toDoubleOrNull() != null -> output.add(token)
                token == "(" -> stack.push(token)
                token == ")" -> {
                    while (stack.isNotEmpty() && stack.peek() != "(") {
                        output.add(stack.pop())
                    }
                    if (stack.isNotEmpty() && stack.peek() == "(") {
                        stack.pop()
                    }
                }
                token in setOf("+", "-", "*", "/", "^") -> {
                    while (stack.isNotEmpty() &&
                        precedence(stack.peek()) >= precedence(token)
                    ) {
                        output.add(stack.pop())
                    }
                    stack.push(token)
                }
            }
        }
        while (stack.isNotEmpty()) {
            output.add(stack.pop())
        }
        return output
    }

    private fun evalRpn(tokens: List<String>): Double? {
        val stack = ArrayDeque<Double>()
        for (token in tokens) {
            val num = token.toDoubleOrNull()
            if (num != null) {
                stack.push(num)
            } else if (token in setOf("+", "-", "*", "/", "^")) {
                if (stack.size < 2) return null
                val b = stack.pop()
                val a = stack.pop()
                val res = when (token) {
                    "+" -> a + b
                    "-" -> a - b
                    "*" -> a * b
                    "/" -> {
                        if (b == 0.0) return null
                        a / b
                    }
                    "^" -> a.pow(b)
                    else -> return null
                }
                stack.push(res)
            }
        }
        return if (stack.size == 1) stack.pop() else null
    }

    fun parseCurrency(
        input: String,
        ratesJson: String?
    ): Pair<Double, String>? {
        val normalized = OtpExtractor.normalizeDigits(input.trim())
        val parts = normalized.split(Regex("""\s+"""))
        var amount: Double? = null
        var foundCode: String? = null

        for (part in parts) {
            val num = part.toDoubleOrNull()
            if (num != null && amount == null) {
                amount = num
            } else {
                val clean = part.lowercase().filter {
                    it.isLetter() || it == '$' || it == '€'
                }
                val code = KNOWN_CURRENCIES[clean]
                if (code != null) {
                    foundCode = code
                }
            }
        }

        if (amount == null || foundCode == null) return null

        val rates = parseRates(ratesJson)
        val rate = rates[foundCode] ?: return null
        val converted = amount * rate
        val baseCurrency = getBaseCurrency(ratesJson)
        return Pair(converted, baseCurrency)
    }

    private fun getBaseCurrency(jsonStr: String?): String {
        if (jsonStr.isNullOrBlank()) return "EGP"
        return try {
            val json = JSONObject(jsonStr)
            json.optString("_BASE_", "EGP")
        } catch (_: Throwable) {
            "EGP"
        }
    }

    private fun parseRates(jsonStr: String?): Map<String, Double> {
        if (jsonStr.isNullOrBlank()) {
            return mapOf(
                "USD" to 50.0,
                "EUR" to 54.0,
                "SAR" to 13.3,
                "AED" to 13.6,
                "KWD" to 163.0,
                "_BASE_" to 1.0
            )
        }
        val map = mutableMapOf<String, Double>()
        try {
            val json = JSONObject(jsonStr)
            val keys = json.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                if (k == "_BASE_") continue
                map[k.uppercase()] = json.getDouble(k)
            }
            if (json.has("_BASE_")) {
                map["_BASE_"] = 1.0
            }
        } catch (_: Throwable) {
        }
        return map
    }

    fun processQuery(context: Context, text: String): Boolean {
        val repo = SettingsRepository.create(context)
        val ratesJson = repo.getExtraString("extra_currency_rates_json", "")

        val currResult = parseCurrency(text, ratesJson)
        if (currResult != null) {
            val (converted, base) = currResult
            val formatted = String.format(Locale.US, "%.2f", converted)
            val msg = context.getString(
                R.string.currency_result,
                text.trim(),
                formatted,
                base
            )
            A11ySpeechCoordinator.speakAnnouncement(context, msg)
            return true
        }

        val mathResult = evaluate(text)
        if (mathResult != null) {
            val formatted = if (mathResult % 1.0 == 0.0) {
                mathResult.toLong().toString()
            } else {
                String.format(Locale.US, "%.2f", mathResult)
            }
            val msg = context.getString(R.string.calc_result, formatted)
            A11ySpeechCoordinator.speakAnnouncement(context, msg)
            return true
        }

        val failMsg = context.getString(R.string.math_calc_failed)
        A11ySpeechCoordinator.speakAnnouncement(context, failMsg)
        return false
    }
}
