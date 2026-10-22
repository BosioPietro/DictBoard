package io.github.bosiopietro.dictboard

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.InputFilter
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextWatcher
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.text.style.StyleSpan
import android.util.TypedValue
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import io.github.bosiopietro.dictboard.dictionary.Definition
import io.github.bosiopietro.dictboard.dictionary.Dictionary
import io.github.bosiopietro.dictboard.dictionary.MAX_LOOKUP_CHARS
import io.github.bosiopietro.dictboard.dictionary.Normalized
import io.github.bosiopietro.dictboard.dictionary.Note
import io.github.bosiopietro.dictboard.dictionary.Words
import io.github.bosiopietro.dictboard.dictionary.normalize

/**
 * The definition card, WinDict's on Android: the word selected in any app
 * ("Define" in its text menu), shared to it, or typed in its box, with
 * suggestions and typo correction as it's typed. Synonyms and opposites lead
 * to their own definitions; Back goes back through them.
 */
class DefineActivity : Activity() {
    private lateinit var dictionary: Dictionary
    private var words: Words? = null

    private lateinit var search: EditText
    private lateinit var body: LinearLayout
    private lateinit var scroll: ScrollView

    /** The words shown before this one, for Back. */
    private val history = ArrayDeque<String>()
    private var shown: String? = null

    /** Set while the box is filled in by the card, not typed in. */
    private var filling = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        dictionary = Dictionaries.english(this)
        setContentView(layout())
        window.setLayout((resources.displayMetrics.widthPixels * 0.92).toInt(), WRAP_CONTENT)

        val text = savedInstanceState?.getString(SHOWN) ?: incomingText(intent)
        if (text != null) {
            show(text)
        } else {
            search.requestFocus()
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        }
        Dictionaries.words(this) { all ->
            words = all
            // What was typed before they were listed, or a word not found,
            // can now be suggested for.
            if (search.hasFocus() && search.text.isNotBlank()) suggest(search.text.toString())
            shown?.let { if (dictionary.lookup(it) == null) show(it) }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        shown?.let { outState.putString(SHOWN, it) }
    }

    @Deprecated("Back walks back through the words shown first.")
    override fun onBackPressed() {
        val previous = history.removeLastOrNull() ?: return super.onBackPressed()
        show(previous)
    }

    private fun incomingText(intent: Intent): String? = when (intent.action) {
        Intent.ACTION_PROCESS_TEXT -> intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
        Intent.ACTION_SEND -> intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
        else -> null
    }

    /** Shows [word], keeping what was shown for Back. */
    private fun navigate(word: String) {
        shown?.let { history.addLast(it) }
        show(word)
    }

    private fun show(raw: String) {
        body.removeAllViews()
        scroll.scrollTo(0, 0)
        when (val normalized = normalize(raw)) {
            Normalized.Empty -> message(getString(R.string.nothing_to_define))
            Normalized.TooLong -> message(getString(R.string.too_long))
            is Normalized.Word -> {
                val word = normalized.text
                shown = word
                fill(word)
                val definition = dictionary.lookup(word)
                if (definition != null) {
                    card(definition)
                } else {
                    notFound(word)
                }
            }
        }
    }

    private fun fill(text: String) {
        filling = true
        search.setText(text)
        search.setSelection(search.text.length)
        // Focus moves to the card, and the keyboard goes with it.
        search.clearFocus()
        getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(search.windowToken, 0)
        filling = false
    }

