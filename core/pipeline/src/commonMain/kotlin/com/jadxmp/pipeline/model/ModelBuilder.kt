package com.jadxmp.pipeline.model

import com.jadxmp.input.ClassData
import com.jadxmp.input.CodeLoader
import com.jadxmp.input.EncodedValue
import com.jadxmp.input.EncodedValueType
import com.jadxmp.input.MethodRef
import com.jadxmp.ir.node.IrClass
import com.jadxmp.ir.node.IrField
import com.jadxmp.ir.node.IrFieldConst
import com.jadxmp.ir.node.IrMethod
import com.jadxmp.ir.node.IrRoot
import com.jadxmp.ir.type.IrType
import com.jadxmp.pipeline.PipelineAttrs

/**
 * Builds the `core:ir` node model ([IrRoot] → [IrClass] → [IrMethod]/[IrField]) from the `core:input`
 * model ([CodeLoader]/[ClassData]) — the "IR build" stage's top half (signatures and structure; the
 * per-method instruction IR is decoded later by the analysis passes).
 *
 * Each method's [com.jadxmp.input.CodeReader] and register count are stashed as attributes so the
 * decode pass can pick them up lazily, keeping method bodies un-decoded until a method is processed.
 */
object ModelBuilder {

    fun build(loader: CodeLoader): IrRoot {
        val root = IrRoot()
        // Two phases: first materialize every class flat (so any outer is resolvable regardless of input
        // order), then wire the nesting tree. Every class stays reachable via [IrRoot.findClass]; only the
        // top-level classes (those with a null [IrClass.outerClass]) are driven for standalone codegen.
        val built = ArrayList<Pair<IrClass, ClassData>>(loader.classes.size)
        for (classData in loader.classes) {
            val cls = buildClass(root, classData)
            root.addClass(cls)
            built.add(cls to classData)
        }
        nestInnerClasses(root, built)
        return root
    }

    /**
     * Move each inner/nested class under its enclosing class ([IrClass.innerClasses]) and set the reverse
     * [IrClass.outerClass] link, so a top-level class is emitted as one unit `class Outer { class Inner … }`
     * instead of a wrong, un-recompilable standalone `Inner.java`. **jadx: RootNode.initInnerClasses**
     *
     * The enclosing class is identified by (in order): the DEX `EnclosingClass`/`EnclosingMethod` system
     * annotations (authoritative — survive `$`-separator obfuscation and pin local/anonymous classes to
     * their method's class), else the `$`-separated binary name. Non-lossy: if the named outer is not in
     * the model (a `$` in a name that is not really an inner class, or a dangling annotation), the class is
     * left top-level rather than dropped. Handles multi-level nesting (`Outer$Inner$Deep`) because the flat
     * map already holds every intermediate class. Anonymous classes (`Outer$1`) are nested as named nested
     * classes for now (full anonymous inlining is a later feature); the point here is to keep them off the
     * top level.
     */
    private fun nestInnerClasses(root: IrRoot, built: List<Pair<IrClass, ClassData>>) {
        for ((cls, data) in built) {
            val outerName = outerClassName(cls.fullName, data) ?: continue
            val outer = root.findClass(outerName) ?: continue // unresolved ⇒ genuinely top-level
            // Defensive: never self-nest, and never form a cycle (a hostile input could make A↔B enclose
            // each other), which would loop forever in the recursive lowering/codegen walk.
            if (outer === cls || enclosedBy(outer, cls)) continue
            outer.innerClasses.add(cls)
            cls.outerClass = outer
        }
    }

    /** True if [candidate] already appears on [cls]'s enclosing-class chain (so nesting would cycle). */
    private fun enclosedBy(cls: IrClass, candidate: IrClass): Boolean {
        var c: IrClass? = cls
        while (c != null) {
            if (c === candidate) return true
            c = c.outerClass
        }
        return false
    }

    private fun outerClassName(fullName: String, data: ClassData): String? =
        annotationOuter(data) ?: nameBasedOuter(fullName)

    /** Enclosing class from the DEX `EnclosingClass`/`EnclosingMethod` system annotations, if present. */
    private fun annotationOuter(data: ClassData): String? {
        for (ann in data.annotations) {
            when (ann.annotationType) {
                ENCLOSING_CLASS_ANNOTATION -> {
                    val v = ann.values["value"] ?: continue
                    if (v.type == EncodedValueType.TYPE) (v.value as? String)?.let { return className(it) }
                }
                ENCLOSING_METHOD_ANNOTATION -> {
                    val v = ann.values["value"] ?: continue
                    if (v.type == EncodedValueType.METHOD) {
                        (v.value as? MethodRef)?.declaringClassType?.let { return className(it) }
                    }
                }
            }
        }
        return null
    }

    /**
     * Enclosing class from the `$`-separated binary name: `com.example.Outer$Inner` → `com.example.Outer`.
     * The `$` must sit inside the simple name and be neither its first nor its last character (matching
     * jadx's `ClassInfo` inner-class rule), so a leading/trailing `$` or a `$` in the package is not treated
     * as a nesting separator.
     */
    private fun nameBasedOuter(fullName: String): String? {
        val lastDot = fullName.lastIndexOf('.')
        val lastDollar = fullName.lastIndexOf('$')
        if (lastDollar <= lastDot + 1) return null // no '$', or '$' is the simple name's first char
        if (lastDollar == fullName.length - 1) return null // trailing '$'
        return fullName.substring(0, lastDollar)
    }

