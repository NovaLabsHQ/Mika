package com.wangzan101.mika.board

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.wangzan101.mika.core.queue.github.ImageShrinker
import java.io.ByteArrayOutputStream

/** 截图缩到长边不超过给定像素、JPEG 不超过给定字节(逐步降质量);读不出来返回 null。 */
object BitmapShrinker : ImageShrinker {
    override fun shrink(bytes: ByteArray, maxEdgePx: Int, maxBytes: Int): ByteArray? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxEdgePx) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val edge = maxOf(decoded.width, decoded.height)
        val scaled = if (edge > maxEdgePx) {
            val f = maxEdgePx.toFloat() / edge
            Bitmap.createScaledBitmap(decoded, (decoded.width * f).toInt().coerceAtLeast(1), (decoded.height * f).toInt().coerceAtLeast(1), true)
        } else decoded
        try {
            for (q in intArrayOf(80, 65, 50, 35)) {
                val out = ByteArrayOutputStream()
                scaled.compress(Bitmap.CompressFormat.JPEG, q, out)
                if (out.size() <= maxBytes) return out.toByteArray()
            }
            return null
        } finally {
            if (scaled !== decoded) scaled.recycle()
            decoded.recycle()
        }
    }
}
