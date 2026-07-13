package com.jadxmp.input

/**
 * The result of loading one or more input containers: the normalized classes the engine will
 * consume. Produced by a format parser (e.g. the DEX loader in `core:input-dex`).
 *
 * Multi-container concerns — merging several `.dex` files and resolving duplicate class definitions
 * — are settled here, so the engine sees one flat, de-duplicated list.
 *
 * jadx: ICodeLoader
 */
public interface CodeLoader {
    public val classes: List<ClassData>

    public val isEmpty: Boolean
        get() = classes.isEmpty()
}

/** A trivial [CodeLoader] backed by an already-assembled list. */
public class ListCodeLoader(
    override val classes: List<ClassData>,
) : CodeLoader
