package com.wangzan101.mika.voice

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.wangzan101.mika.core.voice.ElevenLabsScribe
import com.wangzan101.mika.core.voice.ModelSpec
import com.wangzan101.mika.core.voice.SpeechSynthesizer
import com.wangzan101.mika.core.voice.VoiceCatalog
import com.wangzan101.mika.core.voice.VoiceEngineId
import com.wangzan101.mika.settings.VoiceConfig
import com.wangzan101.mika.settings.VoiceSettings
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 语音引擎的登记处:按设置里的选择给出这一次听写用哪个引擎;选的那个用不了(没填 Key、模型还没接上、
 * 手机没有识别服务)时退到能用的,并说明为什么(界面据此提示一句)。选哪个的规则是纯逻辑 [VoicePick]。
 */
class VoiceEngines(private val context: Context, private val settings: VoiceSettings, root: File) {
    /** 本机模型的下载;模型放在 [root](安卓上是 noBackupFilesDir/voice)。 */
    val models: ModelDownloads = ModelDownloads(
        root,
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            // 清单里都是 HTTPS;不许跳转到明文地址
            .followSslRedirects(false)
            .build(),
        wifiOnly = { settings.config.value.wifiOnly },
        onWifi = { onWifi(context) },
    )

    /** Parakeet v3 的运行时,听写之间留着(闲一阵自己卸)。 */
    val parakeet: ParakeetRuntime = ParakeetRuntime({ models.dir(VoiceCatalog.parakeet3) })

    /** Supertonic 3 的运行时,读回答之间留着(闲一阵自己卸)。 */
    val supertonic: SupertonicRuntime = SupertonicRuntime({ models.dir(VoiceCatalog.supertonic3) })

    /** 放回答的声音;整个 App 一个,新读的会停掉旧的。 */
    val player: VoicePlayer by lazy { VoicePlayer(context) }

    /** 读回答的(界面、语音对话共用)。 */
    val speaker: ReplySpeaker by lazy { ReplySpeaker(context, this, player) }

    /**
     * 读回答用的引擎:现在只有本机的 Supertonic 3(设置里的 ElevenLabs 是旧文件留下的,当它选的是 Supertonic 3)。
     * 模型没下好、或这台手机跑不了时为 null。
     */
    fun synthesizer(): SpeechSynthesizer? =
        if (replyReady()) SherpaSupertonicSynthesizer(supertonic) { settings.config.value.supertonicVoice } else null

    /** 朗读能用:Supertonic 3 下好了、这台手机跑得动。 */
    fun replyReady(): Boolean = ParakeetRuntime.supported && models.isReady(VoiceCatalog.supertonic3)

    /** 这一次听写:[dictation] 为空表示一个能用的都没有;[notice] 不为空时界面要说一句为什么没用上选的那个。 */
    class Choice(val dictation: Dictation?, val notice: VoicePick.Notice?)

    fun dictation(): Choice {
        val pick = VoicePick.recognizer(settings.config.value, PhoneDictation.available(context), implemented())
        return Choice(pick.engine?.let(::create), pick.notice)
    }

    private fun create(id: VoiceEngineId): Dictation? = when (id) {
        VoiceEngineId.PhoneRecognizer -> PhoneDictation(context)
        // Key 用时现解密,不在内存里常驻
        VoiceEngineId.ElevenLabsScribe -> ScribeDictation(ElevenLabsScribe(http, apiKey = settings::elevenLabsKey))
        VoiceEngineId.Parakeet3 -> SherpaParakeetDictation(parakeet)
        else -> null
    }

    /** 此刻能用的识别引擎:接好的加上已经下好模型、这台手机跑得动的本机模型。 */
    fun implemented(): Set<VoiceEngineId> =
        if (ParakeetRuntime.supported && models.isReady(VoiceCatalog.parakeet3)) IMPLEMENTED + VoiceEngineId.Parakeet3 else IMPLEMENTED

    /** 删掉一个本机模型:先从内存里卸掉,再删文件。 */
    suspend fun removeModel(spec: ModelSpec) {
        if (spec.engine == VoiceEngineId.Parakeet3) parakeet.unload()
        if (spec.engine == VoiceEngineId.Supertonic3) { speaker.stop(); supertonic.unload() }
        models.remove(spec)
    }

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    companion object {
        /** 不用下载就能用的识别引擎;本机模型下好后由 [implemented] 加进来。 */
        val IMPLEMENTED: Set<VoiceEngineId> = setOf(VoiceEngineId.PhoneRecognizer, VoiceEngineId.ElevenLabsScribe)

        /** 现在连着的是不是 Wi-Fi(或有线网),只用 Wi-Fi 下载时据此判断。 */
        fun onWifi(context: Context): Boolean {
            val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
            val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
            return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        }
    }
}

/** 按设置挑识别引擎,不碰安卓,好单测。 */
object VoicePick {
    enum class Notice {
        /** 选了 ElevenLabs Scribe 但没填 Key。 */
        NoKey,
        /** 选的本机模型还不能用。 */
        NotReady,
        /** 手机没有识别服务。 */
        PhoneUnavailable,
    }

    class Pick(val engine: VoiceEngineId?, val notice: Notice?)

    fun recognizer(config: VoiceConfig, phoneAvailable: Boolean, implemented: Set<VoiceEngineId>): Pick {
        fun usable(id: VoiceEngineId) = id in implemented &&
            (id != VoiceEngineId.PhoneRecognizer || phoneAvailable) && (!id.needsKey || config.hasElevenLabsKey)
        val wanted = config.speech.id
        if (usable(wanted)) return Pick(wanted, null)
        val notice = when {
            wanted.needsKey && !config.hasElevenLabsKey -> Notice.NoKey
            wanted == VoiceEngineId.PhoneRecognizer && wanted in implemented -> Notice.PhoneUnavailable
            else -> Notice.NotReady
        }
        // 退的顺序:先手机自带(不花钱、不用下载),再下好的本机模型,最后云端
        val fallback = listOf(VoiceEngineId.PhoneRecognizer, VoiceEngineId.Parakeet3, VoiceEngineId.ElevenLabsScribe).firstOrNull { it != wanted && usable(it) }
        return Pick(fallback, notice)
    }
}
