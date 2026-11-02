package com.wangzan101.mika.voice

import com.wangzan101.mika.core.voice.VoiceCatalog
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/** 只建 sherpa-onnx 的配置对象(纯 Kotlin 数据类),不加载 native 库。 */
class ParakeetConfigTest {
    private val dir = File("/m")

    @Test fun recognizerPointsAtTheDownloadedFiles() {
        val c = ParakeetConfig.recognizer(dir, 4)
        assertEquals("nemo_transducer", c.modelConfig.modelType)
        assertEquals(File(dir, VoiceCatalog.Parakeet.ENCODER).path, c.modelConfig.transducer.encoder)
        assertEquals(File(dir, VoiceCatalog.Parakeet.DECODER).path, c.modelConfig.transducer.decoder)
        assertEquals(File(dir, VoiceCatalog.Parakeet.JOINER).path, c.modelConfig.transducer.joiner)
        assertEquals(File(dir, VoiceCatalog.Parakeet.TOKENS).path, c.modelConfig.tokens)
        assertEquals(4, c.modelConfig.numThreads)
        assertEquals("cpu", c.modelConfig.provider)
        assertEquals(16_000, c.featConfig.sampleRate)
        assertEquals("greedy_search", c.decodingMethod)
    }

    @Test fun vadUsesSilero() {
        val v = ParakeetConfig.vad(dir)
        assertEquals(File(dir, VoiceCatalog.Parakeet.VAD).path, v.sileroVadModelConfig.model)
        assertEquals(512, v.sileroVadModelConfig.windowSize)
        assertEquals(16_000, v.sampleRate)
    }

    @Test fun threadsAreCapped() {
        assertEquals(1, ParakeetConfig.threads(1))
        assertEquals(4, ParakeetConfig.threads(8))
    }
}
