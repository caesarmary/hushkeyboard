package com.hushkeyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupWindow
import androidx.core.content.ContextCompat
import android.widget.TextView
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class HushKeyboardService : InputMethodService() {

    // @Volatile ensures the reference written by the background init thread is visible on the
    // main thread before the first keystroke arrives (happens-before guarantee on JVM).
    @Volatile private var autocorrect: Autocorrect? = null

    // ---- Phase 4 learned-words dictionary (Session 36) ----
    // Created + loaded off the main thread in onCreate; stays null until ready, so the keyboard
    // works before it loads. On the hardened build the store self-disables (learn/completions are
    // no-ops) and erases any pre-existing file on load. @Volatile for cross-thread visibility.
    @Volatile private var learnedWordsStore: LearnedWordsStore? = null
    // Disk writes for learned words (encrypt + write) run here, never the IME thread. Single-thread
    // so writes serialize; separate from predictionExecutor so a learn never waits behind inference.
    private val learnedWordsExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    // The raw (lowercase) word the CENTER slot will commit when tapped in State B: either the
    // autocorrect correction or, when there is none, a learned-word prefix completion. null = the
    // center slot holds nothing committable. Recomputed on every updateSuggestionStrip.
    private var currentCenterCandidate: String? = null

    private var suggestionStripView: LinearLayout? = null
    private var suggestionLeftView: TextView? = null
    private var suggestionCenterView: TextView? = null
    private var suggestionRightView: TextView? = null

    // ---- Phase 4 next-word prediction ----
    // Loaded off the main thread in onCreate; stays null (feature simply off) if loading fails,
    // so the keyboard always works even without the model. @Volatile for cross-thread visibility.
    @Volatile private var llamaPredictor: LlamaPredictor? = null
    // Inference runs on this single background thread — never the UI thread (cold ~1.2 s, warm
    // ~0.3 s on a mid-range device). A single thread serializes calls so we never run two at once.
    private val predictionExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    // Main-looper handler used to debounce triggers and to apply results back on the UI thread.
    private val predictionHandler = Handler(Looper.getMainLooper())
    private var predictionRunnable: Runnable? = null
    // Monotonic request id: a result is only applied if its generation is still the latest, so a
    // slow inference for stale context can never overwrite a newer prediction.
    private var predictionGeneration = 0
    // Phase 5 Session 71 (slice 1b): monotonic id for the async context-aware autocorrect refine.
    // Bumped on every word commit and field change so a slow refine for a superseded word is dropped
    // on post-back (same staleness guard as predictionGeneration). NOTE: there is no
    // onUpdateSelection override, so a bare cursor move does NOT bump this — the load-bearing guard
    // against a stale swap is the literal on-screen text re-verification in applyAutocorrectRefine
    // (it refuses to delete anything that isn't exactly the fast-committed correction). The fast ed-1
    // commit is unchanged; the refine only ever upgrades a fast-committed correction to a better one.
    private var autocorrectGeneration = 0
    // The context we last kicked off a prediction for, so identical repeat triggers are skipped.
    private var lastPredictedContext: String? = null
    // The word currently shown in the right slot (null = nothing shown), used by the tap handler.
    private var currentRightWord: String? = null
    // SESSION 31: the top-N next words from the last word-boundary inference. They fill the whole
    // strip at a boundary / on focus (State A); while the user types a word (State B) only
    // boundaryWords[0] is shown, in the right slot, with no new inference. Survives across
    // keystrokes within a field; cleared on field change.
    private var currentBoundaryWords: List<String> = emptyList()
    // Run one throwaway inference the first time an eligible field is focused, so the first REAL
    // prediction is warm (the model's first call is slow). Per process; the seed is a constant, never
    // field text. @Volatile: set on the UI thread, read by the executor.
    @Volatile private var predictionWarmedUp = false

    // Read from SharedPreferences on each onStartInput so changes take effect without
    // restarting the app. Default true so the keyboard works correctly the very first time,
    // before the user has ever opened Settings.
    private var suggestionsEnabled = true
    private var autocorrectEnabled = true
    private var autocapsEnabled = true
    // Whether the numpad's ↵ and ⌫ keys are mirrored to match the QWERTY layout
    // (⌫ left, ↵ right) instead of the default (↵ left, ⌫ right). Configurable in
    // Settings; default false (current layout unchanged).
    private var invertNumpadEnterBackspace = false
    // How long the user must hold a key before its long-press action fires (alternates/
    // punctuation popups, backspace acceleration, spacebar-Settings shortcut). Configurable
    // in Settings; default matches the value this app shipped with through Session 37.
    private var longPressDelayMs = 400L

    // How fast two Shift taps must occur to trigger caps lock. Configurable in Settings;
    // default matches the value this app shipped with through Session 38.
    private var doubleTapDelayMs = 300L

    // How fast each word repeats while backspace is held (word-phase repeat rate).
    // Configurable in Settings; default matches the value this app shipped with through
    // Session 39.
    private var backspaceRepeatMs = 300L

    private val inputState = InputStateManager()
    private val shiftState = ShiftStateManager()
    private val symbolState = SymbolStateManager()

    private var lastShiftTapTime = 0L

    // Used to re-apply leading capital / all-caps when autocorrect replaces the typed word.
    private var wordStartedWithCapital = false
    private var wordIsAllCaps = false
    private var isAtWordStart = true

    // Tracks the last applied autocorrect so a single backspace can revert it (Gboard/iOS
    // behaviour). Cleared by any non-backspace user action — only the immediate next
    // backspace after an autocorrect can revert.
    private data class PendingRevert(
        val typedText: String,
        val correctedText: String,
        val trailer: String = ""
    )
    private var pendingRevert: PendingRevert? = null

    // Phase 5 slice 2: a pending homophone offer for the just-committed real word (e.g. user wrote
    // "there", context suggests "their"). Produced by the async rescore, rendered in the strip until
    // the user taps it, keeps the word, types on, or moves field. [committed] and [offered] are the
    // on-screen forms WITH casing already applied. Never a silent swap — the user opts in by tapping.
    private data class HomophoneOffer(val committed: String, val offered: String)
    private var pendingHomophoneOffer: HomophoneOffer? = null

    // True for exactly one action after the keyboard inserts a trailing space (predicted-word
    // accept or autocorrect-on-space). A following attaching-mark tap consumes it via
    // SmartPunctuation; any other input clears it. Never set in a password field. Session 43.
    private var pendingAutoSpace = false

    private companion object {
        // Char-phase: starts slow, accelerates by ACCEL_STEP each char, floors at MIN.
        const val LONG_PRESS_CHAR_INTERVAL_START_MS = 80L
        const val LONG_PRESS_CHAR_INTERVAL_MIN_MS = 30L
        const val LONG_PRESS_CHAR_ACCEL_STEP_MS = 10L
        // ---- Phase 4 next-word prediction ----
        // The llama.cpp backend's bundled GGUF. noCompress'd in the APK so it can be
        // copied out byte-for-byte before llama.cpp mmaps it (see LlamaPredictorDeviceTest).
        const val GGUF_ASSET = "smollm2_135m_instruct_q8_0.gguf"
        // Characters of text-before-cursor to read for context. The engine trims to 64 tokens;
        // 256 chars comfortably covers that for normal text.
        const val PRED_CONTEXT_CHARS = 256
        // Wait this long after the last input change before running an inference, so typing
        // quickly through word boundaries does not fire one inference per boundary.
        const val PRED_DEBOUNCE_MS = 150L
        // Number of suggestion slots to fill at a word boundary / on focus.
        const val SLOT_COUNT = 3
        // Constant seed for the one-time engine warmup. NOT field text -- just exercises the
        // interpreter so the first real inference is fast. Must be >= 2 tokens.
        const val WARMUP_SEED = "I am"
        // onDestroy waits at most this long for an in-flight predictionExecutor task to finish before
        // nativeFree (UAF barrier). A single warm score is well under this; bounded so onDestroy
        // cannot hang.
        const val EXECUTOR_SHUTDOWN_WAIT_MS = 2000L
    }

    private var keyPreviewEnabled = true
    private var keyPreviewPopup: PopupWindow? = null
    private var keyboardView: View? = null

    // Dedicated handler so removeCallbacksAndMessages(null) cannot cancel unrelated posted work.
    private val longPressHandler = Handler(Looper.getMainLooper())

    // True once the repeat runnable has fired at least once, so ACTION_UP can distinguish
    // a short tap (no fire → perform a single-char delete) from a completed long-press.
    private var longPressDidFire = false

    // Dedicated handler for the alternates popup timer — separate from longPressHandler so
    // backspace cancellation cannot interfere with a simultaneous alternates long-press.
    private val alternatesHandler = Handler(Looper.getMainLooper())
    private var alternatesRunnable: Runnable? = null
    private var alternatesPopupActive = false

    // State for the spacebar long-press -> Settings shortcut.
    private val spaceLongPressHandler = Handler(Looper.getMainLooper())
    private var spaceLongPressRunnable: Runnable? = null
    private var spaceLongPressFired = false
    private var alternatesPopup: PopupWindow? = null
    private var alternatesContainer: LinearLayout? = null
    private var currentAlternates: List<String>? = null
    private var highlightedAlternateIndex = -1
    // Pixels the popup was shifted left from the key's left edge to stay on screen.
    // Used by updateAlternateHighlight to map touch-X (key-relative) to chip index.
    private var alternatesPopupXOffset = 0
    // The chip index sitting directly above the held key (0 when the popup isn't shifted).
    // The connecting tab is drawn under this chip and mirrors its colour.
    private var alternatesAnchorChipIndex = 0
    // Reference to the active callout drawable so updateAlternateHighlight can sync the tab color.
    private var alternatesCalloutDrawable: AlternatesCalloutDrawable? = null

    // State for numpad digit long-press popup. The runnable fires after longPressDelayMs
    // to show the symbol preview; activeSymbol is set at the same time so ACTION_UP knows to
    // insert the symbol (rather than the digit) and dismiss the popup.
    private var numpadLongPressRunnable: Runnable? = null
    private var numpadLongPressActiveSymbol: String? = null

    // Phase tracking for progressive backspace. Reset to true on every ACTION_DOWN.
    // Char phase: delete one char at a time, accelerating, until the word buffer empties.
    // Word phase: delete one word at a time at a fixed interval.
    private var inCharPhase = true
    private var charDeleteCount = 0

    private val backspaceRepeatRunnable = object : Runnable {
        override fun run() {
            longPressDidFire = true
            // Long-press always deletes; it never triggers the undo-autocorrect revert.
            pendingRevert = null
            pendingAutoSpace = false

            if (inCharPhase && !inputState.isWordEmpty) {
                // Char phase: delete one character, then schedule the next fire with an
                // accelerating interval (capped at LONG_PRESS_CHAR_INTERVAL_MIN_MS).
                charDeleteCount++
                inputState.onBackspace(isPasswordField())
                currentInputConnection?.deleteSurroundingText(1, 0)
                if (inputState.isWordEmpty) {
                    isAtWordStart = true
                    wordStartedWithCapital = false
                    wordIsAllCaps = false
                }
                updateSuggestionStrip()
                if (!inputState.isWordEmpty) {
                    val nextInterval = maxOf(
                        LONG_PRESS_CHAR_INTERVAL_MIN_MS,
                        LONG_PRESS_CHAR_INTERVAL_START_MS - charDeleteCount * LONG_PRESS_CHAR_ACCEL_STEP_MS
                    )
                    longPressHandler.postDelayed(this, nextInterval)
                    return
                }
                // Word buffer just emptied — fall through immediately into word phase.
            }

            // Word phase: delete one full word (or fall back to single char in password fields).
            inCharPhase = false
            deleteWordBeforeCursor()
            longPressHandler.postDelayed(this, backspaceRepeatMs)
        }
    }

    override fun onCreate() {
        super.onCreate()
        thread(isDaemon = true) {
            autocorrect = Autocorrect(applicationContext)
        }
        // Load the learned-words store off the main thread (it opens the Keystore, decrypts, and
        // reads a file). Created unconditionally: on the hardened build the store is disabled and
        // its load() erases any dictionary a prior standard build may have written.
        thread(isDaemon = true) {
            learnedWordsStore = LearnedWordsStore.getInstance(applicationContext)
        }
        // Load the prediction model off the main thread. If anything fails (missing asset, OOM),
        // prediction simply stays off — the keyboard remains fully functional.
        thread(isDaemon = true) {
            try {
                val modelFile = File(filesDir, GGUF_ASSET)
                if (!modelFile.exists()) {
                    assets.open(GGUF_ASSET).use { input ->
                        modelFile.outputStream().use { output -> input.copyTo(output) }
                    }
                }
                llamaPredictor = LlamaPredictor(modelFile.absolutePath, applicationInfo.nativeLibraryDir)
            } catch (_: Throwable) {
                // Prediction unavailable; the right suggestion slot just stays empty.
            }
        }
    }

    override fun onDestroy() {
        predictionHandler.removeCallbacksAndMessages(null)
        predictionExecutor.shutdownNow()
        learnedWordsExecutor.shutdownNow()
        // Barrier before nativeFree (UAF guard): predictionExecutor is the sole owner thread for the
        // native handle (prefill/decode/score). Wait for any in-flight task to finish so close() ->
        // nativeFree can never race a native call. Bounded wait so onDestroy never hangs; on the rare
        // timeout we still proceed (process is going away regardless).
        try {
            predictionExecutor.awaitTermination(EXECUTOR_SHUTDOWN_WAIT_MS, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        llamaPredictor?.close()
        llamaPredictor = null
        super.onDestroy()
    }

    override fun onStartInput(attribute: EditorInfo, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        // Re-read the preference on every field focus so changes in Settings take effect
        // immediately — the user does not need to restart the keyboard.
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        suggestionsEnabled = prefs.getBoolean(PREFS_KEY_SUGGESTIONS, true)
        autocorrectEnabled = prefs.getBoolean(PREFS_KEY_AUTOCORRECT, true)
        autocapsEnabled = prefs.getBoolean(PREFS_KEY_AUTOCAPS, true)
        longPressDelayMs = prefs.getInt(PREFS_KEY_LONG_PRESS_DELAY, 400).toLong()
        doubleTapDelayMs = prefs.getInt(PREFS_KEY_DOUBLE_TAP_DELAY, 300).toLong()
        backspaceRepeatMs = prefs.getInt(PREFS_KEY_BACKSPACE_REPEAT_MS, 300).toLong()
        invertNumpadEnterBackspace = prefs.getBoolean(PREFS_KEY_INVERT_NUMPAD_ENTER_BACKSPACE, false)
        // The numpad view is created once in onCreateInputView and reused across field
        // switches, so re-apply the enter/backspace role assignment here in case the
        // Settings toggle changed since the view was created.
        keyboardView?.let { setupNumpadKeys(it) }
        inputState.onFieldChange()
        shiftState.onFieldChange()
        symbolState.onFieldChange()
        // A field change supersedes any in-flight autocorrect refine from the previous field.
        autocorrectGeneration++
        wordStartedWithCapital = false
        wordIsAllCaps = false
        isAtWordStart = true
        pendingRevert = null
        pendingHomophoneOffer = null   // a field change must never carry an offer into the new field
        pendingAutoSpace = false
        currentBoundaryWords = emptyList()
        applyAutoCaps()
        updateSuggestionStrip()
    }

    override fun onFinishInput() {
        super.onFinishInput()
        inputState.onFieldChange()
        shiftState.onFieldChange()
        symbolState.onFieldChange()
        autocorrectGeneration++
        wordStartedWithCapital = false
        wordIsAllCaps = false
        isAtWordStart = true
        pendingRevert = null
        pendingHomophoneOffer = null
        pendingAutoSpace = false
        updateSuggestionStrip()
    }

    // Inflating with null root is correct: the IME framework attaches the returned view itself.
    @SuppressLint("InflateParams")
    override fun onCreateInputView(): View {
        val view = layoutInflater.inflate(R.layout.keyboard_view, null)
        setupKeys(view)
        setupNumpadKeys(view)
        updateShiftVisuals(view)
        updateNumpadLayer(view)
        if (!isNumericInputField()) updateSymbolLayer(view)
        suggestionStripView = view.findViewById(R.id.suggestion_strip)
        val left = view.findViewById<TextView>(R.id.suggestion_left)
        suggestionLeftView = left
        left?.setOnClickListener { acceptLeftSlot() }
        val center = view.findViewById<TextView>(R.id.suggestion_center)
        suggestionCenterView = center
        center?.setOnClickListener { acceptSuggestion() }
        val right = view.findViewById<TextView>(R.id.suggestion_right)
        suggestionRightView = right
        right?.setOnClickListener { acceptNextWordPrediction() }
        keyboardView = view
        updateSuggestionStrip()
        return view
    }

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        keyboardView?.let {
            updateNumpadLayer(it)
            // updateNumpadLayer shows the numpad and hides everything else when numeric,
            // so only call updateSymbolLayer when QWERTY is active.
            if (!isNumericInputField()) updateSymbolLayer(it)
        }
        warmUpPredictionEngine()
        updateSuggestionStrip()
    }

    // Live read of EditorInfo every keystroke — never cached — so stale state from a previous
    // field cannot leak. Null EditorInfo is treated as sensitive (fail-safe).
    private fun isSensitiveField(): Boolean {
        val info = currentInputEditorInfo ?: return true
        return SensitiveFieldChecker.isSensitive(info.inputType)
    }

    // Narrower than isSensitiveField: returns true only for actual password variations.
    // Used exclusively by deleteWordBeforeCursor to guard getTextBeforeCursor.
    // TYPE_TEXT_FLAG_NO_SUGGESTIONS fields (e.g. Google Search) are intentionally excluded —
    // reading a search query carries no privacy risk.
    private fun isPasswordField(): Boolean {
        val info = currentInputEditorInfo ?: return true
        return SensitiveFieldChecker.isPasswordField(info.inputType)
    }

    // Returns true for numeric field classes (NUMBER, PHONE, DATETIME) that are NOT password
    // variations. Those fields show the numpad instead of QWERTY. Never cached — always reads
    // currentInputEditorInfo live so switching between fields is detected cleanly.
    // Null EditorInfo returns false (fail-safe: default to QWERTY, not numpad).
    private fun isNumericInputField(): Boolean {
        val info = currentInputEditorInfo ?: return false
        return NumpadFieldChecker.isNumericField(info.inputType)
    }

    // Auto-capitalization. Asks the framework — via getCursorCapsMode — whether the next letter
    // should be capitalized given the field's CAP_* flags and the text already around the cursor,
    // then arms shift accordingly. getCursorCapsMode returns only an int bitmask; it reads no text
    // into this process and never touches the network. Skipped in password fields as defense in
    // depth (those fields don't declare caps flags anyway). The state owner (ShiftStateManager)
    // makes the actual decision and will not override a shift the user set by hand.
    private fun applyAutoCaps() {
        if (!autocapsEnabled) return
        if (isPasswordField()) return
        val ic = currentInputConnection ?: return
        val info = currentInputEditorInfo ?: return
        shiftState.applyAutoCaps(ic.getCursorCapsMode(info.inputType))
        keyboardView?.let { updateShiftVisuals(it) }
    }

    // Backspace uses OnTouchListener for long-press detection; accessibility is preserved by
    // calling v.performClick() on a short tap, which fires the OnClickListener and the standard
    // accessibility event.
    @SuppressLint("ClickableViewAccessibility")
    private fun setupKeys(view: View) {

        val letterKeys = mapOf(
            R.id.key_q to "q", R.id.key_w to "w", R.id.key_e to "e",
            R.id.key_r to "r", R.id.key_t to "t", R.id.key_y to "y",
            R.id.key_u to "u", R.id.key_i to "i", R.id.key_o to "o",
            R.id.key_p to "p", R.id.key_a to "a", R.id.key_s to "s",
            R.id.key_d to "d", R.id.key_f to "f", R.id.key_g to "g",
            R.id.key_h to "h", R.id.key_j to "j", R.id.key_k to "k",
            R.id.key_l to "l", R.id.key_z to "z", R.id.key_x to "x",
            R.id.key_c to "c", R.id.key_v to "v", R.id.key_b to "b",
            R.id.key_n to "n", R.id.key_m to "m"
        )

        for ((id, character) in letterKeys) {
            val button = view.findViewById<Button>(id)
            button?.setOnClickListener {
                // Typing a letter means the user has moved past the previous autocorrect.
                pendingRevert = null
                pendingHomophoneOffer = null
                pendingAutoSpace = false
                val uppercase = shiftState.onLetterTyped()
                if (isAtWordStart && uppercase) {
                    wordStartedWithCapital = true
                    wordIsAllCaps = (shiftState.state == ShiftState.CAPS_LOCK)
                }
                isAtWordStart = false
                // If the user backspaced into a previously-committed word, pull that word
                // into the buffer before appending the new letter so autocorrect operates
                // on the full on-screen word, not just the new keystrokes.
                syncBufferFromScreenIfNeeded()
                // Buffer always holds lowercase so autocorrect lookups are case-insensitive.
                // Password fields suppress the buffer; TYPE_TEXT_FLAG_NO_SUGGESTIONS fields do
                // not — the strip still shows a candidate, space just won't auto-apply it.
                inputState.onLetterTyped(character[0], isPasswordField())
                val output = if (uppercase) character.uppercase() else character
                currentInputConnection?.commitText(output, 1)
                updateShiftVisuals(view)
                updateSuggestionStrip()
            }

            if (AlternateCharMap.hasAlternates(character[0])) {
                // Keys with alternates: long-press shows the popup; short tap falls through to
                // the click listener above.
                button?.setOnTouchListener { v, event ->
                    when (event.action) {
                        MotionEvent.ACTION_DOWN -> {
                            closeOrphanedAlternatesPopup()
                            showKeyPreview(v as Button)
                            // Suppress the alternates popup only in actual password fields.
                            // The popup writes a chosen character and reads nothing from the field,
                            // so NO_SUGGESTIONS fields (e.g. search bars) carry no privacy risk and
                            // are allowed — matching the punctuation popup's gate.
                            if (!isPasswordField()) {
                                // Capture shift state now so the popup shows the right case even
                                // if ONE_SHOT is consumed between press and popup appearance.
                                val isUppercaseAtPress = shiftState.isUppercase
                                val runnable = Runnable {
                                    dismissKeyPreview()
                                    val alts = AlternateCharMap.getAlternates(character[0])
                                        ?: return@Runnable
                                    showAlternatesPopup(v as Button, character, alts, isUppercaseAtPress)
                                    alternatesPopupActive = true
                                }
                                alternatesRunnable = runnable
                                alternatesHandler.postDelayed(runnable, longPressDelayMs)
                            }
                            false
                        }
                        MotionEvent.ACTION_MOVE -> {
                            if (alternatesPopupActive) updateAlternateHighlight(event.x)
                            false
                        }
                        MotionEvent.ACTION_UP -> {
                            if (alternatesPopupActive) {
                                // Clear the pressed visual state: returning true here means Android
                                // won't process the event itself, so isPressed would stay stuck.
                                v.isPressed = false
                                commitAlternate(view)
                                dismissAlternatesPopup()
                                alternatesPopupActive = false
                                alternatesRunnable = null
                                true  // consume — prevents click listener from also typing the base char
                            } else {
                                alternatesRunnable?.let { alternatesHandler.removeCallbacks(it) }
                                alternatesRunnable = null
                                dismissKeyPreview()
                                false  // let click listener fire for a normal short tap
                            }
                        }
                        MotionEvent.ACTION_CANCEL -> {
                            v.isPressed = false
                            alternatesRunnable?.let { alternatesHandler.removeCallbacks(it) }
                            alternatesRunnable = null
                            dismissKeyPreview()
                            if (alternatesPopupActive) {
                                dismissAlternatesPopup()
                                alternatesPopupActive = false
                            }
                            false
                        }
                        else -> false
                    }
                }
            } else {
                // Keys without alternates: show/dismiss key preview only; click listener handles the tap.
                button?.setOnTouchListener { v, event ->
                    when (event.action) {
                        MotionEvent.ACTION_DOWN -> {
                            closeOrphanedAlternatesPopup()
                            showKeyPreview(v as Button)
                        }
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> dismissKeyPreview()
                    }
                    false
                }
            }
        }

        val spaceIds = listOf(R.id.key_space, R.id.key_space_s1, R.id.key_space_s2)
        for (id in spaceIds) {
            val button = view.findViewById<Button>(id) ?: continue
            button.setOnClickListener {
                commitWordWithCorrection()
                currentInputConnection?.commitText(" ", 1)
                pendingRevert = pendingRevert?.copy(trailer = " ")
                // pendingRevert is non-null only when this space triggered an autocorrect; that is
                // the space smart punctuation may later eat (Session 43). A plain space after an
                // unchanged word is the user's own and is left alone.
                pendingAutoSpace = pendingRevert != null
                applyAutoCaps()
            }
            attachSpaceLongPress(button)
        }

        val enterIds = listOf(R.id.key_enter, R.id.key_enter_s1, R.id.key_enter_s2)
        for (id in enterIds) {
            view.findViewById<Button>(id)?.setOnClickListener {
                commitWordWithCorrection()
                currentInputConnection?.commitText("\n", 1)
                pendingRevert = pendingRevert?.copy(trailer = "\n")
                pendingAutoSpace = false
                applyAutoCaps()
            }
        }

        val backspaceIds = listOf(R.id.key_backspace, R.id.key_backspace_s1, R.id.key_backspace_s2)
        for (id in backspaceIds) {
            val button = view.findViewById<Button>(id) ?: continue
            button.setOnClickListener {
                performSingleBackspace()
            }
            button.setOnTouchListener { v, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        longPressDidFire = false
                        inCharPhase = true
                        charDeleteCount = 0
                        longPressHandler.postDelayed(backspaceRepeatRunnable, longPressDelayMs)
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        longPressHandler.removeCallbacksAndMessages(null)
                        if (!longPressDidFire) v.performClick()
                        longPressDidFire = false
                        true
                    }
                    else -> false
                }
            }
        }

        val shiftButton = view.findViewById<Button>(R.id.key_shift)
        shiftButton?.setOnClickListener {
            val now = SystemClock.uptimeMillis()
            val isDoubleTap = (now - lastShiftTapTime) < doubleTapDelayMs
            lastShiftTapTime = now
            shiftState.onShiftActivate(capsLock = isDoubleTap)
            updateShiftVisuals(view)
        }
        shiftButton?.setOnLongClickListener {
            shiftState.onShiftActivate(capsLock = true)
            // Reset so the following click-up cannot register as a double-tap.
            lastShiftTapTime = 0L
            updateShiftVisuals(view)
            true
        }

        view.findViewById<Button>(R.id.key_sym)?.setOnClickListener {
            symbolState.onSymbolsKeyTapped()
            updateSymbolLayer(view)
        }
        view.findViewById<Button>(R.id.key_abc_s1)?.setOnClickListener {
            symbolState.onLettersKeyTapped()
            updateSymbolLayer(view)
        }
        view.findViewById<Button>(R.id.key_abc_s2)?.setOnClickListener {
            symbolState.onLettersKeyTapped()
            updateSymbolLayer(view)
        }
        view.findViewById<Button>(R.id.key_page2)?.setOnClickListener {
            symbolState.onPageTwoTapped()
            updateSymbolLayer(view)
        }
        view.findViewById<Button>(R.id.key_page1)?.setOnClickListener {
            symbolState.onPageOneTapped()
            updateSymbolLayer(view)
        }

        val symbolKeys = mapOf(
            // Numbers (page 1 row 1)
            R.id.key_1 to "1", R.id.key_2 to "2", R.id.key_3 to "3",
            R.id.key_4 to "4", R.id.key_5 to "5", R.id.key_6 to "6",
            R.id.key_7 to "7", R.id.key_8 to "8", R.id.key_9 to "9",
            R.id.key_0 to "0",
            // Page 1 row 2
            R.id.key_excl    to "!", R.id.key_quest   to "?",
            R.id.key_at      to "@", R.id.key_hash     to "#",
            R.id.key_dollar  to "$", R.id.key_percent  to "%",
            R.id.key_amp     to "&", R.id.key_star     to "*",
            R.id.key_lpar    to "(", R.id.key_rpar     to ")",
            // Page 1 row 3
            R.id.key_minus      to "-", R.id.key_plus      to "+",
            R.id.key_eq         to "=", R.id.key_underscore to "_",
            R.id.key_fwdslash   to "/", R.id.key_colon     to ":",
            R.id.key_semicolon  to ";", R.id.key_apos      to "'",
            R.id.key_dquote     to "\"",
            // Page 2 row 1
            R.id.key_tilde     to "~",  R.id.key_backtick  to "`",
            R.id.key_pipe      to "|",  R.id.key_backslash to "\\",
            R.id.key_lt        to "<",  R.id.key_gt        to ">",
            R.id.key_lbracket  to "[",  R.id.key_rbracket  to "]",
            R.id.key_lbrace    to "{",  R.id.key_rbrace    to "}",
            // Page 2 row 2
            R.id.key_caret     to "^",  R.id.key_euro      to "€",
            R.id.key_pound     to "£",  R.id.key_cent      to "¢",
            R.id.key_times     to "×",  R.id.key_div       to "÷",
            R.id.key_degree    to "°",  R.id.key_plusminus to "±",
            R.id.key_copyright to "©",  R.id.key_trademark to "™",
            // Page 2 row 3
            R.id.key_section  to "§",  R.id.key_pilcrow  to "¶",
            R.id.key_invquest to "¿",  R.id.key_invexcl  to "¡",
            R.id.key_lquote   to "«",  R.id.key_rquote   to "»",
            R.id.key_ellipsis to "…",  R.id.key_ndash    to "–",
            R.id.key_mdash    to "—",
            // Comma and period (present on all three layers)
            R.id.key_comma    to ",",  R.id.key_period    to ".",
            R.id.key_comma_s1 to ",",  R.id.key_period_s1 to ".",
            R.id.key_comma_s2 to ",",  R.id.key_period_s2 to "."
        )

        for ((id, character) in symbolKeys) {
            view.findViewById<Button>(id)?.setOnClickListener {
                pendingRevert = null
                val ic = currentInputConnection
                // Smart punctuation (Session 43): an attaching mark tapped right after a
                // keyboard-inserted trailing space hugs the previous word. The one-char read only
                // runs when pendingAutoSpace is true, which is never the case in a password field,
                // so it leaks nothing.
                val charBefore = if (pendingAutoSpace) ic?.getTextBeforeCursor(1, 0)?.lastOrNull() else null
                val attach = SmartPunctuation.shouldAttach(pendingAutoSpace, charBefore, character)
                // An attaching mark (. , ? ! : ;) ends the preceding word: learn it if novel
                // (Session 45), same gate/threading as the space-commit path. Gated on the mark type
                // (not the spacing decision) so "Znargle." is learned even without a pending space;
                // onWordCommit skips a mid-edited fragment (e.g. "don" before an apostrophe) and any
                // sensitive field. Must run before onNonLetterTyped clears the buffer.
                if (SmartPunctuation.isAttachingMark(character[0])) {
                    val ended = inputState.onWordCommit(isSensitiveField())
                    if (ended != null) maybeLearnWord(ended)
                }
                inputState.onNonLetterTyped()
                wordStartedWithCapital = false
                wordIsAllCaps = false
                isAtWordStart = true
                if (attach && ic != null) {
                    ic.deleteSurroundingText(1, 0)   // remove the pending space
                    ic.commitText("$character ", 1)  // attach the mark, re-add one space
                    pendingAutoSpace = true           // the re-added space is itself pending
                } else {
                    ic?.commitText(character, 1)
                    pendingAutoSpace = false
                }
                updateSuggestionStrip()
            }
        }

        for ((id, character) in symbolKeys) {
            val button = view.findViewById<Button>(id) ?: continue
            if (AlternateCharMap.hasPunctuationAlternates(character[0])) {
                // Punctuation keys with a long-press set (e.g. the period) get the alternates
                // popup, reusing the accent-picker machinery. Short tap still types the base mark
                // via the click listener above. Session 32.
                attachPunctuationPopup(button, view, character)
            } else {
                button.setOnTouchListener { v, event ->
                    when (event.action) {
                        MotionEvent.ACTION_DOWN -> {
                            closeOrphanedAlternatesPopup()
                            showKeyPreview(v as Button)
                        }
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> dismissKeyPreview()
                    }
                    false
                }
            }
        }
    }

    // Spacebar long-press opens Settings (the gear hint in the key's corner). DOWN schedules the
    // open after longPressDelayMs; if it fires, ACTION_UP is consumed so the click
    // listener does not also insert a space. A short tap cancels the runnable and behaves as a
    // normal space via the click listener. MOVE outside the button's bounds also cancels — small
    // in-place tremor (within bounds) does not, so the timer survives a still hold.
    @SuppressLint("ClickableViewAccessibility")
    private fun attachSpaceLongPress(button: Button) {
        button.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    spaceLongPressFired = false
                    val runnable = Runnable {
                        spaceLongPressFired = true
                        v.isPressed = false
                        openSettings()
                    }
                    spaceLongPressRunnable = runnable
                    spaceLongPressHandler.postDelayed(runnable, longPressDelayMs)
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    val outOfBounds = event.x < 0 || event.y < 0 || event.x > v.width || event.y > v.height
                    if (outOfBounds) {
                        spaceLongPressRunnable?.let { spaceLongPressHandler.removeCallbacks(it) }
                        spaceLongPressRunnable = null
                    }
                    false
                }
                MotionEvent.ACTION_UP -> {
                    spaceLongPressRunnable?.let { spaceLongPressHandler.removeCallbacks(it) }
                    spaceLongPressRunnable = null
                    spaceLongPressFired // consume if the long-press already fired
                }
                MotionEvent.ACTION_CANCEL -> {
                    spaceLongPressRunnable?.let { spaceLongPressHandler.removeCallbacks(it) }
                    spaceLongPressRunnable = null
                    false
                }
                else -> false
            }
        }
    }

    // Opens the Settings screen from the keyboard. Requires FLAG_ACTIVITY_NEW_TASK because the
    // IME is not itself an Activity.
    private fun openSettings() {
        val intent = android.content.Intent(this, SettingsActivity::class.java)
        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
    }

    // letter-key alternates touch listener (DOWN schedules the popup after longPressDelayMs;
    // MOVE highlights; UP commits the highlighted mark and consumes the event so the click listener
    // does not also type the base; CANCEL cleans up) but commits via the non-letter path with no
    // Shift logic (punctuation has no case). Suppressed in sensitive fields, exactly like accents.
    @SuppressLint("ClickableViewAccessibility")
    private fun attachPunctuationPopup(button: Button, layerView: View, character: String) {
        val alts = AlternateCharMap.getPunctuationAlternates(character[0]) ?: return
        button.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    closeOrphanedAlternatesPopup()
                    showKeyPreview(v as Button)
                    // Suppress only in actual password fields — the popup reads nothing from the
                    // field, so search bars (NO_SUGGESTIONS) carry no privacy risk.
                    if (!isPasswordField()) {
                        val runnable = Runnable {
                            dismissKeyPreview()
                            showAlternatesPopup(v, character, alts, isUppercase = false)
                            alternatesPopupActive = true
                        }
                        alternatesRunnable = runnable
                        alternatesHandler.postDelayed(runnable, longPressDelayMs)
                    }
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    if (alternatesPopupActive) updateAlternateHighlight(event.x)
                    false
                }
                MotionEvent.ACTION_UP -> {
                    if (alternatesPopupActive) {
                        v.isPressed = false
                        commitPunctuationAlternate()
                        dismissAlternatesPopup()
                        alternatesPopupActive = false
                        alternatesRunnable = null
                        true  // consume — prevents the click listener from also typing the base mark
                    } else {
                        alternatesRunnable?.let { alternatesHandler.removeCallbacks(it) }
                        alternatesRunnable = null
                        dismissKeyPreview()
                        false  // short tap — let the click listener type the base mark
                    }
                }
                MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    alternatesRunnable?.let { alternatesHandler.removeCallbacks(it) }
                    alternatesRunnable = null
                    dismissKeyPreview()
                    if (alternatesPopupActive) {
                        dismissAlternatesPopup()
                        alternatesPopupActive = false
                    }
                    false
                }
                else -> false
            }
        }
    }

    // Wires the numpad digit keys, its backspace key (with full long-press acceleration),
    // and its done key. Kept separate from setupKeys so the numpad path is easy to read.
    @SuppressLint("ClickableViewAccessibility")
    private fun setupNumpadKeys(view: View) {
        val numpadDigitKeys = mapOf(
            R.id.key_np_0 to "0", R.id.key_np_1 to "1", R.id.key_np_2 to "2",
            R.id.key_np_3 to "3", R.id.key_np_4 to "4", R.id.key_np_5 to "5",
            R.id.key_np_6 to "6", R.id.key_np_7 to "7", R.id.key_np_8 to "8",
            R.id.key_np_9 to "9"
        )
        val numpadSymbolKeys = mapOf(
            R.id.key_np_0 to "+", R.id.key_np_1 to "*", R.id.key_np_2 to "#",
            R.id.key_np_3 to "(", R.id.key_np_4 to ")", R.id.key_np_5 to "-",
            R.id.key_np_6 to "/", R.id.key_np_7 to ":", R.id.key_np_8 to ".",
            R.id.key_np_9 to "%"
        )
        for ((id, character) in numpadDigitKeys) {
            val symbol = numpadSymbolKeys[id] ?: continue
            val button = view.findViewById<Button>(id) ?: continue
            // Short tap → digit. The touch listener returns false on ACTION_UP for short taps,
            // so the button's own click dispatch fires this listener.
            button.setOnClickListener {
                currentInputConnection?.commitText(character, 1)
            }
            // Touch listener drives the long-press popup:
            //   DOWN   — schedule a runnable that shows the symbol popup after 400 ms.
            //   UP     — if popup fired: insert symbol, un-press button, consume event.
            //            Otherwise: cancel runnable, return false so digit click fires.
            //   CANCEL — clean up without inserting; button un-presses itself.
            button.setOnTouchListener { v, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        // Cancel any runnable from a prior key (multi-touch safety).
                        numpadLongPressRunnable?.let { longPressHandler.removeCallbacks(it) }
                        numpadLongPressActiveSymbol = null
                        val runnable = Runnable {
                            numpadLongPressActiveSymbol = symbol
                            showKeyPreview(v as Button, symbol)
                        }
                        numpadLongPressRunnable = runnable
                        longPressHandler.postDelayed(runnable, longPressDelayMs)
                        false  // let button set state_pressed → grey colour natively
                    }
                    MotionEvent.ACTION_UP -> {
                        numpadLongPressRunnable?.let { longPressHandler.removeCallbacks(it) }
                        numpadLongPressRunnable = null
                        val sym = numpadLongPressActiveSymbol
                        numpadLongPressActiveSymbol = null
                        dismissKeyPreview()
                        if (sym != null) {
                            // Long-press completed. Button never saw ACTION_UP (we consumed it)
                            // so it stays visually pressed — un-press it explicitly.
                            (v as? Button)?.isPressed = false
                            currentInputConnection?.commitText(sym, 1)
                            true  // consumed — prevent digit click from also firing
                        } else {
                            false  // short tap — let click listener fire the digit
                        }
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        numpadLongPressRunnable?.let { longPressHandler.removeCallbacks(it) }
                        numpadLongPressRunnable = null
                        numpadLongPressActiveSymbol = null
                        dismissKeyPreview()
                        false  // button handles its own un-press on cancel
                    }
                    else -> false
                }
            }
        }

        // Numpad backspace: same long-press acceleration as the QWERTY backspace. Configures
        // whichever button (left or right position) currently holds the backspace role.
        val configureNumpadBackspace = { button: Button ->
            button.foreground = null
            button.text = "⌫"
            button.setOnClickListener { performSingleBackspace() }
            button.setOnTouchListener { v, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        longPressDidFire = false
                        inCharPhase = true
                        charDeleteCount = 0
                        longPressHandler.postDelayed(backspaceRepeatRunnable, longPressDelayMs)
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        longPressHandler.removeCallbacks(backspaceRepeatRunnable)
                        if (!longPressDidFire) v.performClick()
                        longPressDidFire = false
                        true
                    }
                    else -> false
                }
            }
        }

        // Numpad done key: sends the field's declared action (Go, Next, Done, Search, etc.)
        // if one is set, or falls back to a newline for fields that declare no action.
        // The action lives in imeOptions & IME_MASK_ACTION. A custom actionId overrides it.
        // Configures whichever button (left or right position) currently holds the done role.
        val configureNumpadDone = { button: Button ->
            button.foreground = ContextCompat.getDrawable(this, R.drawable.ic_enter)
            button.foregroundGravity = Gravity.CENTER
            button.text = ""
            button.setOnTouchListener(null)
            button.setOnClickListener {
                val info = currentInputEditorInfo
                val ic = currentInputConnection ?: return@setOnClickListener
                val customActionId = info?.actionId ?: 0
                if (customActionId != 0) {
                    // App declared a custom action label/id — fire that.
                    ic.performEditorAction(customActionId)
                } else {
                    val action = (info?.imeOptions ?: 0) and EditorInfo.IME_MASK_ACTION
                    if (action != EditorInfo.IME_ACTION_NONE &&
                        action != EditorInfo.IME_ACTION_UNSPECIFIED) {
                        ic.performEditorAction(action)
                    } else {
                        ic.commitText("\n", 1)
                    }
                }
            }
        }

        // Default layout: ↵ left, ⌫ right. Inverted layout mirrors QWERTY: ⌫ left, ↵ right.
        val leftButton = view.findViewById<Button>(R.id.key_np_done) ?: return
        val rightButton = view.findViewById<Button>(R.id.key_np_backspace) ?: return
        if (invertNumpadEnterBackspace) {
            configureNumpadBackspace(leftButton)
            configureNumpadDone(rightButton)
        } else {
            configureNumpadDone(leftButton)
            configureNumpadBackspace(rightButton)
        }
    }

    private fun performSingleBackspace() {
        pendingAutoSpace = false
        pendingHomophoneOffer = null   // editing dismisses any pending homophone offer
        // If the previous action was an autocorrect, the first backspace reverts it instead
        // of deleting a character (Gboard/iOS behaviour).
        val revert = pendingRevert
        if (revert != null) {
            pendingRevert = null
            val ic = currentInputConnection
            if (ic != null) {
                ic.deleteSurroundingText(revert.correctedText.length + revert.trailer.length, 0)
                ic.commitText(revert.typedText + revert.trailer, 1)
            }
            inputState.onFieldChange()
            isAtWordStart = revert.trailer.isNotEmpty()
            wordStartedWithCapital = false
            wordIsAllCaps = false
            applyAutoCaps()
            updateSuggestionStrip()
            return
        }
        inputState.onBackspace(isPasswordField())
        currentInputConnection?.deleteSurroundingText(1, 0)
        if (inputState.isWordEmpty) {
            isAtWordStart = true
            wordStartedWithCapital = false
            wordIsAllCaps = false
        }
        applyAutoCaps()
        updateSuggestionStrip()
    }

    // In a password field, falls back to single-char delete so getTextBeforeCursor is never
    // called and no password content is read into memory.
    private fun deleteWordBeforeCursor() {
        pendingRevert = null
        pendingAutoSpace = false
        inputState.onWordDelete()
        isAtWordStart = true
        wordStartedWithCapital = false
        wordIsAllCaps = false
        if (isPasswordField()) {
            currentInputConnection?.deleteSurroundingText(1, 0)
            updateSuggestionStrip()
            return
        }
        val ic = currentInputConnection ?: return
        val text = ic.getTextBeforeCursor(200, 0) ?: return
        val count = countCharsToDeleteForWord(text)
        if (count > 0) ic.deleteSurroundingText(count, 0)
        updateSuggestionStrip()
    }

    // Casing is preserved through correction: CAPS_LOCK uppercases the full output;
    // ONE_SHOT (first letter only) capitalises the first letter of the correction.
    private fun commitWordWithCorrection() {
        // Any new commit invalidates the previous revert opportunity.
        pendingRevert = null
        pendingHomophoneOffer = null   // a new word commit supersedes any offer for the previous one
        // A new commit supersedes any in-flight autocorrect refine for the previous word.
        autocorrectGeneration++
        // When autocorrect is disabled, characters are already committed as typed —
        // do not attempt to delete and replace them with a correction.
        if (!autocorrectEnabled) {
            inputState.onWordCommit(isSensitiveField())   // clears the buffer
            wordStartedWithCapital = false
            wordIsAllCaps = false
            isAtWordStart = true
            updateSuggestionStrip()
            return
        }
        val sensitive = isSensitiveField()
        val wordToCorrect = inputState.onWordCommit(sensitive)
        val allCaps = wordIsAllCaps
        val capitalize = wordStartedWithCapital
        wordStartedWithCapital = false
        wordIsAllCaps = false
        isAtWordStart = true
        if (wordToCorrect == null) {
            updateSuggestionStrip()
            return
        }
        // Session-78 learned-words shield: a word the user taught the keyboard is a real word —
        // don't clobber it. Lock-free lookup over the store's published snapshot (safe on the IME
        // thread). Fixes Session-75 (a learned name like "priya" was corrected to "prima").
        val correction = autocorrect?.correct(wordToCorrect) { learnedWordsStore?.isLearned(it) == true }
        if (correction == null) {
            // Autocorrect left the word unchanged: the user kept what they typed. If it is a word
            // the static dictionary does not know (a name, slang, jargon), record it so it can be
            // offered as a completion next time. maybeLearnWord enforces the password/sensitive
            // exclusion and runs the disk write off this thread.
            maybeLearnWord(wordToCorrect)
            if (autocorrect?.isKnownWord(wordToCorrect) == true) {
                // Slice 2: the word was spelled correctly, but if it is a homophone/contraction (there
                // vs their, from vs form), context may show the user meant a different family member.
                // Schedule an async rescore that, if confident, OFFERS the alternative in the strip.
                scheduleHomophoneOffer(wordToCorrect, allCaps, capitalize)
            }
            // Session 75: the ed-2 neural autocorrect path (Session 74) was REMOVED here. On-device
            // measurement (LlamaScoreCandidatesDeviceTest.decideCorrection_ed2OverCorrectionBattery)
            // showed length-normalized context log-prob is the wrong signal for ed-2 non-word fixes:
            // the one genuine typo (tommorow->tomorrow) scored BELOW the typed word, while the cases
            // that did clear the margin were rare-word->common-word substitutions (yeeted->seemed) —
            // i.e. over-corrections. No margin separates good ed-2 fixes from bad ones, so the path is
            // disabled. ed-1 autocorrect and the homophone offer above are unaffected (measured in the
            // Session 75 evaluation). (ContextRescorer's typed-word-as-incumbent guard is retained as the safe
            // design of record if ed-2 is ever revisited with a better signal.)
            updateSuggestionStrip()
            return
        }
        val ic = currentInputConnection ?: return
        val output = when {
            allCaps -> correction.uppercase()
            capitalize -> capitalizeFirstLetter(correction)
            else -> correction
        }
        // Read the on-screen typed text (with its actual casing) before we delete it, so a
        // following backspace can restore exactly what the user typed.
        val originalTyped = ic.getTextBeforeCursor(wordToCorrect.length, 0)?.toString() ?: wordToCorrect
        ic.deleteSurroundingText(wordToCorrect.length, 0)
        ic.commitText(output, 1)
        // Only arm revert if the correction actually changed something on screen.
        if (output != originalTyped) {
            pendingRevert = PendingRevert(typedText = originalTyped, correctedText = output)
            // Fast ed-1 commit done above (typing feel unchanged). Now schedule an async refine
            // that may upgrade `output` to a context-better correction. Only fires when a correction
            // was actually applied; reads the preceding sentence context here on the IME thread
            // (gated), then scores off-thread on predictionExecutor.
            scheduleAutocorrectRefine(wordToCorrect, originalTyped, output, allCaps, capitalize)
        }
        updateSuggestionStrip()
    }

    // Phase 5 Session 71 (slice 1b): "commit fast, refine async." The synchronous edit-distance-1
    // correction above already changed the on-screen word; this schedules a background re-ranking of
    // the dictionary's ed<=2 candidates by sentence context (the bundled SmolLM2 model) and, only if
    // the model STRONGLY prefers a different correction (ContextRescorer's margin), swaps it in.
    //
    // SECURITY (rule 4): the BROADER isSensitiveField() gate is checked BEFORE any context is read or
    // scored; a null EditorInfo is sensitive (fail-safe). The context read reuses prediction's single
    // read site (readContextWindow); scoring runs on predictionExecutor (same owner thread as
    // prefill/decode, the UAF guard), never the IME thread. Nothing about the candidates or context
    // is logged or persisted.
    //
    // [typed] is the lowercase typo (the dictionary candidate-source key); [originalTyped] is the
    // verbatim on-screen text the user typed, with its casing (what a backspace must restore to);
    // [committed] is what is now on screen (with casing); [allCaps]/[capitalize] re-apply that casing
    // to a refined replacement.
    private fun scheduleAutocorrectRefine(
        typed: String,
        originalTyped: String,
        committed: String,
        allCaps: Boolean,
        capitalize: Boolean
    ) {
        if (!BuildConfig.AUTOCORRECT_RESCORE_ENABLED) return
        val predictor = llamaPredictor ?: return
        val corrector = autocorrect ?: return
        // GATE BEFORE ANY READ: sensitive (incl. null EditorInfo) => no read, no score.
        if (isSensitiveField()) return
        // Read the text-before-cursor ONCE on the IME thread (same site as prediction). After the
        // commit above, the cursor sits right after `committed`, so strip it to recover the sentence
        // context that PRECEDES the typo — that is what the candidates are scored against.
        val textBefore = readContextWindow()?.toString() ?: return
        val precedingRaw = if (textBefore.endsWith(committed)) {
            textBefore.dropLast(committed.length)
        } else {
            return // on-screen text not as expected (a race) — skip the refine
        }
        val context = PredictionContext.buildContext(precedingRaw) ?: return
        val generation = autocorrectGeneration
        // Session-78: the learned-words shield (never auto-correct a taught word) and the edit-cost
        // prior (a far-fetched correction must beat the typed word by margin + its typo-implausibility)
        // both plug in here. `learned` reads the store's lock-free snapshot; `editCost` is pure Kotlin.
        val learned: (String) -> Boolean = { learnedWordsStore?.isLearned(it) == true }
        predictionExecutor.execute {
            val refinedLower = try {
                ContextRescorer.decideCorrection(
                    typed = typed,
                    neighbors = corrector.neighbors(typed, maxEdits = 2),
                    fastPick = corrector.correct(typed, learned),
                    scoreAll = { cands -> predictor.scoreCorrectionCandidates(context, cands) },
                    isLearned = learned,
                    editCost = { cand -> EditCost.cost(typed, cand) }
                )
            } catch (_: Throwable) { null } ?: return@execute

            predictionHandler.post {
                applyAutocorrectRefine(generation, originalTyped, committed, refinedLower, allCaps, capitalize)
            }
        }
    }

    // Post-back of the async refine, on the IME thread. Swaps the on-screen fast-committed correction
    // for the model-preferred one — but ONLY if nothing has moved on under us: same generation (no
    // newer commit/edit), the on-screen word is still exactly the fast-committed correction, and the
    // refined word actually differs. Re-arms PendingRevert(trailer = " ") so a single backspace still
    // undoes to exactly what the user typed (M8-safe literal swap).
    private fun applyAutocorrectRefine(
        generation: Int,
        originalTyped: String,
        committed: String,
        refinedLower: String,
        allCaps: Boolean,
        capitalize: Boolean
    ) {
        if (generation != autocorrectGeneration) return // superseded by a newer commit/edit
        val refined = when {
            allCaps -> refinedLower.uppercase()
            capitalize -> capitalizeFirstLetter(refinedLower)
            else -> refinedLower
        }
        if (refined == committed) return // model agreed with the fast pick
        val ic = currentInputConnection ?: return
        // The fast commit appended a trailing space (the space key's handler), so on screen it is
        // "<committed> ". Verify that exact text is still immediately before the cursor; if the user
        // typed on or moved the cursor, the generation check above already bailed, but re-verify the
        // literal text so we never delete something else.
        val onScreen = ic.getTextBeforeCursor(committed.length + 1, 0)?.toString()
        if (onScreen != "$committed ") return
        ic.deleteSurroundingText(committed.length + 1, 0)
        ic.commitText("$refined ", 1)
        // Re-arm revert so a single backspace restores exactly what the user typed (with its casing),
        // plus the trailing space (M8-safe literal swap).
        pendingRevert = PendingRevert(typedText = originalTyped, correctedText = refined, trailer = " ")
    }

    // Phase 5 Session 73 (slice 2): for a CORRECTLY-SPELLED real word just committed, schedule an
    // async context rescore of its confusion family (there/their/they're, from/form, ...) and, if the
    // model STRONGLY prefers a different member, post back a strip OFFER (never a silent swap).
    //
    // SECURITY (rule 4, Session-73 review): the BROADER isSensitiveField() gate is checked BEFORE any
    // context is read or scored (null EditorInfo => sensitive, fail-safe). The candidate set is a
    // STATIC constant (ConfusionSets) — not user content, not learned words — so it is strictly less
    // sensitive than slice 1b. The context read reuses the single gated read site (readContextWindow);
    // scoring runs on predictionExecutor (never the IME thread). Nothing is logged or persisted. The
    // post-back (offerHomophone) RE-GATES the field before painting anything.
    //
    // [typedLower] is the lowercase committed word; [allCaps]/[capitalize] re-apply its on-screen
    // casing to both the committed display and the offered alternative.
    private fun scheduleHomophoneOffer(typedLower: String, allCaps: Boolean, capitalize: Boolean) {
        if (!BuildConfig.AUTOCORRECT_RESCORE_ENABLED) return
        val predictor = llamaPredictor ?: return
        // Only the small static confusion words are ever candidates; everything else returns early
        // BEFORE any field read.
        val confusionSet = ConfusionSets.candidatesFor(typedLower) ?: return
        // GATE BEFORE ANY READ: sensitive (incl. null EditorInfo) => no read, no score.
        if (isSensitiveField()) return
        // On-screen form of the just-committed word (its casing). No trailing space yet — the space
        // key appends it right after this returns; the post-back accounts for that space.
        val committed = when {
            allCaps -> typedLower.uppercase()
            capitalize -> capitalizeFirstLetter(typedLower)
            else -> typedLower
        }
        // Read text-before-cursor ONCE on the IME thread (same site as prediction). The committed word
        // sits at the end; strip it to recover the sentence context that PRECEDES it.
        val textBefore = readContextWindow()?.toString() ?: return
        val precedingRaw = if (textBefore.endsWith(committed)) {
            textBefore.dropLast(committed.length)
        } else {
            return // on-screen text not as expected (a race) — skip
        }
        val context = PredictionContext.buildContext(precedingRaw) ?: return
        val generation = autocorrectGeneration
        predictionExecutor.execute {
            val offeredLower = try {
                ContextRescorer.decideRealWordOffer(
                    typed = typedLower,
                    confusionSet = confusionSet,
                    scoreAll = { cands -> predictor.scoreCorrectionCandidates(context, cands) }
                )
            } catch (_: Throwable) { null } ?: return@execute

            predictionHandler.post {
                offerHomophone(generation, committed, offeredLower, allCaps, capitalize)
            }
        }
    }

    // Post-back of the async homophone rescore, on the IME thread. Arms a strip offer ONLY if nothing
    // has moved on under us: same generation (no newer commit/edit/field change) AND — the load-bearing
    // re-gate (SECURITY rule 4, Session-73 condition 1) — the field is still non-sensitive. A focus
    // change to a sensitive field after the gated read must NOT surface an offer; we do not rely on the
    // generation guard alone for that.
    private fun offerHomophone(
        generation: Int,
        committed: String,
        offeredLower: String,
        allCaps: Boolean,
        capitalize: Boolean
    ) {
        if (generation != autocorrectGeneration) return // superseded
        if (isSensitiveField()) return                  // re-gate before painting anything
        val offered = when {
            allCaps -> offeredLower.uppercase()
            capitalize -> capitalizeFirstLetter(offeredLower)
            else -> offeredLower
        }
        pendingHomophoneOffer = HomophoneOffer(committed = committed, offered = offered)
        updateSuggestionStrip()
    }

    // Tap-to-swap for a slice-2 homophone offer (center slot in State A). Re-gates the field AND
    // re-verifies the literal on-screen text at TAP time (the offer may have been painted a moment
    // ago; SECURITY rule 4 condition 2). Replaces "<committed> " with "<offered> " and arms a single
    // backspace revert back to the word the user actually typed. Never runs in a sensitive field.
    private fun acceptHomophoneOffer(offer: HomophoneOffer) {
        pendingHomophoneOffer = null
        if (isSensitiveField()) { updateSuggestionStrip(); return }
        val ic = currentInputConnection ?: run { updateSuggestionStrip(); return }
        // The space key appended a trailing space after the original commit, so on screen it is
        // "<committed> ". Verify that exact text is still immediately before the cursor.
        val expected = "${offer.committed} "
        val onScreen = ic.getTextBeforeCursor(expected.length, 0)?.toString()
        if (onScreen != expected) { updateSuggestionStrip(); return } // text moved under us — do nothing
        ic.deleteSurroundingText(expected.length, 0)
        ic.commitText("${offer.offered} ", 1)
        // One backspace restores exactly what the user typed (the committed word) + its space.
        pendingRevert = PendingRevert(typedText = offer.committed, correctedText = offer.offered, trailer = " ")
        pendingAutoSpace = true
        isAtWordStart = true
        updateSuggestionStrip()
    }

    // ---- Phase 4 learned-words capture + consumption (Session 36) ----

    // Record [typedLower] (already lowercase, straight from the input buffer) into the learned-words
    // dictionary IF every gate passes: the feature is compiled in, suggestions are enabled, the word
    // is NOVEL (not already in the static autocorrect dictionary), and the field is not
    // password/sensitive. The live currentInputEditorInfo.inputType is read here on the main thread;
    // a null EditorInfo is treated as sensitive by LearnedWordsPolicy's null-aware overload, so
    // nothing is learned then (SECURITY.md rule 4 fail-safe). The encrypt+write itself runs on
    // learnedWordsExecutor, never the IME thread.
    private fun maybeLearnWord(typedLower: String) {
        if (!BuildConfig.LEARNED_WORDS_ENABLED) return
        if (!suggestionsEnabled) return
        val store = learnedWordsStore ?: return
        val ac = autocorrect ?: return
        if (ac.isKnownWord(typedLower)) return
        val inputType = currentInputEditorInfo?.inputType
        if (LearnedWordsPolicy.isFieldExcludedFromLearning(inputType)) return
        val type = inputType ?: return // non-null once the exclusion check passes; explicit for the lambda
        learnedWordsExecutor.execute { store.learn(typedLower, type) }
    }

    // The top learned-word completion of the typed prefix [typedLower], or null. Reads the store's
    // lock-free snapshot, so it is safe to call on the IME thread on every keystroke. The candidate
    // is always strictly longer than the prefix, so it never echoes the word the user already typed.
    // The neural model is untouched: this is a pure dictionary lookup (Option B).
    private fun learnedCompletionFor(typedLower: String): String? {
        if (!BuildConfig.LEARNED_WORDS_ENABLED) return null
        val store = learnedWordsStore ?: return null
        return store.completions(typedLower, 1).firstOrNull()
    }

    internal fun capitalizeFirstLetter(word: String): String {
        if (word.isEmpty()) return word
        return word[0].uppercaseChar() + word.substring(1)
    }

    private fun updateSymbolLayer(view: View) {
        val layerLetters  = view.findViewById<LinearLayout>(R.id.layer_letters)
        val layerSymbols1 = view.findViewById<LinearLayout>(R.id.layer_symbols_1)
        val layerSymbols2 = view.findViewById<LinearLayout>(R.id.layer_symbols_2)
        when (symbolState.state) {
            SymbolState.LETTERS -> {
                layerLetters?.visibility  = View.VISIBLE
                layerSymbols1?.visibility = View.GONE
                layerSymbols2?.visibility = View.GONE
            }
            SymbolState.SYMBOLS_1 -> {
                layerLetters?.visibility  = View.GONE
                layerSymbols1?.visibility = View.VISIBLE
                layerSymbols2?.visibility = View.GONE
            }
            SymbolState.SYMBOLS_2 -> {
                layerLetters?.visibility  = View.GONE
                layerSymbols1?.visibility = View.GONE
                layerSymbols2?.visibility = View.VISIBLE
            }
        }
    }

    // Shows the numpad layer and hides the QWERTY layers and suggestion strip when the field
    // is numeric. Restores the QWERTY view (with the symbol-layer state preserved) and the
    // suggestion strip when the field is not numeric.
    private fun updateNumpadLayer(view: View) {
        val layerNumpad   = view.findViewById<LinearLayout>(R.id.layer_numpad)
        val layerLetters  = view.findViewById<LinearLayout>(R.id.layer_letters)
        val layerSymbols1 = view.findViewById<LinearLayout>(R.id.layer_symbols_1)
        val layerSymbols2 = view.findViewById<LinearLayout>(R.id.layer_symbols_2)
        val strip         = view.findViewById<LinearLayout>(R.id.suggestion_strip)
        if (isNumericInputField()) {
            layerNumpad?.visibility   = View.VISIBLE
            layerLetters?.visibility  = View.GONE
            layerSymbols1?.visibility = View.GONE
            layerSymbols2?.visibility = View.GONE
            strip?.visibility         = View.GONE
        } else {
            layerNumpad?.visibility = View.GONE
            strip?.visibility       = View.VISIBLE
            // QWERTY layer visibility is handled by updateSymbolLayer — do not set it here.
        }
    }

    // text overrides the button label — used by numpad long-press to show a symbol instead.
    private fun showKeyPreview(anchor: Button, text: String? = null) {
        if (!keyPreviewEnabled) return
        dismissKeyPreview()
        try {
            val previewView = layoutInflater.inflate(R.layout.key_preview, null)
            previewView.findViewById<TextView>(R.id.preview_text).text = text ?: anchor.text
            val popup = PopupWindow(previewView, anchor.width, -2, false)
            popup.isOutsideTouchable = false
            val location = IntArray(2)
            anchor.getLocationInWindow(location)
            previewView.measure(
                View.MeasureSpec.makeMeasureSpec(anchor.width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            )
            popup.showAtLocation(anchor, Gravity.NO_GRAVITY, location[0], location[1] - previewView.measuredHeight - 8)
            keyPreviewPopup = popup
        } catch (_: Exception) {
            // PopupWindow.showAtLocation throws (e.g. BadTokenException) in restricted
            // window contexts such as the lock screen. The preview is cosmetic — swallow
            // the error so the key tap always registers.
        }
    }

    private fun dismissKeyPreview() {
        keyPreviewPopup?.dismiss()
        keyPreviewPopup = null
    }

    // baseLetter is the key's own character (e.g. "e"). It is always placed in the chip directly
    // above the held key and pre-selected, so releasing without sliding commits the base letter.
    // Accents sit to its right (or to its left near the right edge of the screen). isUppercase is
    // captured at ACTION_DOWN, not here, so ONE_SHOT state is preserved even if consumed later.
    private fun showAlternatesPopup(anchor: Button, baseLetter: String, alternates: List<String>, isUppercase: Boolean) {
        dismissAlternatesPopup()
        try {
            val dm = resources.displayMetrics
            val chipWidthPx = anchor.width
            val chipHeightPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 44f, dm).toInt()
            // tabHeightPx replaces the old gapPx: same vertical distance, but now filled with a
            // visible connector shape rather than empty space.
            val tabHeightPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 8f, dm).toInt()
            val cornerPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 6f, dm)

            val defaultChipColor = resources.getColor(R.color.key_alternates_chip, theme)
            val highlightColor = resources.getColor(R.color.key_shift_oneshot, theme)
            val textColor = resources.getColor(R.color.key_text, theme)

            // Inner chip row — chips are flush (no margins) for a solid connected-strip look.
            val container = layoutInflater.inflate(R.layout.key_alternates_popup, null) as LinearLayout
            alternatesContainer = container

            val nChips = alternates.size + 1
            val lastIndex = nChips - 1
            val totalWidth = chipWidthPx * nChips

            val location = IntArray(2)
            anchor.getLocationInWindow(location)
            val screenWidthPx = dm.widthPixels
            val idealX = location[0]

            // The base letter must sit in the chip directly above the held key. When the accents
            // fit to the right, lay them out as [base, accents...] with the base above the key.
            // When that would run off the right edge, lay them out as [accents..., base] so the
            // accents extend left and the base still sits above the key (iOS/Gboard edge style).
            val baseFirst = idealX + totalWidth <= screenWidthPx
            val allChips: List<String>
            var clampedX: Int
            val anchorIndex: Int
            if (baseFirst) {
                allChips = listOf(baseLetter) + alternates
                anchorIndex = 0
                clampedX = idealX
            } else {
                allChips = alternates + listOf(baseLetter)
                anchorIndex = lastIndex
                clampedX = idealX - (totalWidth - chipWidthPx)
            }
            if (clampedX < 0) clampedX = 0  // safety: popup wider than space; pin to screen edge
            currentAlternates = allChips
            // Held key sits above the anchor (base) chip; offset maps touch-X to chip index.
            alternatesPopupXOffset = idealX - clampedX
            alternatesAnchorChipIndex = anchorIndex

            for ((i, chipChar) in allChips.withIndex()) {
                val chip = TextView(this)
                chip.text = if (isUppercase) chipChar.uppercase() else chipChar
                chip.textSize = 14f
                chip.setTextColor(textColor)
                chip.gravity = Gravity.CENTER
                // Outer top corners are always rounded. Outer bottom corners are rounded too —
                // unless the connecting tab sits under that chip, in which case the bottom edge
                // flows into the tab and must stay square.
                val chipDrawable = GradientDrawable()
                chipDrawable.setColor(if (i == anchorIndex) highlightColor else defaultChipColor)
                val topLeft = if (i == 0) cornerPx else 0f
                val topRight = if (i == lastIndex) cornerPx else 0f
                val bottomLeft = if (i == 0 && anchorIndex != 0) cornerPx else 0f
                val bottomRight = if (i == lastIndex && anchorIndex != lastIndex) cornerPx else 0f
                chipDrawable.cornerRadii = floatArrayOf(
                    topLeft, topLeft,
                    topRight, topRight,
                    bottomRight, bottomRight,
                    bottomLeft, bottomLeft
                )
                chip.background = chipDrawable
                chip.layoutParams = LinearLayout.LayoutParams(chipWidthPx, chipHeightPx)
                container.addView(chip)
            }

            // Outer wrapper carries the callout background (chip strip + connecting tab).
            // The tab sits under the base chip, which is pre-selected, so it starts active.
            val tabLeftPx = anchorIndex * chipWidthPx
            val initialTabColor = highlightColor
            val wrapper = FrameLayout(this)
            val callout = AlternatesCalloutDrawable(initialTabColor, tabLeftPx, chipWidthPx, tabHeightPx, cornerPx)
            alternatesCalloutDrawable = callout
            wrapper.background = callout
            val containerParams = FrameLayout.LayoutParams(totalWidth, chipHeightPx)
            containerParams.gravity = Gravity.TOP
            wrapper.addView(container, containerParams)

            val popup = PopupWindow(wrapper, totalWidth, chipHeightPx + tabHeightPx, false)
            popup.isOutsideTouchable = false

            popup.showAtLocation(anchor, Gravity.NO_GRAVITY, clampedX, location[1] - chipHeightPx - tabHeightPx)

            alternatesPopup = popup
            // Pre-select the base chip so releasing without sliding commits the base letter.
            highlightedAlternateIndex = anchorIndex
        } catch (_: Exception) {
            // PopupWindow.showAtLocation throws in restricted window contexts (e.g. lock screen).
        }
    }

    private fun dismissAlternatesPopup() {
        alternatesPopup?.dismiss()
        alternatesPopup = null
        alternatesContainer = null
        currentAlternates = null
        highlightedAlternateIndex = -1
        alternatesPopupXOffset = 0
        alternatesAnchorChipIndex = 0
        alternatesCalloutDrawable = null
    }

    // A popup from a previous key-press can be orphaned (its owning key's UP/CANCEL was
    // missed). Call this on every new key-press so it never outlives the finger that opened it.
    private fun closeOrphanedAlternatesPopup() {
        if (alternatesPopupActive) {
            dismissAlternatesPopup()
            alternatesPopupActive = false
        }
    }

    // Translates a touch X coordinate (relative to the anchor key's left edge, which equals the
    // popup's left edge) into the chip index to highlight. Clamped to [0, size-1] so the finger
    // always has a valid chip selected anywhere across the keyboard.
    private fun updateAlternateHighlight(touchX: Float) {
        val container = alternatesContainer ?: return
        val alternates = currentAlternates ?: return
        if (container.childCount == 0) return
        val chipWidth = container.getChildAt(0).width
        if (chipWidth == 0) return

        // touchX is relative to the key's left edge. The popup may have been shifted left
        // by alternatesPopupXOffset pixels, so add that back to get popup-relative X.
        val newIndex = ((touchX + alternatesPopupXOffset) / chipWidth).toInt().coerceIn(0, alternates.size - 1)
        if (newIndex == highlightedAlternateIndex) return

        val restingColor = resources.getColor(R.color.key_alternates_chip, theme)
        val activeColor = resources.getColor(R.color.key_alternates_chip_active, theme)

        if (highlightedAlternateIndex in 0 until container.childCount) {
            (container.getChildAt(highlightedAlternateIndex)?.background as? GradientDrawable)?.setColor(restingColor)
        }
        if (newIndex in 0 until container.childCount) {
            (container.getChildAt(newIndex)?.background as? GradientDrawable)?.setColor(activeColor)
        }
        highlightedAlternateIndex = newIndex
        // Keep the tab in sync with the chip directly above the held key (the anchor chip):
        // when that chip is selected the tab matches it (making the stem look like a longer
        // version of that chip); otherwise it drops to the resting color.
        alternatesCalloutDrawable?.setTabColor(
            if (newIndex == alternatesAnchorChipIndex) activeColor else restingColor
        )
    }

    private fun commitAlternate(view: View) {
        val alternates = currentAlternates ?: return
        val index = highlightedAlternateIndex
        if (index < 0 || index >= alternates.size) return
        val alternate = alternates[index]
        pendingRevert = null
        pendingAutoSpace = false
        val uppercase = shiftState.onLetterTyped()
        if (isAtWordStart && uppercase) {
            wordStartedWithCapital = true
            wordIsAllCaps = (shiftState.state == ShiftState.CAPS_LOCK)
        }
        isAtWordStart = false
        syncBufferFromScreenIfNeeded()
        inputState.onLetterTyped(alternate[0].lowercaseChar(), isPasswordField())
        val output = if (uppercase) alternate[0].uppercaseChar().toString() else alternate
        currentInputConnection?.commitText(output, 1)
        updateShiftVisuals(view)
        updateSuggestionStrip()
    }

    // Commits the highlighted PUNCTUATION alternate (Session 32). Mirrors the symbol-key click
    // listener (non-letter path: no Shift logic, resets word-casing flags, marks a word start) so a
    // long-pressed mark behaves exactly like tapping that mark on the symbols layer. The base mark
    // is the pre-selected chip, so releasing without sliding commits it unchanged.
    private fun commitPunctuationAlternate() {
        val alternates = currentAlternates ?: return
        val index = highlightedAlternateIndex
        if (index < 0 || index >= alternates.size) return
        val mark = alternates[index]
        pendingRevert = null
        pendingAutoSpace = false
        inputState.onNonLetterTyped()
        wordStartedWithCapital = false
        wordIsAllCaps = false
        isAtWordStart = true
        currentInputConnection?.commitText(mark, 1)
        updateSuggestionStrip()
    }

    // When the buffer is empty but the screen has a word in progress immediately before the
    // cursor (typically because the user backspaced past a word boundary into a previously-
    // committed word), restore the buffer from screen so the next keystroke is treated as a
    // continuation of that word. Skipped for sensitive fields (no reads from password or
    // NO_SUGGESTIONS inputs). Case-tracking flags are reseeded from the on-screen casing.
    private fun syncBufferFromScreenIfNeeded() {
        if (!inputState.isWordEmpty) return
        if (isPasswordField()) return
        val ic = currentInputConnection ?: return
        val textBefore = ic.getTextBeforeCursor(200, 0) ?: return
        val wordSoFar = findWordInProgress(textBefore) ?: return
        for (c in wordSoFar) {
            inputState.onLetterTyped(c.lowercaseChar(), isSensitive = false)
        }
        wordStartedWithCapital = wordSoFar[0].isUpperCase()
        wordIsAllCaps = wordSoFar.length > 1 && wordSoFar.all { it.isUpperCase() }
        isAtWordStart = false
    }

    // Repaints all three suggestion slots from the current state. Must be called on the main
    // thread. Safe before the views are inflated (returns early if center is null).
    //
    // Two states (SESSION 31):
    //   State A -- buffer empty (word boundary / focus): the three slots show the top-N predicted
    //             next words (currentBoundaryWords); tapping any inserts it.
    //   State B -- typing a word: left = the exact typed word, center = the autocorrect candidate
    //             (or blank), right = the held boundaryWords[0] (no new inference).
    private fun updateSuggestionStrip() {
        // Kick off / refresh the boundary inference. Has its own eligibility + view-null guards.
        updateNextWordPrediction()

        val center = suggestionCenterView ?: return
        val left = suggestionLeftView
        val right = suggestionRightView
        val strip = suggestionStripView

        // When suggestions are disabled, hide the entire strip container.
        if (!suggestionsEnabled) {
            strip?.visibility = View.GONE
            return
        }
        strip?.visibility = View.VISIBLE

        // Password fields never show anything (defense in depth -- predictions are already gated).
        if (isPasswordField()) {
            setSlot(left, null)
            setSlot(center, null)
            setSlot(right, null)
            currentRightWord = null
            currentCenterCandidate = null
            return
        }

        if (inputState.isWordEmpty) {
            // State A, slice-2 override: a pending homophone offer takes the strip ahead of the
            // next-word predictions. Re-gate here too (rule 4 condition 1) — never surface an offer in
            // a sensitive field even if one slipped through. Left = the word as committed ("keep it",
            // dimmed); center = the offered alternative (tap to swap); right cleared.
            val offer = pendingHomophoneOffer
            if (offer != null && !isSensitiveField()) {
                setSlot(left, offer.committed, alpha = 0.7f)
                setSlot(center, offer.offered)
                setSlot(right, null)
                currentRightWord = null
                currentCenterCandidate = null // center holds the homophone offer, not a prediction
                return
            }
            // State A (boundary): the coordinator fills the three slots from the neural next-words
            // -- but only where prediction is eligible (not numeric / not suggestions-off). Slots
            // stay blank until the async inference returns, or if it yields nothing.
            val words = if (predictionEligibleNow()) currentBoundaryWords else emptyList()
            val slots = SuggestionFacilitator.merge("", null, emptyList(), words)
            setSlot(left, slots.left)
            setSlot(center, slots.center)
            setSlot(right, slots.right)
            currentRightWord = slots.right
            currentCenterCandidate = null // State A center holds a prediction, handled separately
            return
        }

        // State B: the user is typing a word. The coordinator merges the dictionary correction, the
        // learned-word completion, and the neural look-ahead into the three slots, de-duplicated.
        val typed = inputState.peekCurrentWord
        val neural = if (predictionEligibleNow()) currentBoundaryWords else emptyList()
        val slots = SuggestionFacilitator.merge(
            typed,
            autocorrect?.correct(typed),
            listOfNotNull(learnedCompletionFor(typed)),
            neural
        )

        // Left slot: always the typed word, with the casing the user applied. Dimmed (0.7) so it
        // reads as secondary to the center correction.
        val displayedTyped = when {
            wordIsAllCaps -> typed.uppercase()
            wordStartedWithCapital -> capitalizeFirstLetter(typed)
            else -> typed
        }
        setSlot(left, displayedTyped, alpha = 0.7f)

        // Center slot: the autocorrect correction, or — when autocorrect has none — the top
        // learned-word completion of the typed prefix (Session 36, Option B: a pure dictionary
        // lookup; the neural model is untouched). currentCenterCandidate records the raw word so the
        // tap handler commits exactly what is shown. A completion only ever appears on tap, never
        // auto-applied on space (commitWordWithCorrection stays autocorrect-only).
        currentCenterCandidate = slots.centerCommit
        val displayedCandidate = slots.center?.let {
            when {
                wordIsAllCaps -> it.uppercase()
                wordStartedWithCapital -> capitalizeFirstLetter(it)
                else -> it
            }
        }
        setSlot(center, displayedCandidate)

        // Right slot: the next-word guess computed at the last boundary, held without re-running
        // the model mid-word (battery: one inference per completed word). It is independent of the
        // word currently being typed -- a look-ahead, not a live re-prediction -- and the
        // coordinator drops it if it duplicates the typed word or the center candidate.
        setSlot(right, slots.right)
        currentRightWord = slots.right
    }

    // Paints one slot: a non-empty word makes it visible + tappable; null/empty clears and dims it.
    private fun setSlot(view: TextView?, word: String?, alpha: Float = 1.0f) {
        view ?: return
        if (word.isNullOrEmpty()) {
            view.text = ""
            view.isClickable = false
            view.alpha = 0.3f
        } else {
            view.text = word
            view.isClickable = true
            view.alpha = alpha
        }
    }

    // Whether next-word prediction may run/display for the current field: eligible (non-password,
    // suggestions on) and not a numeric field. Live read of EditorInfo (never cached).
    private fun predictionEligibleNow(): Boolean {
        val info = currentInputEditorInfo ?: return false
        return PredictionContext.isEligible(info.inputType, suggestionsEnabled) && !isNumericInputField()
    }

    // The ONE text-before-cursor read that feeds the model (next-word prediction AND the slice-1b
    // autocorrect refine). Each caller checks its own field-type gate BEFORE calling this — there
    // is no gate here, so this MUST NOT be called from an unguarded path. Reads PRED_CONTEXT_CHARS
    // of recent text on the IME thread; the engine trims it to tokens. Returns null if there is no
    // InputConnection. (Phase 5 Session 71: extracted so autocorrect reuses prediction's read site
    // rather than opening a second getTextBeforeCursor path.)
    private fun readContextWindow(): CharSequence? =
        currentInputConnection?.getTextBeforeCursor(PRED_CONTEXT_CHARS, 0)

    // Tap handler for the CENTER slot. In State B it applies the autocorrect candidate; in State A
    // (buffer empty) the slot holds a predicted word, so it is inserted like a prediction.
    // Password fields are blocked (the user opted in by tapping, but passwords stay off).
    private fun acceptSuggestion() {
        if (isPasswordField()) return
        if (inputState.isWordEmpty) {
            // Slice 2: in State A the center slot may hold a homophone offer instead of a prediction.
            pendingHomophoneOffer?.let { acceptHomophoneOffer(it); return }
            commitPredictedWord(currentBoundaryWords.getOrNull(1) ?: return)
            return
        }
        val typed = inputState.peekCurrentWord
        if (typed.isEmpty()) return
        // The center slot holds either an autocorrect correction or a learned-word completion;
        // commit whichever currentCenterCandidate recorded. For a completion the typed text is a
        // prefix of the candidate, so replacing the typed word with it works identically to
        // replacing a typo with its correction (and the same backspace-revert applies).
        val candidate = currentCenterCandidate ?: return
        val ic = currentInputConnection ?: return
        pendingRevert = null
        pendingAutoSpace = false   // center accept commits no trailing space
        val output = when {
            wordIsAllCaps -> candidate.uppercase()
            wordStartedWithCapital -> capitalizeFirstLetter(candidate)
            else -> candidate
        }
        wordStartedWithCapital = false
        wordIsAllCaps = false
        isAtWordStart = false
        val originalTyped = ic.getTextBeforeCursor(typed.length, 0)?.toString() ?: typed
        ic.deleteSurroundingText(typed.length, 0)
        ic.commitText(output, 1)
        inputState.onFieldChange()
        if (output != originalTyped) {
            pendingRevert = PendingRevert(typedText = originalTyped, correctedText = output)
        }
        updateSuggestionStrip()
    }

    // ---- Phase 4 next-word prediction (suggestion strip) ----

    // At a word boundary (buffer empty) in an eligible field, schedules ONE inference whose top-N
    // words fill the whole strip. The SECURITY-CRITICAL password gate (predictionEligibleNow ->
    // PredictionContext.isEligible -> isPasswordField) is checked BEFORE any text is read from the
    // field, so password content is never tokenized or sent to the model.
    private fun updateNextWordPrediction() {
        if (suggestionRightView == null) return

        if (!predictionEligibleNow() || !inputState.isWordEmpty || llamaPredictor == null) {
            clearNextWordPrediction()
            return
        }

        // Safe to read recent text: predictionEligibleNow already excluded password fields.
        val textBefore = readContextWindow() ?: run { clearNextWordPrediction(); return }
        val context = PredictionContext.buildContext(textBefore)
        if (context == null) {
            // No usable context (e.g. a blank field). Nothing to predict; leave the slots blank.
            clearNextWordPrediction()
            return
        }
        // Already showing (or computing) the prediction for this exact context -- don't re-run.
        if (context == lastPredictedContext) return

        schedulePrediction(context)
    }

    // Debounced kick-off: cancels any pending trigger, then after PRED_DEBOUNCE_MS runs the
    // inference on the background executor and applies the result on the UI thread -- but only if
    // it is still the latest request (generation check), so stale results are dropped.
    private fun schedulePrediction(context: String) {
        predictionRunnable?.let { predictionHandler.removeCallbacks(it) }
        lastPredictedContext = context
        val generation = ++predictionGeneration
        val runnable = Runnable {
            if (llamaPredictor == null) return@Runnable
            predictionExecutor.execute {
                val words = try { predictTopWordsFromActiveEngine(context, SLOT_COUNT) } catch (_: Throwable) { emptyList() }
                predictionHandler.post {
                    if (generation != predictionGeneration) return@post // superseded by a newer trigger
                    currentBoundaryWords = words
                    updateSuggestionStrip()
                }
            }
        }
        predictionRunnable = runnable
        predictionHandler.postDelayed(runnable, PRED_DEBOUNCE_MS)
    }

    // Session 59: whichever of the two backends onCreate loaded (exactly one is ever non-null)
    // fills the strip.
    private fun predictTopWordsFromActiveEngine(context: String, n: Int): List<String> =
        llamaPredictor?.predictTopWords(context, n) ?: emptyList()

    // Runs one throwaway inference the first time an eligible field is focused so the first real
    // prediction is warm. The seed is a CONSTANT -- no field text is read -- so this is safe even
    // in a search field and leaks nothing.
    private fun warmUpPredictionEngine() {
        if (predictionWarmedUp) return
        if (llamaPredictor == null) return
        if (!predictionEligibleNow()) return
        predictionWarmedUp = true
        predictionExecutor.execute {
            try { predictTopWordsFromActiveEngine(WARMUP_SEED, 1) } catch (_: Throwable) { }
        }
    }

    // Cancels any pending/in-flight request (the generation bump invalidates a result already on
    // the executor) and clears the right-slot handle. Does NOT clear currentBoundaryWords, so the
    // held words keep showing across keystrokes within a field; field change clears them.
    private fun clearNextWordPrediction() {
        predictionRunnable?.let { predictionHandler.removeCallbacks(it) }
        predictionRunnable = null
        predictionGeneration++
        lastPredictedContext = null
        currentRightWord = null
    }

    // Inserts a predicted [word] plus a trailing space, then refreshes (which predicts the word
    // after it). Shared by all three slots when they hold a prediction.
    private fun commitPredictedWord(word: String) {
        val ic = currentInputConnection ?: return
        pendingRevert = null
        ic.commitText("$word ", 1)
        // A space was inserted, so we are at a fresh word start with an empty buffer.
        inputState.onFieldChange()
        isAtWordStart = true
        wordStartedWithCapital = false
        wordIsAllCaps = false
        pendingAutoSpace = true   // the keyboard inserted this trailing space (Session 43)
        clearNextWordPrediction()
        updateSuggestionStrip()
    }

    // Tap handler for the RIGHT slot: insert its held predicted word. Password fields are blocked
    // again here as defense-in-depth (the slot should already be empty in them).
    private fun acceptNextWordPrediction() {
        if (isPasswordField()) return
        commitPredictedWord(currentRightWord ?: return)
    }

    // Background for the alternates popup: rounded top corners on the chip strip, plus a tab
    // that drops down to bridge the gap to the key that triggered the popup. tabLeftPx places
    // the tab under the held key (which is not always chip 0 — the popup may be shifted left to
    // stay on screen). Tab width = the pressed key's width, so the connector aligns with it.
    private inner class AlternatesCalloutDrawable(
        tabColor: Int,
        private val tabLeftPx: Int,
        private val tabWidthPx: Int,
        private val tabHeightPx: Int,
        private val cornerPx: Float
    ) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = tabColor
            style = Paint.Style.FILL
        }
        private val path = Path()

        fun setTabColor(color: Int) {
            paint.color = color
            invalidateSelf()
        }

        override fun draw(canvas: Canvas) {
            val w = bounds.width().toFloat()
            val h = bounds.height().toFloat()
            val r = cornerPx
            val chipH = h - tabHeightPx
            val tabL = tabLeftPx.toFloat()
            val tabR = tabL + tabWidthPx.toFloat()

            // The strip's free bottom corners (the ones the tab doesn't drop from) get rounded
            // so the whole shape reads as one smooth rounded slab.
            val roundBottomRight = tabR < w
            val roundBottomLeft = tabL > 0f

            path.reset()
            // Chip strip: rounded top corners, full width.
            path.moveTo(r, 0f)
            path.lineTo(w - r, 0f)
            path.arcTo(RectF(w - 2 * r, 0f, w, 2 * r), 270f, 90f)
            // Right edge down to the strip bottom, rounding the bottom-right corner if it's free.
            if (roundBottomRight) {
                path.lineTo(w, chipH - r)
                path.arcTo(RectF(w - 2 * r, chipH - 2 * r, w, chipH), 0f, 90f)
            } else {
                path.lineTo(w, chipH)
            }
            // Strip bottom edge, right portion: in to the tab's right side.
            path.lineTo(tabR, chipH)
            // Tab: right edge down, then rounded bottom-right corner.
            path.lineTo(tabR, h - r)
            path.arcTo(RectF(tabR - 2 * r, h - 2 * r, tabR, h), 0f, 90f)
            // Tab bottom edge, then rounded bottom-left corner.
            path.lineTo(tabL + r, h)
            path.arcTo(RectF(tabL, h - 2 * r, tabL + 2 * r, h), 90f, 90f)
            // Tab left edge back up to the strip bottom.
            path.lineTo(tabL, chipH)
            // Strip bottom edge, left portion, rounding the bottom-left corner if it's free.
            if (roundBottomLeft) {
                path.lineTo(r, chipH)
                path.arcTo(RectF(0f, chipH - 2 * r, 2 * r, chipH), 90f, 90f)
            } else {
                path.lineTo(0f, chipH)
            }
            path.lineTo(0f, r)
            path.arcTo(RectF(0f, 0f, 2 * r, 2 * r), 180f, 90f)
            path.close()

            canvas.drawPath(path, paint)
        }

        override fun setAlpha(alpha: Int) { paint.alpha = alpha; invalidateSelf() }
        override fun setColorFilter(cf: ColorFilter?) { paint.colorFilter = cf; invalidateSelf() }
        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    // Tap handler for the LEFT slot. In State A (buffer empty) it holds a predicted word and is
    // inserted; in State B it holds the user's exact typed word -- tapping keeps it verbatim,
    // bypassing autocorrect (the on-screen text is already what they typed; we just clear the
    // buffer so the next space does not re-apply a correction).
    private fun acceptLeftSlot() {
        if (isPasswordField()) return
        if (inputState.isWordEmpty) {
            // Slice 2: when an offer is showing, the left slot is the "keep what I wrote" option —
            // tapping it just dismisses the offer (the word is already on screen).
            if (pendingHomophoneOffer != null) {
                pendingHomophoneOffer = null
                updateSuggestionStrip()
                return
            }
            commitPredictedWord(currentBoundaryWords.getOrNull(0) ?: return)
            return
        }
        pendingRevert = null
        pendingAutoSpace = false
        wordStartedWithCapital = false
        wordIsAllCaps = false
        isAtWordStart = false
        // Accepting the typed word as-is is a word-finalize: learn it if novel (Session 45), with the
        // same gate/threading as the space-commit path. onWordCommit clears the buffer and returns
        // null when the word was mid-edited (correctionSuppressed) or the field is sensitive.
        val kept = inputState.onWordCommit(isSensitiveField())
        if (kept != null) maybeLearnWord(kept)
        updateSuggestionStrip()
    }

    private fun updateShiftVisuals(view: View) {
        val uppercase = shiftState.isUppercase
        val letterIds = listOf(
            R.id.key_q to "q", R.id.key_w to "w", R.id.key_e to "e",
            R.id.key_r to "r", R.id.key_t to "t", R.id.key_y to "y",
            R.id.key_u to "u", R.id.key_i to "i", R.id.key_o to "o",
            R.id.key_p to "p", R.id.key_a to "a", R.id.key_s to "s",
            R.id.key_d to "d", R.id.key_f to "f", R.id.key_g to "g",
            R.id.key_h to "h", R.id.key_j to "j", R.id.key_k to "k",
            R.id.key_l to "l", R.id.key_z to "z", R.id.key_x to "x",
            R.id.key_c to "c", R.id.key_v to "v", R.id.key_b to "b",
            R.id.key_n to "n", R.id.key_m to "m"
        )
        for ((id, lower) in letterIds) {
            view.findViewById<Button>(id)?.text = if (uppercase) lower.uppercase() else lower
        }
        val shiftButton = view.findViewById<Button>(R.id.key_shift) ?: return
        when (shiftState.state) {
            ShiftState.OFF -> {
                shiftButton.setBackgroundResource(R.drawable.key_background_shift_off)
                shiftButton.foreground = getDrawable(R.drawable.ic_shift_outline)
            }
            ShiftState.ONE_SHOT -> {
                shiftButton.setBackgroundResource(R.drawable.key_background_shift_oneshot)
                shiftButton.foreground = getDrawable(R.drawable.ic_shift_filled)
            }
            ShiftState.CAPS_LOCK -> {
                shiftButton.setBackgroundResource(R.drawable.key_background_shift_capslock)
                shiftButton.foreground = getDrawable(R.drawable.ic_shift_filled)
            }
        }
    }
}
