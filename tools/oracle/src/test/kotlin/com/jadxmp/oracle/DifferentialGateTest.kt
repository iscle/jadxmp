package com.jadxmp.oracle

import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DifferentialGateTest {
    private val passing = SignalScore(noErrors = true, recompiles = true, executesCheck = null)

    @Test
    fun completeParityPassesButRegressionFailsTheBuild() {
        val parity = Scoreboard().apply { add(SampleResult("good", passing, passing)) }
        assertDoesNotThrow { requireDifferentialParity(parity, 1, emptyList(), emptyList()) }
        val regression = Scoreboard().apply {
            add(SampleResult("broken", passing, passing.copy(recompiles = false)))
        }
        val failure = assertThrows(IllegalStateException::class.java) {
            requireDifferentialParity(regression, 1, emptyList(), emptyList())
        }
        assertTrue(failure.message.orEmpty().contains("broken"))
    }

    @Test
    fun missingInputsAndFailedMeasurementsCannotPassVacuously() {
        assertThrows(IllegalStateException::class.java) {
            requireDifferentialParity(Scoreboard(), 0, emptyList(), emptyList())
        }
        assertThrows(IllegalStateException::class.java) {
            requireDifferentialParity(Scoreboard(), 1, listOf("assembly failed"), emptyList())
        }
        assertThrows(IllegalStateException::class.java) {
            requireDifferentialParity(Scoreboard(), 1, emptyList(), listOf("reference crashed"))
        }
    }
}
