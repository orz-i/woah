package com.danceanon.native.service

import android.content.pm.ServiceInfo
import kotlin.test.Test
import kotlin.test.assertEquals

class ExportForegroundServiceTypeTest {

    @Test
    fun `android 14 uses data sync foreground service type`() {
        assertEquals(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            ExportForegroundService.foregroundServiceTypeForSdk(34)
        )
    }

    @Test
    fun `android 15 and newer use media processing foreground service type`() {
        assertEquals(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING,
            ExportForegroundService.foregroundServiceTypeForSdk(35)
        )
        assertEquals(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING,
            ExportForegroundService.foregroundServiceTypeForSdk(36)
        )
    }
}
