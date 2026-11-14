package dev.antisnorr.core

import android.content.Context
import android.util.Log
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Append-only night log stored in the app's private files dir.
 *
 * One compact line per inference frame (~30 bytes), so 7 h of monitoring is
 * roughly 2 MB. A single background executor serialises writes; the writer is
 * flushed every [FLUSH_EVERY] lines and on [flush].
 */
object NightLogger {

    private const val TAG = "NightLogger"
    private const val FILE_NAME = "antisnorr_night.log"
    private const val MAX_BYTES = 20L * 1024 * 1024 // 20 MB cap
    private const val FLUSH_EVERY = 64

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "NightLogger").apply { isDaemon = true }
    }
    private val pending = AtomicInteger(0)
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    @Volatile
    private var writer: BufferedWriter? = null
    @Volatile
    private var file: File? = null
    private val lock = Any()

    fun init(context: Context) {
        synchronized(lock) {
            if (file != null) return
            val f = File(context.filesDir, FILE_NAME)
            if (f.exists() && f.length() > MAX_BYTES) {
                f.delete()
            }
            file = f
            writer = BufferedWriter(OutputStreamWriter(FileOutputStream(f, true), Charsets.UTF_8), 64 * 1024)
            Log.i(TAG, "Night log: ${f.absolutePath} (${f.length()} bytes)")
        }
    }

    /** Append one line. Thread-safe; non-blocking (queued on the executor). */
    fun log(line: String) {
        executor.execute {
            synchronized(lock) {
                val w = writer ?: return@execute
                try {
                    w.write(timeFormat.format(Date()))
                    w.write(" ")
                    w.write(line)
                    w.write("\n")
                    if (pending.incrementAndGet() >= FLUSH_EVERY) {
                        w.flush()
                        pending.set(0)
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "write failed: ${t.message}")
                }
            }
        }
    }

    fun flush() {
        executor.execute {
            synchronized(lock) {
                try { writer?.flush() } catch (_: Throwable) {}
                pending.set(0)
            }
        }
    }

    fun clear() {
        executor.execute {
            synchronized(lock) {
                try { writer?.close() } catch (_: Throwable) {}
                file?.delete()
                val f = file
                if (f != null) {
                    writer = BufferedWriter(OutputStreamWriter(FileOutputStream(f, false), Charsets.UTF_8), 64 * 1024)
                }
                pending.set(0)
            }
        }
    }

    fun file(context: Context): File {
        if (file == null) init(context)
        return file!!
    }

    fun sizeBytes(): Long = file?.length() ?: 0L
}
