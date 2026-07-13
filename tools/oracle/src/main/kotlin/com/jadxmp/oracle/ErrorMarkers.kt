package com.jadxmp.oracle

/**
 * Per-decompiler signal-1 (no-error) markers.
 *
 * Signal 1 is a **text scan for a decompiler's own failure sentinels** (on top of the structured
 * error-attribute count), so the marker set is decompiler-specific — jadx's literal strings can never
 * appear in jadxmp's clean-room output, and vice versa. Each [Decompiler] advertises its own hard
 * [Decompiler.errorMarkers].
 *
 * ## Why WARN is NOT a hard failure (deliberate divergence from jadx's default `checkCode`)
 * jadx's own `TestUtils.checkCode` defaults to `allowWarnInCode = false`, failing on `JADX WARN` too.
 * For a **differential** gate that strictness is actively harmful: a benign `/* JADX WARN: Code
 * duplicated */` on otherwise-correct jadx output would make the *reference* fail no-error, so a
 * genuinely-broken jadxmp rendering of the same method could no longer be flagged as a REGRESSION
 * (jadx "passed" nothing to lose) — and it fabricated false IMPROVEMENTs where jadx merely warned.
 * PROVEN by `switches/TestSwitchOverStrings4` (jadx: clean switch + benign WARN; jadxmp: `block0:{}`
 * soup, reportedErrors=1). So the hard [JADX]/[JADXMP] sets contain only ERROR-level sentinels (plus
 * the one genuinely-dangerous "code lost" WARN, see below); benign WARNs never fail the no-error
 * signal. This makes the gate STRONGER (surfaces real regressions), not weaker.
 */
object ErrorMarkers {

    /**
     * jadx's **hard** failure markers — an error the decompiler itself flagged, meaning it gave up,
     * emitted knowingly-wrong code, or LOST code:
     * - `"JADX ERROR"` — jadx renders comments as `"JADX " + level.name() + ": …"` (`CodeGenUtils`).
     * - `"Code decompiled incorrectly"` — the stable "inconsistent code" sentence from `MethodGen`
     *   (keyed off the fixed sentence, not the bare word `"inconsistent"` which is metadata-mode only).
     * - `"Code restructure failed"` — the ONE genuinely-dangerous WARN (`CheckRegions`: "...code lost:"),
     *   meaning jadx dropped instructions. Treated as HARD even though it is emitted at WARN level: it
     *   denotes real code loss, not a cosmetic note. Defense-in-depth against a future output where it
     *   appears without one of the other sentinels.
     */
    val JADX: List<String> = listOf(
        "JADX ERROR",
        "Code decompiled incorrectly",
        "Code restructure failed",
    )

    /**
     * jadxmp's clean-room **hard** failure markers. The structured `AttrFlag.HAS_ERROR` count
     * (carried as `reportedErrors`) is the primary jadxmp no-error signal; these text sentinels are the
     * belt-and-suspenders for broken output the engine renders but forgot to flag. Reconcile with
     * jadxmp's actual codegen sentinels as the engine evolves.
     */
    val JADXMP: List<String> = listOf(
        "JADXMP ERROR",
        "Code decompiled incorrectly",
    )
}
