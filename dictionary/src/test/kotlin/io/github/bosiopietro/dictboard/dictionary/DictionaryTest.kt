package io.github.bosiopietro.dictboard.dictionary

import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// The same words and expectations as WinDict's tests of its offline
// dictionary (src/offline.rs), so the two read the file alike.
class DictionaryTest {
    companion object {
        private val path: Path = Path.of(System.getProperty("dictboard.english") ?: "../data/english.wdict")
        val english: Dictionary by lazy { assertNotNull(Dictionary.open(path)) }
        val words: Words by lazy { english.words() }
    }

    private fun find(word: String) = assertNotNull(english.lookup(word), "$word not found")

    private fun words(typed: String) = words.suggest(typed, 5).map { it.word }

    @Test
    fun findsPlainWords() {
        val dog = find("dog")
        assertEquals(PartOfSpeech.NOUN, dog.meanings[0].partOfSpeech)
        assertTrue("domesticated" in dog.meanings[0].senses[0].text, "$dog")
        assertNull(dog.note)
        assertEquals(PartOfSpeech.VERB, find("run").meanings[0].partOfSpeech)
        assertEquals("ice cream", find("ice cream").word)
        assertEquals("Paris", find("paris").word)
    }

    @Test
    fun findsAntonyms() {
        fun adjective(word: String) = find(word).meanings.first { it.partOfSpeech == PartOfSpeech.ADJECTIVE }
        val good = adjective("good")
        assertTrue("bad" in good.antonyms, "$good")
        assertFalse("bad" in good.synonyms)
        // A synonym's opposites aren't the word's: "large" is "small", "big" is "little".
        assertEquals(listOf("little"), adjective("big").antonyms)
        assertTrue(find("dog").meanings.all { it.antonyms.isEmpty() })
    }

    @Test
    fun keepsWhatIsAskedFor() {
        val run = assertNotNull(english.lookup("run", Detail(senses = 1, synonyms = 0, examples = false)))
        assertTrue(run.meanings.all { it.senses.size == 1 })
        assertTrue(run.meanings.all { it.synonyms.isEmpty() })
        assertTrue(run.meanings.flatMap { it.senses }.all { it.example == null })
        assertTrue(find("run").meanings.flatMap { it.senses }.any { it.example != null })
    }

    @Test
    fun resolvesInflections() {
        assertEquals("run", find("ran").word)
        assertEquals("goose", find("geese").word)
        assertEquals("cat", find("cats").word)
        assertEquals("walk", find("walked").word)
        assertEquals(Note.BaseFormOf("walked"), find("walked").note)
    }

    @Test
    fun findsWordsTypedWithoutAccents() {
        assertEquals(find("arlêng").word, find("arleng").word)
        assertTrue('ê' in find("arleng").word)
        assertNull(find("arleng").note)
        // Offered as written, once.
        val arleng = words("arleng")
        assertEquals(1, arleng.count { it == "Arlêng" }, "$arleng")
        assertTrue(sameWord("Señor", "señor"))
        assertFalse(sameWord("senor", "señor"))
    }

    @Test
    fun saysWhatItIs() {
        assertEquals("en", english.language)
        assertEquals("Open English WordNet", english.name)
        assertEquals("Open English WordNet", find("dog").source)
        assertEquals("https://en-word.net/lemma/ice%20cream", english.link("ice cream"))
    }

    @Test
    fun refusesWhatIsNotADictionary() {
        val bytes = Files.readAllBytes(path)
        for (broken in listOf("not a dictionary, nor anything else".toByteArray(), bytes.copyOf(1000), ByteArray(0))) {
            assertNull(Dictionary.open(ByteBuffer.wrap(broken)))
        }
        // From memory as from a file.
        assertNotNull(Dictionary.open(ByteBuffer.wrap(bytes))?.lookup("dog"))
    }

    @Test
    fun handlesMissingWords() {
        assertNull(english.lookup("qwzxv"))
        assertNull(english.lookup(""))
        assertNull(english.lookup("zzzzzzzzz"))
        assertNotNull(english.lookup("a"))
    }

    @Test
    fun listsCompoundSpellings() {
        assertEquals(listOf("e-mail", "e mail", "email"), spellings("e-mail"))
        assertEquals(listOf("ice cream", "ice-cream"), spellings("ice cream"))
        assertEquals(listOf("dog"), spellings("dog"))
    }