    private fun card(definition: Definition) {
        body.addView(text(definition.word, size = 24f, style = Typeface.BOLD))
        definition.note?.let { note ->
            val line = when (note) {
                is Note.BaseFormOf -> getString(R.string.base_form_of, note.word)
                is Note.AlsoFormOf -> getString(R.string.also_form_of, note.word)
            }
            body.addView(text(line, secondary = true, style = Typeface.ITALIC))
        }
        for (meaning in definition.meanings) {
            body.addView(text(meaning.partOfSpeech.label, style = Typeface.BOLD_ITALIC, top = 12))
            meaning.senses.forEachIndexed { i, sense ->
                body.addView(text("${i + 1}. ${sense.text}", top = 4))
                sense.example?.let { body.addView(text("“$it”", secondary = true, style = Typeface.ITALIC, start = 16)) }
            }
            if (meaning.synonyms.isNotEmpty()) body.addView(links(getString(R.string.synonyms), meaning.synonyms))
            if (meaning.antonyms.isNotEmpty()) body.addView(links(getString(R.string.antonyms), meaning.antonyms))
        }
        val source = getString(R.string.source, definition.source)
        val link = dictionary.link(definition.word)
        body.addView(
            if (link == null) {
                text(source, size = 12f, secondary = true, top = 16)
            } else {
                linked(source, size = 12f, top = 16) { open(link) }
            },
        )
    }

    private fun notFound(word: String) {
        body.addView(text(getString(R.string.not_found, word)))
        val meant = words?.correct(word) ?: return
        body.addView(linked(getString(R.string.did_you_mean, meant), top = 8) { navigate(meant) })
    }

    private fun message(line: String) {
        shown = null
        body.addView(text(line))
    }

    /** Suggestions for what's being typed, in place of the card. */
    private fun suggest(typed: String) {
        body.removeAllViews()
        val word = (normalize(typed) as? Normalized.Word)?.text ?: return
        for (s in words?.suggest(word, SUGGESTIONS).orEmpty()) {
            val target = s.base ?: s.word
            val label = if (s.base != null) getString(R.string.form_of, s.word, s.base) else s.word
            body.addView(linked(label, top = 6) { navigate(target) })
        }
    }

    private fun layout(): View {
        val pad = dp(20)
        search = EditText(this).apply {
            hint = getString(R.string.search_hint)
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            filters = arrayOf(InputFilter.LengthFilter(MAX_LOOKUP_CHARS))
            setOnEditorActionListener { v, actionId, event ->
                val enter = event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN
                if (actionId == EditorInfo.IME_ACTION_SEARCH || enter) {
                    navigate(v.text.toString())
                    true
                } else {
                    false
                }
            }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    if (!filling) suggest(s.toString())
                }
            })
        }
        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll = ScrollView(this).apply {
            addView(body, MATCH_PARENT, WRAP_CONTENT)
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            // Takes the focus the box gives up once a word is shown.
            isFocusableInTouchMode = true
            setPadding(pad, pad, pad, pad)
            addView(search, MATCH_PARENT, WRAP_CONTENT)
            addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(8) })
        }
    }

    private fun text(
        value: CharSequence,
        size: Float = 16f,
        style: Int = Typeface.NORMAL,
        secondary: Boolean = false,
        top: Int = 0,
        start: Int = 0,
    ) = TextView(this).apply {
        text = value
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        setTypeface(typeface, style)
        if (secondary) textColorSecondary?.let { setTextColor(it) }
        setPaddingRelative(dp(start), dp(top), 0, 0)
        setTextIsSelectable(false)
    }

    /** "Synonyms: a, b, c", each leading to its definition. */
    private fun links(label: String, words: List<String>): TextView {
        val line = SpannableStringBuilder()
        line.append("$label: ", StyleSpan(Typeface.BOLD), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        words.forEachIndexed { i, word ->
            if (i > 0) line.append(", ")
            line.append(word, onClick { navigate(word) }, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return text(line, top = 4).apply { movementMethod = LinkMovementMethod.getInstance() }
    }

    private fun linked(value: String, size: Float = 16f, top: Int = 0, action: () -> Unit): TextView {
        val line = SpannableStringBuilder().append(value, onClick(action), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return text(line, size = size, top = top).apply { movementMethod = LinkMovementMethod.getInstance() }
    }

    private fun onClick(action: () -> Unit) = object : ClickableSpan() {
        override fun onClick(widget: View) = action()
    }

    private fun open(link: String) {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link))) }
    }

    private val textColorSecondary by lazy {
        val attributes = obtainStyledAttributes(intArrayOf(android.R.attr.textColorSecondary))
        try {
            attributes.getColorStateList(0)
        } finally {
            attributes.recycle()
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val SHOWN = "shown"
        const val SUGGESTIONS = 6
    }
}
