package app.vandilaunch

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/** Provider logos: downloaded once, shrunk, and kept on disk. */
object Logos {
    /** Source images run to thousands of pixels; the app shows them about 20dp tall. */
    private const val MAX_HEIGHT = 96
    private const val MAX_BYTES = 8 * 1024 * 1024

    private val memory = HashMap<String, Bitmap>()
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    fun cached(url: String): Bitmap? = synchronized(memory) { memory[url] }

    /** Calls [done] on the main thread once the logo is available; never if it cannot be had. */
    fun load(c: Context, url: String, done: (Bitmap) -> Unit) {
        cached(url)?.let { done(it); return }
        if (!url.startsWith("https://")) return
        val app = c.applicationContext
        io.execute {
            val bitmap = cached(url) ?: try {
                val file = File(app.cacheDir, "logo_" + Integer.toHexString(url.hashCode()) + ".png")
                (if (file.exists()) BitmapFactory.decodeFile(file.path) else null)
                    ?: download(url)?.also { b -> file.outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) } }
            } catch (e: Exception) {
                null
            }
            if (bitmap != null) {
                synchronized(memory) { memory[url] = bitmap }
                main.post { done(bitmap) }
            }
        }
    }

    private fun download(url: String): Bitmap? {
        val c = URL(url).openConnection() as HttpURLConnection
        val bytes = try {
            c.connectTimeout = 10_000
            c.readTimeout = 20_000
            if (c.responseCode != 200 || c.contentLengthLong > MAX_BYTES) return null
            c.inputStream.use { it.readBytes() }
        } finally {
            c.disconnect()
        }
        if (bytes.size > MAX_BYTES) return null

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outHeight <= 0 || bounds.outWidth <= 0) return null
        var sample = 1
        while (bounds.outHeight / (sample * 2) >= MAX_HEIGHT) sample *= 2
        val rough = BitmapFactory.decodeByteArray(
            bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }
        ) ?: return null
        if (rough.height <= MAX_HEIGHT) return rough
        val width = maxOf(1, rough.width * MAX_HEIGHT / rough.height)
        return Bitmap.createScaledBitmap(rough, width, MAX_HEIGHT, true)
    }
}
