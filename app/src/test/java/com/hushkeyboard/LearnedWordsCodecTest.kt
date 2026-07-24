package com.hushkeyboard

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for LearnedWordsCodec — the two formats we own and document
 * (SECURITY.md rule 5: "documented and verifiable; no opaque binary blobs").
 *
 *  - serialize / deserialize: the plaintext word-list text that sits INSIDE the
 *    encryption.
 *  - packEnvelope / envelopeIv / envelopeBody: the on-disk byte layout
 *    [12-byte IV][ciphertext+tag]. The actual AES-GCM is on-device (LearnedWordsCipher);
 *    here we test only the pure assemble/split arithmetic.
 *
 * No Android runtime needed — these run on the plain JVM.
 */
class LearnedWordsCodecTest {

    // ---- Plaintext word-list format ----

    @Test
    fun `serialize then deserialize round-trips entries`() {
        val entries = linkedMapOf("hello" to 3, "world" to 1, "don't" to 7)
        val bytes = LearnedWordsCodec.serialize(entries)
        val back = LearnedWordsCodec.deserialize(bytes)
        assertEquals(entries, back)
    }

    @Test
    fun `serialized form starts with the format header`() {
        val bytes = LearnedWordsCodec.serialize(mapOf("hello" to 1))
        val text = String(bytes, Charsets.UTF_8)
        assertTrue(text.startsWith(LearnedWordsCodec.FORMAT_HEADER + "\n"))
    }

    @Test
    fun `serialized form is human-readable plaintext (rule 5 non-opaque)`() {
        val bytes = LearnedWordsCodec.serialize(linkedMapOf("hello" to 2))
        val text = String(bytes, Charsets.UTF_8)
        assertEquals("HUSHLW1\nhello\t2\n", text)
    }

    @Test
    fun `empty dictionary serializes to header only and round-trips`() {
        val bytes = LearnedWordsCodec.serialize(emptyMap())
        assertEquals("HUSHLW1\n", String(bytes, Charsets.UTF_8))
        assertTrue(LearnedWordsCodec.deserialize(bytes).isEmpty())
    }

    @Test
    fun `deserialize rejects a missing or wrong header`() {
        val bad = "NOTHUSH\nhello\t1\n".toByteArray(Charsets.UTF_8)
        assertThrows(LearnedWordsFormatException::class.java) {
            LearnedWordsCodec.deserialize(bad)
        }
    }

    @Test
    fun `deserialize rejects empty input`() {
        assertThrows(LearnedWordsFormatException::class.java) {
            LearnedWordsCodec.deserialize(ByteArray(0))
        }
    }

    @Test
    fun `deserialize skips malformed lines but keeps good ones`() {
        // A line with no tab, a line with a non-integer count, and a blank line are
        // all skipped; the two valid lines survive.
        val text = "HUSHLW1\nhello\t2\nbroken-no-tab\nbad\tNaN\nworld\t5\n\n"
        val back = LearnedWordsCodec.deserialize(text.toByteArray(Charsets.UTF_8))
        assertEquals(linkedMapOf("hello" to 2, "world" to 5), back)
    }

    @Test
    fun `deserialize skips non-positive counts`() {
        val text = "HUSHLW1\nzero\t0\nneg\t-4\ngood\t1\n"
        val back = LearnedWordsCodec.deserialize(text.toByteArray(Charsets.UTF_8))
        assertEquals(linkedMapOf("good" to 1), back)
    }

    // ---- Encryption envelope ----

    @Test
    fun `packEnvelope concatenates IV then body`() {
        val iv = ByteArray(LearnedWordsCodec.IV_LENGTH) { it.toByte() }
        val body = ByteArray(20) { (100 + it).toByte() }
        val env = LearnedWordsCodec.packEnvelope(iv, body)
        assertEquals(iv.size + body.size, env.size)
        assertArrayEquals(iv, env.copyOfRange(0, iv.size))
        assertArrayEquals(body, env.copyOfRange(iv.size, env.size))
    }

    @Test
    fun `envelopeIv and envelopeBody invert packEnvelope`() {
        val iv = ByteArray(LearnedWordsCodec.IV_LENGTH) { (it * 7).toByte() }
        val body = ByteArray(33) { (it * 3 + 1).toByte() }
        val env = LearnedWordsCodec.packEnvelope(iv, body)
        assertArrayEquals(iv, LearnedWordsCodec.envelopeIv(env))
        assertArrayEquals(body, LearnedWordsCodec.envelopeBody(env))
    }

    @Test
    fun `packEnvelope rejects a wrong-size IV`() {
        val badIv = ByteArray(LearnedWordsCodec.IV_LENGTH - 1)
        val body = ByteArray(LearnedWordsCodec.GCM_TAG_LENGTH_BYTES)
        assertThrows(IllegalArgumentException::class.java) {
            LearnedWordsCodec.packEnvelope(badIv, body)
        }
    }

    @Test
    fun `packEnvelope rejects a body shorter than the GCM tag`() {
        val iv = ByteArray(LearnedWordsCodec.IV_LENGTH)
        val tooShort = ByteArray(LearnedWordsCodec.GCM_TAG_LENGTH_BYTES - 1)
        assertThrows(IllegalArgumentException::class.java) {
            LearnedWordsCodec.packEnvelope(iv, tooShort)
        }
    }

    @Test
    fun `envelopeIv rejects a truncated envelope`() {
        val truncated = ByteArray(LearnedWordsCodec.IV_LENGTH) // no room for ciphertext+tag
        assertThrows(LearnedWordsFormatException::class.java) {
            LearnedWordsCodec.envelopeIv(truncated)
        }
    }

    @Test
    fun `envelopeBody rejects a truncated envelope`() {
        val truncated = ByteArray(LearnedWordsCodec.IV_LENGTH + LearnedWordsCodec.GCM_TAG_LENGTH_BYTES - 1)
        assertThrows(LearnedWordsFormatException::class.java) {
            LearnedWordsCodec.envelopeBody(truncated)
        }
    }
}
