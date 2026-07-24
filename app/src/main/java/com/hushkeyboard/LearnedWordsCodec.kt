package com.hushkeyboard

import java.io.ByteArrayOutputStream

/**
 * Phase 4 learned-words dictionary: the two PURE, JVM-testable formats we own and
 * document ourselves. No Android-framework imports (Gate 8 Option 1) — so both
 * formats are fully exercised by plain JUnit tests.
 *
 * SECURITY.md rule 5 requires a "documented and verifiable" dictionary format with
 * "no opaque binary blobs". This file is that documentation, in code:
 *
 *   1. The PLAINTEXT word-list format ([serialize] / [deserialize]) — a small,
 *      human-readable, line-based UTF-8 text we define. This is what sits INSIDE
 *      the encryption; if you decrypted the file by hand you would see exactly
 *      this text.
 *
 *   2. The ENCRYPTION ENVELOPE ([packEnvelope] / [envelopeIv] / [envelopeBody]) —
 *      the on-disk byte layout `[12-byte IV][ciphertext][16-byte GCM tag]`. This
 *      class only assembles and splits the envelope; the actual AES-256-GCM
 *      encryption (which needs the AndroidKeyStore key) lives in the thin
 *      [LearnedWordsCipher] wrapper. Splitting the IV off the front is pure byte
 *      arithmetic, so it is tested here without a device.
 *
 * Note: with `AES/GCM/NoPadding`, the JCE `Cipher.doFinal` already APPENDS the
 * 16-byte authentication tag to the ciphertext. So "ciphertext + tag" is one
 * blob — the [envelopeBody] returned here is exactly what the cipher consumes /
 * produces; the tag is its last 16 bytes.
 */
object LearnedWordsCodec {

    // ---- 1. Plaintext word-list format ----------------------------------------

    /**
     * Format marker + version on the first line. If a future version changes the
     * layout we bump this; an unrecognized header makes [deserialize] throw, which
     * the store treats as "corrupt → discard and rebuild" (the dictionary is a
     * disposable cache, so a format change costs nothing but a relearn).
     */
    const val FORMAT_HEADER = "HUSHLW1"

    private const val NEWLINE = '\n'
    private const val TAB = '\t'

    /**
     * Serialize [entries] (word → frequency count) to the plaintext byte form.
     * Layout, UTF-8:
     *
     *     HUSHLW1\n
     *     <word>\t<count>\n
     *     <word>\t<count>\n
     *     ...
     *
     * Words are guaranteed tab/newline-free by [LearnedWords.isLearnable] at the
     * point they are learned, so the line format is unambiguous. Insertion order
     * is preserved for determinism (helps testing); order carries no meaning.
     */
    fun serialize(entries: Map<String, Int>): ByteArray {
        val sb = StringBuilder()
        sb.append(FORMAT_HEADER).append(NEWLINE)
        for ((word, count) in entries) {
            sb.append(word).append(TAB).append(count).append(NEWLINE)
        }
        return sb.toString().toByteArray(Charsets.UTF_8)
    }

    /**
     * Parse plaintext bytes produced by [serialize] back into a word → count map.
     *
     * Defensive by design: the first line MUST be [FORMAT_HEADER] or this throws
     * [LearnedWordsFormatException] (the store then discards and rebuilds). Past
     * the header, any malformed line (no tab, non-integer count, empty word) is
     * SKIPPED rather than throwing — a single bad line should not cost the whole
     * dictionary. Duplicate words: the last occurrence wins.
     */
    fun deserialize(bytes: ByteArray): LinkedHashMap<String, Int> {
        val text = String(bytes, Charsets.UTF_8)
        val lines = text.split(NEWLINE)
        if (lines.isEmpty() || lines[0] != FORMAT_HEADER) {
            throw LearnedWordsFormatException("unrecognized learned-words header")
        }
        val out = LinkedHashMap<String, Int>()
        for (i in 1 until lines.size) {
            val line = lines[i]
            if (line.isEmpty()) continue // trailing newline yields a final empty element
            val tab = line.indexOf(TAB)
            if (tab <= 0 || tab == line.length - 1) continue // no word or no count
            val word = line.substring(0, tab)
            val count = line.substring(tab + 1).toIntOrNull() ?: continue
            if (count <= 0) continue
            out[word] = count
        }
        return out
    }

    // ---- 2. Encryption envelope -----------------------------------------------

    /** AES-GCM standard nonce length. 12 bytes is the recommended GCM IV size. */
    const val IV_LENGTH = 12

    /** AES-GCM authentication tag length: 128 bits = 16 bytes, appended to the ciphertext. */
    const val GCM_TAG_LENGTH_BYTES = 16

    /** Smallest possible valid envelope: IV + an empty ciphertext that is still tag-protected. */
    private const val MIN_ENVELOPE_LENGTH = IV_LENGTH + GCM_TAG_LENGTH_BYTES

    /**
     * Assemble the on-disk envelope `[12-byte IV][ciphertext+tag]`. [iv] must be
     * exactly [IV_LENGTH] bytes; [body] is the cipher's `doFinal` output (ciphertext
     * with the 16-byte tag already appended) and must be at least
     * [GCM_TAG_LENGTH_BYTES] long.
     */
    fun packEnvelope(iv: ByteArray, body: ByteArray): ByteArray {
        require(iv.size == IV_LENGTH) { "IV must be $IV_LENGTH bytes, was ${iv.size}" }
        require(body.size >= GCM_TAG_LENGTH_BYTES) {
            "cipher body must be >= $GCM_TAG_LENGTH_BYTES bytes (the GCM tag), was ${body.size}"
        }
        val out = ByteArrayOutputStream(iv.size + body.size)
        out.write(iv)
        out.write(body)
        return out.toByteArray()
    }

    /**
     * Extract the 12-byte IV from the front of an [envelope]. Throws
     * [LearnedWordsFormatException] if the envelope is too short to be valid —
     * which the store treats as "corrupt → discard and rebuild".
     */
    fun envelopeIv(envelope: ByteArray): ByteArray {
        requireValidEnvelope(envelope)
        return envelope.copyOfRange(0, IV_LENGTH)
    }

    /**
     * Extract the ciphertext+tag body (everything after the IV) from an [envelope],
     * i.e. exactly what the cipher's `doFinal` must consume to decrypt. Throws
     * [LearnedWordsFormatException] on a too-short envelope.
     */
    fun envelopeBody(envelope: ByteArray): ByteArray {
        requireValidEnvelope(envelope)
        return envelope.copyOfRange(IV_LENGTH, envelope.size)
    }

    private fun requireValidEnvelope(envelope: ByteArray) {
        if (envelope.size < MIN_ENVELOPE_LENGTH) {
            throw LearnedWordsFormatException(
                "envelope too short: ${envelope.size} < $MIN_ENVELOPE_LENGTH bytes"
            )
        }
    }
}

/**
 * Thrown when learned-words data on disk cannot be parsed (bad header, truncated
 * envelope). It is NOT an error condition to surface to the user: the store
 * catches it and rebuilds the dictionary from empty, because the dictionary is a
 * disposable cache (a deliberate design decision).
 */
class LearnedWordsFormatException(message: String) : Exception(message)
