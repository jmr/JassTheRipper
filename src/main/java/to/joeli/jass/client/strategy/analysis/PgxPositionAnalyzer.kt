package to.joeli.jass.client.strategy.analysis

import to.joeli.jass.client.game.Game
import to.joeli.jass.client.game.GameSession
import to.joeli.jass.client.strategy.config.Config
import to.joeli.jass.client.strategy.helpers.CardKnowledgeBase
import to.joeli.jass.client.strategy.training.networks.CardsEstimator
import to.joeli.jass.client.strategy.training.networks.PgxPolicyValueEstimator
import to.joeli.jass.game.cards.Card
import to.joeli.jass.game.mode.Mode
import java.util.EnumMap

/**
 * The result of [PgxPositionAnalyzer.analyzeTrump]: legal trump declarations ranked by mean
 * pgx-policy probability over the determinizations sampled, best first, alongside the mean raw
 * (pre-softmax) logit per mode and the mean value-head estimate — both diagnostic, not used by
 * raw trump play (which only takes [best]).
 */
data class TrumpAnalysis(
    val ranked: List<Pair<Mode, Double>>,
    val meanLogits: Map<Mode, Double>,
    val meanValue: Double,
    val numDeterminizations: Int
) {
    /** Argmax mode — what [PgxPositionAnalyzer]-driven raw play would choose. */
    val best: Mode get() = ranked.first().first
}

/**
 * The result of [PgxPositionAnalyzer.analyzeCard]: possible cards ranked by mean pgx-policy
 * probability over the determinizations sampled, best first.
 */
data class CardAnalysis(val ranked: List<Pair<Card, Double>>, val numDeterminizations: Int) {
    /** Argmax card — what [PgxPositionAnalyzer]-driven raw play would choose. */
    val best: Card get() = ranked.first().first
}

/**
 * Evaluates a (possibly imperfect-information) Jass position with the pgx policy net and
 * reports the *full* ranked distribution over legal actions, rather than only the argmax.
 *
 * This is the shared core behind raw pgx play (`--pgx-trump` / `--pgx-raw` in
 * [to.joeli.jass.client.strategy.JassTheRipperJassStrategy], which takes [TrumpAnalysis.best] /
 * [CardAnalysis.best]) and the `ApplicationAnalyze` CLI, which prints the whole ranking. The net
 * only ever sees fully-determinized full-information observations, so both methods sample the
 * hidden cards repeatedly (mirroring the determinization budget MCTS would use for the same
 * position) and average the masked policy over the samples.
 *
 * @param estimator a loaded [PgxPolicyValueEstimator]
 * @param config supplies the determinization budget (trumpfStrengthLevel / cardStrengthLevel /
 *   cheating / trumpConditionedDeterminization) via [Config.getMctsConfig]
 * @param cardsEstimator optional cards estimator consulted while sampling determinizations for
 *   [analyzeCard] (same role as [to.joeli.jass.client.strategy.JassTheRipperJassStrategy.getCardsEstimator])
 */
class PgxPositionAnalyzer @JvmOverloads constructor(
    private val estimator: PgxPolicyValueEstimator,
    private val config: Config,
    private val cardsEstimator: CardsEstimator? = null
) {

    /**
     * Evaluates the legal trump declarations for the trumpf-selection phase of [session].
     *
     * @param session a session in the trumpf-selection phase; only the declaring player's hand
     *   needs to be populated (the other three are overwritten by determinization unless cheating)
     * @param availableCards the declaring player's 9 cards
     * @param shifted whether the forehand already passed (removes Schiebe from the legal set;
     *   the declaring player is then the forehand's partner, per [GameSession.getPartnerOfPlayer])
     */
    fun analyzeTrump(session: GameSession, availableCards: Set<Card>, shifted: Boolean): TrumpAnalysis {
        val mctsConfig = config.mctsConfig
        val cheating = mctsConfig.cheating
        val numDeterminizations = if (cheating) 1
            else TRUMP_ROUND_MULTIPLIER * mctsConfig.trumpfStrengthLevel.numDeterminizationsFactor

        val summedProbs = HashMap<Mode, Double>()
        val summedLogits = HashMap<Mode, Double>()
        var summedValue = 0.0
        repeat(numDeterminizations) {
            val determinizedSession = GameSession(session)
            if (!cheating) {
                CardKnowledgeBase.sampleCardDeterminizationToPlayers(determinizedSession, availableCards, shifted)
            }
            val result = estimator.predictTrumpOverLegal(determinizedSession, shifted)
            result.probabilities.forEach { (mode, p) -> summedProbs.merge(mode, p.toDouble(), Double::plus) }
            result.logits.forEach { (mode, l) -> summedLogits.merge(mode, l.toDouble(), Double::plus) }
            summedValue += result.value
        }

        val ranked = summedProbs.entries
            .map { it.key to it.value / numDeterminizations }
            .sortedByDescending { it.second }
        val meanLogits = summedLogits.mapValues { it.value / numDeterminizations }
        return TrumpAnalysis(ranked, meanLogits, summedValue / numDeterminizations, numDeterminizations)
    }

    /**
     * Evaluates the possible cards to play from the current position in [game].
     *
     * @param game a fully- or partially-determinized game (imperfect-information hands are
     *   overwritten by determinization unless cheating)
     * @param availableCards the current player's hand
     * @param possibleCards the subset of [availableCards] that are legal to play now
     */
    fun analyzeCard(game: Game, availableCards: Set<Card>, possibleCards: Set<Card>): CardAnalysis {
        val mctsConfig = config.mctsConfig
        val cheating = mctsConfig.cheating
        val numDeterminizations = if (cheating) 1
            else (9 - game.currentRound.roundNumber) * mctsConfig.cardStrengthLevel.numDeterminizationsFactor

        val summed = EnumMap<Card, Double>(Card::class.java)
        repeat(numDeterminizations) {
            val determinizedGame = Game(game)
            if (!cheating) {
                CardKnowledgeBase.sampleCardDeterminizationToPlayers(
                    determinizedGame, availableCards, cardsEstimator, mctsConfig.trumpConditionedDeterminization)
            }
            val prior = estimator.predictPriorOverLegal(determinizedGame, possibleCards)
            prior.forEach { (card, p) -> summed.merge(card, p.toDouble(), Double::plus) }
        }

        val ranked = summed.entries
            .map { it.key to it.value / numDeterminizations }
            .sortedByDescending { it.second }
        return CardAnalysis(ranked, numDeterminizations)
    }

    companion object {
        /**
         * Determinizations for trump selection, mirroring the trumpf-phase budget MCTS would use
         * in RUNS mode: `ROUND_MULTIPLIER (10) x numDeterminizationsFactor` (see
         * `MCTSHelper.computeNumDeterminizations`). Round 0, so the round discount doesn't apply.
         */
        private const val TRUMP_ROUND_MULTIPLIER = 10
    }
}
