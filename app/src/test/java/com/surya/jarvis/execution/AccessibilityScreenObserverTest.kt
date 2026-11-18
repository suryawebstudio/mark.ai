package com.surya.jarvis.execution

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessibilityScreenObserverTest {
    @Test
    fun parsesSuccessfulAccessibilitySnapshot() {
        val payload = """
            {
              "success": true,
              "snapshot_id": "a1abc123",
              "package": "com.example.app",
              "class": "ExampleActivity",
              "elements": [
                {
                  "role": "Button",
                  "text": "Continue",
                  "description": "Continue setup",
                  "name": "Continue",
                  "visible": true,
                  "enabled": true,
                  "clickable": true,
                  "editable": false,
                  "scrollable": false,
                  "states": ["enabled"],
                  "ref": "@a1abc123:e1",
                  "bounds": {"left": 10, "top": 20, "right": 110, "bottom": 70}
                }
              ]
            }
        """.trimIndent()

        val screen = AccessibilityScreenObserver().parse(payload, 1234L)

        assertNotNull(screen)
        assertEquals("a1abc123", screen?.snapshotId)
        assertEquals("com.example.app", screen?.packageName)
        assertEquals(1234L, screen?.timestamp)
        assertEquals(1, screen?.elements?.size)
        val element = screen!!.elements.single()
        assertEquals("@a1abc123:e1", element.ref)
        assertEquals("Continue", element.text)
        assertEquals(100, element.bounds.width)
        assertTrue(element.actionable)
    }

    @Test
    fun rejectsFailedAccessibilitySnapshot() {
        val screen = AccessibilityScreenObserver().parse(
            "{\"success\":false,\"message\":\"Accessibility is disabled\"}",
            1234L
        )

        assertEquals(null, screen)
    }
}
