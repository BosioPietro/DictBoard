package io.github.bosiopietro.dictboard.dictionary

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.DataFormatException
import java.util.zip.Inflater

/**
 * A dictionary file in WinDict's format, as WinDict's `tools/wdict.py`
 * writes it (little endian):
 *
 *     b"WDIC"  u32 version (4)
 *     u32 entry_block_count   u32 synset_block_count   u32 synsets_per_block
 *     u32 info_offset   u32 info_length
 *     entry_block_count  x (u32 first_key_offset, u32 data_offset, u32 data_length)
 *     synset_block_count x (u32 data_offset, u32 data_length)
 *     first keys of the entry blocks (NUL-terminated UTF-8), info, raw-deflate blocks
 *
 * It's read in place: from a memory-mapped file, a lookup only pages in the
 * block index and the few small compressed blocks it touches.
 */
internal class Wdict private constructor(private val data: ByteBuffer) {
    val entryBlocks = u32(8)
    val synsetBlocks = u32(12)
    private val synsetsPerBlock = maxOf(u32(16), 1)

    /** What the dictionary says about itself: "name=value" lines. */
    fun info(): String? = bytes(u32(20), u32(24))?.toString(Charsets.UTF_8)

    /** The text of entry block [block]: entries separated by 0x1E. */
    fun entryBlock(block: Int): String? {
        val row = HEADER + 12 * block
        return inflate(u32(row + 4), u32(row + 8))
    }

    fun entry(key: String): Entry? {
        val text = entryBlock(blockOf(key.toByteArray(Charsets.UTF_8)) ?: return null) ?: return null
        val record = text.splitToSequence(RS).firstOrNull { it.substringBefore('\n') == key } ?: return null
        return Entry.parse(record)
    }

    /** The entry for [key], if it has senses of its own. */
    fun defined(key: String): Entry? = entry(key)?.takeIf { it.senses.isNotEmpty() }

    /**
     * What [word] is a form of, if it isn't a word in its own right:
     * "geese" → "goose", "walked" → "walk".
     */
    fun baseOf(word: String): String? {
        val entry = entry(word) ?: return null
        if (entry.senses.isNotEmpty()) return null
        return entry.redirects.firstOrNull { defined(it) != null }
    }

    fun synset(id: Int): Synset? {
        val block = id / synsetsPerBlock
        if (id < 0 || block >= synsetBlocks) return null
        val row = HEADER + 12 * entryBlocks + 8 * block
        val text = inflate(u32(row), u32(row + 4)) ?: return null
        val record = text.splitToSequence(RS).elementAtOrNull(id % synsetsPerBlock) ?: return null
        val lines = record.split('\n')
        if (lines.size < 3) return null
        return Synset(
            lemmas = lines[0].split('|'),
            antonyms = lines[1].split('|').map { group -> group.split('\t').filter { it.isNotEmpty() } },
            definition = lines[2],
            examples = lines.drop(3),
        )
    }

    private fun u32(offset: Int): Int =
        if (offset >= 0 && offset.toLong() + 4 <= data.limit()) data.getInt(offset) else 0

    private fun bytes(offset: Int, length: Int): ByteArray? {
        if (offset < 0 || length < 0 || offset.toLong() + length > data.limit()) return null
        val out = ByteArray(length)
        val view = data.duplicate()
        view.position(offset)
        view.get(out)
        return out
    }

    private fun inflate(offset: Int, length: Int): String? {
        val packed = bytes(offset, length) ?: return null
        val inflater = Inflater(true)
        try {
            inflater.setInput(packed)
            val out = ByteArrayOutputStream(length * 4)
            val buffer = ByteArray(16 * 1024)
            while (!inflater.finished()) {
                val n = inflater.inflate(buffer)
                if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) return null
                out.write(buffer, 0, n)
                if (out.size() > MAX_BLOCK) return null
            }
            return String(out.toByteArray(), Charsets.UTF_8)
        } catch (e: DataFormatException) {
            return null
        } finally {
            inflater.end()
        }
    }

    private fun firstKey(block: Int): ByteArray {
        val start = u32(HEADER + 12 * block)
        var end = start
        while (end < data.limit() && data.get(end) != 0.toByte()) end++
        return bytes(start, end - start) ?: ByteArray(0)
    }

    /** The last block whose first key is <= [key], in byte order. */
    private fun blockOf(key: ByteArray): Int? {
        var lo = 0
        var hi = entryBlocks
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (compareBytes(firstKey(mid), key) <= 0) lo = mid + 1 else hi = mid
        }
        return (lo - 1).takeIf { it >= 0 }
    }

    companion object {
        private const val HEADER = 28
        private const val VERSION = 4
        private const val RS = "\u001e"

        /**
         * The most a block may inflate to. They're a few KB; this keeps a
         * broken file from taking all the memory.
         */
        private const val MAX_BLOCK = 1 shl 20

        /** [data] read as a dictionary, if it is one, whole. */
        fun open(data: ByteBuffer): Wdict? {
            val bytes = data.slice().order(ByteOrder.LITTLE_ENDIAN)
            if (bytes.limit() < HEADER) return null
            if (bytes.getInt(0) != 0x43494457 /* "WDIC" */) return null
            val dict = Wdict(bytes)
            if (dict.u32(4) != VERSION) return null
            // Counts read from a broken file mustn't send lookups out of it.
            val index = HEADER.toLong() + 12L * dict.entryBlocks + 8L * dict.synsetBlocks
            if (dict.entryBlocks < 0 || dict.synsetBlocks < 0 || index > bytes.limit()) return null
            if (dict.info() == null) return null
            return dict
        }

        private fun compareBytes(a: ByteArray, b: ByteArray): Int {
            for (i in 0 until minOf(a.size, b.size)) {
                val d = (a[i].toInt() and 0xff) - (b[i].toInt() and 0xff)
                if (d != 0) return d
            }
            return a.size - b.size
        }
    }
}

internal class Entry(
    val display: String?,
    val redirects: List<String>,
    /** How it's written, when it's typed without its accents. */
    val spellings: List<String>,
    /** Part of speech and synset ids, most-used part of speech first. */
    val senses: List<Pair<Char, List<Int>>>,
) {
    fun withDisplay(display: String?) = Entry(display, redirects, spellings, senses)

    companion object {
        /** An entry's record, as stored: its key, then one line per field. */
        fun parse(record: String): Entry {
            var display: String? = null
            val redirects = mutableListOf<String>()
            val spellings = mutableListOf<String>()
            val senses = mutableListOf<Pair<Char, List<Int>>>()
            for (line in record.split('\n').drop(1)) {
                when (line.firstOrNull()) {
                    'W' -> display = line.substring(1)
                    'R' -> redirects += line.substring(1)
                    'S' -> spellings += line.substring(1)
                    'P' -> {
                        val pos = line.getOrElse(1) { 'n' }
                        val ids = line.drop(2).split(' ').mapNotNull { it.toIntOrNull() }
                        senses += pos to ids
                    }
                }
            }
            return Entry(display, redirects, spellings, senses)
        }
    }
}

internal class Synset(
    val lemmas: List<String>,
    /** Each lemma's opposites, in the order of [lemmas]. */
    val antonyms: List<List<String>>,
    val definition: String,
    val examples: List<String>,
)
