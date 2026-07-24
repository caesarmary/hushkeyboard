package com.hushkeyboard

import android.content.Context
import java.io.File

/**
 * Phase 4 learned-words dictionary: the storage orchestrator that ties the pure
 * model ([LearnedWords]), the format ([LearnedWordsCodec]), the field policy
 * ([LearnedWordsPolicy]) and the on-device crypto ([LearnedWordsCipher]) together,
 * and owns the file on disk.
 *
 * This class is intentionally thin Android glue (it needs only [Context.getFilesDir]).
 * Every non-trivial decision it makes is delegated to a pure, unit-tested class;
 * what is left here — read a file, write a file, catch failures — is verified
 * on-device by the instrumented test.
 *
 * SECURITY.md rule 5 mapping:
 *   - **Private internal storage:** the file lives in [Context.getFilesDir]
 *     (`/data/data/com.hushkeyboard/files/`), never external or shared.
 *   - **Encrypted at rest:** bytes on disk are always the AES-256-GCM envelope.
 *   - **No password content:** [learn] refuses excluded fields via
 *     [LearnedWordsPolicy] before any word is recorded.
 *   - **One-action clear:** [clear] deletes the file outright.
 *   - **Compile-time disable:** when [enabled] is false (the hardened build), the
 *     store reads/writes nothing AND proactively deletes any pre-existing file on
 *     [load], so flipping a build to hardened erases the dictionary.
 *
 * Disposable cache (a deliberate design decision): the dictionary holds nothing the user
 * cannot regenerate by typing, so ANY load failure — corrupt file, bad GCM tag, a
 * `KeyPermanentlyInvalidatedException` after a device-credential change — is handled
 * by discarding the file (and the now-useless key) and starting empty. Nothing is
 * surfaced to the user; nothing about content is logged (SECURITY.md rule 3).
 *
 * Thread-safety: methods are `@Synchronized` because the keyboard may touch this
 * from the IME thread and a background load thread.
 */
