package com.hushkeyboard

import org.junit.Assert.*
import org.junit.Test

class AlternateCharMapTest {

    @Test
    fun `a has alternates`() {
        val alternates = AlternateCharMap.getAlternates('a')
        assertNotNull(alternates)
        assertTrue(alternates!!.isNotEmpty())
    }

    @Test
    fun `a alternates contain expected characters`() {
        val alternates = AlternateCharMap.getAlternates('a')!!
        assertTrue("missing à", alternates.contains("à"))
        assertTrue("missing á", alternates.contains("á"))
        assertTrue("missing ä", alternates.contains("ä"))
    }

    @Test
    fun `a alternates are in expected order`() {
        val alternates = AlternateCharMap.getAlternates('a')!!
        assertEquals("à", alternates[0])
        assertEquals("á", alternates[1])
        assertEquals("â", alternates[2])
        assertEquals("ä", alternates[3])
        assertEquals("æ", alternates[4])
        assertEquals("ã", alternates[5])
        assertEquals("å", alternates[6])
    }

    @Test
    fun `b has no alternates`() {
        assertNull(AlternateCharMap.getAlternates('b'))
        assertFalse(AlternateCharMap.hasAlternates('b'))
    }

    @Test
    fun `e has alternates and contains é`() {
        val alternates = AlternateCharMap.getAlternates('e')
        assertNotNull(alternates)
        assertTrue(alternates!!.contains("é"))
    }

    @Test
    fun `uppercase input maps the same as lowercase`() {
        assertEquals(AlternateCharMap.getAlternates('a'), AlternateCharMap.getAlternates('A'))
        assertEquals(AlternateCharMap.getAlternates('e'), AlternateCharMap.getAlternates('E'))
        assertNull(AlternateCharMap.getAlternates('B'))
        assertNull(AlternateCharMap.getAlternates('b'))
    }

    @Test
    fun `all 11 mapped letters return non-empty lists`() {
        val mappedLetters = listOf('a', 'c', 'e', 'i', 'l', 'n', 'o', 's', 'u', 'y', 'z')
        for (letter in mappedLetters) {
            val alternates = AlternateCharMap.getAlternates(letter)
            assertNotNull("expected alternates for '$letter' but got null", alternates)
            assertTrue("expected non-empty list for '$letter'", alternates!!.isNotEmpty())
        }
    }

    @Test
    fun `unmapped letters return null`() {
        for (letter in listOf('b', 'd', 'f', 'm', 'r', 't')) {
            assertNull("expected null for '$letter'", AlternateCharMap.getAlternates(letter))
        }
    }

    @Test
    fun `hasAlternates returns true for mapped letters`() {
        val mappedLetters = listOf('a', 'c', 'e', 'i', 'l', 'n', 'o', 's', 'u', 'y', 'z')
        for (letter in mappedLetters) {
            assertTrue("expected hasAlternates=true for '$letter'", AlternateCharMap.hasAlternates(letter))
        }
    }

    @Test
    fun `hasAlternates returns false for unmapped letters`() {
        for (letter in listOf('b', 'd', 'f', 'm', 'r', 't')) {
            assertFalse("expected hasAlternates=false for '$letter'", AlternateCharMap.hasAlternates(letter))
        }
    }

    // -------------------------------------------------------------------------
    // Punctuation alternates (Session 32) — long-press popup behind the period key
    // -------------------------------------------------------------------------

    @Test
    fun `period has punctuation alternates`() {
        val alts = AlternateCharMap.getPunctuationAlternates('.')
        assertNotNull(alts)
        assertTrue(alts!!.isNotEmpty())
        assertTrue(AlternateCharMap.hasPunctuationAlternates('.'))
    }

    @Test
    fun `period punctuation alternates are the expected set in order`() {
        assertEquals(listOf("?", "!", ",", ":", ";", "-"), AlternateCharMap.getPunctuationAlternates('.'))
    }

    @Test
    fun `period punctuation alternates do not include the base mark`() {
        // The popup machinery adds the base '.' as the pre-selected chip; it must not be duplicated
        // in the data, or the base would appear twice in the strip.
        assertFalse(AlternateCharMap.getPunctuationAlternates('.')!!.contains("."))
    }

    @Test
    fun `comma has punctuation alternates`() {
        val alts = AlternateCharMap.getPunctuationAlternates(',')
        assertNotNull(alts)
        assertTrue(alts!!.isNotEmpty())
        assertTrue(AlternateCharMap.hasPunctuationAlternates(','))
    }

    @Test
    fun `comma punctuation alternates are the expected set in order`() {
        assertEquals(listOf("'", "\"", ";", ":"), AlternateCharMap.getPunctuationAlternates(','))
    }

    @Test
    fun `comma punctuation alternates do not include the base mark`() {
        assertFalse(AlternateCharMap.getPunctuationAlternates(',')!!.contains(","))
    }

    @Test
    fun `letters have no punctuation alternates`() {
        for (c in listOf('a', 'e', 'z', 'q')) {
            assertNull("expected null punctuation alternates for '$c'", AlternateCharMap.getPunctuationAlternates(c))
            assertFalse(AlternateCharMap.hasPunctuationAlternates(c))
        }
    }

    @Test
    fun `letter and punctuation maps are independent`() {
        // '.' has punctuation alternates but no letter alternates; 'a' is the reverse.
        assertNull(AlternateCharMap.getAlternates('.'))
        assertNull(AlternateCharMap.getPunctuationAlternates('a'))
    }
}
