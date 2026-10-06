package com.jadxmp.pipeline.model

import com.jadxmp.ir.generics.ClassSignature
import com.jadxmp.ir.generics.MethodSignature
import com.jadxmp.ir.generics.TypeParameter
import com.jadxmp.ir.type.IrType
import com.jadxmp.ir.type.WildcardBound

/** JVMS 4.7.9.1 grammar, bounded independently of the input parser. Never changes erased descriptors. */
internal object GenericSignatures {
    fun parseClass(text: String, enclosing: List<TypeParameter> = emptyList()): ClassSignature {
        val parser = Parser(text)
        val parameters = parser.parameters()
        val superType = parser.classType()
        val interfaces = buildList { while (!parser.finished) add(parser.classType()) }
        parser.requireSupported()
        val resolved = validate(parameters, enclosing, emptyList())
        try {
            validate(emptyList(), enclosing + resolved, listOf(superType) + interfaces)
        } catch (unbound: UnboundGenericVariable) {
            throw UnboundGenericVariable(unbound.variableName, resolved)
        }
        return ClassSignature(resolved, superType, interfaces)
    }

    fun parseMethod(text: String, enclosing: List<TypeParameter> = emptyList()): MethodSignature {
        val parser = Parser(text)
        val parameters = parser.parameters()
        parser.expect('(')
        val arguments = buildList { while (!parser.take(')')) add(parser.type()) }
        val result = if (parser.take('V')) IrType.VOID else parser.type()
        val throws = buildList {
            while (parser.take('^')) {
                val type = parser.reference()
                signatureRequire(type is IrType.Object || type is IrType.TypeVariable) { "invalid throws signature" }
                add(type)
            }
        }
        signatureRequire(parser.finished) { "trailing method signature input" }
        parser.requireSupported()
        val resolved = validate(parameters, enclosing, arguments + result + throws)
        return MethodSignature(resolved, arguments, result, throws)
    }

    fun parseField(text: String, enclosing: List<TypeParameter> = emptyList()): IrType {
        val parser = Parser(text)
        val type = parser.reference()
        signatureRequire(parser.finished) { "trailing field signature input" }
        parser.requireSupported()
        validate(emptyList(), enclosing, listOf(type))
        return type
    }

    fun erase(type: IrType, parameters: List<TypeParameter>): IrType = ErasureScope(parameters).erase(type)

    /** One symbol table and memo per validation; a declaration with many formals must not rebuild it for each bound. */
    class ErasureScope(parameters: List<TypeParameter>) {
        private val scope = parameters.associateBy { it.name }
        private val resolved = mutableMapOf<String, IrType>()
        fun erase(current: IrType, visiting: Set<String> = emptySet()): IrType = when (current) {
            is IrType.Object -> IrType.objectType(current.className)
            is IrType.ArrayType -> IrType.array(erase(current.element, visiting))
            is IrType.TypeVariable -> {
                signatureRequire(current.name !in visiting) { "cyclic type-variable erasure" }
                if (visiting.size >= 64) throw UnsupportedGenericSignature("type-variable erasure depth exceeded")
                resolved[current.name] ?: run {
                    val parameter = scope[current.name] ?: throw UnboundGenericVariable(current.name)
                    (parameter.erasedType ?: erase(parameter.bounds.firstOrNull() ?: IrType.OBJECT, visiting + current.name))
                        .also { resolved[current.name] = it }
                }
            }
            is IrType.Primitive -> current
            else -> throw InvalidGenericSignature("non-reifiable signature erasure")
        }
    }

    private fun validate(parameters: List<TypeParameter>, enclosing: List<TypeParameter>, types: List<IrType>): List<TypeParameter> {
        signatureRequire(parameters.map { it.name }.toSet().size == parameters.size) { "duplicate type parameter" }
        val scope = enclosing + parameters
        val names = scope.mapTo(mutableSetOf()) { it.name }
        fun check(type: IrType) {
            when (type) {
                is IrType.TypeVariable -> if (type.name !in names) throw UnboundGenericVariable(type.name)
                is IrType.Object -> type.generics.forEach(::check)
                is IrType.ArrayType -> check(type.element)
                is IrType.Wildcard -> type.boundType?.let(::check)
                else -> Unit
            }
        }
        (types + parameters.flatMap { it.bounds }).forEach(::check)
        val erasures = ErasureScope(scope)
        return parameters.map { it.copy(erasedType = erasures.erase(IrType.typeVariable(it.name))) }
    }

