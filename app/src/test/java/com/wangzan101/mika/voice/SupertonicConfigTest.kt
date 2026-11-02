package com.wangzan101.mika.voice

import com.wangzan101.mika.core.voice.VoiceCatalog
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/** 只建 sherpa-onnx 的配置对象(纯 Kotlin 数据类),不加载 native 库。 */
class SupertonicConfigTest {
    private val dir = File("/m")

    @Test fun ttsPointsAtTheUnpackedFiles() {
        val c = SupertonicConfig.tts(dir, 3)
        val s = c.model.supertonic
        assertEquals(File(dir, VoiceCatalog.Supertonic.DURATION_PREDICTOR).path, s.durationPredictor)
        assertEquals(File(dir, VoiceCatalog.Supertonic.TEXT_ENCODER).path, s.textEncoder)
        assertEquals(File(dir, VoiceCatalog.Supertonic.VECTOR_ESTIMATOR).path, s.vectorEstimator)
        assertEquals(File(dir, VoiceCatalog.Supertonic.VOCODER).path, s.vocoder)
        assertEquals(File(dir, VoiceCatalog.Supertonic.TTS_JSON).path, s.ttsJson)
        assertEquals(File(dir, VoiceCatalog.Supertonic.UNICODE_INDEXER).path, s.unicodeIndexer)
        assertEquals(File(dir, VoiceCatalog.Supertonic.VOICES).path, s.voiceStyle)
        assertEquals(3, c.model.numThreads)
        assertEquals("cpu", c.model.provider)
        assertEquals(1, c.maxNumSentences)
    }

    @Test fun languageGoesThroughExtra() {
        val g = SupertonicConfig.generation(7, "fr")
        assertEquals(7, g.sid)
        assertEquals(mapOf("lang" to "fr"), g.extra)
        assertEquals(SupertonicConfig.STEPS, g.numSteps)
        assertEquals(SupertonicConfig.SPEED, g.speed, 0f)
    }

    @Test fun threadsAreCapped() {
        assertEquals(1, SupertonicConfig.threads(1))
        assertEquals(4, SupertonicConfig.threads(8))
    }
}
