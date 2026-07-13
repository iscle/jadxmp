package com.jadxmp.codegen

/**
 * The complete result of generating one source file: the rendered [code] and its [metadata].
 * **jadx: ICodeInfo**
 *
 * These two are produced together by a single [CodeWriter] pass, so the offsets in [metadata] index
 * directly into [code].
 */
data class CodeInfo(
    val code: String,
    val metadata: CodeMetadata,
)
