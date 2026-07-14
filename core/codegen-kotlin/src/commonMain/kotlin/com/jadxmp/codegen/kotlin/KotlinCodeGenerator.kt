package com.jadxmp.codegen.kotlin

import com.jadxmp.codegen.ClassNodeRef
import com.jadxmp.codegen.CodeInfo
import com.jadxmp.codegen.CodeWriter
import com.jadxmp.codegen.FieldNodeRef
import com.jadxmp.codegen.ImportCollector
import com.jadxmp.codegen.MethodNodeRef
import com.jadxmp.codegen.NameGenerator
import com.jadxmp.codegen.CodegenKeys
import com.jadxmp.ir.attr.AttrFlag
import com.jadxmp.ir.attr.AttrNode
import com.jadxmp.ir.attr.IrAttrs
import com.jadxmp.ir.insn.FieldInstruction
import com.jadxmp.ir.insn.Instruction
import com.jadxmp.ir.insn.IrOpcode
import com.jadxmp.ir.insn.LiteralOperand
import com.jadxmp.ir.node.BasicBlock
import com.jadxmp.ir.node.IrClass
import com.jadxmp.ir.node.IrField
import com.jadxmp.ir.node.IrFieldConst
import com.jadxmp.ir.node.IrMethod
import com.jadxmp.ir.region.SequenceRegion
import com.jadxmp.ir.type.IrType

