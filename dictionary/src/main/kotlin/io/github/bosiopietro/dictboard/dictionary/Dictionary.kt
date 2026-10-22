package io.github.bosiopietro.dictboard.dictionary

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.Locale

/**
 * An offline dictionary in WinDict's format: Open English WordNet, as WinDict
 * bundles it, or any other `.wdict` WinDict's tools build.
 *
 * Lookups only read the dictionary, so one can be used from any thread.
 */
class Dictionary private constructor(private val dict: Wdict) {
    /** The code of the language it's of ("en"). */
    val language: String

    /** The source credited for its entries ("Open English WordNet"). */
    val name: String

    /** Its page for a word, with "{}" for the word. Only web pages are kept. */
    private val link: String?

    init {
        val info = parseInfo(dict.info().orEmpty())
        language = info["language"].orEmpty()
        name = info["name"].orEmpty()
        link = info["link"]?.takeIf { it.startsWith("https://") }
    }

    /** The dictionary's page for [word], if it has one. */
    fun link(word: String): String? = link?.replace("{}", percentEncode(word))

    /**
     * [word] (lowercase, as [normalize] makes it), if the dictionary has it:
     * inflected forms give their base ("ran" → "run"), compounds are tried
     * with and without hyphens, and words typed without their accents are
     * shown as they're written.
     */
    fun lookup(word: String, detail: Detail = Detail()): Definition? {
        for (candidate in spellings(word)) {
            val entry = dict.entry(candidate) ?: continue
            if (entry.senses.isNotEmpty()) {
                val note = entry.redirects.firstOrNull()?.let { Note.AlsoFormOf(it) }
                return build(candidate, entry, note, detail)
            }
            // An inflected form ("ran", "walked"): show its base.
            for (base in entry.redirects) {
                val baseEntry = dict.defined(base) ?: continue
                return build(base, baseEntry, Note.BaseFormOf(word), detail)
            }
            // Typed without its accents ("arleng"): show it as it's written.
            for (spelling in entry.spellings) {
                val written = dict.defined(spelling) ?: continue
                return build(spelling, written, null, detail)
            }
        }
        return null
    }

    /**
     * Every word the dictionary defines, to suggest from as a word is typed.
     * Building it reads the whole dictionary (some tens of ms), so it's
     * best made off the main thread, once.
     */
    fun words(): Words = Words(dict)

    private fun build(key: String, entry: Entry, note: Note?, detail: Detail): Definition? {
        val meanings = mutableListOf<Meaning>()
        for ((pos, ids) in entry.senses) {
            val senses = mutableListOf<Sense>()
            val synonyms = mutableListOf<String>()
            val antonyms = mutableListOf<String>()
            for (synset in ids.take(maxOf(detail.senses, 1)).mapNotNull { dict.synset(it) }) {
                synset.lemmas.forEachIndexed { i, lemma ->
                    if (sameWord(lemma, key)) {
                        // Opposites belong to a word, not to its synonyms.
                        for (a in synset.antonyms.getOrNull(i).orEmpty()) {
                            if (antonyms.size < MAX_ANTONYMS && a !in antonyms) antonyms += a
                        }
                    } else if (synonyms.size < detail.synonyms && synonyms.none { sameWord(it, lemma) }) {
                        synonyms += lemma
                    }
                }
                senses += Sense(
                    text = sentence(synset.definition),
                    example = synset.examples.firstOrNull().takeIf { detail.examples },
                )
            }
            if (senses.isEmpty()) continue
            meanings += Meaning(PartOfSpeech.of(pos), senses, synonyms, antonyms)
        }
        if (meanings.isEmpty()) return null
        return Definition(
            word = entry.display ?: key,
            language = language,
            meanings = meanings,
            note = note,
            source = name,
        )
    }

    companion object {
        /** Most opposites shown per part of speech. Words rarely have more. */
        const val MAX_ANTONYMS = 6

        /** [data] as a dictionary, if it's one: a file mapped into memory, say. */
        fun open(data: ByteBuffer): Dictionary? = Wdict.open(data)?.let(::Dictionary)

        /** The dictionary at [path], mapped into memory, if it's one. */
        fun open(path: Path): Dictionary? =
            FileChannel.open(path, StandardOpenOption.READ).use { channel ->
                // The mapping outlives the channel.
                open(channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size()))
            }

