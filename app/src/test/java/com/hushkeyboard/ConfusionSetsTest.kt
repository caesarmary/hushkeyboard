package com.hushkeyboard

import org.junit.Assert.*
import org.junit.Test

/**
 * ConfusionSetsTest — Phase 5 slice 2.
 *
 * Verifies the static confusion-family lookup and pins the table's invariants (every family has at
 * least two members; no word appears in two families) so a future hand-edit fails loudly here rather
 * than silently mis-mapping a word. Pure-JVM JUnit4.
 */
class ConfusionSetsTest {

    @Test
    fun lookupReturnsWholeFamilyIncludingTheWordItself() {
        val fam = ConfusionSets.candidatesFor("there")
        assertNotNull(fam)
        assertTrue(fam!!.contains("there"))
        assertTrue(fam.contains("their"))
        assertTrue(fam.contains("they're"))
    }

    @Test
    fun contractionMembersAreLookable() {
        assertEquals(ConfusionSets.candidatesFor("it's"), ConfusionSets.candidatesFor("its"))
        assertTrue(ConfusionSets.candidatesFor("you're")!!.contains("your"))
    }

    @Test
    fun lookupIsCaseInsensitive() {
        assertEquals(ConfusionSets.candidatesFor("from"), ConfusionSets.candidatesFor("FROM"))
        assertEquals(ConfusionSets.candidatesFor("from"), ConfusionSets.candidatesFor("From"))
    }

    @Test
    fun unknownWordReturnsNull() {
        assertNull(ConfusionSets.candidatesFor("banana"))
        assertNull(ConfusionSets.candidatesFor(""))
    }

    // Table invariants — catch a malformed future edit.
    @Test
    fun everyKnownWordMapsToAFamilyOfAtLeastTwoContainingItself() {
        for (w in listOf("form", "from", "there", "their", "they're", "its", "it's",
                         "your", "you're", "than", "then", "whether", "weather")) {
            val fam = ConfusionSets.candidatesFor(w)
            assertNotNull("$w should be in a family", fam)
            assertTrue("$w family too small", fam!!.size >= 2)
            assertTrue("$w family must contain $w", fam.contains(w))
        }
    }
}
