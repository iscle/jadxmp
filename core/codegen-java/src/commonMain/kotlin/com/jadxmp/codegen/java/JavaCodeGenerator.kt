package com.jadxmp.codegen.java

import com.jadxmp.codegen.ClassNodeRef
import com.jadxmp.codegen.CodeInfo
import com.jadxmp.codegen.CodeWriter
import com.jadxmp.codegen.CodegenKeys
import com.jadxmp.codegen.FieldNodeRef
import com.jadxmp.codegen.ImportCollector
import com.jadxmp.codegen.MethodNodeRef
import com.jadxmp.codegen.NameGenerator
import com.jadxmp.ir.attr.AttrFlag
import com.jadxmp.ir.attr.AttrKey
import com.jadxmp.ir.attr.AttrNode
import com.jadxmp.ir.attr.DecompileError
import com.jadxmp.ir.attr.IrAttrs
import com.jadxmp.ir.insn.LiteralOperand
import com.jadxmp.ir.node.IrClass
import com.jadxmp.ir.node.IrField
import com.jadxmp.ir.node.IrFieldConst
import com.jadxmp.ir.node.IrMethod
import com.jadxmp.ir.type.IrType

/**
 * Set on a node whose error is ALREADY shown inline at its source site (an enum constant's arg marker),
 * so [JavaCodeGenerator]'s top-of-node `emitErrorComment` does not ALSO emit a duplicate `// JADXMP
 * ERROR` line. The two-pass generator flags the shared IR node in pass 1 (import collection); pass 2's
 * top comment sees it. Mirrors `core:codegen-kotlin`'s `ERROR_SURFACED_INLINE`.
 */
internal val ERROR_SURFACED_INLINE: AttrKey<Boolean> = AttrKey("java.codegen.errorSurfacedInline")

/**
 * The Java source backend: renders an [IrClass] into `.java` text plus its offset→annotation metadata.
 * **jadx: ClassGen + CodeGen**
 *
 * Rendering is done in two passes over the class so imports (which must appear before the class body)
 * can be decided from the body's usage. The first pass generates into a throwaway writer purely to
 * populate a shared [ImportCollector]; the second pass, with imports known, writes the real file:
 * package, imports, then the (identically generated) class body. Both passes make the same
 * name/variable-id choices, so output and metadata are deterministic.
 */
class JavaCodeGenerator {

    /** Render [cls] to Java source and metadata. */
    fun generate(cls: IrClass): CodeInfo {
        val packageName = cls.fullName.substringBeforeLast('.', "")
        val imports = ImportCollector(packageName)

        // Pass 1: populate imports (output discarded).
        ClassEmitter(CodeWriter(), imports).emitClass(cls, topLevel = true)

        // Pass 2: real output with the header.
        val code = CodeWriter()
        if (packageName.isNotEmpty()) {
            code.add("package ").add(JavaIdentifiers.sanitizeQualified(packageName)).add(";").newLine()
            code.newLine()
        }
        val importList = imports.imports()
        if (importList.isNotEmpty()) {
            for (imp in importList) code.add("import ").add(JavaIdentifiers.sanitizeQualified(imp)).add(";").newLine()
            code.newLine()
        }
        ClassEmitter(code, imports).emitClass(cls, topLevel = true)
        return code.finish()
    }

    companion object {
        /**
         * The emitted Java source name of top-level [cls] (sanitized package + simple name) — the name the
         * output file path (`pkg/Simple.java` / `pkg/Simple.class`) must use so it agrees with the class
         * body this backend renders. `core:api` reads this instead of the binary [IrClass.fullName]; the
         * body itself already routes through the same [JavaSourceName]. See [JavaSourceName].
         */
        fun sourceName(cls: IrClass): String = JavaSourceName.sourceName(cls)
    }

