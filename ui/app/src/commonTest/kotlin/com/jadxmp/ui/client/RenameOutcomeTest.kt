package com.jadxmp.ui.client

import com.jadxmp.api.RenameResult
import com.jadxmp.codegen.ClassNodeRef
import com.jadxmp.codegen.FieldNodeRef
import com.jadxmp.codegen.MethodNodeRef
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Unit tests for [toRenameOutcome] — the pure projection of the engine's `RenameResult` to the engine-free
 * [RenameOutcome] the workbench consumes. The one success case carries the applied name; each of the three
 * rejections folds to [RenameOutcome.Rejected] surfacing the engine's own reason/conflict text verbatim.
 */
class RenameOutcomeTest {

    private val cls = ClassNodeRef("pkg.A")

    @Test
    fun appliedCarriesTheAppliedName() {
        assertEquals(
            RenameOutcome.Applied("Greeter"),
            RenameResult.Applied(cls, "Greeter").toRenameOutcome(),
        )
    }

    @Test
    fun invalidNameFoldsToRejectedWithItsReason() {
        assertEquals(
            RenameOutcome.Rejected("'int' is a Java reserved word"),
            RenameResult.InvalidName(cls, "int", "'int' is a Java reserved word").toRenameOutcome(),
        )
    }

    @Test
    fun collisionFoldsToRejectedWithItsConflictText() {
        assertEquals(
            RenameOutcome.Rejected("package 'pkg' already has a class named 'B'"),
            RenameResult.Collision(cls, "B", "package 'pkg' already has a class named 'B'").toRenameOutcome(),
        )
    }

    @Test
    fun unrenamableTargetFoldsToRejectedWithItsReason() {
        assertEquals(
            RenameOutcome.Rejected("constructors and static initializers cannot be renamed"),
            RenameResult.UnrenamableTarget(
                MethodNodeRef("A", "<init>", emptyList()),
                "constructors and static initializers cannot be renamed",
            ).toRenameOutcome(),
        )
    }

    @Test
    fun fieldRejectionAlsoCarriesTheReason() {
        assertEquals(
            RenameOutcome.Rejected("no such field on the declaring class"),
            RenameResult.UnrenamableTarget(FieldNodeRef("A", "x"), "no such field on the declaring class")
                .toRenameOutcome(),
        )
    }
}
