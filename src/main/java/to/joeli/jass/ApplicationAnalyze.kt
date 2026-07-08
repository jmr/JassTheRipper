package to.joeli.jass

import to.joeli.jass.client.strategy.analysis.PgxPositionAnalyzer
import to.joeli.jass.client.strategy.config.Config
import to.joeli.jass.client.strategy.config.StrengthLevel
import to.joeli.jass.client.strategy.helpers.GameSessionBuilder
import to.joeli.jass.client.strategy.training.networks.PgxPolicyValueEstimator
import to.joeli.jass.game.cards.Card
import java.util.Locale

/**
 * Position-analysis CLI for the pgx policy/value net: type a 9-card hand and see the full,
 * sorted trump-declaration evaluation, instead of only the argmax that raw match play
 * ([to.joeli.jass.client.strategy.JassTheRipperJassStrategy.chooseTrumpf] with `--pgx-trump`)
 * picks. Thin wrapper around [PgxPositionAnalyzer] — see that class for the shared evaluation
 * logic.
 *
 * <pre>
 *   JassTheRipperAnalyze --cards="SA SK SQ SJ S10 HA HK H10 D7"
 * </pre>
 *
 * Flags (a subset of [ApplicationArena]'s, single position — no per-team suffix):
 * <pre>
 *   --cards=&lt;9 cards&gt;   Required. Space- or comma-separated hand: HSLU short codes
 *                       (e.g. SA, H10, DQ) or enum names (e.g. SPADE_ACE).
 *   --pgx-model=&lt;path&gt;  pgx SavedModel directory (default: PgxPolicyValueEstimator.DEFAULT_MODEL_PATH)
 *   --strength=&lt;level&gt;  StrengthLevel setting the trumpf determinization budget (default:
 *                       MCTSConfig's default, POWERFUL). Unlike ApplicationArena's --strength,
 *                       which only ever sets cardStrengthLevel, here it sets trumpfStrengthLevel
 *                       directly since this tool only analyzes trump selection so far.
 *   --cheating          Single forward pass, no hidden-card sampling. Without table history
 *                       (see class doc) this tool has no real opponent hands to feed in, so the
 *                       other 27 cards are an arbitrary fixed placeholder deal, not anything
 *                       meaningful — mainly useful once full-table history is supported.
 *   --shifted           Analyze as the partner declaring after the forehand passed
 * </pre>
 *
 * Note: sampling the 27 hidden cards uses the JVM's global, unseeded
 * {@code Collections.shuffle} (same as raw match play), so repeated runs of an identical
 * command will vary — there is currently no reproducible-seed knob for this, in this tool or
 * in match play.
 */
object ApplicationAnalyze {

    @JvmStatic
    fun main(args: Array<String>) {
        val flags = Application.parseFlags(args)

        val cardsFlag = flags["cards"]
            ?: throw IllegalArgumentException(
                "--cards=<9 cards> is required, e.g. --cards=\"SA SK SQ SJ S10 HA HK H10 D7\"")
        val cards = parseCards(cardsFlag)
        require(cards.size == 9) { "Expected 9 distinct cards, got ${cards.size}: $cards" }

        val shifted = flags.containsKey("shifted")
        val config = Config()
        if (flags.containsKey("strength")) {
            config.mctsConfig.trumpfStrengthLevel = StrengthLevel.valueOf(flags.getValue("strength"))
        }
        if (flags.containsKey("cheating")) {
            config.mctsConfig.cheating = true
        }

        val modelPath = flags.getOrDefault("pgx-model", PgxPolicyValueEstimator.DEFAULT_MODEL_PATH)
        val estimator = PgxPolicyValueEstimator()
        estimator.loadModel(modelPath)

        // No history yet (see class doc): an otherwise-empty session is enough because
        // PgxPositionAnalyzer/CardKnowledgeBase overwrite every hand from `cards` and a fresh
        // random deal of the other 27 cards, ignoring whatever placeholder hands the builder set.
        val session = GameSessionBuilder.newSession().createGameSession()
        val analysis = PgxPositionAnalyzer(estimator, config).analyzeTrump(session, cards, shifted)

        println("Hand: ${cards.joinToString(", ")}" + if (shifted) " (shifted)" else "")
        println(String.format(Locale.ROOT,
            "Trump analysis over %d determinizations (mean value estimate %.1f pts):",
            analysis.numDeterminizations, analysis.meanValue))
        println(String.format(Locale.ROOT, "%-12s %8s %10s", "Mode", "Prob", "Logit"))
        analysis.ranked.forEach { (mode, prob) ->
            val logit = analysis.meanLogits.getValue(mode)
            println(String.format(Locale.ROOT, "%-12s %7.1f%% %10.3f", mode.toString(), prob * 100, logit))
        }
    }

    /** Parses a space- or comma-separated list of cards, each in HSLU short-code or enum-name form. */
    private fun parseCards(spec: String): Set<Card> {
        val tokens = spec.split(',', ' ').map { it.trim() }.filter { it.isNotEmpty() }
        val cards = tokens.map(::parseCard)
        val distinct = LinkedHashSet(cards)
        require(distinct.size == cards.size) { "Duplicate card in --cards: $spec" }
        return distinct
    }

    private fun parseCard(token: String): Card {
        try {
            return Card.getCard(token)
        } catch (e: Exception) {
            try {
                return Card.valueOf(token.uppercase(Locale.ROOT))
            } catch (e2: Exception) {
                throw IllegalArgumentException(
                    "Unrecognized card '$token' (expected HSLU short code like SA/H10, or enum name like SPADE_ACE)", e2)
            }
        }
    }
}
