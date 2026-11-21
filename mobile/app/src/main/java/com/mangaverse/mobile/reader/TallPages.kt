package com.mangaverse.mobile.reader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.os.Build
import coil3.imageLoader
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.size.Size
import com.mangaverse.mobile.data.PageModel
import com.mangaverse.mobile.data.PageKeyer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Tiras longas de manhwa/webtoon (ex.: 720 × 30000 px) passam do tamanho máximo de imagem que a GPU desenha e
 * aparecem cortadas, pretas ou distorcidas. Essas páginas são lidas do disco e desenhadas em fatias.
 */
sealed interface PageLayout {
    /** Página comum: o Coil desenha inteira. */
    data object Normal : PageLayout

    /** Página alta: [slices] fatias de até [sliceHeight] px (na resolução original). */
    class Tall(val file: File, val width: Int, val height: Int, val sliceHeight: Int) : PageLayout {
        val slices: Int get() = (height + sliceHeight - 1) / sliceHeight
        fun sliceRect(k: Int) = Rect(0, k * sliceHeight, width, minOf(height, (k + 1) * sliceHeight))

        private var decoder: BitmapRegionDecoder? = null

        @Synchronized
        fun decode(k: Int, targetWidth: Int): Bitmap? {
            val d = decoder ?: newDecoder(file)?.also { decoder = it } ?: return null
            var sample = 1
            while (width / (sample * 2) >= targetWidth) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 }
            return runCatching { d.decodeRegion(sliceRect(k), opts) }.getOrNull()
        }

        @Suppress("DEPRECATION")
        private fun newDecoder(f: File): BitmapRegionDecoder? = runCatching {
            if (Build.VERSION.SDK_INT >= 31) BitmapRegionDecoder.newInstance(f.path) else BitmapRegionDecoder.newInstance(f.path, false)
        }.getOrNull()
    }
}

/** Proporção (altura ÷ largura) a partir da qual a página é tratada como tira longa. */
const val TALL_RATIO = 3f

/** Altura de cada fatia, em px da imagem original: bem abaixo do limite de textura de qualquer aparelho. */
private const val SLICE = 2048

/** Arquivo da página no aparelho (baixada ou no cache de imagens) e decide se ela precisa de fatias. */
suspend fun probePage(context: Context, model: Any): PageLayout = withContext(Dispatchers.IO) {
    val file = when (model) {
        is File -> model
        is PageModel -> {
            val loader = context.imageLoader
            val cache = loader.diskCache ?: return@withContext PageLayout.Normal
            val key = PageKeyer.key(model, coil3.request.Options(context))
            // baixa para o cache de disco sem decodificar a imagem inteira (miniatura de 64 px)
            if (cache.openSnapshot(key)?.use { true } != true) {
                loader.execute(ImageRequest.Builder(context).data(model).size(Size(64, 64)).memoryCachePolicy(CachePolicy.DISABLED).build())
            }
            cache.openSnapshot(key)?.use { it.data.toFile() } ?: return@withContext PageLayout.Normal
        }
        else -> return@withContext PageLayout.Normal
    }
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    val w = bounds.outWidth
    val h = bounds.outHeight
    if (w <= 0 || h <= 0 || h < w * TALL_RATIO) PageLayout.Normal else PageLayout.Tall(file, w, h, SLICE.coerceAtMost(maxOf(w * 2, 1024)))
}
