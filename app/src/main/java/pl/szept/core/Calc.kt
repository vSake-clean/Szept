package pl.szept.core

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

/** Prosty kalkulator (zamaskowana ikona): + − × ÷ i %, z kolejnością działań. */
object Calc {
    class CalcException(msg: String) : Exception(msg)

    fun eval(expr: String): String {
        val tokens = tokenize(expr)
        if (tokens.isEmpty()) return "0"
        var pos = 0
        fun peek() = tokens.getOrNull(pos)
        fun number(): BigDecimal {
            val t = peek() ?: throw CalcException("Błąd")
            if (t == "−") { pos++; return number().negate() }
            pos++
            var v = t.toBigDecimalOrNull() ?: throw CalcException("Błąd")
            if (peek() == "%") { pos++; v = v.divide(BigDecimal(100)) }
            return v
        }
        fun term(): BigDecimal {
            var v = number()
            while (peek() == "×" || peek() == "÷") {
                val op = tokens[pos++]
                val r = number()
                v = if (op == "×") v.multiply(r) else {
                    if (r.signum() == 0) throw CalcException("Nie dziel przez 0")
                    v.divide(r, MathContext(16, RoundingMode.HALF_UP))
                }
            }
            return v
        }
        var v = term()
        while (peek() == "+" || peek() == "−") {
            val op = tokens[pos++]
            val r = term()
            v = if (op == "+") v.add(r) else v.subtract(r)
        }
        if (pos != tokens.size) throw CalcException("Błąd")
        return format(v)
    }

    fun format(v: BigDecimal): String {
        val r = v.round(MathContext(12, RoundingMode.HALF_UP)).stripTrailingZeros()
        val s = if (r.abs() >= BigDecimal("1e12") || (r.signum() != 0 && r.abs() < BigDecimal("1e-9"))) r.toString() else r.toPlainString()
        return s.replace('.', ',')
    }

    private fun tokenize(expr: String): List<String> {
        val out = ArrayList<String>()
        val num = StringBuilder()
        fun flush() { if (num.isNotEmpty()) { out += num.toString(); num.clear() } }
        for (ch in expr) when (ch) {
            in '0'..'9' -> num.append(ch)
            ',', '.' -> num.append('.')
            '+', '−', '-', '×', '*', '÷', '/', '%' -> {
                flush()
                out += when (ch) { '-' -> "−"; '*' -> "×"; '/' -> "÷"; else -> ch.toString() }
            }
            ' ' -> {}
            else -> throw CalcException("Błąd")
        }
        flush()
        // operator na końcu (np. „12+”) — pomijamy
        while (out.isNotEmpty() && out.last() in listOf("+", "−", "×", "÷")) out.removeAt(out.size - 1)
        return out
    }
}
