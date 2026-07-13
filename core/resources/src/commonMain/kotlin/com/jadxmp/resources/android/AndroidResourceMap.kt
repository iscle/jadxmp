package com.jadxmp.resources.android

/**
 * Android framework resource-id → symbolic-name map.
 *
 * Framework resources live in package `0x01` and keep stable, well-known ids (e.g. `0x01010000` is
 * `android:attr/theme`). Binary XML and `resources.arsc` reference these ids numerically; resolving
 * them back to `type/name` is what makes a decoded `AndroidManifest.xml` or layout readable.
 *
 * We bundle a compact subset — the public `attr`, `id` and `style` symbols — which is what the
 * binary-XML resource-map and the common framework references (`@android:style/…`, `@android:id/…`)
 * need. The data lives in [AndroidResourceMapData] as split string constants and is parsed once,
 * lazily, into an id→name map. Everything here is pure `commonMain` (no resource-stream loading,
 * which has no portable wasm form).
 *
 * jadx equivalent: `AndroidResourcesMap` + bundled `res-map.txt`.
 */
public object AndroidResourceMap {
    private val map: Map<Int, String> by lazy { parse() }

    /** Number of bundled framework symbols. */
    public val size: Int get() = map.size

    /**
     * Resolve a framework resource id to its `type/name` (e.g. `attr/theme`), or `null` if this id
     * is not a known bundled framework symbol.
     */
    public fun resName(resId: Int): String? = map[resId]

    private fun parse(): Map<Int, String> {
        val result = HashMap<Int, String>(8192)
        for (part in AndroidResourceMapData.parts) {
            var lineStart = 0
            val len = part.length
            while (lineStart < len) {
                var lineEnd = part.indexOf('\n', lineStart)
                if (lineEnd == -1) lineEnd = len
                val eq = part.indexOf('=', lineStart)
                if (eq in (lineStart + 1) until lineEnd) {
                    val id = part.substring(lineStart, eq).toIntOrNull(16)
                    if (id != null) {
                        result[id] = part.substring(eq + 1, lineEnd)
                    }
                }
                lineStart = lineEnd + 1
            }
        }
        return result
    }
}
