package eu.kanade.tachiyomi.extension.ja.pdftoshokan

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import keiyoushi.utils.applicationContext
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.asResponseBody
import okio.Buffer
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.sqrt

/**
 * Serves `<pdf url>#pdfpage=<index>` requests by rendering that page of the (disk-cached) PDF to a JPEG,
 * so the reader and downloader see ordinary image pages. The PDF is re-downloaded on demand if the cache
 * was evicted, which keeps downloads and re-reads working after the cache is cleared.
 */
class PdfPageInterceptor(private val cache: PdfCache) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val fragment = request.url.fragment
        if (fragment == null || !fragment.startsWith(FRAGMENT_PREFIX)) return chain.proceed(request)

        val index = fragment.removePrefix(FRAGMENT_PREFIX).toInt()
        val pdfUrl = request.url.newBuilder().fragment(null).build().toString()
        val file = cache.getOrDownload(pdfUrl) { chain.proceed(pdfRequest(pdfUrl, request.headers)) }

        val buffer = Buffer()
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            PdfRenderer(fd).use { renderer ->
                renderer.openPage(index).use { page ->
                    // PDF units are 1/72 inch; render at a readable width without blowing up memory on huge pages.
                    val scale = minOf(
                        TARGET_WIDTH.toFloat() / page.width,
                        sqrt(MAX_PIXELS.toFloat() / (page.width * page.height)),
                        MAX_SCALE,
                    )
                    val bitmap = Bitmap.createBitmap(
                        (page.width * scale).toInt().coerceAtLeast(1),
                        (page.height * scale).toInt().coerceAtLeast(1),
                        Bitmap.Config.ARGB_8888,
                    )
                    // Pages render onto a transparent bitmap
                    bitmap.eraseColor(Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 90, buffer.outputStream())
                    bitmap.recycle()
                }
            }
        }

        return Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(buffer.asResponseBody(JPEG))
            .build()
    }

    companion object {
        private const val FRAGMENT_PREFIX = "pdfpage="
        private const val TARGET_WIDTH = 1440
        private const val MAX_PIXELS = 1440 * 6000
        private const val MAX_SCALE = 4f
        private val JPEG = "image/jpeg".toMediaType()

        fun pageUrl(pdfUrl: String, index: Int): String = pdfUrl.toHttpUrl().newBuilder()
            .fragment("$FRAGMENT_PREFIX$index")
            .build()
            .toString()

        fun pdfRequest(pdfUrl: String, headers: Headers): Request = Request.Builder()
            .url(pdfUrl)
            .headers(headers.newBuilder().set("Accept", "application/pdf,*/*").build())
            .build()
    }
}

class PdfCache {

    private val dir by lazy { File(applicationContext.cacheDir, "pdftoshokan_pdf").apply { mkdirs() } }
    private val locks = ConcurrentHashMap<String, Any>()

    fun getOrDownload(pdfUrl: String, fetch: () -> Response): File {
        val key = sha256(pdfUrl)
        val file = File(dir, "$key.pdf")
        // Reader loads several pages in parallel; only one of them should download the PDF.
        synchronized(locks.getOrPut(key) { Any() }) {
            if (file.exists()) {
                file.setLastModified(System.currentTimeMillis())
                return file
            }
            trim()
            val tmp = File(dir, "$key.tmp")
            fetch().use { response ->
                if (!response.isSuccessful) throw IOException("HTTP ${response.code} while downloading PDF")
                val contentType = response.header("Content-Type").orEmpty()
                if (contentType.startsWith("text/html")) throw IOException("Expected a PDF but got HTML; open in WebView to pass the site check")
                tmp.outputStream().use { response.body.byteStream().copyTo(it) }
            }
            if (!tmp.renameTo(file)) throw IOException("Failed to store PDF")
            return file
        }
    }

    private fun trim() {
        val files = dir.listFiles { f -> f.extension == "pdf" }?.sortedByDescending { it.lastModified() } ?: return
        var total = 0L
        files.forEach { f ->
            total += f.length()
            if (total > MAX_CACHE_BYTES) f.delete()
        }
    }

    companion object {
        private const val MAX_CACHE_BYTES = 500L * 1024 * 1024

        fun pageCount(file: File): Int = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            PdfRenderer(fd).use { it.pageCount }
        }

        private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }
}
