package com.surya.jarvis

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UiAutomatorSmokeTest {
    @Test fun deviceIsReachable() {
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        check(device.displayWidth > 0 && device.displayHeight > 0)
    }
}
