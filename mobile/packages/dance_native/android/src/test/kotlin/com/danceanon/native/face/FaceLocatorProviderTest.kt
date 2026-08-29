package com.danceanon.native.face

import android.content.Context
import org.mockito.Mockito.mock
import kotlin.test.Test
import kotlin.test.assertNull

class FaceLocatorProviderTest {
    @Test
    fun `face locator is disabled unless caller explicitly opts in`() {
        val context = mock(Context::class.java)
        assertNull(FaceLocatorProvider.createOrNull(context))
        assertNull(FaceLocatorProvider.createOrNull(context, enabled = false))
    }
}
