package com.danceanon.native.privacy

import kotlin.test.Test
import kotlin.test.assertEquals

class PersonPrivacyModeResolverTest {
    @Test
    fun `legacy selected ids remain full body when face list is absent`() {
        assertEquals(
            mapOf(2 to PersonPrivacyMode.FULL_BODY, 7 to PersonPrivacyMode.FULL_BODY),
            PersonPrivacyModeResolver.resolve(listOf(7, 2), null)
        )
    }

    @Test
    fun `face-only ids become face-only without changing full-body ids`() {
        assertEquals(
            mapOf(
                3 to PersonPrivacyMode.FACE_ONLY,
                9 to PersonPrivacyMode.FACE_ONLY,
                1 to PersonPrivacyMode.FULL_BODY
            ),
            PersonPrivacyModeResolver.resolve(listOf(1), listOf(9, 3))
        )
    }

    @Test
    fun `full body wins when the same id appears in both request lists`() {
        assertEquals(
            mapOf(
                4 to PersonPrivacyMode.FULL_BODY,
                8 to PersonPrivacyMode.FULL_BODY
            ),
            PersonPrivacyModeResolver.resolve(listOf(8, 4), listOf(4))
        )
    }

    @Test
    fun `duplicate request ids collapse deterministically`() {
        assertEquals(
            mapOf(
                5 to PersonPrivacyMode.FACE_ONLY,
                6 to PersonPrivacyMode.FULL_BODY
            ),
            PersonPrivacyModeResolver.resolve(listOf(6, 6), listOf(5, 5))
        )
    }
}
