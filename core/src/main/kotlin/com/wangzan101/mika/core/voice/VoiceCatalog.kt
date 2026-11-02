package com.wangzan101.mika.core.voice

/**
 * 一个要下载的本机语音模型:从 [url] 下一个压缩包,大小 [downloadBytes]、SHA-256 是 [sha256](下完核对,对不上就删掉),
 * 解开后的目录名 [dirName]。[license] 是模型权重的许可,界面在下载前写明。
 */
data class ModelSpec(
    val engine: VoiceEngineId,
    val url: String,
    val sha256: String,
    val downloadBytes: Long,
    val dirName: String,
    val license: String,
    val licenseUrl: String,
    /** 可选的音色;只有朗读模型有。第一个是默认。 */
    val voices: List<VoiceOption> = emptyList(),
) {
    init {
        require(url.startsWith("https://")) { "模型只从 HTTPS 地址下载" }
        require(Regex("[0-9a-f]{64}").matches(sha256)) { "SHA-256 要写成 64 位小写十六进制" }
    }
}

/** 朗读模型里的一个音色:[speakerId] 是模型里的编号,[label] 是界面上的名字的键(界面自己翻译)。 */
data class VoiceOption(val speakerId: Int, val label: String)

/**
 * 本机语音模型的清单。都是 sherpa-onnx 发布页上打包好的 int8 版本(地址、大小、SHA-256 取自 GitHub 发布接口),
 * 运行时同样用 sherpa-onnx;把运行库接进构建是后面的一步,见 docs/roadmap.md。
 */
object VoiceCatalog {
    private const val RELEASES = "https://github.com/k2-fsa/sherpa-onnx/releases/download"

    val parakeet3 = ModelSpec(
        engine = VoiceEngineId.Parakeet3,
        url = "$RELEASES/asr-models/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8.tar.bz2",
        sha256 = "5793d0fd397c5778d2cf2126994d58e9d56b1be7c04d13c7a15bb1b4eafb16bf",
        downloadBytes = 487_170_055,
        dirName = "sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8",
        license = "CC-BY-4.0",
        licenseUrl = "https://huggingface.co/nvidia/parakeet-tdt-0.6b-v3",
    )

    val supertonic3 = ModelSpec(
        engine = VoiceEngineId.Supertonic3,
        url = "$RELEASES/tts-models/sherpa-onnx-supertonic-3-tts-int8-2026-05-11.tar.bz2",
        sha256 = "82fa96f91c4ef8abaae3a14a3f4153facf88bed821d1f7331cec2700f432c427",
        downloadBytes = 128_774_318,
        dirName = "sherpa-onnx-supertonic-3-tts-int8-2026-05-11",
        license = "OpenRAIL-M",
        licenseUrl = "https://huggingface.co/Supertone/supertonic",
        // 先只列默认音色;其余音色的编号等接上运行时、对照模型自带的说明再补
        voices = listOf(VoiceOption(0, "female_1")),
    )

    val models: List<ModelSpec> = listOf(parakeet3, supertonic3)

    /** [engine] 要下载的模型;不用下载的引擎为空。 */
    fun modelFor(engine: VoiceEngineId): ModelSpec? = models.firstOrNull { it.engine == engine }
}
