package com.jadxmp.ir.attr

/**
 * A typed, identity-based key for a node attribute.
 *
 * jadx: AType
 *
 *
 * Keys are compared by reference identity (like an enum constant), never by [name] — two keys with
 * the same name are still distinct. Declare each key once as a `val` (see [IrAttrs]) and reuse it;
 * the phantom type [T] ties the key to the value type so [AttrStorage.get] needs no cast.
 *
 * Immutable.
 *
 * @param T the type of value stored under this key.
 */
class AttrKey<T : Any>(val name: String) {
    override fun toString(): String = name
}
