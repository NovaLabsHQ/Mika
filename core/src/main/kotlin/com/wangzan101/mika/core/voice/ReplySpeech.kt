package com.wangzan101.mika.core.voice

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * 一句话用什么语言读:按文字里占多数的书写系统定,拉丁字母和西里尔字母再看界面/手机的语言([preferred])。
 * 引擎不会读的(Supertonic 3 没有中文)返回 null,这句就不读。纯逻辑,好单测。
 */
object SpeechLanguage {
    private val LATIN = setOf("en", "cs", "da", "de", "es", "et", "fi", "fr", "hr", "hu", "id", "it", "lt", "lv", "nl", "pl",
        "pt", "ro", "sk", "sl", "sv", "tr", "vi")
    private val CYRILLIC = setOf("ru", "uk", "bg")

    private enum class Script { Latin, Cyrillic, Greek, Arabic, Devanagari, Hangul, Kana, Han }

    private fun script(ch: Char): Script? = when (Character.UnicodeScript.of(ch.code)) {
        Character.UnicodeScript.LATIN -> Script.Latin
        Character.UnicodeScript.CYRILLIC -> Script.Cyrillic
        Character.UnicodeScript.GREEK -> Script.Greek
        Character.UnicodeScript.ARABIC -> Script.Arabic
        Character.UnicodeScript.DEVANAGARI -> Script.Devanagari
        Character.UnicodeScript.HANGUL -> Script.Hangul
        Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA -> Script.Kana
        Character.UnicodeScript.HAN -> Script.Han
        else -> null
    }

    /** 「pt-BR」「zh_Hant」这种只取前面的语言部分,小写。 */
    fun base(tag: String?): String? = tag?.trim()?.lowercase()?.split('-', '_')?.firstOrNull()?.takeIf { it.isNotEmpty() }

    fun pick(text: String, preferred: String?, supported: Set<String> = VoiceCatalog.Supertonic.LANGUAGES): String? {
        val pref = base(preferred)
        val counts = HashMap<Script, Int>()
        for (ch in text) script(ch)?.let { counts[it] = (counts[it] ?: 0) + 1 }
        // 日文里汉字和假名混着写:有假名就是日文
        val kana = counts[Script.Kana] ?: 0
        if (kana > 0) counts[Script.Kana] = kana + (counts.remove(Script.Han) ?: 0)
        val top = counts.maxByOrNull { it.value }?.key
        val lang = when (top) {
            null -> pref?.takeIf { it in supported } ?: "en"
            Script.Latin -> pref?.takeIf { it in LATIN } ?: "en"
            Script.Cyrillic -> pref?.takeIf { it in CYRILLIC } ?: if (text.any { it in "іїєґІЇЄҐ" }) "uk" else "ru"
            Script.Greek -> "el"
            Script.Arabic -> "ar"
            Script.Devanagari -> "hi"
            Script.Hangul -> "ko"
            Script.Kana -> "ja"
            Script.Han -> "zh"
        }
        return lang.takeIf { it in supported }
    }
}

/** 本机模型给的 [-1,1] 浮点采样转成 16 位 PCM(超出的截到边上)。 */
object FloatPcm {
    fun toPcm16(samples: FloatArray): ShortArray = ShortArray(samples.size) { i ->
        (samples[i].coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort()
    }
}

/** 一段回答要读的句子:先去掉 Markdown([SpeakableText]),再切句([SentenceSplitter])。 */
object SpeechPlan {
    fun sentences(markdown: String, splitter: SentenceSplitter = SentenceSplitter()): List<String> =
        splitter.split(SpeakableText.from(markdown)).map { it.replace(SPACE, " ").trim() }.filter { s -> s.any { it.isLetterOrDigit() } }

    private val SPACE = Regex("\\s+")
}

/** 声音写到哪:安卓上是 AudioTrack([write] 写满时挂起),测试里是记录。 */
interface PcmSink {
    suspend fun write(chunk: PcmChunk)
    /** 写完了:等已经写进去的放完。 */
    suspend fun drain()
}

/**
 * 把一段回答读出来:一句一句交给 [synth] 合成,合成好的块马上写给 [sink];第一句在放的时候后面的接着合成。
 * 每句的语言由 [language] 定,为 null 的句子跳过(引擎不会这种语言)。取消协程就停(合成到一半的那句丢掉)。
 */
class ReplyReader(private val synth: SpeechSynthesizer, private val language: (String) -> String?) {
    /** [spoken] 句读了,[skipped] 句因为语言不支持没读。 */
    data class Result(val spoken: Int, val skipped: Int)

    suspend fun read(markdown: String, sink: PcmSink): Result = coroutineScope {
        val sentences = SpeechPlan.sentences(markdown)
        val plan = sentences.map { it to language(it) }
        val skipped = plan.count { it.second == null }
        if (skipped == plan.size) return@coroutineScope Result(0, skipped)
        // 留几句的余量:合成比播放快时不至于一下子把整段都攒在内存里
        val chunks = Channel<PcmChunk>(capacity = 4)
        launch {
            try {
                for ((s, lang) in plan) if (lang != null) synth.synthesize(s, lang).collect { chunks.send(it) }
                chunks.close()
            } catch (e: Throwable) {
                // 合成出错时播放那边也要拿到这个错,不能当成正常读完
                chunks.close(e)
                throw e
            }
        }
        for (c in chunks) sink.write(c)
        sink.drain()
        Result(plan.size - skipped, skipped)
    }
}