    @Test
    fun normalizesSelections() {
        assertEquals(Normalized.Word("dog"), normalize("  Dog, "))
        assertEquals(Normalized.Word("ice cream"), normalize("\"ice\n cream\""))
        assertEquals(Normalized.Empty, normalize(" ... "))
        assertEquals(Normalized.TooLong, normalize("one two three four five"))
    }

    @Test
    fun suggestsWords() {
        // The word itself first, then the likeliest completions.
        val dog = words("dog")
        assertEquals("dog", dog[0])
        assertTrue(dog.all { it.lowercase().startsWith("dog") }, "$dog")
        assertTrue("serendipity" in words("serendip"))
        // Single words come before phrases.
        assertFalse(' ' in words("ice")[0])
        assertEquals("ice cream", words("ice cr")[0])
        // Shown as written.
        assertTrue("Paris" in words("pari"))
        assertTrue(words("qwzx").isEmpty())
        assertTrue(words("").isEmpty())
        // Inflected forms only when typed in full.
        assertEquals("goose", words.suggest("geese", 5)[0].base)
        assertEquals("walk", words.suggest("walked", 5)[0].base)
        assertTrue(words.suggest("gee", 5).all { it.base == null })
    }

    @Test
    fun suggestsDespiteTypos() {
        // Swapped, changed, missing and extra letters.
        assertEquals("receive", words("recieve")[0])
        assertEquals("separate", words("seperate")[0])
        assertEquals("definitely", words("definately")[0])
        assertTrue("accommodate" in words("acomodate"))
        assertTrue("embarrass" in words("embarass"))
        // While typing, too.
        assertTrue("necessary" in words("necces"))
        // What's typed correctly still comes first.
        assertTrue(words("hous").all { it.lowercase().startsWith("hous") })
        // A swap is the likeliest typo.
        assertEquals("receive", words("recie")[0])
        // Short words are taken as typed.
        assertTrue(words("qwz").isEmpty())
        assertTrue(words("xqzvw").isEmpty())
    }

    @Test
    fun countsTypos() {
        fun count(typed: String, key: String, whole: Boolean = false): Int? {
            val chars = typed.codePoints().toArray()
            return typos(chars, key, 2 * TYPO, whole, IntArray(3 * (chars.size + 1)))
        }
        assertEquals(0, count("house", "house"))
        assertEquals(0, count("hous", "household"))
        assertEquals(SWAP, count("hosue", "house"))
        assertEquals(TYPO, count("huse", "house"))
        assertEquals(TYPO, count("hoouse", "house"))
        assertEquals(TYPO, count("hause", "house"))
        assertEquals(2 * TYPO, count("haase", "house"))
        assertNull(count("hhaase", "house"))
        assertEquals(TYPO, count("accomodate", "accommodate"))
        assertEquals(2 * TYPO, count("acomodate", "accommodate"))
        assertNull(count("zzzzz", "house"))
        // All of the key, when whole.
        assertNull(count("hous", "household", whole = true))
        assertEquals(TYPO, count("hous", "house", whole = true))
        assertEquals(SWAP, count("emobdy", "embody", whole = true))
        assertNull(count("emobdy", "embodying", whole = true))
    }

    @Test
    fun correctsTypos() {
        assertEquals("embody", words.correct("emobdy"))
        assertEquals("embody", words.correct("embnody"))
        assertEquals("receive", words.correct("recieve"))
        assertEquals("separate", words.correct("seperate"))
        assertEquals("definitely", words.correct("definately"))
        assertEquals("house", words.correct("hosue"))
        // Short words are taken as typed.
        assertNull(words.correct("qwz"))
        assertNull(words.correct("xqzvwkj"))
    }

    @Test
    fun suggestsQuickly() {
        val start = System.nanoTime()
        val all = words
        println("load: ${(System.nanoTime() - start) / 1_000_000} ms, ${all.size} words")
        val typed = listOf("co", "re", "st", "pr", "ma", "recie", "seperat", "definately")
        val each = System.nanoTime()
        for (t in typed) assertTrue(all.suggest(t, 5).isNotEmpty(), t)
        println("suggest: ${(System.nanoTime() - each) / 1_000_000 / typed.size} ms per query")
    }

    @Test
    fun formatsSenses() {
        assertEquals("A canine.", sentence("a canine"))
        assertEquals("Done!", sentence("done!"))
    }
}
