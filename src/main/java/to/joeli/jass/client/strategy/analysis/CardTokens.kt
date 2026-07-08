package to.joeli.jass.client.strategy.analysis

import to.joeli.jass.game.cards.Card
import java.util.Locale

/**
 * Parses cards from the compact text tokens used by the `ApplicationAnalyze` CLI (`--cards`) and
 * by [GameNotation]: either an HSLU short code (`SA`, `H10`, `DQ`) or a [Card] enum name
 * (`SPADE_ACE`).
 */
object CardTokens {

    /** Parses a space- or comma-separated list of cards, each in HSLU short-code or enum-name form. */
    @JvmStatic
    fun parseCards(spec: String): Set<Card> {
        val tokens = spec.split(',', ' ').map { it.trim() }.filter { it.isNotEmpty() }
        val cards = tokens.map(::parseCard)
        val distinct = LinkedHashSet(cards)
        require(distinct.size == cards.size) { "Duplicate card in '$spec'" }
        return distinct
    }

    @JvmStatic
    fun parseCard(token: String): Card {
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
