package to.joeli.jass.client.strategy.config

/**
 * Describes the strength of the strategy. More configurations can be added when needed.
 * NOTE: When using a value estimator much fewer runs are needed than when using random playouts
 *
 *
 * IMPORTANT: maxThinkingTime (the maximal number of milliseconds per choose card move) has to
 * be tweaked in order not to exceed Timeout but still compute good moves
 *
 *
 * IMPORTANT: numDeterminizationsFactor is a hyperparameter that has to be tweaked
 * The MCTS creates (9-roundNumber) * numDeterminizationsFactor determinizations.
 * The higher this number, the more threads are spawned.
 *
 *
 * IMPORTANT: numRuns is a hyperparameter. Determines how many nodes should be explored in one mcts tree
 *
 *
 * Card rollouts per move (RUNS mode), where round is 0-indexed (0 = first trick, 8 = last):
 * ```
 * numDeterminizations = (9 - round) * numDeterminizationsFactor
 * cardsPerPlayout     = 4 * (9 - round)   // 4 players, one card each per remaining trick
 * cardsPerMove        = numDeterminizations * numRuns * cardsPerPlayout
 *                     = 4 * (9-round)^2 * numDeterminizationsFactor * numRuns
 * ```
 * For POWERFUL (factor=5, numRuns=200) at rounds 0/4/8: ~324k / ~100k / ~4k cards/move.
 * Summed over all 9 rounds: 4 * factor * numRuns * Σ(k=1..9) k²  =  4 * factor * numRuns * 285
 * For POWERFUL: 4 * 5 * 200 * 285 = 1,140,000 card simulations per game.
 * In TIME mode, numRuns is unused. Each determinization thread loops until the clock runs out:
 * ```
 * effectiveRunsPerDet ≈ (maxThinkingTime_ms / 1000) * rolloutSpeed   // rolloutSpeed in rollouts/sec, hardware-dependent
 * ```
 */
enum class StrengthLevel constructor(val numDeterminizationsFactor: Int, val maxThinkingTime: Long, val numRuns: Long) {

    FAST_TEST(1, 50, 10),
    TEST(2, 100, 20),
    FAST(3, 200, 40),
    STRONG(4, 500, 100),
    POWERFUL(5, 1000, 200),
    POWERFUL_10X(5, 1000, 2000),      // temporary: 10x numRuns of POWERFUL to test RUNS mode scaling
    POWERFUL_100X(5, 1000, 20000),    // temporary: 100x numRuns of POWERFUL (~reasonable think time on dev machine)
    POWERFUL_100X_DETS(500, 1000, 200), // temporary: 100x numDeterminizationsFactor of POWERFUL (same total compute as 100X)

    // PUCT runs/determination sweep (Component C, real-PUCT validation). With a network leaf,
    // MCTSHelper divides numRuns by 10, so runs/det = numRuns/10. These fill the gap between
    // POWERFUL (20 runs/det) and POWERFUL_10X (200 runs/det) to locate where soft-prior PUCT
    // starts beating the raw prior (pgx crossover was ~sims 40-50, healthy by ~128). Use in
    // RUNS mode; maxThinkingTime is unused there.
    SWEEP_32(5, 10000, 320),    // 32 runs/det
    SWEEP_64(5, 10000, 640),    // 64 runs/det
    SWEEP_128(5, 10000, 1280),  // 128 runs/det
    SWEEP_256(5, 10000, 2560),  // 256 runs/det

    EXTREME(6, 2000, 400),
    INSANE(7, 2500, 500),
    SUPERMAN(8, 5000, 1000),
    IRONMAN(9, 10000, 2000),
    JASS_TEPPICH(10, 5000, 2000),
    HSLU_SERVER(15, 9900, 2000),
    TRUMPF(15, 10000, 2000),
    CARD_VALUATION(25, 30000, 2000),

    TEST_50_MS(1, 50, 10),
    TEST_100_MS(1, 100, 10),
    TEST_200_MS(1, 200, 10),
    TEST_500_MS(1, 500, 10),
    TEST_1000_MS(1, 1000, 10),
    TEST_5_DETERMINIZATIONS_FACTOR(5, 5000, 2000),
    TEST_10_DETERMINIZATIONS_FACTOR(10, 5000, 2000),
    TEST_15_DETERMINIZATIONS_FACTOR(15, 5000, 2000),
    TEST_20_DETERMINIZATIONS_FACTOR(20, 5000, 2000),
    TEST_500_DETERMINIZATIONS_FACTOR(500, 5000, 2000);

    override fun toString(): String {
        return this.name + ": {" +
                "numDeterminizationsFactor=" + numDeterminizationsFactor +
                ", maxThinkingTime=" + maxThinkingTime +
                ", numRuns=" + numRuns +
                '}'.toString()
    }
}
