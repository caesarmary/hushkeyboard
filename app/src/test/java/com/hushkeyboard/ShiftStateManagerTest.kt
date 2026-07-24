package com.hushkeyboard

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ShiftStateManagerTest {

    private lateinit var sm: ShiftStateManager

    @Before
    fun setUp() {
        sm = ShiftStateManager()
    }

    // --- Initial state ---

    @Test
    fun `initial state is OFF`() {
        assertEquals(ShiftState.OFF, sm.state)
        assertFalse(sm.isUppercase)
    }

    // --- Single-tap transitions ---

    @Test
    fun `OFF plus single tap goes to ONE_SHOT`() {
        sm.onShiftActivate(capsLock = false)
        assertEquals(ShiftState.ONE_SHOT, sm.state)
        assertTrue(sm.isUppercase)
    }

    @Test
    fun `ONE_SHOT plus single tap cancels back to OFF`() {
        sm.onShiftActivate(capsLock = false)
        sm.onShiftActivate(capsLock = false)
        assertEquals(ShiftState.OFF, sm.state)
        assertFalse(sm.isUppercase)
    }

    @Test
    fun `CAPS_LOCK plus single tap goes to OFF`() {
        sm.onShiftActivate(capsLock = true)
        sm.onShiftActivate(capsLock = false)
        assertEquals(ShiftState.OFF, sm.state)
    }

    // --- Double-tap / long-press (capsLock=true) transitions ---

    @Test
    fun `OFF plus capsLock activation goes to CAPS_LOCK`() {
        sm.onShiftActivate(capsLock = true)
        assertEquals(ShiftState.CAPS_LOCK, sm.state)
        assertTrue(sm.isUppercase)
    }

    @Test
    fun `ONE_SHOT plus capsLock activation goes to CAPS_LOCK`() {
        sm.onShiftActivate(capsLock = false)
        sm.onShiftActivate(capsLock = true)
        assertEquals(ShiftState.CAPS_LOCK, sm.state)
    }

    @Test
    fun `CAPS_LOCK plus capsLock activation also goes to CAPS_LOCK`() {
        sm.onShiftActivate(capsLock = true)
        sm.onShiftActivate(capsLock = true)
        assertEquals(ShiftState.CAPS_LOCK, sm.state)
    }

    // --- onLetterTyped return value ---

    @Test
    fun `onLetterTyped returns false in OFF state`() {
        assertFalse(sm.onLetterTyped())
    }

    @Test
    fun `onLetterTyped returns true in ONE_SHOT state`() {
        sm.onShiftActivate(capsLock = false)
        assertTrue(sm.onLetterTyped())
    }

    @Test
    fun `onLetterTyped returns true in CAPS_LOCK state`() {
        sm.onShiftActivate(capsLock = true)
        assertTrue(sm.onLetterTyped())
    }

    // --- ONE_SHOT auto-reset ---

    @Test
    fun `ONE_SHOT resets to OFF after a letter is typed`() {
        sm.onShiftActivate(capsLock = false)
        sm.onLetterTyped()
        assertEquals(ShiftState.OFF, sm.state)
        assertFalse(sm.isUppercase)
    }

    // --- CAPS_LOCK persistence ---

    @Test
    fun `CAPS_LOCK stays on after multiple letters typed`() {
        sm.onShiftActivate(capsLock = true)
        repeat(5) {
            assertTrue(sm.onLetterTyped())
        }
        assertEquals(ShiftState.CAPS_LOCK, sm.state)
    }

    // --- onFieldChange ---

    @Test
    fun `onFieldChange resets ONE_SHOT to OFF`() {
        sm.onShiftActivate(capsLock = false)
        sm.onFieldChange()
        assertEquals(ShiftState.OFF, sm.state)
    }

    @Test
    fun `onFieldChange resets CAPS_LOCK to OFF`() {
        sm.onShiftActivate(capsLock = true)
        sm.onFieldChange()
        assertEquals(ShiftState.OFF, sm.state)
    }

    // --- applyAutoCaps (auto-capitalization from getCursorCapsMode) ---

    @Test
    fun `applyAutoCaps with CAP_SENTENCES arms ONE_SHOT from OFF`() {
        sm.applyAutoCaps(ShiftStateManager.CAP_SENTENCES)
        assertEquals(ShiftState.ONE_SHOT, sm.state)
    }

    @Test
    fun `applyAutoCaps with CAP_WORDS arms ONE_SHOT from OFF`() {
        sm.applyAutoCaps(ShiftStateManager.CAP_WORDS)
        assertEquals(ShiftState.ONE_SHOT, sm.state)
    }

    @Test
    fun `applyAutoCaps with CAP_CHARACTERS arms CAPS_LOCK from OFF`() {
        sm.applyAutoCaps(ShiftStateManager.CAP_CHARACTERS)
        assertEquals(ShiftState.CAPS_LOCK, sm.state)
    }

    @Test
    fun `applyAutoCaps with zero leaves state OFF`() {
        sm.applyAutoCaps(0)
        assertEquals(ShiftState.OFF, sm.state)
    }

    @Test
    fun `applyAutoCaps does not override a user ONE_SHOT`() {
        sm.onShiftActivate(capsLock = false)
        // Field reports no caps, but the user already armed shift by hand — keep it.
        sm.applyAutoCaps(0)
        assertEquals(ShiftState.ONE_SHOT, sm.state)
    }

    @Test
    fun `applyAutoCaps does not override a user CAPS_LOCK`() {
        sm.onShiftActivate(capsLock = true)
        sm.applyAutoCaps(ShiftStateManager.CAP_SENTENCES)
        assertEquals(ShiftState.CAPS_LOCK, sm.state)
    }

    @Test
    fun `applyAutoCaps treats CAP_CHARACTERS as dominant when bits are combined`() {
        // getCursorCapsMode can OR several bits; CHARACTERS (caps everything) wins.
        sm.applyAutoCaps(ShiftStateManager.CAP_CHARACTERS or ShiftStateManager.CAP_SENTENCES)
        assertEquals(ShiftState.CAPS_LOCK, sm.state)
    }

    @Test
    fun `applyAutoCaps one-shot is consumed by a typed letter`() {
        sm.applyAutoCaps(ShiftStateManager.CAP_SENTENCES)
        assertTrue(sm.onLetterTyped())          // first letter capitalized
        assertEquals(ShiftState.OFF, sm.state)  // then released
        assertFalse(sm.onLetterTyped())         // subsequent letters lowercase
    }
}
