package com.wangzan101.mika.core.voice

/**
 * 模型里要下载的一个文件:从 [url] 下载,大小 [bytes]、SHA-256 是 [sha256](下完核对,对不上就删掉)。
 * [name] 是放进模型目录后的文件名;[unpack] 为真时它是个 .tar.bz2,核对后解进模型目录(去掉包里最外一层目录)、再删掉压缩包。
 */
data class ModelFile(
    val name: String,
    val url: String,
    val sha256: String,
    val bytes: Long,
    val unpack: Boolean = false,
) {
    init {
        require(url.startsWith("https://")) { "模型只从 HTTPS 地址下载" }
        require(Regex("[0-9a-f]{64}").matches(sha256)) { "SHA-256 要写成 64 位小写十六进制" }
        require(bytes > 0) { "大小要写明" }
        require(Regex("[A-Za-z0-9._-]+").matches(name) && name != "." && name != ".." && !name.startsWith(".")) { "文件名不能带路径" }
        require(!unpack || name.endsWith(".tar.bz2")) { "只解 .tar.bz2" }
    }
}

/**
 * 一个要下载的本机语音模型:[files] 一个个下载、核对,放进本机的模型目录 `voice/<[id]>/`;
 * [license] 是模型权重的许可,界面在下载前写明。
 */
data class ModelSpec(
    val engine: VoiceEngineId,
    val id: String,
    val files: List<ModelFile>,
    val license: String,
    val licenseUrl: String,
    /** 可选的音色;只有朗读模型有。第一个是默认。 */
    val voices: List<VoiceOption> = emptyList(),
) {
    init {
        require(Regex("[a-z0-9][a-z0-9._-]*").matches(id)) { "模型编号只用小写字母、数字和 ._-" }
        require(files.isNotEmpty()) { "至少一个文件" }
        require(files.map { it.name }.toSet().size == files.size) { "文件名不能重复" }
    }

    /** 要下载的总字节数。 */
    val downloadBytes: Long get() = files.sumOf { it.bytes }

    /** 清单里这一版文件的指纹;下好的模型目录记着它,清单换了文件就算没下载。 */
    val fingerprint: String get() = files.joinToString("\n") { "${it.name} ${it.sha256} ${it.bytes} ${it.unpack}" }
}

/**
 * 朗读模型里的一个音色:[speakerId] 是模型里的编号(sherpa-onnx 的 sid),[label] 是上游给这个音色起的名字,
 * 界面原样显示(不另起「温柔女声」之类上游没说过的名字)。
 */
data class VoiceOption(val speakerId: Int, val label: String)

/**
 * 本机语音模型的清单,运行时是 sherpa-onnx(见 tools/voice/)。地址、大小、SHA-256 都是对着下载下来的文件核过的。
 */
object VoiceCatalog {
    private const val RELEASES = "https://github.com/k2-fsa/sherpa-onnx/releases/download"
    /** k2-fsa 转好的 Parakeet v3 int8,固定在一个提交上,文件不会悄悄变。 */
    private const val PARAKEET_HF =
        "https://huggingface.co/csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8/resolve/2bda32ec70b097a55adaa07d9a7173915b43cc78"

    /** Parakeet v3 目录里的文件名,识别引擎按这些名字找。 */
    object Parakeet {
        const val ENCODER = "encoder.int8.onnx"
        const val DECODER = "decoder.int8.onnx"
        const val JOINER = "joiner.int8.onnx"
        const val TOKENS = "tokens.txt"
        /** 判断话说完没有的 Silero VAD,和识别模型放在一起。 */
        const val VAD = "silero_vad.onnx"
    }

