package com.jadxmp.ui.client

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for the platform-agnostic drop bridge. The shells (desktop `dragAndDropTarget`, web DOM
 * `drop`) only build an [OpenRequest] and call [FileDropController.dropFile]; the workbench collects
 * [FileDropController.drops] and routes each through the same `openProject` a picked file uses. These
 * cover the wasm-safe common half — the platform drop wiring itself is exercised at runtime.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FileDropControllerTest {

    @Test
    fun droppedFilesReachCollectorsInOrder() = runTest {
        val controller = FileDropController()
        val a = OpenRequest("a.apk", byteArrayOf(1))
        val b = OpenRequest("b.dex", byteArrayOf(2))

        // Subscribe first (the workbench installs its collector at composition, before any drop).
        val received = ArrayList<OpenRequest>()
        val job = launch {
            controller.drops.take(2).toList(received)
        }
        // Let the collector subscribe before emitting.
        kotlinx.coroutines.yield()

        controller.dropFile(a)
        controller.dropFile(b)
        job.join()

        assertEquals(listOf("a.apk", "b.dex"), received.map { it.name })
    }

    @Test
    fun dragActiveReflectsTheShellToggle() {
        val controller = FileDropController()
        assertFalse(controller.dragActive.value, "starts inactive")

        controller.setDragActive(true)
        assertTrue(controller.dragActive.value, "a drag entering the window arms the highlight")

        controller.setDragActive(false)
        assertFalse(controller.dragActive.value, "leaving / dropping clears it")
    }
}
