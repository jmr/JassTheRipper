package to.joeli.jass

import to.joeli.jass.client.strategy.analysis.AnalysisCursor
import to.joeli.jass.client.strategy.analysis.CardAnalysis
import to.joeli.jass.client.strategy.analysis.CardTokens
import to.joeli.jass.client.strategy.analysis.GameNotation
import to.joeli.jass.client.strategy.analysis.ParsedGame
import to.joeli.jass.client.strategy.analysis.PgxPositionAnalyzer
import to.joeli.jass.client.strategy.analysis.TrumpAnalysis
import to.joeli.jass.client.strategy.config.Config
import to.joeli.jass.client.strategy.config.StrengthLevel
import to.joeli.jass.client.strategy.helpers.GameSessionBuilder
import to.joeli.jass.client.strategy.training.networks.PgxPolicyValueEstimator
import to.joeli.jass.game.cards.Card
import java.util.Locale

/**
 * Position-analysis CLI for the pgx policy/value net. Two ways to point it at a position:
 * `--cards` for a bare opening hand (trump only), or `--game` for a full history in
 * [GameNotation]'s compact notation (trump *or* mid-play card analysis, at whatever decision
 * point the notation's cursor lands on). Either way it prints the full, sorted decision
 * distribution instead of only the argmax that raw match play
 * ([to.joeli.jass.client.strategy.JassTheRipperJassStrategy.chooseTrumpf]/`choosePgxRawCard` with
 * `--pgx-trump`/`--pgx-raw`) picks. Thin wrapper around [PgxPositionAnalyzer] and [GameNotation]
 * — see those classes for the shared evaluation/reconstruction logic.
 *
 * <pre>
 *   JassTheRipperAnalyze --cards="SA SK SQ SJ S10 HA HK H10 D7"
 *   JassTheRipperAnalyze --game="SA SK SQ SJ S10 HA HK H10 D7 / 0 2 / G"
 * </pre>
 *
 * Flags (a subset of [ApplicationArena]'s, single position — no per-team suffix):
 * <pre>
 *   --cards=&lt;9 cards&gt;   Space- or comma-separated hand: HSLU short codes (e.g. SA, H10, DQ) or
 *                       enum names (e.g. SPADE_ACE). Trump-declaration analysis only; exactly one
 *                       of --cards/--game is required.
 *   --shifted           With --cards: analyze as the partner declaring after the forehand passed.
 *   --game=&lt;notation&gt;   A full game history in GameNotation's notation; see that class's doc.
 *                       Exactly one of --cards/--game is required.
 *   --pgx-model=&lt;path&gt;  pgx SavedModel directory (default: PgxPolicyValueEstimator.DEFAULT_MODEL_PATH)
 *   --strength=&lt;level&gt;  StrengthLevel setting the trumpf determinization budget (default:
 *                       MCTSConfig's default, POWERFUL). Unlike ApplicationArena's --strength,
 *                       which only ever sets cardStrengthLevel, here it sets trumpfStrengthLevel
 *                       directly since this tool only analyzes trump selection so far.
 *   --cheating          Single forward pass, no hidden-card sampling. With --cards (no history),
 *                       the other 27 cards are an arbitrary fixed placeholder deal, not anything
 *                       meaningful. With --game, the other three hands are still empty (that
 *                       notation never carries them — see GameNotation's doc) so --cheating only
 *                       saves the determinization loop, it doesn't add real information.
 * </pre>
 *
 * Note: sampling hidden cards uses the JVM's global, unseeded {@code Collections.shuffle} (same
 * as raw match play), so repeated runs of an identical command will vary — there is currently no
 * reproducible-seed knob for this, in this tool or in match play.
 */
object ApplicationAnalyze {

    @JvmStatic
    fun main(args: Array<String>) {
        val flags = Application.parseFlags(args)

        val cardsFlag = flags["cards"]
        val gameFlag = flags["game"]
        require((cardsFlag == null) != (gameFlag == null)) {
            "specify exactly one of --cards=<9 cards> or --game=<notation>"
        }

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
        val analyzer = PgxPositionAnalyzer(estimator, config)

        if (cardsFlag != null) {
            val cards = CardTokens.parseCards(cardsFlag)
            require(cards.size == 9) { "Expected 9 distinct cards, got ${cards.size}: $cards" }
            val shifted = flags.containsKey("shifted")

            // No history (see class doc): an otherwise-empty session is enough because
            // PgxPositionAnalyzer/CardKnowledgeBase overwrite every hand from `cards` and a fresh
            // random deal of the other 27 cards, ignoring whatever placeholder hands the builder set.
            val session = GameSessionBuilder.newSession().createGameSession()
            println("Hand: ${cards.joinToString(", ")}" + if (shifted) " (shifted)" else "")
            printTrumpAnalysis(analyzer.analyzeTrump(session, cards, shifted))
        } else {
            val parsed = GameNotation.parse(gameFlag!!)
            printPositionSummary(parsed)
            when (val cursor = GameNotation.buildCursor(parsed)) {
                is AnalysisCursor.Trump ->
                    printTrumpAnalysis(analyzer.analyzeTrump(cursor.session, parsed.povHand, cursor.shifted))
                is AnalysisCursor.CardPlay ->
                    printCardAnalysis(analyzer.analyzeCard(cursor.game, cursor.availableCards, cursor.possibleCards))
            }
        }
    }

    private fun printPositionSummary(parsed: ParsedGame) {
        println(String.format(Locale.ROOT,
            "Position: pov=%d forehand=%d shifted=%b trump=%s tricksRecorded=%d",
            parsed.povSeat, parsed.foreSeat, parsed.shifted,
            parsed.mode?.toString() ?: "undeclared", parsed.tricks.size))
    }

    private fun printTrumpAnalysis(analysis: TrumpAnalysis) {
        println(String.format(Locale.ROOT,
            "Trump analysis over %d determinizations (mean value estimate %.1f pts):",
            analysis.numDeterminizations, analysis.meanValue))
        println(String.format(Locale.ROOT, "%-12s %8s %10s", "Mode", "Prob", "Logit"))
        analysis.ranked.forEach { (mode, prob) ->
            val logit = analysis.meanLogits.getValue(mode)
            println(String.format(Locale.ROOT, "%-12s %7.1f%% %10.3f", mode.toString(), prob * 100, logit))
        }
    }

    private fun printCardAnalysis(analysis: CardAnalysis) {
        println(String.format(Locale.ROOT, "Card analysis over %d determinizations:", analysis.numDeterminizations))
        println(String.format(Locale.ROOT, "%-6s %8s", "Card", "Prob"))
        analysis.ranked.forEach { (card: Card, prob) ->
            println(String.format(Locale.ROOT, "%-6s %7.1f%%", card.toString(), prob * 100))
        }
    }
}
