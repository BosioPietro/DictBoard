package io.github.bosiopietro.dictboard

import android.content.Context
import android.os.Handler
import android.os.Looper
import io.github.bosiopietro.dictboard.dictionary.Dictionary
import io.github.bosiopietro.dictboard.dictionary.Words
import java.io.FileInputStream
import java.nio.channels.FileChannel
import java.util.concurrent.Executors

/**
 * The bundled dictionary, opened once per process. It's mapped from the APK
 * (stored uncompressed), so it costs no memory beyond the pages lookups
 * touch; the list of words to suggest from is built off the main thread.
 */
object Dictionaries {
    private const val ENGLISH = "english.wdict"

    private val background = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    @Volatile
    private var english: Dictionary? = null

    @Volatile
    private var words: Words? = null

    fun english(context: Context): Dictionary =
        english ?: synchronized(this) {
            english ?: open(context).also { english = it }
        }

    /** Calls [then] on the main thread with every word, once they're listed. */
    fun words(context: Context, then: (Words) -> Unit) {
        words?.let { return then(it) }
        val dictionary = english(context)
        background.execute {
            val all = words ?: dictionary.words().also { words = it }
            main.post { then(all) }
        }
    }

    private fun open(context: Context): Dictionary =
        context.assets.openFd(ENGLISH).use { fd ->
            FileInputStream(fd.fileDescriptor).channel.use { channel ->
                // The mapping outlives the channel.
                val data = channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
                checkNotNull(Dictionary.open(data)) { "$ENGLISH isn't a dictionary" }
            }
        }
}
