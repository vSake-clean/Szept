package pl.szept.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle

/**
 * Formatowanie tekstu w wiadomościach (jak w innych komunikatorach):
 * *pogrubienie*, _kursywa_, ~przekreślenie~, `kod`, ||spoiler|| (ukryty do dotknięcia).
 */
object TextFormat {
    enum class Style { BOLD, ITALIC, STRIKE, CODE, SPOILER }
    data class Part(val text: String, val styles: Set<Style> = emptySet(), val link: String? = null, val mention: Boolean = false, val spoilerId: Int = -1)

    private val linkRx = Regex("""(https?://[^\s]+)|(@[\p{L}\p{N}_.\-]+)""")
    // znaczniki muszą przylegać do tekstu i nie mogą być w środku słowa (np. snake_case zostaje bez zmian)
    private val fmtRx = Regex(
        """\|\|(.+?)\|\||`([^`\n]+)`|(?<![\p{L}\p{N}])\*\*?(?=\S)(.+?)(?<=\S)\*\*?(?![\p{L}\p{N}])|(?<![\p{L}\p{N}])_(?=\S)(.+?)(?<=\S)_(?![\p{L}\p{N}])|(?<![\p{L}\p{N}])~~?(?=\S)(.+?)(?<=\S)~~?(?![\p{L}\p{N}])""",
    )

    /** Rozbija tekst na fragmenty ze stylami (bez zagnieżdżania poza spoilerem). */
    fun parse(text: String): List<Part> {
        val out = ArrayList<Part>()
        var spoilers = 0
        fun plain(t: String, base: Set<Style>, sid: Int) {
            var last = 0
            for (m in linkRx.findAll(t)) {
                if (m.range.first > last) out += Part(t.substring(last, m.range.first), base, spoilerId = sid)
                val v = m.value
                if (v.startsWith("@")) out += Part(v, base, mention = true, spoilerId = sid)
                else {
                    val url = v.trimEnd('.', ',', ')', '!', '?')
                    out += Part(url, base, link = url, spoilerId = sid)
                    if (url.length < v.length) out += Part(v.substring(url.length), base, spoilerId = sid)
                }
                last = m.range.last + 1
            }
            if (last < t.length) out += Part(t.substring(last), base, spoilerId = sid)
        }
        fun formatted(t: String, base: Set<Style>, sid: Int) {
            // linki zostają nietknięte (podkreślniki w adresach to nie kursywa)
            var last = 0
            for (lm in linkRx.findAll(t)) {
                if (lm.range.first > last) styled(t.substring(last, lm.range.first), base, sid, ::plain)
                plain(lm.value, base, sid)
                last = lm.range.last + 1
            }
            if (last < t.length) styled(t.substring(last), base, sid, ::plain)
        }
        fun top(t: String) {
            var last = 0
            for (m in Regex("""\|\|(.+?)\|\|""").findAll(t)) {
                if (m.range.first > last) formatted(t.substring(last, m.range.first), emptySet(), -1)
                formatted(m.groupValues[1], setOf(Style.SPOILER), spoilers++)
                last = m.range.last + 1
            }
            if (last < t.length) formatted(t.substring(last), emptySet(), -1)
        }
        top(text)
        return out.filter { it.text.isNotEmpty() }
    }

    private fun styled(t: String, base: Set<Style>, sid: Int, plain: (String, Set<Style>, Int) -> Unit) {
        var last = 0
        for (m in fmtRx.findAll(t)) {
            if (m.groups[1] != null) continue // spoilery obsłużone wyżej
            if (m.range.first > last) plain(t.substring(last, m.range.first), base, sid)
            val g = m.groupValues
            val (inner, st) = when {
                m.groups[2] != null -> g[2] to Style.CODE
                m.groups[3] != null -> g[3] to Style.BOLD
                m.groups[4] != null -> g[4] to Style.ITALIC
                else -> g[5] to Style.STRIKE
            }
            plain(inner, base + st, sid)
            last = m.range.last + 1
        }
        if (last < t.length) plain(t.substring(last), base, sid)
    }

    /** Tekst do podglądu (powiadomienia, lista rozmów): bez znaczników, spoiler zakryty. */
    fun plainPreview(text: String): String = parse(text).joinToString("") { if (Style.SPOILER in it.styles) "▒".repeat(it.text.length.coerceAtMost(8)) else it.text }

    fun annotate(
        parts: List<Part>, primary: Color, spoilerBg: Color, codeBg: Color, revealed: Set<Int>, onSpoiler: (Int) -> Unit,
    ): AnnotatedString = buildAnnotatedString {
        for (p in parts) {
            val hidden = Style.SPOILER in p.styles && p.spoilerId !in revealed
            var st = SpanStyle()
            if (Style.BOLD in p.styles) st = st.merge(SpanStyle(fontWeight = FontWeight.Bold))
            if (Style.ITALIC in p.styles) st = st.merge(SpanStyle(fontStyle = FontStyle.Italic))
            if (Style.STRIKE in p.styles) st = st.merge(SpanStyle(textDecoration = TextDecoration.LineThrough))
            if (Style.CODE in p.styles) st = st.merge(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBg))
            if (p.mention) st = st.merge(SpanStyle(color = primary, fontWeight = FontWeight.Bold))
            if (Style.SPOILER in p.styles) st = st.merge(SpanStyle(background = spoilerBg))
            if (hidden) st = st.merge(SpanStyle(color = Color.Transparent))
            when {
                hidden -> withLink(LinkAnnotation.Clickable("spoiler:${p.spoilerId}", TextLinkStyles(st)) { onSpoiler(p.spoilerId) }) { append(p.text) }
                p.link != null -> withLink(LinkAnnotation.Url(p.link, TextLinkStyles(st.merge(SpanStyle(color = primary, textDecoration = TextDecoration.Underline))))) { append(p.text) }
                else -> withStyle(st) { append(p.text) }
            }
        }
    }
}
