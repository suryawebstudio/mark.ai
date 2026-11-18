package com.surya.jarvis.execution

import com.surya.jarvis.accessibility.UnifiedScreen

/**
 * Supplies a point-in-time screen model to planning and verification code.
 * Implementations must observe only; they must not perform UI actions.
 */
fun interface ScreenObserver {
    fun inspect(): UnifiedScreen?
}
