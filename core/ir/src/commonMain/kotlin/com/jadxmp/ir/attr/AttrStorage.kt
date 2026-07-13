package com.jadxmp.ir.attr

/**
 * A small, lazily-allocated store of typed [AttrKey] values plus a set of [AttrFlag]s.
 *
 * Not thread-safe: mutation happens on a single per-class coroutine that holds that class's mutex
 * (see ARCHITECTURE §4), so no synchronization is required here.
 *
 * jadx: AttributeStorage
 */
class AttrStorage {
    // Both maps/sets are created on first write to keep leaf nodes (the vast majority) cheap.
    private var values: MutableMap<AttrKey<*>, Any>? = null
    private var flags: MutableSet<AttrFlag>? = null

    fun <T : Any> put(key: AttrKey<T>, value: T) {
        val map = values ?: HashMap<AttrKey<*>, Any>().also { values = it }
        map[key] = value
    }

    @Suppress("UNCHECKED_CAST")
    fun <T : Any> get(key: AttrKey<T>): T? = values?.get(key) as T?

    fun contains(key: AttrKey<*>): Boolean = values?.containsKey(key) == true

    fun <T : Any> remove(key: AttrKey<T>) {
        values?.remove(key)
    }

    fun add(flag: AttrFlag) {
        val set = flags ?: HashSet<AttrFlag>().also { flags = it }
        set.add(flag)
    }

    fun contains(flag: AttrFlag): Boolean = flags?.contains(flag) == true

    fun remove(flag: AttrFlag) {
        flags?.remove(flag)
    }

    val isEmpty: Boolean
        get() = values.isNullOrEmpty() && flags.isNullOrEmpty()
}
