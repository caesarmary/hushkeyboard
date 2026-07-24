package com.hushkeyboard

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Session 35 on-device verification for the learned-words crypto + storage. The
 * AES-256-GCM AndroidKeyStore path ([LearnedWordsCipher]) and the file I/O in
 * [LearnedWordsStore] cannot run on a plain JVM, so per Gate 8 Option 1 they are
 * verified here on real hardware. The format/model logic is covered separately by
 * the JUnit unit tests.
 *
 * Covers: encrypt/decrypt round-trip, the documented envelope shape, fresh-IV-
 * per-write, the store round-trip through a real encrypted file, the disposable-
 * cache recovery on a corrupt file, the one-action clear, and the
 * compile-time-disabled behaviour.
 */
@RunWith(AndroidJUnit4::class)
class LearnedWordsStoreDeviceTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun dictFile() = File(context.filesDir, LearnedWordsStore.FILE_NAME)

    // Normal vs password input types (values from android.text.InputType).
    private val normalField = 0x00000001
    private val passwordField = 0x00000001 or 0x00000080

    @Before
    fun clean() {
        // Start every test from a known-empty state (file gone, key dropped).
        LearnedWordsStore(context).clear()
    }

    @After
    fun tearDown() {
        LearnedWordsStore(context).clear()
    }

    // ---- LearnedWordsCipher: the on-device AES-256-GCM ----

    @Test
    fun cipher_encryptThenDecrypt_roundTrips() {
        val cipher = LearnedWordsCipher()
        val plaintext = "HUSHLW1\nhello\t3\n".toByteArray(Charsets.UTF_8)
        try {
            val envelope = cipher.encrypt(plaintext)
            assertArrayEquals(plaintext, cipher.decrypt(envelope))
        } finally {
            cipher.deleteKey()
        }
    }

    @Test
    fun cipher_envelope_hasDocumentedShape() {
        val cipher = LearnedWordsCipher()
        try {
            val envelope = cipher.encrypt("hi".toByteArray(Charsets.UTF_8))
            // [12-byte IV][ciphertext][16-byte tag] => IV is exactly 12 bytes and the
            // body is at least the 16-byte tag.
            val iv = LearnedWordsCodec.envelopeIv(envelope)
            val body = LearnedWordsCodec.envelopeBody(envelope)
            assertEquals(LearnedWordsCodec.IV_LENGTH, iv.size)
            assertTrue(body.size >= LearnedWordsCodec.GCM_TAG_LENGTH_BYTES)
        } finally {
            cipher.deleteKey()
        }
    }

    @Test
    fun cipher_usesFreshIvPerWrite() {
        val cipher = LearnedWordsCipher()
        val plaintext = "same plaintext".toByteArray(Charsets.UTF_8)
        try {
            val a = LearnedWordsCodec.envelopeIv(cipher.encrypt(plaintext))
            val b = LearnedWordsCodec.envelopeIv(cipher.encrypt(plaintext))
            // Two encryptions of identical plaintext must use different IVs (the
            // randomized-encryption guarantee that prevents GCM IV reuse).
            assertFalse("IV must not be reused across writes", a.contentEquals(b))
        } finally {
            cipher.deleteKey()
        }
    }

    // ---- LearnedWordsStore: file persistence ----

    @Test
    fun store_persistsAndReloadsAcrossInstances() {
        val store = LearnedWordsStore(context, enabled = true)
        store.learn("hello", normalField)
        store.learn("hello", normalField)
        store.learn("world", normalField)

        // The bytes on disk must be the encrypted envelope, never plaintext.
        assertTrue("dictionary file should exist after learning", dictFile().exists())
        val onDisk = dictFile().readBytes()
        assertFalse(
            "on-disk bytes must be encrypted, not the plaintext header",
            String(onDisk, Charsets.UTF_8).contains(LearnedWordsCodec.FORMAT_HEADER)
        )

        // A fresh store instance reads the same data back.
        val reopened = LearnedWordsStore(context, enabled = true)
        reopened.load()
        val snap = reopened.snapshot()
        assertEquals(2, snap["hello"])
        assertEquals(1, snap["world"])
    }

    @Test
    fun store_doesNotLearnFromPasswordField() {
        val store = LearnedWordsStore(context, enabled = true)
        store.learn("secret", passwordField)
        assertFalse("password-field word must never be persisted", dictFile().exists())
        assertTrue(store.snapshot().isEmpty())
    }

    @Test
    fun store_corruptFile_isDiscardedAndRebuilds() {
        val store = LearnedWordsStore(context, enabled = true)
        store.learn("hello", normalField)
        assertTrue(dictFile().exists())

        // Simulate corruption / a key change: overwrite with garbage.
        dictFile().writeBytes(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14))

        val reopened = LearnedWordsStore(context, enabled = true)
        reopened.load()
        assertTrue("corrupt file must yield an empty dictionary", reopened.snapshot().isEmpty())

        // And the store remains usable afterwards (a fresh key is generated).
        reopened.learn("again", normalField)
        assertEquals(1, reopened.snapshot()["again"])
    }

    @Test
    fun store_clear_deletesTheFile() {
        val store = LearnedWordsStore(context, enabled = true)
        store.learn("hello", normalField)
        assertTrue(dictFile().exists())

        store.clear()
        assertFalse("clear must delete the dictionary file", dictFile().exists())
        assertTrue(store.snapshot().isEmpty())
    }

    // ---- Compile-time disable (hardened build behaviour) ----

    @Test
    fun store_whenDisabled_neverWritesAndErasesExistingFile() {
        // First write a file with an enabled store.
        LearnedWordsStore(context, enabled = true).learn("hello", normalField)
        assertTrue(dictFile().exists())

        // A disabled store must learn nothing and must erase the pre-existing file
        // on load (a build flipped to hardened removes the dictionary).
        val disabled = LearnedWordsStore(context, enabled = false)
        disabled.load()
        assertFalse("disabled store must erase any existing dictionary", dictFile().exists())

        disabled.learn("world", normalField)
        assertFalse("disabled store must never write", dictFile().exists())
        assertTrue(disabled.snapshot().isEmpty())
    }
}