    /**
     * Parakeet v3(NVIDIA parakeet-tdt-0.6b-v3 的 int8 ONNX,25 种欧洲语言,自己认语言)加 Silero VAD。
     * 一个个文件直接下(不是一个大压缩包),断了能接着下,下完不用再解包。
     */
    val parakeet3 = ModelSpec(
        engine = VoiceEngineId.Parakeet3,
        id = "parakeet-tdt-0.6b-v3-int8",
        files = listOf(
            ModelFile(Parakeet.ENCODER, "$PARAKEET_HF/${Parakeet.ENCODER}",
                "acfc2b4456377e15d04f0243af540b7fe7c992f8d898d751cf134c3a55fd2247", 652_184_281),
            ModelFile(Parakeet.DECODER, "$PARAKEET_HF/${Parakeet.DECODER}",
                "179e50c43d1a9de79c8a24149a2f9bac6eb5981823f2a2ed88d655b24248db4e", 11_845_275),
            ModelFile(Parakeet.JOINER, "$PARAKEET_HF/${Parakeet.JOINER}",
                "3164c13fc2821009440d20fcb5fdc78bff28b4db2f8d0f0b329101719c0948b3", 6_355_277),
            ModelFile(Parakeet.TOKENS, "$PARAKEET_HF/${Parakeet.TOKENS}",
                "d58544679ea4bc6ac563d1f545eb7d474bd6cfa467f0a6e2c1dc1c7d37e3c35d", 93_939),
            ModelFile(Parakeet.VAD, "$RELEASES/asr-models/silero_vad.onnx",
                "9e2449e1087496d8d4caba907f23e0bd3f78d91fa552479bb9c23ac09cbb1fd6", 643_854),
        ),
        license = "CC-BY-4.0",
        licenseUrl = "https://huggingface.co/nvidia/parakeet-tdt-0.6b-v3",
    )

    /** Supertonic 3 解包后目录里的文件名(去掉了包里最外一层目录),朗读引擎按这些名字找。 */
    object Supertonic {
        const val ARCHIVE = "sherpa-onnx-supertonic-3-tts-int8-2026-05-11.tar.bz2"
        const val DURATION_PREDICTOR = "duration_predictor.int8.onnx"
        const val TEXT_ENCODER = "text_encoder.int8.onnx"
        const val VECTOR_ESTIMATOR = "vector_estimator.int8.onnx"
        const val VOCODER = "vocoder.int8.onnx"
        const val TTS_JSON = "tts.json"
        const val UNICODE_INDEXER = "unicode_indexer.bin"
        /** 10 个预设音色的风格向量,sid 0..9。 */
        const val VOICES = "voice.bin"

        /** 引擎起来之前要在的文件;缺一个 sherpa-onnx 可能直接让进程退出,先自己查。 */
        val REQUIRED = listOf(DURATION_PREDICTOR, TEXT_ENCODER, VECTOR_ESTIMATOR, VOCODER, TTS_JSON, UNICODE_INDEXER, VOICES)

        /**
         * 上游(Supertone/supertonic-3)的 31 种语言代码,朗读时经 GenerationConfig.extra["lang"] 交给引擎。
         * 没有中文:中文的话读不出来(见 [SpeechLanguage])。
         */
        val LANGUAGES: Set<String> = setOf(
            "en", "ko", "ja", "ar", "bg", "cs", "da", "de", "el", "es", "et", "fi", "fr", "hi", "hr", "hu",
            "id", "it", "lt", "lv", "nl", "pl", "pt", "ro", "ru", "sk", "sl", "sv", "tr", "uk", "vi",
        )
    }

    /**
     * Supertonic 3(Supertone 的多语言朗读模型,k2-fsa 转成 int8 ONNX 打成一个包),解包后约 145 MB,输出 44.1 kHz。
     * 音色:包里的 voice.bin 是 sherpa-onnx 的 generate_voices_bin.py 按文件名排序把上游的
     * voice_styles/F1..F5、M1..M5.json 拼起来的,所以 sid 0..4 = F1..F5、5..9 = M1..M5(逐字节对照过)。
     * 名字照上游的写;上游没有单独说明每个音色的声音,界面上就只叫 F1、M1。
     */
    val supertonic3 = ModelSpec(
        engine = VoiceEngineId.Supertonic3,
        id = "supertonic-3-tts-int8-2026-05-11",
        files = listOf(
            ModelFile(Supertonic.ARCHIVE, "$RELEASES/tts-models/${Supertonic.ARCHIVE}",
                "82fa96f91c4ef8abaae3a14a3f4153facf88bed821d1f7331cec2700f432c427", 128_774_318, unpack = true),
        ),
        // 权重是 BigScience OpenRAIL-M(带使用限制),推理代码是 MIT
        license = "OpenRAIL-M",
        licenseUrl = "https://huggingface.co/Supertone/supertonic-3",
        voices = listOf("F1", "F2", "F3", "F4", "F5", "M1", "M2", "M3", "M4", "M5").mapIndexed { i, n -> VoiceOption(i, n) },
    )

    val models: List<ModelSpec> = listOf(parakeet3, supertonic3)

    /** [engine] 要下载的模型;不用下载的引擎为空。 */
    fun modelFor(engine: VoiceEngineId): ModelSpec? = models.firstOrNull { it.engine == engine }
}
