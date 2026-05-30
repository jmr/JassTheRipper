package to.joeli.jass.client.strategy.config

import kotlin.math.pow

/**
 * Controls how numRuns is distributed across rounds in RUNS mode.
 * All three modes are normalized so total card simulations per game equal the FLAT baseline,
 * allowing fair quality comparisons:
 *
 * ```
 * normFactor = Σ(k=1..9) k² / Σ(k=1..9) k^(2+exponent)
 *            =       285     /  [2025 (linear) | 15333 (quadratic)]
 * runsForRound = max(1, round(baseRuns * normFactor * (9-round)^exponent))
 * ```
 *
 * Effective numRuns for POWERFUL (baseRuns=200) at rounds 0/4/8:
 *   FLAT:      200 / 200 / 200
 *   LINEAR:    253 / 141 /  28
 *   QUADRATIC: 301 /  93 /   4
 */
enum class RunsScaling(val exponent: Int, val normFactor: Double) {
    FLAT(0, 1.0),
    LINEAR(1, 285.0 / 2025.0),
    QUADRATIC(2, 285.0 / 15333.0);

    fun computeRuns(baseRuns: Long, roundNumber: Int): Long {
        val k = (9 - roundNumber).toDouble()
        return maxOf(1L, (baseRuns * normFactor * k.pow(exponent)).toLong())
    }
}
