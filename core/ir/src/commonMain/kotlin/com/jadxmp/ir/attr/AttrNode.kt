package com.jadxmp.ir.attr

/**
 * Base class for every IR node that can carry analysis results and flags.
 *
 * Provides thin delegating accessors over an [AttrStorage] so callers write `node.add(FLAG)` /
 * `node[KEY]` instead of reaching through `.attrs`. The storage is the single mutation surface for
 * otherwise immutable-leaning nodes.
 *
 * jadx: AttrNode / IAttributeNode
 */
abstract class AttrNode {
    val attrs: AttrStorage = AttrStorage()

    operator fun <T : Any> get(key: AttrKey<T>): T? = attrs.get(key)

    operator fun <T : Any> set(key: AttrKey<T>, value: T) = attrs.put(key, value)

    fun <T : Any> put(key: AttrKey<T>, value: T) = attrs.put(key, value)

    fun contains(key: AttrKey<*>): Boolean = attrs.contains(key)

    fun <T : Any> remove(key: AttrKey<T>) = attrs.remove(key)

    fun add(flag: AttrFlag) = attrs.add(flag)

    fun contains(flag: AttrFlag): Boolean = attrs.contains(flag)

    fun remove(flag: AttrFlag) = attrs.remove(flag)
}
