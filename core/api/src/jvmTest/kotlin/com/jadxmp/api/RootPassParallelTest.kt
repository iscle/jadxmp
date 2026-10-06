package com.jadxmp.api

import com.jadxmp.api.plugin.PassPlugin
import com.jadxmp.api.plugin.PluginRegistry
import com.jadxmp.ir.node.IrRoot
import com.jadxmp.pipeline.pass.PassContext
import com.jadxmp.pipeline.pass.RootPass
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class RootPassParallelTest {
    @Test fun parallelEmptyResultRetainsRootFailureExactlyOnce() = runBlocking {
        val plugin = object : PassPlugin {
            override val id = "root-failure"
            override fun rootPasses() = listOf(object : RootPass {
                override val name = "failure"
                override fun run(root: IrRoot, context: PassContext) = error("root failure")
            })
        }
        val engine = Decompiler(DecompilerArgs(registry = PluginRegistry(emptyList(), listOf(plugin))))
        engine.load("fixture", byteArrayOf())
        repeat(2) { assertEquals(1, engine.decompileAllParallel().errorCount) }
    }
}
