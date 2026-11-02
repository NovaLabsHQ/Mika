package com.wangzan101.mika.core.voice

/**
 * 把模型的回复(Markdown)变成能读出来的话:去掉代码块、图片、链接地址、表格竖线、标题和强调符号、列表记号,
 * 只留下人会念的字。代码块整段不读(念代码没意义),行内代码留下里面的字。
 */
object SpeakableText {
    private val fence = Regex("(?ms)^\\s*(```|~~~).*?(^\\s*\\1\\s*$|\\z)")
    private val image = Regex("!\\[([^\\]]*)]\\([^)]*\\)")
    private val link = Regex("\\[([^\\]]+)]\\([^)]*\\)")
    private val autolink = Regex("<(https?://[^>]+)>")
    private val bareUrl = Regex("https?://\\S+")
    private val html = Regex("</?[a-zA-Z][^>]*>")
    private val heading = Regex("(?m)^\\s{0,3}#{1,6}\\s*")
    private val quote = Regex("(?m)^\\s*>+\\s?")
    private val bullet = Regex("(?m)^\\s*([-*+]|\\d+[.)])\\s+(\\[[ xX]]\\s+)?")
    private val rule = Regex("(?m)^[ \\t]*([-*_][ \\t]*){3,}$\\n?")
    private val tableSep = Regex("(?m)^[ \\t]*\\|?[ \\t]*:?-{3,}:?[ \\t]*(\\|[ \\t]*:?-{3,}:?[ \\t]*)*\\|?[ \\t]*$\\n?")
    private val inlineCode = Regex("`([^`]*)`")
    private val emphasis = Regex("(\\*\\*|__|\\*|_|~~)(?=\\S)(.+?)(?<=\\S)\\1")
    private val spaces = Regex("[ \\t]+")
    private val blankLines = Regex("\\n{3,}")

    fun from(markdown: String): String {
        var s = markdown.replace("\r\n", "\n")
        s = fence.replace(s, "\n")
        s = image.replace(s, "")
        s = link.replace(s) { it.groupValues[1] }
        s = autolink.replace(s, "")
        s = bareUrl.replace(s, "")
        s = html.replace(s, "")
        s = rule.replace(s, "")
        s = tableSep.replace(s, "")
        s = heading.replace(s, "")
        s = quote.replace(s, "")
        s = bullet.replace(s, "")
        s = inlineCode.replace(s) { it.groupValues[1] }
        // 嵌套的强调(***粗斜体***)要剥两遍
        repeat(2) { s = emphasis.replace(s) { it.groupValues[2] } }
        // 表格的一行:单元格之间换成逗号停顿
        s = s.lines().joinToString("\n") { line ->
            val t = line.trim()
            if (t.startsWith("|") && t.endsWith("|") && t.length > 1) t.trim('|').split('|').map { it.trim() }.filter { it.isNotEmpty() }.joinToString(", ")
            else line
        }
        s = spaces.replace(s, " ")
        s = s.lines().joinToString("\n") { it.trim() }
        return blankLines.replace(s, "\n\n").trim()
    }
}

/**
 * 把要读的文字切成一句一句,好一句合成完就开始播,不必等整段。按句末标点(中英文都认)和空行切;
 * 太短的句子并到下一句(免得一个字一个字地停顿),太长的在逗号或空格处再切开(合成服务对单次长度有限制)。
 */
class SentenceSplitter(private val minChars: Int = 12, private val maxChars: Int = 300) {
    init { require(minChars in 1 until maxChars) }

    /** 一次切完一整段。 */
    fun split(text: String): List<String> {
        val inc = Incremental()
        return inc.push(text) + inc.flush()
    }

    /** 边收边切:流式回复每来一段 [push] 一次,拿到已经完整的句子;回复完了 [flush] 拿剩下的。 */
    inner class Incremental {
        private val pending = StringBuilder()
        private val ready = ArrayList<String>()
        private var carry = ""

        fun push(delta: String): List<String> {
            pending.append(delta)
            var cut = boundary(pending)
            while (cut > 0) {
                accept(pending.substring(0, cut))
                pending.delete(0, cut)
                cut = boundary(pending)
            }
            // 没有句末标点却已经很长:在逗号或空格处先切一段出去
            while (pending.length > maxChars) {
                val at = softCut(pending, maxChars)
                accept(pending.substring(0, at))
                pending.delete(0, at)
            }
            return drain()
        }

        fun flush(): List<String> {
            accept(pending.toString())
            pending.clear()
            if (carry.isNotBlank()) ready += carry.trim()
            carry = ""
            return drain()
        }

        private fun accept(piece: String) {
            // 留着原来的空白再拼:英文句子之间本来有空格,中文之间本来没有
            val raw = carry + piece
            val joined = raw.trim()
            if (joined.isEmpty()) return
            if (joined.length < minChars) { carry = raw; return }
            carry = ""
            var rest = joined
            while (rest.length > maxChars) {
                val at = softCut(rest, maxChars)
                ready += rest.substring(0, at).trim()
                rest = rest.substring(at).trim()
            }
            if (rest.isNotEmpty()) ready += rest
        }

        private fun drain(): List<String> = ready.toList().also { ready.clear() }
    }

    private companion object {
        /** 不用后跟空白的句末标点(全角)。 */
        const val CJK_ENDS = "。！？；…\n"
        /** 后跟空白才算句末的标点(半角:免得把 3.14、e.g. 中间切开)。 */
        const val LATIN_ENDS = ".!?;:"
        const val CLOSERS = "\"'”’)）」』"

        /** 第一个句子边界之后的位置;还没有完整的句子时为 0。 */
        fun boundary(s: CharSequence): Int {
            var i = 0
            while (i < s.length) {
                val ch = s[i]
                val end = when {
                    ch in CJK_ENDS -> true
                    ch in LATIN_ENDS -> i + 1 < s.length && (s[i + 1].isWhitespace() || s[i + 1] in CLOSERS)
                    else -> false
                }
                if (end) {
                    var j = i + 1
                    while (j < s.length && (s[j] in CLOSERS || s[j] in CJK_ENDS && s[j] != '\n')) j++
                    return j
                }
                i++
            }
            return 0
        }

        /** 在 [limit] 以内找最后一个逗号或空白切开;都没有就硬切。 */
        fun softCut(s: CharSequence, limit: Int): Int {
            val window = s.subSequence(0, limit)
            val comma = window.indexOfLast { it in ",，、" }
            if (comma > limit / 3) return comma + 1
            val space = window.indexOfLast { it.isWhitespace() }
            if (space > limit / 3) return space + 1
            return limit
        }
    }
}
