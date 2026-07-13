package com.jadxmp.pipeline.support

import com.jadxmp.input.CodeReader
import com.jadxmp.ir.node.IrClass
import com.jadxmp.ir.node.IrMethod
import com.jadxmp.ir.node.IrRoot
import com.jadxmp.ir.type.IrType
import com.jadxmp.pipeline.PipelineAttrs
import com.jadxmp.pipeline.cfg.CfgBuilder
import com.jadxmp.pipeline.cfg.Dominators
import com.jadxmp.pipeline.decode.MethodCode
import com.jadxmp.pipeline.decode.MethodDecoder
import com.jadxmp.pipeline.ssa.SsaBuilder
import com.jadxmp.pipeline.types.ClassHierarchy
import com.jadxmp.pipeline.types.TypeInference

/** Test scaffolding: assemble a one-method model and drive analysis stages against a [CodeReader]. */
class TestMethod(
    val method: IrMethod,
    val root: IrRoot,
    val code: MethodCode,
) {
    val blocks get() = method.blocks
}

object TestPipeline {

    fun root(vararg classes: IrClass): IrRoot {
        val r = IrRoot()
        for (c in classes) r.addClass(c)
        return r
    }

    fun buildMethod(
        reader: CodeReader,
        className: String = "com.example.Sample",
        methodName: String = "m",
        returnType: IrType = IrType.VOID,
        argTypes: List<IrType> = emptyList(),
        isStatic: Boolean = true,
        superType: IrType? = IrType.OBJECT,
        root: IrRoot = IrRoot(),
    ): IrMethod {
        val cls = IrClass(root, className, accessFlags = 0, superType = superType)
        root.addClass(cls)
        val flags = if (isStatic) IrMethod.ACC_STATIC else 0
        val method = IrMethod(cls, methodName, returnType, argTypes, flags)
        method[PipelineAttrs.CODE_READER] = reader
        method[PipelineAttrs.REGISTER_COUNT] = reader.registerCount
        cls.methods.add(method)
        return method
    }

    /** Decode + build CFG only. */
    fun cfg(method: IrMethod): MethodCode {
        val reader = method[PipelineAttrs.CODE_READER]!!
        val code = MethodDecoder().decode(reader)
        CfgBuilder(method, code).build()
        return code
    }

    /** Decode + CFG + dominators (+ post-dominators). */
    fun dominators(method: IrMethod): MethodCode {
        val code = cfg(method)
        Dominators.compute(method)
        Dominators.computePostDominators(method)
        return code
    }

    /** Through SSA construction. */
    fun ssa(method: IrMethod): MethodCode {
        val code = dominators(method)
        SsaBuilder(method, method[PipelineAttrs.REGISTER_COUNT]!!).build()
        return code
    }

    /** Full analysis through type inference. */
    fun full(method: IrMethod): MethodCode {
        val code = ssa(method)
        TypeInference(method, ClassHierarchy(method.declaringClass.root)).run()
        return code
    }

    /** Full analysis through control-flow structuring (out-of-SSA → shaping → region tree). */
    fun structured(method: IrMethod): MethodCode {
        val code = full(method)
        com.jadxmp.pipeline.structure.OutOfSsa(method).run()
        com.jadxmp.pipeline.structure.ExpressionShaping(method).run()
        com.jadxmp.pipeline.structure.RegionMaker(method).run()
        return code
    }

    /** The block whose (non-φ) instruction has the given original offset. */
    fun blockAt(method: IrMethod, offset: Int) =
        method.blocks.first { b -> b.instructions.any { it.offset == offset } }
}

