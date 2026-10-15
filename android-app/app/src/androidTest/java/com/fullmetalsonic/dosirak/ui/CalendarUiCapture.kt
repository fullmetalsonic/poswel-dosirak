package com.fullmetalsonic.dosirak.ui

import android.graphics.Bitmap
import android.view.Choreographer
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

internal fun ComposeContentTestRule.awaitWorkUiFrames() {
    waitForIdle()
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val drawn = CountDownLatch(1)
    instrumentation.runOnMainSync {
        Choreographer.getInstance().postFrameCallback {
            Choreographer.getInstance().postFrameCallback { drawn.countDown() }
        }
    }
    check(drawn.await(5, TimeUnit.SECONDS)) { "UI frames did not advance" }
    instrumentation.waitForIdleSync()
    waitForIdle()
}

internal fun ComposeContentTestRule.performWorkSave(tag: String) {
    onNodeWithTag(tag).performScrollTo().assertIsEnabled()
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
    var previous: Rect? = null
    var stableFrames = 0
    fun displayed(): Boolean = try { onNodeWithTag(tag).assertIsDisplayed(); true } catch (_: AssertionError) { false }
    while (stableFrames < 3) {
        check(System.nanoTime() < deadline) { "Save button did not remain visible at stable window bounds after scrolling and IME layout" }
        if (!displayed()) {
            onNodeWithTag(tag).performScrollTo()
            previous = null
            stableFrames = 0
        }
        awaitWorkUiFrames()
        val current = onNodeWithTag(tag).fetchSemanticsNode().boundsInWindow
        val visible = displayed() && current.width > 0 && current.height > 0
        stableFrames = if (visible && current == previous) stableFrames + 1 else 0
        previous = if (visible) current else null
    }
    onNodeWithTag(tag).assertIsDisplayed().assertIsEnabled().performClick()
    awaitWorkUiFrames()
}

internal fun ComposeContentTestRule.captureWorkCalendar(name: String, wholeWindow: Boolean = false) {
    val directory = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "calendar-ui-evidence")
    check(directory.exists() || directory.mkdirs())
    awaitWorkUiFrames()
    val bitmap = if (wholeWindow) checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        else onRoot().captureToImage().asAndroidBitmap()
    File(directory, "$name.png").outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
}
