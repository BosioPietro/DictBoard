package io.github.bosiopietro.dictboard.dictionary

/** A word offered for what's been typed. */
data class Suggestion(
    /** As it's written ("Paris", "ice cream"). */
    val word: String,
    /** What an inflected form is a form of ("geese" → "goose"). */
    val base: String?,
    /** How many of its first characters are what was typed: none when it puts a typo right. */
    val typed: Int,
)

/**
 * Every word a dictionary defines, to suggest from as one is typed: made by
 * [Dictionary.words]. The keys are kept in one string, in the dictionary's
 * order, so 150,000 words cost a few MB rather than 150,000 objects.
 */
class Words internal constructor(private val dict: Wdict) {
    /** Each word's key, followed by its display form when that differs. */
    private val text: String
    private val starts: IntArray
    private val keyLengths: IntArray
    private val displayLengths: IntArray
    private val senseCounts: IntArray

    init {
        val text = StringBuilder()
        val starts = IntList()
        val keyLengths = IntList()
        val displayLengths = IntList()
        val senseCounts = IntList()
        for (block in 0 until dict.entryBlocks) {
            val records = dict.entryBlock(block) ?: continue
            for (record in records.splitToSequence('\u001e')) {
                val key = record.substringBefore('\n')
                var entry = Entry.parse(record)
                // Spelt without its accents ("arleng"): offered as written.
                if (entry.senses.isEmpty()) {
                    entry = entry.spellings.firstNotNullOfOrNull { s ->
                        dict.defined(s)?.let { it.withDisplay(it.display ?: s) }
                    } ?: entry
                }
                val senses = entry.senses.sumOf { it.second.size }
                if (senses == 0) continue
                val display = entry.display?.takeIf { it != key }.orEmpty()
                starts.add(text.length)
                keyLengths.add(key.length)
                displayLengths.add(display.length)
                senseCounts.add(senses)
                text.append(key).append(display)
            }
        }
        this.text = text.toString()
        this.starts = starts.toArray()
        this.keyLengths = keyLengths.toArray()
        this.displayLengths = displayLengths.toArray()
        this.senseCounts = senseCounts.toArray()
    }

    val size: Int get() = starts.size

    private fun key(i: Int) = text.substring(starts[i], starts[i] + keyLengths[i])

    private fun keyStartsWith(i: Int, prefix: String) =
        keyLengths[i] >= prefix.length && text.startsWith(prefix, starts[i])

    private fun display(i: Int): String {
        val start = starts[i] + keyLengths[i]
        return if (displayLengths[i] == 0) key(i) else text.substring(start, start + displayLengths[i])
    }

    /**
     * Up to [limit] words for what's been [typed] (lowercase), the likeliest
     * first: the word itself, then words starting with what's typed, then
     * words that do once a typo or two is put right ("recieve", "seperat").
     * Among those, single words come before phrases, then the ones with the
     * most meanings, which tend to be the common ones.
     */
    fun suggest(typed: String, limit: Int): List<Suggestion> {
        if (typed.isEmpty() || limit <= 0) return emptyList()
        class Found(val index: Int, val typos: Int) {
            val key = key(index)
        }
        // Words starting with what's typed are consecutive.
        val found = mutableListOf<Found>()
        var i = partitionPoint(typed)
        while (i < size && keyStartsWith(i, typed)) found += Found(i++, 0)
        val typedChars = typed.codePoints().toArray()
        val max = typosAllowed(typedChars.size)
        // Words with typos rank below those, so they're looked for only
        // when there's room left.
        if (found.size < limit && max > 0) {
            val rows = IntArray(3 * (typedChars.size + 1))
            for (j in 0 until size) {
                if (keyStartsWith(j, typed)) continue
                typos(typedChars, key(j), max, false, rows)?.let { found += Found(j, it) }
            }
        }
        // Stable: equal ranks stay in alphabetical order.
        found.sortWith(
            compareBy<Found> { it.key != typed }
                .thenBy { it.typos }
                .thenBy { ' ' in it.key }
                .thenByDescending { senseCounts[it.index] }
                .thenBy { it.key.length },
        )
        val out = mutableListOf<Suggestion>()
        for (f in found) {
            if (out.size == limit) break
            val word = display(f.index)
            // Once, though it's there with and without its accents.
            if (out.any { it.word == word }) continue
            out += Suggestion(word, null, if (f.typos == 0) typedChars.size else 0)
        }
        // An inflected form ("geese", "walked") is offered, as what it's a
        // form of, only once it's typed in full, and when it isn't a word of
        // its own.
        if (out.none { it.word.lowercase() == typed }) {
            dict.baseOf(typed)?.let { out.add(0, Suggestion(typed, it, typedChars.size)) }
        }
        return out.take(limit)
    }

