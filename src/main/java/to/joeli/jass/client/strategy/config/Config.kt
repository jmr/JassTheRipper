package to.joeli.jass.client.strategy.config

class Config {

    var mctsConfig = MCTSConfig()

    var isMctsEnabled = true // disable this for pitting only the networks against each other

    var isScoreEstimatorUsed = false // This is used in Self Play Training
    var isCardsEstimatorUsed = false // This is used in Self Play Training
    var isScoreEstimatorTrainable = false // This is used in Self Play Training
    var isCardsEstimatorTrainable = false // This is used in Self Play Training

    /** Route the pgx PolicyValueNet value head into MCTS leaf scoring. */
    var isPgxValueUsed = false
    /** Route the pgx PolicyValueNet policy head into PUCT priors. */
    var isPgxPolicyUsed = false
    /**
     * Play the argmax of the pgx policy head directly, without MCTS. The net takes fully
     * determinized observations, so the policy is averaged over the same number of
     * determinizations MCTS would use for the round — forward passes only, no search.
     */
    var isPgxRawPlayUsed = false
    /**
     * Select trump by the argmax of the pgx policy head (indices 36–42), averaged over
     * determinizations — no search. Independent of card play: can be combined with any
     * card-play method (MCTS, PUCT, raw). Overrides [trumpfSelectionMethod] when enabled.
     */
    var isPgxTrumpUsed = false
    /** Path to the pgx TF2 SavedModel directory; null means pgx is disabled. */
    var pgxModelPath: String? = null
    /**
     * Path to the belief likelihood net's SavedModel (gen-11hc); null disables
     * belief-weighted determinization. When set, each card decision runs the
     * particle filter (PgxBeliefFilter) and MCTS root determinizations are drawn
     * ∝ the belief weights instead of uniformly (pgx log 2026-07-17).
     */
    var pgxBeliefModelPath: String? = null
    /** N candidate worlds per decision for the belief particle filter (pgx gate: 32). */
    var beliefParticles = 32
    /** λ share of uniform mixed into the belief weights — degenerate-likelihood guard (pgx gate: 0). */
    var beliefMixUniform = 0.0

    // TODO MCTS still does not like to shift by itself. It is forced to shift now because of the rule-based pruning
    //  --> Investigate why MCTS without pruning does not like shifting
    // NOTE: In Situations where shifting is good, MCTS is inferior.
    // In other situations they seem to be comparable but even there MCTS is weaker
    var trumpfSelectionMethod = TrumpfSelectionMethod.RULE_BASED

    constructor()

    constructor(mctsEnabled: Boolean, scoreEstimatorUsed: Boolean, scoreEstimatorTrainable: Boolean) {
        this.isMctsEnabled = mctsEnabled
        this.isScoreEstimatorUsed = scoreEstimatorUsed
        this.isScoreEstimatorTrainable = scoreEstimatorTrainable
    }

    constructor(mctsEnabled: Boolean, cardsEstimatorUsed: Boolean, cardsEstimatorTrainable: Boolean, scoreEstimatorUsed: Boolean, scoreEstimatorTrainable: Boolean) {
        this.isMctsEnabled = mctsEnabled
        this.isCardsEstimatorUsed = cardsEstimatorUsed
        this.isCardsEstimatorTrainable = cardsEstimatorTrainable
        this.isScoreEstimatorUsed = scoreEstimatorUsed
        this.isScoreEstimatorTrainable = scoreEstimatorTrainable
    }

    constructor(mctsConfig: MCTSConfig) {
        this.mctsConfig = mctsConfig
    }

    constructor(mctsConfig: MCTSConfig, trumpfSelectionMethod: TrumpfSelectionMethod) {
        this.mctsConfig = mctsConfig
        this.trumpfSelectionMethod = trumpfSelectionMethod
    }

    override fun toString(): String {
        return "Config(mctsConfig=$mctsConfig, isMctsEnabled=$isMctsEnabled, isScoreEstimatorUsed=$isScoreEstimatorUsed, isCardsEstimatorUsed=$isCardsEstimatorUsed, isScoreEstimatorTrainable=$isScoreEstimatorTrainable, isCardsEstimatorTrainable=$isCardsEstimatorTrainable, trumpfSelectionMethod=$trumpfSelectionMethod)"
    }


}