        private fun parseInfo(text: String): Map<String, String> =
            text.lines().mapNotNull { line ->
                val at = line.indexOf('=')
                if (at < 0) null else line.substring(0, at) to line.substring(at + 1)
            }.toMap()
    }
}

/** How much of an entry a lookup returns. */
data class Detail(
    /** The most senses per part of speech. */
    val senses: Int = 4,
    /** The most synonyms per part of speech. */
    val synonyms: Int = 6,
    /** Whether the example sentences are kept. */
    val examples: Boolean = true,
)

data class Definition(
    /** As it's written ("Paris", "ice cream"). */
    val word: String,
    /** The code of the language it's in ("en"). */
    val language: String,
    val meanings: List<Meaning>,
    /** Shown above the senses, e.g. when an inflection was resolved. */
    val note: Note?,
    /** Where it came from, for the footer. */
    val source: String,
)

/** Said of a word above its senses; how is up to whoever shows it. */
sealed interface Note {
    /** The word shown is the base form of the word looked up ("walked"). */
    data class BaseFormOf(val word: String) : Note

    /** The word shown is a word of its own, and also a form of [word]. */
    data class AlsoFormOf(val word: String) : Note
}

data class Meaning(
    val partOfSpeech: PartOfSpeech,
    val senses: List<Sense>,
    val synonyms: List<String>,
    val antonyms: List<String>,
)

data class Sense(val text: String, val example: String?)

enum class PartOfSpeech(val label: String) {
    NOUN("noun"),
    VERB("verb"),
    ADJECTIVE("adjective"),
    ADVERB("adverb"),
    PHRASE("phrase"),
    INTERJECTION("interjection"),
    PRONOUN("pronoun"),
    PREPOSITION("preposition"),
    CONJUNCTION("conjunction"),
    ARTICLE("article"),
    ABBREVIATION("abbreviation");

    companion object {
        internal fun of(code: Char) = when (code) {
            'v' -> VERB
            'a', 's' -> ADJECTIVE
            'r' -> ADVERB
            'p' -> PHRASE
            'i' -> INTERJECTION
            'o' -> PRONOUN
            'e' -> PREPOSITION
            'c' -> CONJUNCTION
            't' -> ARTICLE
            'b' -> ABBREVIATION
            else -> NOUN
        }
    }
}

/** What selected or typed text makes, to be looked up. */
sealed interface Normalized {
    data class Word(val text: String) : Normalized

    /** No letters or digits at all. */
    data object Empty : Normalized

    /** More than a short phrase. */
    data object TooLong : Normalized
}

/** The most words, and characters, a lookup takes: a short phrase. */
private const val MAX_LOOKUP_WORDS = 4
const val MAX_LOOKUP_CHARS = 60

/** Cleans up raw selected text into something worth looking up. */
fun normalize(raw: String): Normalized {
    val trimmed = raw.trim { !it.isLetterOrDigit() }
    val words = trimmed.split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (words.isEmpty()) return Normalized.Empty
    if (words.size > MAX_LOOKUP_WORDS || trimmed.codePointCount(0, trimmed.length) > MAX_LOOKUP_CHARS) {
        return Normalized.TooLong
    }
    return Normalized.Word(words.joinToString(" ").lowercase(Locale.ROOT))
}

/** "a domesticated canid" → "A domesticated canid." */
internal fun sentence(text: String): String {
    if (text.isEmpty()) return text
    val out = text.replaceFirstChar { it.uppercase(Locale.ROOT) }
    return if (out.last() in ".!?)") out else "$out."
}

/** Whether [a] and [b] are the same word but for case, in any alphabet. */
internal fun sameWord(a: String, b: String) = a.lowercase(Locale.ROOT) == b.lowercase(Locale.ROOT)

/** The word as looked up, plus common spelling variants of compounds. */
internal fun spellings(word: String): List<String> =
    listOf(word, word.replace('-', ' '), word.replace(' ', '-'), word.replace("-", "")).distinct()

internal fun percentEncode(s: String): String = buildString {
    for (b in s.toByteArray(Charsets.UTF_8)) {
        val c = (b.toInt() and 0xff).toChar()
        when (c) {
            in 'A'..'Z', in 'a'..'z', in '0'..'9', '-', '_', '.', '~' -> append(c)
            else -> append("%%%02X".format(c.code))
        }
    }
}
