package com.jadxmp.input.jvm

/** Reference joins that require no external hierarchy or class loading. */
internal object JvmReferenceTypes {
    val OBJECT = JvmFrameValue.Reference("Ljava/lang/Object;")

    /** Null is assignable to each initialized reference; unrelated references safely join at Object. */
    fun merge(left: JvmFrameValue, right: JvmFrameValue): JvmFrameValue? = when {
        left == right -> left
        left == JvmFrameValue.NullValue && right is JvmFrameValue.Reference -> right
        right == JvmFrameValue.NullValue && left is JvmFrameValue.Reference -> left
        left is JvmFrameValue.Reference && right is JvmFrameValue.Reference -> OBJECT
        else -> null
    }
}