    private fun buildClass(root: IrRoot, data: ClassData): IrClass {
        val superType = data.superType?.let { Descriptors.parseClassType(it) }
        val interfaces = data.interfaces.map { Descriptors.parseClassType(it) }
        val cls = IrClass(
            root = root,
            fullName = className(data.type),
            accessFlags = effectiveAccessFlags(data),
            superType = superType,
            interfaces = interfaces,
        )
        for (f in data.fields) {
            val field = IrField(cls, f.name, Descriptors.parseType(f.type), f.accessFlags)
            field.constValue = toFieldConst(f.constValue)
            cls.fields.add(field)
        }
        for (m in data.methods) {
            val ref = m.ref
            val method = IrMethod(
                declaringClass = cls,
                name = ref.name,
                returnType = Descriptors.parseType(ref.returnType),
                argTypes = ref.parameterTypes.map { Descriptors.parseType(it) },
                accessFlags = m.accessFlags,
            )
            val reader = m.codeReader
            if (reader != null) {
                method[PipelineAttrs.CODE_READER] = reader
                method[PipelineAttrs.REGISTER_COUNT] = reader.registerCount
            }
            cls.methods.add(method)
        }
        return cls
    }

    /**
     * Map a static field's [EncodedValue] (from DEX `static_values`) onto the IR-native [IrFieldConst],
     * using the same Long-bits convention as `LiteralOperand`. Only the compile-time constants that
     * belong at the field declaration are mapped; anything else leaves the value null (no `= …`).
     *
     * Non-lossy caveat: `static_values` can encode TYPE/ENUM/ARRAY/… constants that JVM `ConstantValue`
     * cannot and [IrFieldConst] does not model. Those are deliberately left null — the field then gets
     * no initializer, which HONESTLY fails recompile for an uninitialized `final` rather than emitting a
     * wrong value. TODO(field-const): TYPE/ENUM/ARRAY encoded field constants.
     */
    private fun toFieldConst(value: EncodedValue?): IrFieldConst? {
        if (value == null) return null
        return when (value.type) {
            EncodedValueType.BOOLEAN ->
                IrFieldConst.Primitive(if (value.value == true) 1L else 0L, IrType.BOOLEAN)
            EncodedValueType.BYTE -> IrFieldConst.Primitive((value.value as Byte).toLong(), IrType.BYTE)
            EncodedValueType.SHORT -> IrFieldConst.Primitive((value.value as Short).toLong(), IrType.SHORT)
            EncodedValueType.CHAR -> IrFieldConst.Primitive((value.value as Char).code.toLong(), IrType.CHAR)
            EncodedValueType.INT -> IrFieldConst.Primitive((value.value as Int).toLong(), IrType.INT)
            EncodedValueType.LONG -> IrFieldConst.Primitive(value.value as Long, IrType.LONG)
            // FLOAT/DOUBLE ride as raw IEEE-754 bits, matching LiteralOperand's convention.
            EncodedValueType.FLOAT ->
                IrFieldConst.Primitive((value.value as Float).toRawBits().toLong(), IrType.FLOAT)
            EncodedValueType.DOUBLE ->
                IrFieldConst.Primitive((value.value as Double).toRawBits(), IrType.DOUBLE)
            EncodedValueType.STRING -> IrFieldConst.Str(value.value as String)
            // NULL and the reference/aggregate encodings (TYPE/ENUM/FIELD/METHOD/ARRAY/ANNOTATION/…)
            // are not declaration-site constants we model — leave null (see caveat above).
            else -> null
        }
    }

    /**
     * A nested class's own `class_def` access flags do NOT carry the member-only modifiers
     * (`static`/`private`/`protected`) — those live in the DEX `InnerClass` system annotation (just as they
     * live in a `.class` file's `InnerClasses` attribute, never the nested type's own `access_flags`).
     * Prefer the annotation's flags when present so, e.g., `R$color` recompiles as `static final` inside
     * `R`. **jadx: ClassNode.getAccessFlags via InnerClassesAttr**
     */
    private fun effectiveAccessFlags(data: ClassData): Int {
        for (ann in data.annotations) {
            if (ann.annotationType == INNER_CLASS_ANNOTATION) {
                val v = ann.values["accessFlags"] ?: continue
                if (v.type == EncodedValueType.INT) return v.value as Int
            }
        }
        return data.accessFlags
    }

    private fun className(typeDescriptor: String): String {
        val t = Descriptors.parseClassType(typeDescriptor)
        return (t as? IrType.Object)?.className ?: typeDescriptor
    }

    private const val ENCLOSING_CLASS_ANNOTATION = "Ldalvik/annotation/EnclosingClass;"
    private const val ENCLOSING_METHOD_ANNOTATION = "Ldalvik/annotation/EnclosingMethod;"
    private const val INNER_CLASS_ANNOTATION = "Ldalvik/annotation/InnerClass;"
}