    /**
     * The word that was likely meant by [typed] (lowercase), which wasn't
     * found: the closest one once a typo or two is put right ("emobdy" →
     * "embody"), single words before phrases, then the one with the most
     * meanings.
     */
    fun correct(typed: String): String? {
        val typedChars = typed.codePoints().toArray()
        val max = typosAllowed(typedChars.size)
        if (max == 0) return null
        val rows = IntArray(3 * (typedChars.size + 1))
        var best = -1
        var bestTypos = 0
        for (i in 0 until size) {
            val key = key(i)
            if (key == typed) continue
            val n = typos(typedChars, key, max, true, rows) ?: continue
            if (best < 0 || isCloser(i, n, best, bestTypos)) {
                best = i
                bestTypos = n
            }
        }
        return if (best < 0) null else display(best)
    }

    /** Whether word [a], [aTypos] away, is likelier meant than word [b]. */
    private fun isCloser(a: Int, aTypos: Int, b: Int, bTypos: Int): Boolean {
        if (aTypos != bTypos) return aTypos < bTypos
        val aPhrase = ' ' in key(a)
        if (aPhrase != ' ' in key(b)) return !aPhrase
        return senseCounts[a] > senseCounts[b]
    }

    /** The first word whose key isn't before [typed], in the dictionary's (code point) order. */
    private fun partitionPoint(typed: String): Int {
        var lo = 0
        var hi = size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (compareCodePoints(key(mid), typed) < 0) lo = mid + 1 else hi = mid
        }
        return lo
    }
}

/** A typo, in half typos: two letters swapped, the likeliest slip, counts as half of one. */
internal const val TYPO = 2
internal const val SWAP = 1

/** How many typos a suggestion may put right, in half typos: none in a short word. */
internal fun typosAllowed(chars: Int) = when {
    chars <= 3 -> 0
    chars <= 7 -> TYPO
    else -> 2 * TYPO
}

/**
 * The fewest typos (a letter added, dropped, changed, or two swapped) that
 * turn the start of [key], or all of it when [whole], into [typed], in half
 * typos, if there are at most [max]. [rows] is scratch space of at least
 * `3 * (typed.size + 1)`, kept between calls.
 */
internal fun typos(typed: IntArray, key: String, max: Int, whole: Boolean, rows: IntArray): Int? {
    // Edit distance, three rows at a time: one per letter of the key. With
    // at most `band` letters added or dropped, only cells that close to the
    // diagonal can be in reach; the ones just past it read as out of reach.
    val far = Int.MAX_VALUE / 4
    val m = typed.size
    val band = max / TYPO
    // Only the cells at the key's end count when all of it must match.
    val end = if (whole) {
        val length = key.codePointCount(0, key.length)
        if (length > m + band) return null
        length
    } else {
        -1
    }
    val w = m + 1
    for (i in 0 until w) rows[i] = if (i <= band) i * TYPO else far
    rows.fill(far, w, 3 * w)
    var best = if (m <= band && (end < 0 || end == 0)) m * TYPO else far
    var before = 0
    var j = 0
    var at = 0
    while (at < key.length && j < m + band) {
        val c = key.codePointAt(at)
        at += Character.charCount(c)
        j++
        val cur = (j % 3) * w
        val prev = ((j - 1) % 3) * w
        val prev2 = ((j + 1) % 3) * w
        val lo = maxOf(j - band, 1)
        val hi = minOf(j + band, m)
        rows[cur + lo - 1] = if (j <= band) j * TYPO else far
        var rowMin = rows[cur + lo - 1]
        for (i in lo..hi) {
            val cost = if (typed[i - 1] == c) 0 else TYPO
            var d = minOf(rows[prev + i] + TYPO, rows[cur + i - 1] + TYPO, rows[prev + i - 1] + cost)
            if (i > 1 && j > 1 && typed[i - 1] == before && typed[i - 2] == c) {
                d = minOf(d, rows[prev2 + i - 2] + SWAP)
            }
            rows[cur + i] = d
            rowMin = minOf(rowMin, d)
        }
        if (hi < m) rows[cur + hi + 1] = far
        if (hi == m && (end < 0 || end == j)) best = minOf(best, rows[cur + m])
        if (rowMin > max) break
        before = c
    }
    return if (best <= max) best else null
}

/** [a] and [b] in code point order, as UTF-8 bytes sort: the dictionary's order. */
private fun compareCodePoints(a: String, b: String): Int {
    var i = 0
    var j = 0
    while (i < a.length && j < b.length) {
        val x = a.codePointAt(i)
        val y = b.codePointAt(j)
        if (x != y) return x.compareTo(y)
        i += Character.charCount(x)
        j += Character.charCount(y)
    }
    return (a.length - i).compareTo(b.length - j)
}

/** A growable IntArray, so building [Words] boxes nothing. */
private class IntList {
    private var items = IntArray(1024)
    private var size = 0

    fun add(value: Int) {
        if (size == items.size) items = items.copyOf(size * 2)
        items[size++] = value
    }

    fun toArray(): IntArray = items.copyOf(size)
}
