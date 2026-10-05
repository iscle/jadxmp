package com.jadxmp.input.jvm

import com.jadxmp.io.ByteReaderException

/** Bounded descriptor syntax validation; class loading and assignability belong to later stages. */
internal object JvmDescriptors {
    fun field(value: String) {
        if (typeEnd(value, 0) != value.length) invalid(value)
    }

    fun method(value: String): Int {
        if (!value.startsWith('(')) invalid(value)
        var position = 1
        var slots = 0
        while (position < value.length && value[position] != ')') {
            val start = position
            position = typeEnd(value, position)
            slots += if (value[start] == 'J' || value[start] == 'D') 2 else 1
            if (slots > 255) invalid(value)
        }
        if (position >= value.length) invalid(value)
        position++
        val end = if (value.getOrNull(position) == 'V') position + 1 else typeEnd(value, position)
        if (end != value.length) invalid(value)
        return slots
    }

    fun className(value: String, allowArray: Boolean = true) {
        if (allowArray && value.startsWith('[')) {
            field(value)
        } else if (value.isEmpty() || value.split('/').any { part ->
                part.isEmpty() || part.any { it == '.' || it == ';' || it == '[' }
            }
        ) invalid(value)
    }

    fun memberName(value: String, method: Boolean) {
        if (method && value in setOf("<init>", "<clinit>")) return
        if (value.isEmpty() || value.any { it in ".;[/" || method && it in "<>" }) invalid(value)
    }

    private fun typeEnd(value: String, start: Int): Int {
        var position = start
        while (value.getOrNull(position) == '[') {
            position++
            if (position - start > 255) invalid(value)
        }
        return when (value.getOrNull(position)) {
            'B', 'C', 'D', 'F', 'I', 'J', 'S', 'Z' -> position + 1
            'L' -> {
                val end = value.indexOf(';', position + 1)
                if (end < 0) invalid(value)
                className(value.substring(position + 1, end), allowArray = false)
                end + 1
            }
            else -> invalid(value)
        }
    }

    private fun invalid(value: String): Nothing = throw ByteReaderException("invalid JVM name or descriptor: $value")
}