/**
 * The Kotlin source backend: renders an [IrClass] into `.kt` text plus its offset→annotation metadata.
 * **jadx: ClassGen + CodeGen (Kotlin projection)**
 *
 * It shares the region-tree walk shape with the Java backend and the same two-pass import strategy
 * (pass 1 populates a shared [ImportCollector]; pass 2 emits the header then the identically-generated
 * body), but every *leaf* differs: Kotlin keywords, `val`/`var` properties, `companion object` for
 * static members, `fun` signatures with the return type after the parameters, `Unit` returns omitted,
 * and no semicolons.
 *
 * ### First-pass scope
 * The goal is **valid, semantics-preserving Kotlin**, not maximally idiomatic Kotlin. Covered:
 * class/interface/`object`/`enum class`/`annotation class`; Kotlin visibility & modality (`open`
 * default-final); `val`/`var`/`const val` properties; `fun`/`constructor`/`init`; the region tree →
 * `if`/`when`/`while`/`do`/`for`/`try`/`synchronized`; expressions with Kotlin operators and `T(args)`
 * construction, `as`/`is`, `.inv()`, named infix bitwise ops; the honesty markers (`// JADXMP ERROR`,
 * `/* OPCODE */`).
 *
 * ### Deferred (documented TODOs — not attempted here)
 * coroutine state-machine → `suspend`; lambda/SAM reconstruction; exhaustive
 * null-safety (every reference is emitted non-null `T`); getter/setter → property fusion; extension
 * functions; and a **kotlinc-recompile oracle** (the Kotlin analogue of the javac recompile signal) so
 * this backend's accuracy can be measured against the corpus — currently there is none.
 */
class KotlinCodeGenerator {

    /** Render [cls] to Kotlin source and metadata. */
    fun generate(cls: IrClass): CodeInfo {
        val packageName = cls.fullName.substringBeforeLast('.', "")
        val imports = ImportCollector(packageName)

        // Pass 1: populate imports (output discarded).
        ClassEmitter(CodeWriter(), imports).emitClass(cls, topLevel = true)

        // Pass 2: real output with the header.
        val code = CodeWriter()
        if (packageName.isNotEmpty()) {
            code.add("package ").add(KotlinIdentifiers.sanitizeQualified(packageName)).newLine()
            code.newLine()
        }
        val importList = imports.imports()
        if (importList.isNotEmpty()) {
            for (imp in importList) code.add("import ").add(KotlinIdentifiers.sanitizeQualified(imp)).newLine()
            code.newLine()
        }
        ClassEmitter(code, imports).emitClass(cls, topLevel = true)
        return code.finish()
    }

    /** The Kotlin declaration keyword a class maps to. */
    private enum class ClassKind { CLASS, INTERFACE, OBJECT, ENUM, ANNOTATION }

    /** Writes a class declaration, its members, and nested classes into [code]. */
    private class ClassEmitter(
        private val code: CodeWriter,
        private val imports: ImportCollector,
    ) {
        private val types = KotlinTypeRenderer(imports)

        fun emitClass(cls: IrClass, topLevel: Boolean) {
            emitErrorComment(cls)
            val kind = classKind(cls)
            // A confirmed Kotlin `data class`: its canonical-constructor properties move into the header
            // and the compiler-only members (canonical ctor, componentN, copy/copy$default) are dropped.
            // equals/hashCode/toString are KEPT in the body — a user may override them (see
            // isGeneratedDataMember) and signature alone can't distinguish that from the default.
            val dataShape = if (kind == ClassKind.CLASS) detectDataClass(cls) else null
            emitDeclarationHeader(cls, kind, topLevel, dataShape)
            code.add(" {").newLine()
            code.incIndent()

            val isObject = kind == ClassKind.OBJECT
            val isEnum = kind == ClassKind.ENUM
            // A confidently-reconstructable enum: its synthetic `$VALUES`/`values()`/`valueOf` are hidden
            // and its `<clinit>` enum-construction is suppressed (so the entry `val`s are never reassigned).
            // Non-null only for the conservative subset (all default ctors ⇒ argument-less entries).
            val enumInfo = if (isEnum) KotlinEnumReconstruction.analyze(cls) else null

            // ---- partition members ----
            val instanceFields = ArrayList<IrField>()
            val staticFields = ArrayList<IrField>()
            val enumConstants = ArrayList<IrField>()
            for (f in cls.fields) {
                if (f.contains(AttrFlag.DONT_GENERATE)) continue
                // Properties reconstructed into the data-class primary constructor are not repeated here.
                if (dataShape != null && dataShape.properties.any { it === f }) continue
                if (isObject && f.name == "INSTANCE") continue // the singleton self-reference is implicit
                if (enumInfo != null && f in enumInfo.hiddenFields) continue // synthetic `$VALUES` backing array
                if (isEnum && isEnumConstant(f)) {
                    enumConstants.add(f)
                    continue
                }
                if (isEnum && f.name == "\$VALUES") continue // synthetic enum backing array
                when {
                    isObject -> instanceFields.add(f) // object members are not static in Kotlin
                    KotlinModifiers.has(f.accessFlags, KotlinModifiers.STATIC) -> staticFields.add(f)
                    else -> instanceFields.add(f)
                }
            }

            val instanceMethods = ArrayList<IrMethod>()
            val staticMethods = ArrayList<IrMethod>()
            var clinit: IrMethod? = null
            val objectInits = ArrayList<IrMethod>()
            for (m in cls.methods) {
                if (m.contains(AttrFlag.DONT_GENERATE)) continue
                // Compiler-generated data-class members are regenerated by `data class`, so drop them.
                if (dataShape != null && isGeneratedDataMember(cls, m, dataShape)) continue
                if (isEnum && isSyntheticEnumMethod(m)) continue // compiler-synthesized values()/valueOf(String)
                if (enumInfo != null && m in enumInfo.hiddenMethods) continue // synthetic values()/valueOf/$values
                // A default enum constructor (only the synthetic name/ordinal + super) is implicit in a
                // Kotlin `enum class` — never emit it as a `constructor(name, ordinal)`.
                if (enumInfo != null && m.name == "<init>" && KotlinEnumReconstruction.isDefaultEnumConstructor(m)) {
                    continue
                }
                if (m.name == "<clinit>") {
                    if (isObject) objectInits.add(m) else clinit = m
                    continue
                }
                when {
                    isObject && m.name == "<init>" -> objectInits.add(m) // object's ctor folds into init
                    isObject -> instanceMethods.add(m)
                    KotlinModifiers.has(m.accessFlags, KotlinModifiers.STATIC) -> staticMethods.add(m)
                    else -> instanceMethods.add(m)
                }
            }

            // The enum-construction insns the `<clinit>` residual must not re-emit (they became entries).
            val enumSuppressed = enumInfo?.suppressedClinitInsns ?: emptySet()
            // The `<clinit>` drives a companion only when its residual (after enum suppression) is not
            // empty. A reconstructed enum whose `<clinit>` is pure construction drops it entirely — no
            // companion `init {}` reassigning the entry `val`s. (Static-final field inlining can only
            // FURTHER empty the residual, and only when static fields exist — which independently force a
            // companion — so this pre-inline check never wrongly emits an empty companion.)
            val clinitDrivesCompanion = clinit != null && methodHasBody(clinit) &&
                !KotlinEnumReconstruction.residualIsEmpty(clinit, enumSuppressed)
            val hasCompanion = !isObject &&
                (staticFields.isNotEmpty() || staticMethods.isNotEmpty() || clinitDrivesCompanion)
            val hasMembersAfterEntries = instanceFields.isNotEmpty() || instanceMethods.isNotEmpty() ||
                objectInits.isNotEmpty() || cls.innerClasses.any { !it.contains(AttrFlag.DONT_GENERATE) } ||
                hasCompanion

            // ---- emit members ----
            var wrote = false
            if (enumConstants.isNotEmpty()) {
                emitEnumEntries(cls, enumConstants, hasMembersAfterEntries)
                wrote = true
            }
            for (f in instanceFields) {
                emitProperty(cls, f)
                wrote = true
            }
            for (m in objectInits) {
                if (!methodHasBody(m)) continue
                if (wrote) code.newLine()
                emitInitBlock(cls, m)
                wrote = true
            }
            for (m in instanceMethods) {
                if (wrote) code.newLine()
                emitMethod(cls, m, kind)
                wrote = true
            }
            for (inner in cls.innerClasses) {
                if (inner.contains(AttrFlag.DONT_GENERATE)) continue
                if (wrote) code.newLine()
                emitClass(inner, topLevel = false)
                wrote = true
            }
            if (hasCompanion) {
                if (wrote) code.newLine()
                emitCompanion(cls, staticFields, clinit, staticMethods, enumSuppressed)
                wrote = true
            }

            code.decIndent()
            code.attachNodeEnd()
            code.add("}").newLine()
        }

        // ---------- declaration header ----------

        private fun emitDeclarationHeader(cls: IrClass, kind: ClassKind, topLevel: Boolean, dataShape: DataClassShape?) {
            code.add(classVisibility(cls.accessFlags, topLevel))
            // Interfaces/annotations/enums/objects carry their own modality; only a plain class gets
            // open/abstract from the flags (Kotlin classes are final by default). A data class is final
            // by construction, so it never needs `open`/`abstract`.
            code.add(
                KotlinModifiers.classModality(
                    cls.accessFlags,
                    suppressModality = kind != ClassKind.CLASS || dataShape != null,
                ),
            )
            if (dataShape != null) code.add("data ")
            code.add(keywordFor(kind))
            code.attachDefinition(ClassNodeRef(cls.fullName))
            code.add(KotlinIdentifiers.sanitize(cls.shortName))
            if (dataShape != null) emitPrimaryConstructor(cls, dataShape)
            emitSupertypes(cls, kind)
        }

        /**
         * Emit the reconstructed data-class primary constructor: `(val a: A, var b: B)`. Each property
         * is `val` when its backing field is final, `var` otherwise. Visibility is intentionally left at
         * Kotlin's `public` default: a data-class property's backing field is always `private` in
         * bytecode, so the field flags do NOT carry the property's real visibility (that lives on the
         * getter, which getter/setter fusion — still deferred — would recover). Emitting `public`
         * matches the overwhelmingly common case and never narrows a public property to `private`.
         */
        private fun emitPrimaryConstructor(cls: IrClass, shape: DataClassShape) {
            code.attachDefinition(methodRef(cls, shape.constructor))
            code.add("(")
            for ((i, field) in shape.properties.withIndex()) {
                if (i > 0) code.add(", ")
                val isFinal = KotlinModifiers.has(field.accessFlags, KotlinModifiers.FINAL)
                code.add(if (isFinal) "val " else "var ")
                code.attachDefinition(FieldNodeRef(cls.fullName, field.name))
                code.add(KotlinIdentifiers.sanitize(field.name))
                code.add(": ")
                emitTypeName(field.type)
            }
            code.add(")")
        }

        private fun classVisibility(flags: Int, topLevel: Boolean): String {
            // `protected` is illegal on a top-level declaration; drop it (→ public default) there.
            if (topLevel && KotlinModifiers.has(flags, KotlinModifiers.PROTECTED)) return ""
            return KotlinModifiers.visibility(flags)
        }

        private fun keywordFor(kind: ClassKind): String = when (kind) {
            ClassKind.CLASS -> "class "
            ClassKind.INTERFACE -> "interface "
            ClassKind.OBJECT -> "object "
            ClassKind.ENUM -> "enum class "
            ClassKind.ANNOTATION -> "annotation class "
        }

        private fun classKind(cls: IrClass): ClassKind = when {
            KotlinModifiers.has(cls.accessFlags, KotlinModifiers.ANNOTATION) -> ClassKind.ANNOTATION
            KotlinModifiers.has(cls.accessFlags, KotlinModifiers.INTERFACE) -> ClassKind.INTERFACE
            KotlinModifiers.has(cls.accessFlags, KotlinModifiers.ENUM) -> ClassKind.ENUM
            isObjectSingleton(cls) -> ClassKind.OBJECT
            else -> ClassKind.CLASS
        }

        /**
         * A Kotlin `object` compiles to a `final` class with a `public static final <Self> INSTANCE`
         * field and a **private, no-arg** constructor. We require BOTH signals: the INSTANCE field AND
         * that every declared constructor is private and parameterless. A class with any parameterized
         * or non-private constructor is a normal singleton-style class, not a Kotlin `object` — rendering
         * it `object` would fold its real `<init>(args)` into an `init {}` and silently drop the
         * parameters (the M3 bug). When in doubt, fall back to a normal class.
         */
        private fun isObjectSingleton(cls: IrClass): Boolean {
            val hasInstance = cls.fields.any { f ->
                f.name == "INSTANCE" &&
                    KotlinModifiers.has(f.accessFlags, KotlinModifiers.STATIC) &&
                    KotlinModifiers.has(f.accessFlags, KotlinModifiers.FINAL) &&
                    (f.type as? IrType.Object)?.className == cls.fullName
            }
            if (!hasInstance) return false
            // Any constructor present must be the object shape: private and no-arg.
            for (m in cls.methods) {
                if (m.name != "<init>") continue
                if (m.argTypes.isNotEmpty()) return false
                if (!KotlinModifiers.has(m.accessFlags, KotlinModifiers.PRIVATE)) return false
            }
            return true
        }

        /**
         * A real enum constant carries the `ACC_ENUM` field flag. Gating on that flag (not on a
         * "type equals the enum" heuristic) is what stops a plain alias like
         * `static final Color DEFAULT = RED;` from being mistaken for an entry and losing its
         * initializer (the S1 bug) — such a field falls through to normal property handling instead.
         */
        private fun isEnumConstant(field: IrField): Boolean =
            KotlinModifiers.has(field.accessFlags, KotlinModifiers.ENUM) &&
                KotlinModifiers.has(field.accessFlags, KotlinModifiers.STATIC) &&
                KotlinModifiers.has(field.accessFlags, KotlinModifiers.FINAL)

        /**
         * The compiler-synthesized enum members `values()` / `valueOf(String)` (dropped for an
         * `enum class`, which regenerates them). Matched by exact signature so a user-declared overload
         * such as `valueOf(int)` is NOT dropped.
         */
        private fun isSyntheticEnumMethod(m: IrMethod): Boolean =
            (m.name == "values" && m.argTypes.isEmpty()) ||
                (m.name == "valueOf" && m.argTypes.size == 1 &&
                    (m.argTypes[0] as? IrType.Object)?.className == "java.lang.String")

        // ---------- data class detection ----------

        /** A confirmed Kotlin `data class`: its canonical constructor and the [properties] it declares. */
        private class DataClassShape(val constructor: IrMethod, val properties: List<IrField>)

        /**
         * Recognise the JVM shape of a Kotlin `data class X(val a: A, var b: B)` **conservatively**, and
         * return its canonical constructor + property list — or `null` (⇒ emit a plain class) if any
         * signal is missing or ambiguous. Rule 4: never a half-reconstructed, silently-wrong data class.
         *
         * All of the following must hold:
         *  1. A **final, non-abstract** class (a data class is always final).
         *  2. ≥1 instance (non-static) field — the candidate properties, in declaration order.
         *  3. **Exactly one** constructor, whose parameters map 1:1 by *order and type* to the
         *     properties. A secondary constructor (or an arity/type mismatch) ⇒ fall back: we can't
         *     represent it in the header cleanly, so we don't guess.
         *  4. **`component1()`…`componentK()`** present — exactly `K` of them (K = property count), each
         *     no-arg and returning the matching property type, none beyond `K`. This arity match is the
         *     strongest signal (a data class always has exactly `component1..componentK`), so a regular
         *     class is very unlikely to be misread as data.
         *  5. The generated value members `equals(Any?): Boolean`, `hashCode(): Int`,
         *     `toString(): String` are all present.
         *  6. A generated **`copy(...)`** is present — args matching the properties by order/type and
         *     returning the class type. Every real data class emits `copy`; a hand-written destructurable
         *     value class (manual `componentN` + custom `equals`/`hashCode`/`toString`) virtually never
         *     does, so this is what keeps such a lookalike a regular class (its custom bodies preserved,
         *     no `copy` fabricated).
         */
        private fun detectDataClass(cls: IrClass): DataClassShape? {
            if (!KotlinModifiers.has(cls.accessFlags, KotlinModifiers.FINAL)) return null
            if (KotlinModifiers.has(cls.accessFlags, KotlinModifiers.ABSTRACT)) return null

            val properties = cls.fields.filter { f ->
                !f.contains(AttrFlag.DONT_GENERATE) &&
                    !KotlinModifiers.has(f.accessFlags, KotlinModifiers.STATIC)
            }
            if (properties.isEmpty()) return null
            val arity = properties.size

            val constructors = cls.methods.filter { it.name == "<init>" && !it.contains(AttrFlag.DONT_GENERATE) }
            val constructor = constructors.singleOrNull() ?: return null
            if (constructor.argTypes.size != arity) return null
            for (i in 0 until arity) {
                if (constructor.argTypes[i] != properties[i].type) return null
            }

            // componentN: exactly component1..componentK, each returning the matching property type.
            val byIndex = HashMap<Int, IrMethod>()
            for (m in cls.methods) {
                val idx = componentIndex(m) ?: continue
                if (idx < 1 || idx > arity) return null // an out-of-range componentN ⇒ not this shape
                if (byIndex.put(idx, m) != null) return null // duplicate componentN
            }
            if (byIndex.size != arity) return null
            for (i in 1..arity) {
                val m = byIndex[i] ?: return null
                if (m.returnType != properties[i - 1].type) return null
            }

            if (!hasGeneratedEquals(cls)) return null
            if (!hasGeneratedHashCode(cls)) return null
            if (!hasGeneratedToString(cls)) return null
            if (cls.methods.none { isGeneratedCopy(cls, it, properties) }) return null

            return DataClassShape(constructor, properties)
        }

        /**
         * The 1-based index `n` of a no-arg `componentN` method, or `null`. The suffix must be a plain
         * decimal with no leading zero (`component01` is not compiler-generated); an out-of-range or
         * overflowing suffix yields `null`, so such a method is treated as an ordinary user member.
         */
        private fun componentIndex(m: IrMethod): Int? {
            if (m.argTypes.isNotEmpty()) return null
            val prefix = "component"
            if (!m.name.startsWith(prefix) || m.name.length == prefix.length) return null
            val suffix = m.name.substring(prefix.length)
            if (suffix.any { it !in '0'..'9' }) return null
            if (suffix.length > 1 && suffix[0] == '0') return null
            return suffix.toIntOrNull()
        }

        private fun hasGeneratedEquals(cls: IrClass): Boolean =
            cls.methods.any { m ->
                m.name == "equals" && m.returnType == IrType.BOOLEAN && m.argTypes.size == 1 &&
                    (m.argTypes[0] as? IrType.Object)?.className == IrType.OBJECT_CLASS
            }

        private fun hasGeneratedHashCode(cls: IrClass): Boolean =
            cls.methods.any { it.name == "hashCode" && it.argTypes.isEmpty() && it.returnType == IrType.INT }

        private fun hasGeneratedToString(cls: IrClass): Boolean =
            cls.methods.any { m ->
                m.name == "toString" && m.argTypes.isEmpty() &&
                    (m.returnType as? IrType.Object)?.className == "java.lang.String"
            }

        /**
         * A member of the confirmed data class [shape] that only the compiler can author, so `data class`
         * fully regenerates it and it is safe to suppress: the canonical constructor, every `componentN`,
         * `copy(...)`, and the synthetic `copy$default`. Matched by exact signature so a genuine user
         * method (a different overload, an unrelated helper) still survives.
         *
         * `equals`/`hashCode`/`toString` are deliberately **NOT** suppressed: a data class may legally
         * override any of them, and the override is emitted under the very same signature the compiler
         * would use — signature alone cannot tell a default from a user body. Suppressing them would
         * silently drop a real override (rule 4). Keeping them in the body is always lossless — a data
         * class is allowed to redeclare them — at the cost of one line of idiom in the default case.
         */
        private fun isGeneratedDataMember(cls: IrClass, m: IrMethod, shape: DataClassShape): Boolean {
            if (m === shape.constructor) return true
            if (componentIndex(m) != null) return true
            if (m.name == "copy\$default") return true
            return isGeneratedCopy(cls, m, shape.properties)
        }

        /** The generated `copy(a: A, b: B): X` — args match the properties by order/type, returns the class. */
        private fun isGeneratedCopy(cls: IrClass, m: IrMethod, properties: List<IrField>): Boolean =
            m.name == "copy" && m.argTypes.size == properties.size &&
                (m.returnType as? IrType.Object)?.className == cls.fullName &&
                properties.indices.all { m.argTypes[it] == properties[it].type }

        private fun emitSupertypes(cls: IrClass, kind: ClassKind) {
            val parts = ArrayList<Pair<IrType, Boolean>>() // type, needsConstructorParens
            // Only a class/object may declare (and construct) a superclass. Enum's super is the implicit
            // java.lang.Enum; an annotation class may not declare supertypes at all.
            if (kind == ClassKind.CLASS || kind == ClassKind.OBJECT) {
                val superType = cls.superType
                if (superType != null && superType != IrType.OBJECT) parts.add(superType to true)
            }
            if (kind != ClassKind.ANNOTATION) {
                for (itf in cls.interfaces) parts.add(itf to false)
            }
            if (parts.isEmpty()) return
            code.add(" : ")
            for ((i, part) in parts.withIndex()) {
                if (i > 0) code.add(", ")
                emitTypeName(part.first)
                if (part.second) code.add("()") // implicit no-arg super constructor call
            }
        }

        // ---------- enum entries ----------

        private fun emitEnumEntries(cls: IrClass, constants: List<IrField>, hasMoreMembers: Boolean) {
            for ((i, c) in constants.withIndex()) {
                code.attachDefinition(FieldNodeRef(cls.fullName, c.name))
                code.add(KotlinIdentifiers.sanitize(c.name))
                when {
                    i < constants.lastIndex -> code.add(",")
                    hasMoreMembers -> code.add(";") // terminate the entry list before other members
                }
                code.newLine()
            }
        }

        // ---------- properties ----------

        /** The `<clinit>` and the shared set of stores hoisted into `val X = …` initializers (Feature B). */
        private class StaticInitContext(val clinit: IrMethod?, val suppressed: MutableSet<Instruction>)

        private fun emitProperty(cls: IrClass, field: IrField, staticInit: StaticInitContext? = null) {
            // A `static final` field whose value is a non-literal single unconditional `<clinit>` store is
            // rendered `val X = <the store's RHS>` (the store is suppressed in the init block). Kotlin
            // cannot reassign a `val` in `init {}`, so this is what makes such a field compile at all.
            if (staticInit != null && tryEmitInlinedStaticFinal(cls, field, staticInit)) {
                code.newLine()
                return
            }
            val isFinal = KotlinModifiers.has(field.accessFlags, KotlinModifiers.FINAL)
            val const = field.constValue
            // Kotlin requires every property to be initialized (or `lateinit`/`abstract`). We only have a
            // reconstructed value for the compile-time constant (`constValue`); a field initialized in a
            // constructor / `<clinit>` has no value here yet. Rather than emit a silently-uncompilable
            // `val name: T` (the M2 bug), pick an honest form:
            //   - a `var` primitive → the JVM default (0/false/…): this MATCHES uninitialized-field
            //     semantics and is a harmless placeholder when the constructor overwrites it;
            //   - a `var` non-null reference → `lateinit var` (says "assigned later", which is true);
            //   - anything else (a `val`, where neither is legal) → keep the declaration but flag it with
            //     a `// JADXMP ERROR` marker so it is never *silently* invalid.
            val isConst = constEligible(field)
            when {
                isConst || const != null -> {
                    code.add(KotlinModifiers.visibility(field.accessFlags))
                    if (isConst) code.add("const ")
                    code.add(if (isFinal) "val " else "var ")
                    emitPropertyNameAndType(cls, field)
                    code.add(" = ").add(renderFieldConst(const!!))
                }
                !isFinal && field.type is IrType.Primitive -> {
                    code.add(KotlinModifiers.visibility(field.accessFlags))
                    code.add("var ")
                    emitPropertyNameAndType(cls, field)
                    code.add(" = ").add(KotlinLiterals.format(LiteralOperand(0L, field.type)))
                }
                !isFinal && isNonNullReference(field.type) -> {
                    code.add(KotlinModifiers.visibility(field.accessFlags))
                    code.add("lateinit var ")
                    emitPropertyNameAndType(cls, field)
                }
                else -> {
                    // Broken output (an uninitialized `val`) ⇒ flag the owning class HAS_ERROR AND emit the
                    // marker together, so error accounting (countErrors/reportedErrors) can't undercount it.
                    code.emitErrorMarker(cls, "field initializer not reconstructed")
                    code.add(KotlinModifiers.visibility(field.accessFlags))
                    code.add(if (isFinal) "val " else "var ")
                    emitPropertyNameAndType(cls, field)
                }
            }
            code.newLine()
        }

        private fun emitPropertyNameAndType(cls: IrClass, field: IrField) {
            code.attachDefinition(FieldNodeRef(cls.fullName, field.name))
            code.add(KotlinIdentifiers.sanitize(field.name))
            code.add(": ").add(types.render(field.type))
        }

        /**
         * A type `lateinit` is legal on: a non-null class or array type. A bare [IrType.TypeVariable]
         * is deliberately EXCLUDED — an unbounded `T` has upper bound `Any?`, so `lateinit var x: T` is
         * rejected by kotlinc; such a field must fall through to the honest `// JADXMP ERROR` marker
         * branch rather than emit silently-invalid `lateinit` (M2's "never silently invalid" contract).
         */
        private fun isNonNullReference(type: IrType): Boolean = when (type) {
            is IrType.Object -> true
            is IrType.ArrayType -> true
            else -> false
        }

        private fun constEligible(field: IrField): Boolean =
            KotlinModifiers.has(field.accessFlags, KotlinModifiers.STATIC) &&
                KotlinModifiers.has(field.accessFlags, KotlinModifiers.FINAL) &&
                field.constValue != null

        private fun renderFieldConst(const: IrFieldConst): String = when (const) {
            is IrFieldConst.Primitive -> KotlinLiterals.format(LiteralOperand(const.bits, const.type))
            is IrFieldConst.Str -> KotlinLiterals.stringLiteral(const.value)
        }

        /**
         * Render `visibility val X: T = <expr>` for a `static final` [field] whose value is the single,
         * unconditional `<clinit>` store of a self-contained expression, marking that store suppressed so
         * the companion `init {}` does not also emit it. Returns false (emitting nothing) when the field is
         * not a hoistable static-final, is written more than once / conditionally, or whose stored value
         * cannot be inlined — the caller then falls through to the honest existing behavior (CLAUDE rule 4).
         */
        private fun tryEmitInlinedStaticFinal(cls: IrClass, field: IrField, staticInit: StaticInitContext): Boolean {
            val clinit = staticInit.clinit ?: return false
            val staticFinal = KotlinModifiers.STATIC or KotlinModifiers.FINAL
            if (field.accessFlags and staticFinal != staticFinal) return false
            if (field.constValue != null) return false // a compile-time literal is handled as `const val`
            val store = singleUnconditionalStore(clinit, cls.fullName, field.name) ?: return false
            val writer = MethodBodyWriter(code, imports, clinit, NameGenerator(), emptyList())
            val toSuppress = writer.planStaticFinalInline(store, staticInit.suppressed) ?: return false
            code.add(KotlinModifiers.visibility(field.accessFlags))
            code.add("val ")
            emitPropertyNameAndType(cls, field)
            code.add(" = ")
            writer.emitStaticFinalInit(store)
            staticInit.suppressed.addAll(toSuppress)
            return true
        }

        /**
         * The one `STATIC_PUT` into [fieldName] of [ownerClass] in [clinit], iff it is written EXACTLY once
         * across the whole `<clinit>` AND that write is a provably-unconditional top-level statement (not
         * nested in an `if`/loop/`try`). Any other shape returns null so the value is never wrongly hoisted
         * out of the control flow that guarded it.
         */
        private fun singleUnconditionalStore(clinit: IrMethod, ownerClass: String, fieldName: String): Instruction? {
            val allStores = clinit.blocks.asSequence()
                .flatMap { it.instructions.asSequence() }
                .filter { isStaticPutInto(it, ownerClass, fieldName) }
                .toList()
            val store = allStores.singleOrNull() ?: return null
            return if (store in topLevelStatements(clinit)) store else null
        }

        private fun isStaticPutInto(insn: Instruction, ownerClass: String, fieldName: String): Boolean {
            if (insn.opcode != IrOpcode.STATIC_PUT) return false
            val fr = (insn as? FieldInstruction)?.fieldRef ?: return false
            return fr.name == fieldName && (fr.declaringType as? IrType.Object)?.className == ownerClass
        }

        /** The unconditionally-executed top-level statements of [method] (empty if it isn't straight-line). */
        private fun topLevelStatements(method: IrMethod): List<Instruction> {
            val region = method.region
            return when {
                region == null -> if (isLinear(method.blocks)) method.blocks.flatMap { it.instructions } else emptyList()
                region is SequenceRegion -> region.children.filterIsInstance<BasicBlock>().flatMap { it.instructions }
                else -> emptyList() // an If/Loop/Try directly at the top ⇒ nothing is unconditional
            }
        }

        /** A branch-free, acyclic block list: one straight-line path (no fork, no back edge). */
        private fun isLinear(blocks: List<BasicBlock>): Boolean {
            val orderOf = HashMap<Int, Int>(blocks.size)
            blocks.forEachIndexed { i, b -> orderOf[b.id] = i }
            blocks.forEachIndexed { i, b ->
                if (b.successors.size > 1) return false
                for (s in b.successors) {
                    val si = orderOf[s.id] ?: return false
                    if (si <= i) return false
                }
            }
            return true
        }

        // ---------- methods ----------

        private fun emitMethod(cls: IrClass, method: IrMethod, kind: ClassKind) {
            emitErrorComment(method)
            val isConstructor = method.name == "<init>"
            val overrides = isOverride(cls, method, kind)

            code.add(KotlinModifiers.visibility(method.accessFlags))
            if (!isConstructor) {
                code.add(methodModality(cls, method, kind, overrides))
                code.add("fun ")
                code.attachDefinition(methodRef(cls, method))
                code.add(KotlinIdentifiers.sanitize(method.name))
            } else {
                code.attachDefinition(methodRef(cls, method))
                code.add("constructor")
            }

            val methodNames = NameGenerator()
            val paramNames = resolveParamNames(method, methodNames)
            // `equals(Object)` must be rendered `equals(other: Any?)` to actually override
            // `Any.equals(other: Any?)`; a non-null `Any` parameter overrides nothing and won't compile.
            emitParamList(method, paramNames, nullableAnyParam0 = overrides && isEqualsObjectSignature(method))

            if (!isConstructor && method.returnType != IrType.VOID) {
                // A `Unit` return is Kotlin's default and is omitted; anything else is spelled out.
                code.add(": ")
                emitTypeName(method.returnType)
            }

            val noBody = KotlinModifiers.has(method.accessFlags, KotlinModifiers.ABSTRACT) ||
                KotlinModifiers.has(method.accessFlags, KotlinModifiers.NATIVE)
            if (noBody) {
                // Still emit a NodeEnd so nodeAt's declaration/brace nesting stays balanced.
                code.attachNodeEnd()
                code.newLine()
                return
            }
            // A constructor's `this(...)`/`super(...)` delegation is header-only in Kotlin. The body writer,
            // reused for the body, first renders the header delegation (` : this(args)`) — or omits an
            // implicit no-arg super — and marks that instruction to be skipped in the body. It BAILS to the
            // honest body marker when the delegation can't be faithfully hoisted (rule 4), leaving the body
            // path unchanged. The SAME writer must render header then body so variable naming/ids stay in
            // sync between the two.
            val writer = MethodBodyWriter(code, imports, method, methodNames, paramNames)
            if (isConstructor) writer.emitConstructorDelegationHeader()
            code.add(" ")
            emitBody(writer)
        }

        private fun methodRef(cls: IrClass, method: IrMethod) =
            MethodNodeRef(cls.fullName, method.name, method.argTypes.map { it.toString() })

        private fun methodModality(cls: IrClass, method: IrMethod, kind: ClassKind, isOverride: Boolean): String {
            val isAbstract = KotlinModifiers.has(method.accessFlags, KotlinModifiers.ABSTRACT)
            val sb = StringBuilder()
            when {
                // Interface members are implicitly abstract/open — no keyword needed.
                kind == ClassKind.INTERFACE -> {}
                isAbstract -> sb.append("abstract ")
                // A non-final, overridable member of an extendable class must be `open`; an override is
                // already open by inheritance, so it never needs `open` too.
                !isOverride && isOpenCandidate(cls, method, kind) -> sb.append("open ")
            }
            if (isOverride) sb.append("override ")
            return sb.toString()
        }

        private fun isOpenCandidate(cls: IrClass, method: IrMethod, kind: ClassKind): Boolean {
            if (kind != ClassKind.CLASS) return false
            val classOpen = !KotlinModifiers.has(cls.accessFlags, KotlinModifiers.FINAL) ||
                KotlinModifiers.has(cls.accessFlags, KotlinModifiers.ABSTRACT)
            if (!classOpen) return false
            val f = method.accessFlags
            return !KotlinModifiers.has(f, KotlinModifiers.FINAL) &&
                !KotlinModifiers.has(f, KotlinModifiers.PRIVATE) &&
                !KotlinModifiers.has(f, KotlinModifiers.STATIC)
        }

        /**
         * True when [method] carries the `override` modifier: either flagged by the pipeline
         * ([KotlinCodegenKeys.IS_OVERRIDE]) or one of the members that always overrides `Any`
         * (`toString`/`hashCode`/`equals`), which we can recognise without the class hierarchy.
         */
        private fun isOverride(cls: IrClass, method: IrMethod, kind: ClassKind): Boolean {
            if (method[KotlinCodegenKeys.IS_OVERRIDE] == true) return true
            if (kind != ClassKind.CLASS && kind != ClassKind.OBJECT) return false
            return when (method.name) {
                "toString" -> method.argTypes.isEmpty()
                "hashCode" -> method.argTypes.isEmpty()
                "equals" -> isEqualsObjectSignature(method)
                else -> false
            }
        }

        private fun isEqualsObjectSignature(method: IrMethod): Boolean =
            method.name == "equals" && method.argTypes.size == 1 &&
                (method.argTypes[0] as? IrType.Object)?.className == IrType.OBJECT_CLASS

        private fun emitParamList(method: IrMethod, paramNames: List<String>, nullableAnyParam0: Boolean) {
            code.add("(")
            for (i in method.argTypes.indices) {
                if (i > 0) code.add(", ")
                code.add(paramNames[i]).add(": ")
                if (i == 0 && nullableAnyParam0) {
                    code.add("Any?") // the overriding `equals(other: Any?)` signature
                } else {
                    emitTypeName(method.argTypes[i])
                }
            }
            code.add(")")
        }

        private fun resolveParamNames(method: IrMethod, names: NameGenerator): List<String> {
            val provided = method[CodegenKeys.PARAM_NAMES]
            return method.argTypes.mapIndexed { i, type ->
                val supplied = provided?.getOrNull(i)
                if (supplied != null) names.reserve(KotlinIdentifiers.sanitize(supplied)) else names.forType(type)
            }
        }

        private fun emitBody(writer: MethodBodyWriter) {
            code.add("{").newLine()
            code.incIndent()
            writer.writeBody()
            code.decIndent()
            code.attachNodeEnd()
            code.add("}").newLine()
        }

        // ---------- companion object & init ----------

        private fun emitCompanion(
            cls: IrClass,
            staticFields: List<IrField>,
            clinit: IrMethod?,
            staticMethods: List<IrMethod>,
            enumSuppressed: Set<Instruction>,
        ) {
            // The companion is itself a (Kotlin) nested class; give its definition a ref so its NodeEnd
            // is balanced for nodeAt, matching how every other body-scope is recorded.
            code.attachDefinition(ClassNodeRef(cls.fullName + ".Companion"))
            code.add("companion object {").newLine()
            code.incIndent()
            // Stores hoisted out of the `<clinit>` into a field's `val X = …` initializer, plus any enum
            // construction to suppress. Static-final hoisting mutates this while properties are emitted, so
            // the residual-`<clinit>` decision below sees the final set.
            val suppressed = HashSet(enumSuppressed)
            val staticInit = StaticInitContext(clinit, suppressed)
            var wrote = false
            for (f in staticFields) {
                emitProperty(cls, f, staticInit)
                wrote = true
            }
            if (clinit != null && methodHasBody(clinit) &&
                !KotlinEnumReconstruction.residualIsEmpty(clinit, suppressed)
            ) {
                if (wrote) code.newLine()
                emitInitBlock(cls, clinit, suppressed)
                wrote = true
            }
            for (m in staticMethods) {
                if (wrote) code.newLine()
                emitMethod(cls, m, ClassKind.CLASS)
                wrote = true
            }
            code.decIndent()
            code.attachNodeEnd()
            code.add("}").newLine()
        }

        private fun emitInitBlock(cls: IrClass, method: IrMethod, suppressed: Set<Instruction> = emptySet()) {
            emitErrorComment(method)
            code.attachDefinition(methodRef(cls, method))
            code.add("init {").newLine()
            code.incIndent()
            MethodBodyWriter(code, imports, method, NameGenerator(), emptyList(), suppressed).writeBody()
            code.decIndent()
            code.attachNodeEnd()
            code.add("}").newLine()
        }

        private fun methodHasBody(method: IrMethod): Boolean =
            method.region != null || method.blocks.isNotEmpty()

        // ---------- shared ----------

        private fun emitTypeName(type: IrType) {
            classNameForRef(type)?.let { code.attachReference(ClassNodeRef(it)) }
            code.add(types.render(type))
        }

        private fun classNameForRef(type: IrType): String? = when (type) {
            is IrType.Object -> if (type.isRootObject) null else type.className
            is IrType.ArrayType -> classNameForRef(type.element)
            else -> null
        }

        /**
         * Surface a fault-isolated node error as a source comment so the "no-error" accuracy signal
         * (and a reader) sees it rather than a silently-partial body. Same honesty invariant and marker
         * text (`JADXMP ERROR`) as the Java backend.
         */
        private fun emitErrorComment(node: AttrNode) {
            if (!node.contains(AttrFlag.HAS_ERROR)) return
            // The error is already shown inline (emitErrorMarker) — a top marker would duplicate it.
            if (node[ERROR_SURFACED_INLINE] == true) return
            val message = node[IrAttrs.ERROR]?.message?.replace('\n', ' ')?.replace('\r', ' ')
                ?: "decompilation failed"
            code.add("// JADXMP ERROR: ").add(message).newLine()
        }
    }
}
