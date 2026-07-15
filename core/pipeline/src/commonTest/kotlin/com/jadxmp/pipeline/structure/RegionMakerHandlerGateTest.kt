package com.jadxmp.pipeline.structure

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The HANDLER-GRANULAR open-try gate ([RegionMaker.gateFires]) — the data-structure refactor of the former
 * block-granular `isProtected(block) && block !in openTryBlocks`. These tests pin two properties:
 *
 *  (a) BEHAVIOUR-PRESERVATION of the old block-granular decision. Because every current reconstruct/makeTry
 *      path opens a block's ENTIRE protecting-handler set at once, "block in openTryBlocks" (old) is exactly
 *      "all protecting handlers open" (new). So for a single-handler block the gate suppresses re-opening iff
 *      that one handler is open — identical to the old set-membership test — and an unprotected block is never
 *      a try head.
 *  (b) The NEW expressiveness the refactor unlocks (unused by Phase A, enabling Phase B): on a block shared by
 *      two protecting handlers, opening only ONE still lets the OTHER re-trigger a try. The old block-granular
 *      set could not express this (the block was simply "open" or "not"), which is why the two-handler-shared-
 *      block shape (TestNestedTryCatch4) currently bails.
 */
class RegionMakerHandlerGateTest {

    // Opaque handler tokens; identity is all the gate uses (mirrors handler-entry BasicBlock identity).
    private val hFinally = "finally-catchall"
    private val hCatch = "typed-catch"

    // --- (a) behaviour-preservation: single-handler block reproduces the old set-membership decision ---

    @Test
    fun unprotectedBlockNeverOpensTry() {
        // isProtected(block) == false in the old gate ⇒ never a try head, regardless of the open set.
        assertFalse(RegionMaker.gateFires(protecting = emptyList<String>(), open = null))
        assertFalse(RegionMaker.gateFires(protecting = emptyList<String>(), open = setOf(hFinally)))
    }

    @Test
    fun singleHandlerNotYetOpenTriggersTry() {
        // Old: isProtected && block !in openTryBlocks ⇒ true. New: the one handler is unopened ⇒ true.
        assertTrue(RegionMaker.gateFires(protecting = listOf(hFinally), open = null))
        assertTrue(RegionMaker.gateFires(protecting = listOf(hFinally), open = emptySet()))
    }

    @Test
    fun singleHandlerAlreadyOpenSuppressesReopen() {
        // Old: block in openTryBlocks ⇒ false. New: the sole protecting handler is open ⇒ false. Identical.
        assertFalse(RegionMaker.gateFires(protecting = listOf(hFinally), open = setOf(hFinally)))
    }

    @Test
    fun multiHandlerBlockFullyOpenSuppressesReopen() {
        // The block-granular case the corpus actually exercises: a body block whose WHOLE handler set was
        // opened at once. All protecting handlers open ⇒ no re-open (== old "block in openTryBlocks").
        assertFalse(
            RegionMaker.gateFires(protecting = listOf(hCatch, hFinally), open = setOf(hCatch, hFinally)),
        )
        // An unrelated extra open handler is inert (never consulted for this block).
        assertFalse(
            RegionMaker.gateFires(protecting = listOf(hFinally), open = setOf(hFinally, "unrelated")),
        )
    }

    // --- (b) new capability: partially-open shared block can still open the other handler ---

    @Test
    fun sharedBlockWithOneHandlerOpenStillOpensTheOther() {
        // NEW: catch-all open, typed catch NOT ⇒ the typed catch can still be structured. The old
        // block-granular set could not say this — the block would be flatly "open" and ALL tries suppressed.
        assertTrue(RegionMaker.gateFires(protecting = listOf(hCatch, hFinally), open = setOf(hFinally)))
        // Symmetric: typed catch open, catch-all NOT ⇒ the catch-all can still open (the Phase B nesting).
        assertTrue(RegionMaker.gateFires(protecting = listOf(hCatch, hFinally), open = setOf(hCatch)))
    }
}