    /** Writes a class declaration, its members, and nested classes into [code]. */
    private class ClassEmitter(
        private val code: CodeWriter,
        private val imports: ImportCollector,
    ) {
        private val types = JavaTypeRenderer(imports)

        fun emitClass(cls: IrClass, topLevel: Boolean) {
            emitErrorComment(cls)
            // An ACC_ENUM class that reconstructs cleanly renders as a Java `enum` (constants first, the
            // synthetic `$VALUES`/`values()`/`valueOf` hidden, its enum-construction `<clinit>`
            // suppressed); null ⇒ not a confidently-reconstructable enum, emit as an ordinary class.
            val enumInfo = EnumReconstruction.analyze(cls)

            var flags = enumInfo?.fixedClassFlags ?: cls.accessFlags
            // Interfaces/annotations are implicitly abstract; don't spell it out (matches Java style).
            if (JavaModifiers.has(flags, JavaModifiers.INTERFACE) || JavaModifiers.has(flags, JavaModifiers.ANNOTATION)) {
                flags = flags and JavaModifiers.ABSTRACT.inv()
            }
            // A top-level class (a nested class decompiled standalone still emits at file scope) may not
            // carry member-only access modifiers — `private`/`protected`/`static` are illegal there.
            if (topLevel) {
                flags = flags and (JavaModifiers.PRIVATE or JavaModifiers.PROTECTED or JavaModifiers.STATIC).inv()
            }
            code.add(JavaModifiers.forClass(flags))
            code.add(classKeyword(cls.accessFlags)).add(" ")
            code.attachDefinition(ClassNodeRef(cls.fullName))
            // The emitted simple name is the single source of truth shared with the output file path
            // (JavaSourceName): a top-level class is disambiguated so its body name equals its file name.
            code.add(JavaSourceName.sourceSimpleName(cls))
            // A Java `enum` implicitly extends java.lang.Enum — never spell the `extends` out.
            if (enumInfo == null) emitExtends(cls)
            emitImplements(cls)
            code.add(" {").newLine()
            code.incIndent()

            if (enumInfo != null) emitEnumMembers(cls, enumInfo) else emitClassMembers(cls)

            code.decIndent()
            code.attachNodeEnd()
            code.add("}").newLine()
        }

        private fun emitClassMembers(cls: IrClass) {
            var wroteMember = false
            for (field in cls.fields) {
                if (field.contains(AttrFlag.DONT_GENERATE)) continue
                emitField(cls, field)
                wroteMember = true
            }
            for (method in cls.methods) {
                if (method.contains(AttrFlag.DONT_GENERATE)) continue
                if (wroteMember) code.newLine()
                emitMethod(cls, method)
                wroteMember = true
            }
            for (inner in cls.innerClasses) {
                if (inner.contains(AttrFlag.DONT_GENERATE)) continue
                if (wroteMember) code.newLine()
                emitClass(inner, topLevel = false)
                wroteMember = true
            }
        }

        // ---------- enum members ----------

        private fun emitEnumMembers(cls: IrClass, e: EnumReconstruction) {
            val hasMore = enumHasMembersAfterConstants(cls, e)
            emitEnumConstants(cls, e, hasMore)
            var wrote = e.constants.isNotEmpty() || (e.constants.isEmpty() && hasMore)

            for (field in cls.fields) {
                if (field.contains(AttrFlag.DONT_GENERATE)) continue
                if (field in e.enumConstantFields || field in e.hiddenFields) continue
                emitField(cls, field)
                wrote = true
            }
            for (method in cls.methods) {
                if (method.contains(AttrFlag.DONT_GENERATE)) continue
                if (method in e.hiddenMethods) continue
                if (method.name == "<clinit>") {
                    if (e.dropClinit) continue
                    if (wrote) code.newLine()
                    emitEnumClinit(cls, method, e)
                    wrote = true
                    continue
                }
                if (method.name == "<init>") {
                    if (EnumReconstruction.isDefaultEnumConstructor(method)) continue
                    if (wrote) code.newLine()
                    emitEnumConstructor(cls, method)
                    wrote = true
                    continue
                }
                if (wrote) code.newLine()
                emitMethod(cls, method)
                wrote = true
            }
            for (inner in cls.innerClasses) {
                if (inner.contains(AttrFlag.DONT_GENERATE)) continue
                if (wrote) code.newLine()
                emitClass(inner, topLevel = false)
                wrote = true
            }
        }

        /** Whether anything follows the constant list — decides if the entries end with `;`. */
        private fun enumHasMembersAfterConstants(cls: IrClass, e: EnumReconstruction): Boolean {
            val hasField = cls.fields.any {
                !it.contains(AttrFlag.DONT_GENERATE) && it !in e.enumConstantFields && it !in e.hiddenFields
            }
            if (hasField) return true
            val hasMethod = cls.methods.any { m ->
                if (m.contains(AttrFlag.DONT_GENERATE) || m in e.hiddenMethods) return@any false
                when (m.name) {
                    "<clinit>" -> !e.dropClinit
                    "<init>" -> !EnumReconstruction.isDefaultEnumConstructor(m)
                    else -> true
                }
            }
            if (hasMethod) return true
            return cls.innerClasses.any { !it.contains(AttrFlag.DONT_GENERATE) }
        }

        private fun emitEnumConstants(cls: IrClass, e: EnumReconstruction, hasMore: Boolean) {
            if (e.constants.isEmpty()) {
                // An empty constant list still needs a `;` to separate it from any following members.
                if (hasMore) code.add(";").newLine()
                return
            }
            for ((i, c) in e.constants.withIndex()) {
                code.attachDefinition(FieldNodeRef(cls.fullName, c.field.name))
                code.add(JavaMemberAliases.aliasOf(c.field))
                if (c.args.isNotEmpty()) emitEnumConstantArgs(cls, e, c)
                when {
                    i < e.constants.lastIndex -> code.add(",")
                    hasMore -> code.add(";")
                }
                code.newLine()
            }
        }

        private fun emitEnumConstantArgs(cls: IrClass, e: EnumReconstruction, c: EnumReconstruction.EnumConstant) {
            val clinit = e.clinit
            val ok = clinit != null &&
                MethodBodyWriter(code, imports, clinit, NameGenerator(), emptyList(), e.suppressedClinitInsns)
                    .emitEnumConstantArgs(c.args, c.argParamTypes)
            if (!ok) {
                // Args reference values we cannot inline to a self-contained expression (a NEW_ARRAY
                // filled by separate puts, or a reference to another enum constant) — be honest with an
                // inline marker rather than emit an empty array / a fabricated `new EnumType(...)` /
                // an undefined reference (CLAUDE rule 4).
                flagError(cls, "enum constant '${c.field.name}' constructor arguments not reconstructed", surfacedInline = true)
                code.add(" /* JADXMP ERROR: enum constant arguments not reconstructed */")
            }
        }

        private fun emitEnumClinit(cls: IrClass, method: IrMethod, e: EnumReconstruction) {
            emitErrorComment(method)
            code.add("static ")
            code.add("{").newLine()
            code.incIndent()
            MethodBodyWriter(
                code, imports, method, NameGenerator(), emptyList(),
                e.suppressedClinitInsns, e.constantResultFields,
            ).writeBody()
            code.decIndent()
            code.attachNodeEnd()
            code.add("}").newLine()
        }

        /**
         * An enum constructor with the synthetic leading `name`/`ordinal` params stripped and its
         * `super(name, ordinal)` call suppressed (jadx: markArgsForSkip + super removal). Visibility
         * modifiers are dropped — an `enum` constructor is implicitly private.
         */
        private fun emitEnumConstructor(cls: IrClass, method: IrMethod) {
            emitErrorComment(method)
            code.attachDefinition(MethodNodeRef(cls.fullName, method.name, method.argTypes.map { it.toString() }))
            code.add(JavaSourceName.sourceSimpleName(cls))

            val methodNames = NameGenerator()
            val paramNames = resolveParamNames(method, methodNames)
            val skip = EnumReconstruction.syntheticArgCount(method.argTypes.size)
            code.add("(")
            var emitted = 0
            for (i in skip until method.argTypes.size) {
                if (emitted > 0) code.add(", ")
                code.add(types.render(method.argTypes[i])).add(" ").add(paramNames[i])
                emitted++
            }
            code.add(")")
            emitThrows(method)
            code.add(" ")
            val superCall = EnumReconstruction.enumSuperCall(method)
            val suppressed = if (superCall != null) setOf(superCall) else emptySet()
            code.add("{").newLine()
            code.incIndent()
            MethodBodyWriter(code, imports, method, methodNames, paramNames, suppressed).writeBody()
            code.decIndent()
            code.attachNodeEnd()
            code.add("}").newLine()
        }

        private fun emitExtends(cls: IrClass) {
            val superType = cls.superType ?: return
            if (superType == IrType.OBJECT) return
            if (JavaModifiers.has(cls.accessFlags, JavaModifiers.INTERFACE)) return
            code.add(" extends ")
            emitTypeName(superType)
        }

        private fun emitImplements(cls: IrClass) {
            if (cls.interfaces.isEmpty()) return
            val keyword = if (JavaModifiers.has(cls.accessFlags, JavaModifiers.INTERFACE)) " extends " else " implements "
            code.add(keyword)
            for ((i, itf) in cls.interfaces.withIndex()) {
                if (i > 0) code.add(", ")
                emitTypeName(itf)
            }
        }

        private fun emitField(cls: IrClass, field: IrField) {
            code.add(JavaModifiers.forField(field.accessFlags))
            code.add(types.render(field.type)).add(" ")
            code.attachDefinition(FieldNodeRef(cls.fullName, field.name))
            // The metadata ref above keeps the binary name (jump-to-def identity); the emitted text uses
            // the scope-unique alias so a name duplicated with another field is disambiguated.
            code.add(JavaMemberAliases.aliasOf(field))
            // A `static final` field's compile-time constant is emitted as a declaration initializer
            // (so `static final int X;` doesn't fail as "might not have been initialized").
            field.constValue?.let { code.add(" = ").add(renderFieldConst(it)) }
            code.add(";").newLine()
        }

        private fun renderFieldConst(const: IrFieldConst): String = when (const) {
            is IrFieldConst.Primitive -> JavaLiterals.format(LiteralOperand(const.bits, const.type))
            is IrFieldConst.Str -> JavaLiterals.stringLiteral(const.value)
        }

        private fun emitMethod(cls: IrClass, method: IrMethod) {
            emitErrorComment(method)
            // Static initializer: `static { ... }`, no signature.
            if (method.name == "<clinit>") {
                code.add("static ")
                emitBodyOrSemicolon(method, forceBody = true)
                return
            }
            code.add(JavaModifiers.forMethod(method.accessFlags))

            val isConstructor = method.name == "<init>"
            if (!isConstructor) {
                code.add(types.render(method.returnType)).add(" ")
            }
            code.attachDefinition(MethodNodeRef(cls.fullName, method.name, method.argTypes.map { it.toString() }))
            // Constructor name must equal the emitted class name (same JavaSourceName source of truth);
            // other method names use the scope-unique alias so two methods colliding on name+params differ.
            code.add(if (isConstructor) JavaSourceName.sourceSimpleName(cls) else JavaMemberAliases.aliasOf(method))

            val methodNames = NameGenerator()
            val paramNames = resolveParamNames(method, methodNames)
            code.add("(")
            for (i in method.argTypes.indices) {
                if (i > 0) code.add(", ")
                code.add(types.render(method.argTypes[i])).add(" ").add(paramNames[i])
            }
            code.add(")")
            emitThrows(method)

            val abstractOrNative = JavaModifiers.has(method.accessFlags, JavaModifiers.ABSTRACT) ||
                JavaModifiers.has(method.accessFlags, JavaModifiers.NATIVE)
            if (abstractOrNative) {
                // Body-less method: still emit a NodeEnd so nodeAt's brace-nesting stays balanced (the
                // METHOD definition otherwise leaks as an enclosing scope for everything after it).
                code.attachNodeEnd()
                code.add(";").newLine()
                return
            }
            code.add(" ")
            emitBody(method, methodNames, paramNames)
        }

        private fun emitThrows(method: IrMethod) {
            val throws = method[CodegenKeys.THROWS]
            if (throws.isNullOrEmpty()) return
            code.add(" throws ")
            for ((i, t) in throws.withIndex()) {
                if (i > 0) code.add(", ")
                emitTypeName(t)
            }
        }

        private fun emitBodyOrSemicolon(method: IrMethod, forceBody: Boolean) {
            if (!forceBody &&
                (
                    JavaModifiers.has(method.accessFlags, JavaModifiers.ABSTRACT) ||
                        JavaModifiers.has(method.accessFlags, JavaModifiers.NATIVE)
                    )
            ) {
                code.attachNodeEnd()
                code.add(";").newLine()
                return
            }
            emitBody(method, NameGenerator(), emptyList())
        }

        private fun emitBody(method: IrMethod, methodNames: NameGenerator, paramNames: List<String>) {
            code.add("{").newLine()
            code.incIndent()
            MethodBodyWriter(code, imports, method, methodNames, paramNames).writeBody()
            code.decIndent()
            code.attachNodeEnd()
            code.add("}").newLine()
        }

        private fun resolveParamNames(method: IrMethod, names: NameGenerator): List<String> {
            val provided = method[CodegenKeys.PARAM_NAMES]
            return method.argTypes.mapIndexed { i, type ->
                val supplied = provided?.getOrNull(i)
                if (supplied != null) names.reserve(JavaIdentifiers.sanitize(supplied)) else names.forType(type)
            }
        }

        private fun emitTypeName(type: IrType) {
            (type as? IrType.Object)?.let { code.attachReference(ClassNodeRef(it.className)) }
            code.add(types.render(type))
        }

        /**
         * Surface a fault-isolated node error as a source comment so the "no-error" accuracy signal
         * (and a reader) sees it rather than a silently-partial body. The marker text (`JADXMP ERROR`)
         * is the clean-room equivalent of jadx's `JADX ERROR` sentinel.
         */
        private fun emitErrorComment(node: AttrNode) {
            if (!node.contains(AttrFlag.HAS_ERROR)) return
            // Error already shown inline at its exact site (e.g. an enum constant's arg marker) — a top
            // comment would duplicate it. Two-pass generation makes the flag set in pass 1 visible here.
            if (node[ERROR_SURFACED_INLINE] == true) return
            val message = node[IrAttrs.ERROR]?.message?.replace('\n', ' ')?.replace('\r', ' ')
                ?: "decompilation failed"
            code.add("// JADXMP ERROR: ").add(message).newLine()
        }

        /**
         * Flag [owner] HAS_ERROR (with [reason]) so the accuracy signal (`reportedErrors`/`countErrors`)
         * counts the honesty marker just emitted — a marker without the flag would silently undercount
         * (CLAUDE rule 4). Idempotent; never clobbers a pre-existing, more-specific diagnostic. When
         * [surfacedInline] the marker is already shown at its site, so [emitErrorComment] won't repeat it.
         */
        private fun flagError(owner: AttrNode, reason: String, surfacedInline: Boolean = false) {
            if (!owner.contains(IrAttrs.ERROR)) owner[IrAttrs.ERROR] = DecompileError(reason)
            owner.add(AttrFlag.HAS_ERROR)
            if (surfacedInline) owner[ERROR_SURFACED_INLINE] = true
        }

        private fun classKeyword(flags: Int): String = when {
            JavaModifiers.has(flags, JavaModifiers.ANNOTATION) -> "@interface"
            JavaModifiers.has(flags, JavaModifiers.INTERFACE) -> "interface"
            JavaModifiers.has(flags, JavaModifiers.ENUM) -> "enum"
            else -> "class"
        }
    }
}
