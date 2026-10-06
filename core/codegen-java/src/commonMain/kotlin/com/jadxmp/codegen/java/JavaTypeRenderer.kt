package com.jadxmp.codegen.java

import com.jadxmp.codegen.AliasMap
import com.jadxmp.codegen.ClassNodeRef
import com.jadxmp.codegen.ImportCollector
import com.jadxmp.ir.node.IrRoot
import com.jadxmp.ir.type.IrType
import com.jadxmp.ir.type.TypeKind
import com.jadxmp.ir.type.WildcardBound

/**
 * Renders an [IrType] as Java source, routing class names through the [imports] collector so they come
 * out short (with an import) or fully qualified on clash. **jadx: TypeGen**
 *
 * Partial/unknown types can still be present before type inference has resolved everything (the linear
 * fallback path runs pre-inference); they are rendered to a deterministic concrete representative so
 * the output at least compiles.
 */
internal class JavaTypeRenderer(
    private val imports: ImportCollector,
    private val aliasMap: AliasMap = AliasMap.EMPTY,
    // The loaded model, needed only to spell a renamed-class *reference* by the same source-of-truth its
    // definition uses. Null (and the empty map) ⇒ the byte-identical no-deobfuscation path.
    private val root: IrRoot? = null,
) {

    fun render(type: IrType): String = when (type) {
        is IrType.Primitive -> primitiveName(type.kind)
        is IrType.Object -> renderObject(type)
        is IrType.ArrayType -> render(type.element) + "[]"
        is IrType.TypeVariable -> JavaIdentifiers.sanitize(type.name)
        is IrType.Wildcard -> renderWildcard(type)
        is IrType.Unknown -> renderUnknown(type)
    }

    /** The class name (short or FQN) for [type], without generics; also registers the import. */
    fun classNameOf(type: IrType): String = when (type) {
        is IrType.Object -> JavaIdentifiers.sanitizeQualified(imports.useClass(aliasedClassName(type.className)))
        is IrType.ArrayType -> classNameOf(type.element)
        else -> render(type)
    }

    private val literalScopeNames = HashMap<com.jadxmp.ir.node.IrClass, Set<String>>()
    private val literalPackageNames = HashMap<String, Set<String>>()
    private val literalReservedNames = setOf("java", "Float", "Double")

    /** Resolve source-generated wrapper references in type context, including inherited shadows. */
    fun literalTypeName(simpleName: String, context: com.jadxmp.ir.node.IrClass): String {
        val fullName = "java.lang.$simpleName"
        val imported = classNameOf(IrType.objectType(fullName))
        val scope = literalScopeNames.getOrPut(context) { literalNamesInScope(context) }
        val pkg = JavaSourceName.sourcePackage(context)
        val siblings = literalPackageNames.getOrPut(pkg) {
            (root ?: context.root).classes.asSequence().filter { it.outerClass == null }
                .mapNotNull { cls -> literalReservedName(cls)?.takeIf { JavaSourceName.sourcePackage(cls) == pkg } }.toSet()
        }
        // Imports are still being discovered in pass 1; unlike immutable model scopes they must be
        // checked each time. The collector query is constant-time and never copies/sorts imports.
        val packageShadow = "java" in scope || "java" in siblings || imports.isSimpleNameClaimed("java")
        if (packageShadow && (simpleName in scope || simpleName in siblings || imported != simpleName)) {
            val reason = "cannot resolve NaN helper owner $fullName in shadowed source scope"
            flagError(context, reason)
            // Keep the payload in the surrounding expression and prevent a call to a user type.
            // This deliberately invalid cast is an honest unsupported output, never clean parity.
            // Include the marker here too: a late-discovered import can first expose a conflict in
            // pass 2, after the class-level error header has already been written.
            return "void /* JADXMP ERROR: $reason */"
        }
        // Prefer qualification: even an external base absent from the model can inherit Float/Double.
        return if (packageShadow) imported else fullName
    }

    private fun literalNamesInScope(context: com.jadxmp.ir.node.IrClass): Set<String> {
        val names = HashSet<String>()
        val pending = ArrayDeque<com.jadxmp.ir.node.IrClass>()
        val visited = HashSet<com.jadxmp.ir.node.IrClass>()
        pending.add(context)
        while (pending.isNotEmpty()) {
            val owner = pending.removeLast()
            if (!visited.add(owner)) continue
            literalReservedName(owner)?.let(names::add)
            owner.innerClasses.mapNotNullTo(names, ::literalReservedName)
            owner.outerClass?.let(pending::add)
            for (type in listOfNotNull(owner.superType) + owner.interfaces) {
                val name = (type as? IrType.Object)?.className ?: continue
                (root ?: context.root).findClass(name)?.let(pending::add)
            }
        }
        return names
    }

    private fun literalReservedName(cls: com.jadxmp.ir.node.IrClass): String? {
        // Disambiguation scans package siblings. A suffix cannot turn another base into any of
        // these names, so do that work only for possible matches, not every ancestor/package peer.
        val base = aliasMap.aliasOf(ClassNodeRef(cls.fullName)) ?: JavaIdentifiers.sanitize(cls.shortName)
        if (base !in literalReservedNames) return null
        return JavaSourceName.sourceSimpleName(cls, aliasMap).takeIf { it in literalReservedNames }
    }

    private fun renderObject(type: IrType.Object): String {
        // Sanitize the displayed name segments (a class/package named `do`, `1a`, etc. must still emit
        // valid Java); the ImportCollector keeps the raw name for clash detection and identity.
        val name = JavaIdentifiers.sanitizeQualified(imports.useClass(aliasedClassName(type.className)))
        if (type.generics.isEmpty()) return name
        return name + type.generics.joinToString(", ", "<", ">") { render(it) }
    }

    /**
     * Rewrite a binary class name to its deobfuscation alias BEFORE it reaches [ImportCollector], so a
     * renamed class reference gets the same import/short-vs-qualified treatment as any other name and — via
     * [JavaSourceName.sourceSimpleName] — spells the *identical* simple name the class's definition and file
     * path use (never a half-rename). Only a class we actually renamed is rewritten; every kept/library
     * class is returned verbatim, so the string handed to [ImportCollector] is unchanged and its decision
     * is identical to the no-deobfuscation path. The empty-map fast path makes that guarantee free.
     *
     * A renamed class is always leaf top-level (the populator's restriction: no outer, no inner, no `$`), so
     * `package + simple` reconstructs its full name exactly and nested-reference spelling is never touched.
     */
    private fun aliasedClassName(className: String): String {
        if (aliasMap.isEmpty) return className
        if (aliasMap.aliasOf(ClassNodeRef(className)) == null) return className
        val cls = root?.findClass(className) ?: return className
        val pkg = className.substringBeforeLast('.', "")
        val simple = JavaSourceName.sourceSimpleName(cls, aliasMap)
        return if (pkg.isEmpty()) simple else "$pkg.$simple"
    }

    private fun renderWildcard(type: IrType.Wildcard): String = when (type.bound) {
        WildcardBound.UNBOUNDED -> "?"
        WildcardBound.EXTENDS -> "? extends " + render(type.boundType ?: IrType.OBJECT)
        WildcardBound.SUPER -> "? super " + render(type.boundType ?: IrType.OBJECT)
    }

    /** A deterministic concrete stand-in for a still-partial type. */
    private fun renderUnknown(type: IrType.Unknown): String {
        val kinds = type.possible
        // Prefer a reference if the value could be one (matches jadx defaulting ambiguous refs to Object).
        if (TypeKind.OBJECT in kinds || TypeKind.ARRAY in kinds) return "java.lang.Object".let { imports.useClass(it) }
        // Otherwise pick the first primitive by a fixed priority.
        for (k in PRIMITIVE_PRIORITY) if (k in kinds) return primitiveName(k)
        return "int"
    }

    private fun primitiveName(kind: TypeKind): String = when (kind) {
        TypeKind.BOOLEAN -> "boolean"
        TypeKind.CHAR -> "char"
        TypeKind.BYTE -> "byte"
        TypeKind.SHORT -> "short"
        TypeKind.INT -> "int"
        TypeKind.FLOAT -> "float"
        TypeKind.LONG -> "long"
        TypeKind.DOUBLE -> "double"
        TypeKind.VOID -> "void"
        TypeKind.OBJECT, TypeKind.ARRAY -> "java.lang.Object".let { imports.useClass(it) }
    }

    private companion object {
        val PRIMITIVE_PRIORITY = listOf(
            TypeKind.INT, TypeKind.LONG, TypeKind.DOUBLE, TypeKind.FLOAT,
            TypeKind.BOOLEAN, TypeKind.CHAR, TypeKind.BYTE, TypeKind.SHORT,
        )
    }
}