    private class Parser(private val text: String) {
        private var offset = 0
        private var depth = 0
        private var nodes = 0
        private var parameterizedOwner = false
        fun requireSupported() {
            if (parameterizedOwner) throw UnsupportedGenericSignature("parameterized enclosing class signatures are not supported")
        }
        init {
            signatureRequire(text.isNotEmpty()) { "empty generic signature" }
            if (text.length > 65535) throw UnsupportedGenericSignature("oversized generic signature")
        }
        val finished: Boolean get() = offset == text.length
        fun take(char: Char): Boolean = if (text.getOrNull(offset) == char) { offset++; true } else false
        fun expect(char: Char) { signatureRequire(take(char)) { "expected '$char' at signature offset $offset" } }
        fun parameters(): List<TypeParameter> {
            if (!take('<')) return emptyList()
            val result = buildList {
                do {
                    chargeNode()
                    val name = identifier()
                    expect(':')
                    val classBound = if (hasReferenceBound()) reference() else null
                    val interfaces = buildList { while (take(':')) add(reference()) }
                    add(TypeParameter(name, classBound, interfaces))
                } while (!take('>'))
            }
            return result
        }
        private fun hasReferenceBound(): Boolean {
            if (text.getOrNull(offset) == '[') return true
            if (text.getOrNull(offset) !in listOf('L', 'T')) return false
            // An absent bound can be followed by a formal parameter whose name itself begins L/T.
            // A colon before any class/type delimiter identifies that next declaration, not a bound.
            val delimiter = text.indexOfAny(charArrayOf(':', ';', '<', '/', '.', '>'), offset)
            return delimiter < 0 || text[delimiter] != ':'
        }
        private fun chargeNode() {
            if (++nodes > 4096) throw UnsupportedGenericSignature("generic signature complexity limit exceeded")
        }
        fun type(): IrType = when (text.getOrNull(offset)) {
            'B', 'C', 'D', 'F', 'I', 'J', 'S', 'Z' -> { chargeNode(); Descriptors.parseType(text[offset++].toString()) }
            else -> reference()
        }
        fun reference(): IrType {
            if (++depth > 64) throw UnsupportedGenericSignature("generic signature complexity limit exceeded")
            val result = when {
                take('T') -> { chargeNode(); IrType.typeVariable(identifier()).also { expect(';') } }
                take('[') -> { chargeNode(); IrType.array(type()) }
                text.getOrNull(offset) == 'L' -> classType()
                else -> throw InvalidGenericSignature("expected reference at signature offset $offset")
            }
            depth--
            return result
        }
        fun classType(): IrType {
            chargeNode()
            expect('L')
            val path = buildList {
                add(identifier())
                while (take('/')) add(identifier())
            }
            val name = StringBuilder(path.joinToString("."))
            var arguments = arguments()
            while (take('.')) {
                // Parse the complete grammar before reporting a representation limitation, so a malformed
                // suffix is never mislabeled as a valid unsupported owner. This temporary flattened value
                // cannot escape: every public parser checks requireSupported before validation/return.
                if (arguments.isNotEmpty()) parameterizedOwner = true
                chargeNode()
                name.append('$').append(identifier())
                arguments = arguments()
            }
            expect(';')
            return IrType.generic(name.toString(), arguments)
        }
        private fun arguments(): List<IrType> {
            if (!take('<')) return emptyList()
            return buildList {
                do {
                    add(when {
                        take('*') -> { chargeNode(); IrType.wildcard() }
                        take('+') -> { chargeNode(); IrType.wildcard(WildcardBound.EXTENDS, reference()) }
                        take('-') -> { chargeNode(); IrType.wildcard(WildcardBound.SUPER, reference()) }
                        else -> reference()
                    })
                } while (!take('>'))
            }
        }
        private fun identifier(): String {
            val start = offset
            while (offset < text.length && text[offset] !in ".;[/<>:") offset++
            signatureRequire(offset > start) { "empty identifier at signature offset $offset" }
            return text.substring(start, offset)
        }
    }
}
