package com.danceanon.native.bridge

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DanceNativeExceptionTest {

    @Test
    fun testExceptionMessageIncludesCodeAndMessage() {
        val ex = DanceNativeException("TEST_CODE", "hello")
        assertEquals("[TEST_CODE] hello", ex.message)
        assertTrue(ex.message!!.contains("TEST_CODE"))
        assertTrue(ex.message!!.contains("hello"))
        assertEquals("TEST_CODE", ex.code)
    }

    @Test
    fun testExceptionWithCause() {
        val cause = IllegalArgumentException("underlying root cause")
        val ex = DanceNativeException("MODEL_INIT_FAILED", "Failed to load model", cause)
        assertEquals("[MODEL_INIT_FAILED] Failed to load model", ex.message)
        assertEquals(cause, ex.cause)
    }
}