class LearnedWordsStore(
    context: Context,
    private val cipher: LearnedWordsCipher = LearnedWordsCipher(),
    private val enabled: Boolean = BuildConfig.LEARNED_WORDS_ENABLED,
    private val maxWords: Int = LearnedWords.DEFAULT_MAX_WORDS,
) {
    private val filesDir: File = context.filesDir
    private val file = File(filesDir, FILE_NAME)
    private val words = LearnedWords(maxWords)
    private var loaded = false

    /**
     * An immutable snapshot of the dictionary, republished after every change, read
     * by [completions] WITHOUT taking the lock. The suggestion strip queries
     * completions on the IME thread on (potentially) every keystroke; a background
     * [learn] holds this object's monitor while it encrypts and writes to disk, so a
     * `@Synchronized` read could stall the keyboard for that write. Reading a
     * `@Volatile` snapshot instead keeps the read off the lock entirely. Stale by at
     * most one just-learned word, which is harmless for a suggestion.
     */
    @Volatile private var completionIndex: Map<String, Int> = emptyMap()

    /**
     * Load the dictionary from disk into memory. Safe to call repeatedly. On the
     * hardened build ([enabled] == false) this clears memory and deletes any file
     * that a previous standard build may have written. On any decrypt/format
     * failure it discards and starts empty (disposable cache).
     */
    @Synchronized
    fun load() {
        words.clear()
        if (!enabled) {
            deleteFileQuietly()
            completionIndex = emptyMap()
            loaded = true
            return
        }
        if (file.exists()) {
            try {
                val envelope = file.readBytes()
                val plaintext = cipher.decrypt(envelope)
                words.replaceAll(LearnedWordsCodec.deserialize(plaintext))
            } catch (_: Throwable) {
                discardAndReset()
            }
        }
        completionIndex = words.snapshot()
        loaded = true
    }

    /**
     * Record one occurrence of [rawWord] typed in a field of the given [inputType].
     * No-op when the feature is disabled, when the field is excluded
     * ([LearnedWordsPolicy] — the password/sensitive guard), or when the word is
     * not learnable. Persists immediately on a real change.
     *
     * NOTE (Session 35): no caller is wired yet — capture from live typing is a
     * later session, deliberately, so words are only persisted once a consuming
     * feature exists. This method is the entry point that future session will call.
     */
    @Synchronized
    fun learn(rawWord: String, inputType: Int) {
        if (!enabled) return
        if (LearnedWordsPolicy.isFieldExcludedFromLearning(inputType)) return
        if (!loaded) load()
        if (words.learn(rawWord)) {
            save()
            completionIndex = words.snapshot()
        }
    }

    /**
     * Phase 4 Session 36 (consumption): the learned words that complete [prefix],
     * most-frequent first, capped at [limit]. A pure dictionary lookup
     * ([LearnedWords.completionsFor]) over the lock-free [completionIndex] — it
     * never touches the model, the cipher, or the disk, so it is safe to call on
     * the IME thread on every keystroke. Returns empty when the feature is disabled
     * or before the first [load] has published an index.
     */
    fun completions(prefix: String, limit: Int = 3): List<String> {
        if (!enabled) return emptyList()
        return LearnedWords.completionsFor(completionIndex, prefix, limit)
    }

    /**
     * Session-78 learned-words shield: whether [word] is one the user taught the keyboard.
     * Reads the lock-free [completionIndex] (the same @Volatile snapshot [completions] uses), so it
     * is safe to call on the IME thread without blocking on a background [learn]. Used by the
     * autocorrect path to leave a learned word untouched instead of clobbering it to a dictionary
     * neighbour. Returns false when the feature is disabled or before the first [load].
     */
    fun isLearned(word: String): Boolean {
        if (!enabled) return false
        return completionIndex.containsKey(LearnedWords.normalize(word))
    }

    /**
     * The one-action user clear (SECURITY.md rule 5): forget every learned word and
     * delete the file from disk. Also drops the key so nothing recoverable remains.
     */
    @Synchronized
    fun clear() {
        words.clear()
        completionIndex = emptyMap()
        deleteFileQuietly()
        try {
            cipher.deleteKey()
        } catch (_: Throwable) {
            // Key deletion is best-effort; the file (the actual data) is already gone.
        }
    }

    /** A copy of the current learned words (for future prediction/ranking use). */
    @Synchronized
    fun snapshot(): Map<String, Int> {
        if (!loaded) load()
        return words.snapshot()
    }

    private fun save() {
        if (!enabled) return
        try {
            val plaintext = LearnedWordsCodec.serialize(words.snapshot())
            val envelope = cipher.encrypt(plaintext)
            // Atomic-ish write: write a temp file, then rename over the target, so a
            // crash mid-write cannot leave a half-encrypted file in place.
            val tmp = File(filesDir, "$FILE_NAME.tmp")
            tmp.writeBytes(envelope)
            if (!tmp.renameTo(file)) {
                file.writeBytes(envelope)
                tmp.delete()
            }
        } catch (_: Throwable) {
            // Persistence is best-effort: an unwritable cache must never crash the
            // keyboard. Drop any partial file so the next load starts clean.
            deleteFileQuietly()
        }
    }

    private fun discardAndReset() {
        words.clear()
        deleteFileQuietly()
        try {
            cipher.deleteKey()
        } catch (_: Throwable) {
            // ignore — next encrypt regenerates a fresh key
        }
    }

    private fun deleteFileQuietly() {
        try {
            if (file.exists()) file.delete()
        } catch (_: Throwable) {
            // ignore
        }
    }

    companion object {
        const val FILE_NAME = "learned_words.bin"

        @Volatile private var instance: LearnedWordsStore? = null

        /**
         * The single store shared by the keyboard service and the Settings screen
         * within this process, so a [clear] from Settings is immediately visible to
         * the running keyboard's in-memory [completionIndex] (not just the file on
         * disk).
         */
        fun getInstance(context: Context): LearnedWordsStore =
            instance ?: synchronized(this) {
                instance ?: LearnedWordsStore(context.applicationContext).also {
                    it.load()
                    instance = it
                }
            }
    }
}
