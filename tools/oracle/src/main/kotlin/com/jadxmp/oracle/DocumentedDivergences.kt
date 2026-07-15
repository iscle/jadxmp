package com.jadxmp.oracle

/**
 * A single **documented-divergence allowlist** entry: one corpus sample where the mechanical
 * differential scores a REGRESSION only because *jadx (the oracle) is the buggy side* and jadxmp is
 * faithfully correct. Recorded here so the gate can reach zero on these samples WITHOUT weakening it.
 *
 * @property sample the scoreboard sample label — the smali path relative to `corpus/smali/`, WITH the
 *   `.smali` extension (see [Corpus.smaliSampleName], e.g. `loops/TestLoopRestore3.smali`).
 * @property signals the EXACT set of signal names (see [SignalNames]) that jadx passes and jadxmp is
 *   expected to fail on this sample — no more, no fewer. This set is matched for *exact* equality against
 *   the actually-regressed signals; any extra jadxmp-failing signal makes the sample a REGRESSION again
 *   (the anti-masking guard), and jadxmp passing an allowlisted signal marks the entry stale.
 * @property rationale prose explaining WHY jadx is buggy here and WHY jadxmp's divergence is the correct
 *   behavior. This is the whole point of the allowlist: it must be reviewable and convincing, not a
 *   silent suppression. Keep it specific (name the miscompile).
 */
data class DocumentedDivergence(
    val sample: String,
    val signals: Set<String>,
    val rationale: String,
)

/**
 * The documented-divergence allowlist. **Every entry is a place where jadx miscompiles and jadxmp
 * refuses to (Rule 4: no silent code loss).** An entry converts a mechanical REGRESSION into an
 * [Verdict.EXPECTED_DIVERGENCE] — but ONLY when the jadx-passes/jadxmp-fails signals match the entry's
 * [DocumentedDivergence.signals] set *exactly* (see [SampleResult.classify]). It can therefore never
 * hide a NEW or DIFFERENT regression on the same sample.
 *
 * Adding an entry is a deliberate, adversarially-reviewed act:
 *  1. Dump both decompilers' output for the sample (`-Djadxmp.smali.dump=<name>`).
 *  2. Prove jadx's output is semantically WRONG for the divergent signal, and jadxmp's is faithful.
 *  3. Record the exact divergent signal(s) and a specific rationale below.
 *
 * When jadxmp later improves to pass an allowlisted signal, the runner flags the entry as STALE so it
 * gets removed — the allowlist must stay minimal and current, never accumulate dead suppressions.
 */
object DocumentedDivergences {

    /**
     * The two currently-justified entries. Both are cases where flipping jadxmp to "pass" would require
     * emitting jadx's semantically-broken structure, violating Rule 4.
     */
    val entries: List<DocumentedDivergence> = listOf(
        DocumentedDivergence(
            sample = "loops/TestLoopRestore3.smali",
            signals = setOf(SignalNames.NO_ERROR),
            rationale =
                "jadx's structured output moves the empty-collection `else` branch's `break` out of the " +
                    "outer `while(true)`, so that path SKIPS the trailing `atomicReference.compareAndSet(obj, ...)` " +
                    "retry loop entirely — the atomic update is silently dropped (a lost compare-and-swap). " +
                    "The CFG cannot be structured without either duplicating the CAS loop or emitting a wrong " +
                    "break, so jadxmp bails honestly with a `JADXMP ERROR: unstructured control flow` marker and " +
                    "faithful (uglier, block-labelled) output. jadx passes `no-error`; jadxmp fails it by design. " +
                    "Both sides fail `recompiles` for unrelated missing-dependency reasons (not a divergence).",
        ),
        DocumentedDivergence(
            sample = "others/TestInsnsBeforeThis.smali",
            signals = setOf(SignalNames.RECOMPILES),
            rationale =
                "The constructor delegates with `this(str.length())` but a side-effecting `checkNull(str)` " +
                    "runs BEFORE the delegation in the bytecode. jadx reorders `this(...)` ahead of `checkNull(str)` " +
                    "(emitting `/* JADX WARN: 'this' call moved to the top of the method (can break code semantics) */`) " +
                    "purely so javac accepts it — changing observable order of a call that can throw. Java mandates " +
                    "this()/super() be the first statement, so jadxmp faithfully keeps `checkNull(str); this(...)`, " +
                    "which does not recompile. jadx passes `recompiles` by miscompiling; jadxmp fails it by staying " +
                    "faithful. Both sides pass `no-error` (not a divergence).",
        ),
    )

    private val bySample: Map<String, DocumentedDivergence> = entries.associateBy { it.sample }

    init {
        require(bySample.size == entries.size) { "Duplicate sample in documented-divergence allowlist" }
    }

    /** The allowlist entry for [sample], or `null` if the sample is not documented (the common case). */
    fun forSample(sample: String): DocumentedDivergence? = bySample[sample]
}
