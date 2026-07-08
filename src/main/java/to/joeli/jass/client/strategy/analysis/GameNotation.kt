package to.joeli.jass.client.strategy.analysis

import to.joeli.jass.client.game.Game
import to.joeli.jass.client.game.GameSession
import to.joeli.jass.client.game.Move
import to.joeli.jass.client.game.Player
import to.joeli.jass.client.game.Team
import to.joeli.jass.client.strategy.JassTheRipperJassStrategy
import to.joeli.jass.client.strategy.helpers.CardSelectionHelper
import to.joeli.jass.client.strategy.helpers.GameSessionBuilder
import to.joeli.jass.game.cards.Card
import to.joeli.jass.game.cards.Color
import to.joeli.jass.game.mode.Mode
import java.util.EnumSet

/** A single recorded trick: cards in play order starting from the leader. */
data class ParsedTrick(val cards: List<Card>, val leaderSeat: Int?, val winnerSeat: Int?)

/**
 * A game history from one player's point of view, as parsed from the notation described in
 * [GameNotation]'s class doc.
 */
data class ParsedGame(
    val povHand: Set<Card>,
    val povSeat: Int,
    val foreSeat: Int,
    val shifted: Boolean,
    val mode: Mode?,
    val tricks: List<ParsedTrick>
)

/** The position [GameNotation.buildCursor] reconstructed, ready to hand to [PgxPositionAnalyzer]. */
sealed class AnalysisCursor {
    /** Trump-declaration cursor: mirrors what `ApplicationAnalyze`'s `--cards`/`--shifted` build. */
    data class Trump(val session: GameSession, val shifted: Boolean) : AnalysisCursor()

    /** Card-play cursor: [availableCards] is pov's remaining hand, [possibleCards] the legal subset. */
    data class CardPlay(val game: Game, val availableCards: Set<Card>, val possibleCards: Set<Card>) :
        AnalysisCursor()
}

/**
 * Parses the compact, FEN-like game-history notation used by the `ApplicationAnalyze` `--game`
 * flag, and reconstructs the position it describes so [PgxPositionAnalyzer] can evaluate it.
 *
 * ```
 * <pov_hand> / <pov_seat> <forehand_seat> [ / <trump> ] [ / <trick> ]*
 * ```
 *
 * - `pov_hand`: the 9 cards *dealt* to the analyzing player (not their current remaining hand —
 *   see below), in [CardTokens] form.
 * - `pov_seat forehand_seat`: two seat numbers 0-3. Partners sit at `(seat + 2) & 3`.
 * - `trump` (optional): `D`/`H`/`S`/`C` (colour trump), `O` (Obeabe/top-down), `U` (Undeufe/
 *   bottom-up), or `G` (Geschoben/shift). A shift followed by the partner's declaration is two
 *   tokens, e.g. `G H`. Omitted entirely (or a bare `G`) means trump hasn't been declared yet —
 *   *that* declaration becomes the thing to analyze.
 * - `trick`: `[leader] c1 c2 c3 c4 [=winner]`, cards in play order from the leader. `leader`/
 *   `=winner` are optional checksums, validated against the engine when present rather than
 *   trusted blindly. Every trick must have 4 cards except optionally the last, which may be
 *   partial (1-3 cards) — that partial trick is the card-play cursor.
 *
 * Examples: `SA SK SQ SJ S10 HA HK H10 D7 / 0 2 / G` (pov 0, forehand 2 shifted to pov: analyze
 * pov's trump choice); `<hand> / 0 0 / H / 0 HA H10 HK H6 =0 / 0 S...` (Hearts, mid-trick 2: what
 * should seat 0 play).
 *
 * ### Why POV-only, and why replaying it looks like this
 *
 * This mirrors the real HSLU wire protocol ([to.joeli.jass.client.websocket.GameHandler]), which
 * is itself POV-only and incremental: `DEAL_CARD` tells the local player only its own 9 cards
 * (the other three hands are never sent), `PLAYED_CARDS` arrives one card at a time, and
 * `BROADCAST_STICH` announces each trick's winner. Reconstructing a position from this notation
 * ([buildCardCursor]) replays that same event sequence — `startNewGame` (= `BROADCAST_TRUMPF`),
 * `makeMove` per recorded card (= `PLAYED_CARDS`), `startNextRound` per completed trick
 * (= `BROADCAST_STICH`) — just without generating the `ChooseTrumpf`/`ChooseCard` responses in
 * between. [Round.makeMove][to.joeli.jass.client.game.Round.makeMove] only checks turn order, not
 * hand membership, so the other three seats can stay empty throughout: no hidden hand is ever
 * invented, exactly like the HSLU advisor client that replays other players' moves the same way.
 *
 * (The HSLU protocol also has a snapshot-style message, `SessionJoinedData.GameStateReplay` +
 * `GameSession.startNewGameAt`, for advisors joining mid-hand — but it only carries the
 * *in-flight* trick, not earlier completed tricks. That's fine for the server's purposes but not
 * for the pgx net, which needs every previously-played card removed from the determinization
 * pool, so this notation always carries full trick history and replays it in full instead.)
 */
object GameNotation {

    @JvmStatic
    fun parse(spec: String): ParsedGame {
        val sections = spec.split('/').map { it.trim() }
        require(sections.size >= 2) {
            "expected at least '<pov_hand> / <pov_seat> <forehand_seat>', got '$spec'"
        }

        val povHand = CardTokens.parseCards(sections[0])
        require(povHand.size == 9) { "expected 9 distinct cards in the pov hand, got ${povHand.size}: $povHand" }

        val seatTokens = sections[1].split(Regex("\\s+")).filter { it.isNotEmpty() }
        require(seatTokens.size == 2) { "expected '<pov_seat> <forehand_seat>', got '${sections[1]}'" }
        val povSeat = parseSeat(seatTokens[0])
        val foreSeat = parseSeat(seatTokens[1])

        var remainingSections = sections.drop(2)
        var shifted = false
        var mode: Mode? = null
        if (remainingSections.isNotEmpty()) {
            val (parsedShifted, parsedMode) = parseTrumpSection(remainingSections[0])
            shifted = parsedShifted
            mode = parsedMode
            remainingSections = remainingSections.drop(1)
        }

        if (mode == null) {
            // Trump not resolved yet: this notation only carries pov's own information, so it can
            // only pose "what should I declare" -- pov must be the one deciding (forehand, or its
            // partner once the forehand has shifted).
            val decider = if (shifted) partnerOf(foreSeat) else foreSeat
            require(povSeat == decider) {
                "pov seat $povSeat is not the trump decider (seat $decider) for shifted=$shifted"
            }
            require(remainingSections.isEmpty()) { "cards can't be played before trump is declared" }
        }

        val tricks = remainingSections.mapIndexed { i, section -> parseTrick(section, i + 1) }
        require(tricks.size <= 9) { "a game has at most 9 tricks, got ${tricks.size}" }
        tricks.dropLast(1).forEachIndexed { i, trick ->
            require(trick.cards.size == 4) {
                "trick ${i + 1} has ${trick.cards.size} card(s); only the last recorded trick may be partial"
            }
        }

        return ParsedGame(povHand, povSeat, foreSeat, shifted, mode, tricks)
    }

    /** Dispatches to [buildTrumpCursor] or [buildCardCursor] depending on whether trump is resolved. */
    @JvmStatic
    fun buildCursor(parsed: ParsedGame): AnalysisCursor =
        if (parsed.mode == null) buildTrumpCursor(parsed) else buildCardCursor(parsed)

    /**
     * The net only ever evaluates a fully-determinized full-information observation (see
     * [PgxPositionAnalyzer.analyzeTrump]), so the session's absolute seat arrangement is cosmetic
     * here -- this mirrors what `ApplicationAnalyze`'s original `--cards`/`--shifted` path has
     * always done. [ParsedGame.povSeat]/[ParsedGame.foreSeat] were already used, in [parse], to
     * check that pov is actually the one deciding.
     */
    private fun buildTrumpCursor(parsed: ParsedGame): AnalysisCursor.Trump {
        val session = GameSessionBuilder.newSession().createGameSession()
        return AnalysisCursor.Trump(session, parsed.shifted)
    }

    /** POV-only replay of the recorded tricks -- see the class doc for why this shape mirrors the wire protocol. */
    private fun buildCardCursor(parsed: ParsedGame): AnalysisCursor.CardPlay {
        val mode = checkNotNull(parsed.mode)

        val players = (0..3).map { seat ->
            val hand = if (seat == parsed.povSeat) EnumSet.copyOf(parsed.povHand) else EnumSet.noneOf(Card::class.java)
            Player(seat.toString(), "Seat$seat", seat, hand, JassTheRipperJassStrategy.getTestInstance())
        }
        val teams = listOf(
            Team("Team0", listOf(players[0], players[2])),
            Team("Team1", listOf(players[1], players[3]))
        )

        // Seat the forehand first: PlayingOrder starts at list index 0 (the same seating trick
        // GameSessionBuilder.withDealer uses), and the forehand always leads trick 1 regardless of
        // shifted -- shifted only changes who declared, never who leads.
        val order = (0..3).map { players[(parsed.foreSeat + it) and 3] }
        val session = GameSession(teams, order)
        session.startNewGame(mode, parsed.shifted) // = BROADCAST_TRUMPF

        val remainingPovHand = EnumSet.copyOf(parsed.povHand)
        var expectedLeader = parsed.foreSeat
        parsed.tricks.forEachIndexed { trickIndex, trick ->
            trick.leaderSeat?.let { leader ->
                require(leader == expectedLeader) {
                    "trick ${trickIndex + 1}: recorded leader seat $leader but the engine expects seat $expectedLeader"
                }
            }
            for (card in trick.cards) {
                val player = session.currentPlayer
                if (player.seatId == parsed.povSeat) {
                    // The one hard checksum we always have: pov's own cards are real, so a
                    // recorded pov move must actually come from pov's hand.
                    require(remainingPovHand.remove(card)) {
                        "trick ${trickIndex + 1}: pov (seat ${parsed.povSeat}) does not hold $card " +
                            "(not dealt, or already played earlier in the record)"
                    }
                }
                session.makeMove(Move(player, card)) // = PLAYED_CARDS
            }
            if (trick.cards.size == 4) {
                val winner = session.currentRound.winner
                trick.winnerSeat?.let { expectedWinner ->
                    require(winner?.seatId == expectedWinner) {
                        "trick ${trickIndex + 1}: recorded winner seat $expectedWinner but the engine " +
                            "determined seat ${winner?.seatId} (mode=$mode)"
                    }
                }
                checkNotNull(winner) { "trick ${trickIndex + 1}: engine could not determine a winner" }
                session.startNextRound() // = BROADCAST_STICH
                expectedLeader = winner.seatId
            }
        }

        val game = session.currentGame
        val currentPlayer = game.currentPlayer
        require(currentPlayer.seatId == parsed.povSeat) {
            "it's seat ${currentPlayer.seatId}'s turn, not pov's (seat ${parsed.povSeat}) -- this " +
                "notation only carries pov's own information, so it can only analyze pov's own decisions"
        }
        val possibleCards = CardSelectionHelper.getCardsPossibleToPlay(remainingPovHand, game)
        return AnalysisCursor.CardPlay(game, remainingPovHand, possibleCards)
    }

    private fun partnerOf(seat: Int) = (seat + 2) and 3

    private fun parseSeat(token: String): Int {
        val seat = token.toIntOrNull() ?: throw IllegalArgumentException("seat must be 0-3, got '$token'")
        require(seat in 0..3) { "seat must be 0-3, got '$token'" }
        return seat
    }

    private fun parseTrumpSection(section: String): Pair<Boolean, Mode?> {
        val tokens = section.split(Regex("\\s+")).filter { it.isNotEmpty() }
        require(tokens.isNotEmpty() && tokens.size <= 2) {
            "trump section must be 1-2 tokens (e.g. 'H', 'O', 'G', 'G H'), got '$section'"
        }
        val shifted = tokens[0] == "G"
        if (!shifted) require(tokens.size == 1) {
            "trump section must be a single token unless it starts with 'G', got '$section'"
        }
        val declToken = if (shifted) tokens.getOrNull(1) else tokens[0]
        val mode = declToken?.let(::parseModeToken)
        return shifted to mode
    }

    private fun parseModeToken(token: String): Mode = when (token) {
        "O" -> Mode.topDown()
        "U" -> Mode.bottomUp()
        else -> {
            val color = Color.getColor(token)
                ?: throw IllegalArgumentException("Unknown trump token '$token' (expected D/H/S/C/O/U/G)")
            Mode.trump(color)
        }
    }

    private fun parseTrick(section: String, trickNumber: Int): ParsedTrick {
        val tokens = section.split(Regex("\\s+")).filter { it.isNotEmpty() }.toMutableList()
        require(tokens.isNotEmpty()) { "trick $trickNumber is empty" }

        var winnerSeat: Int? = null
        val last = tokens.last()
        if (last.length == 2 && last[0] == '=' && last[1].isDigit()) {
            winnerSeat = last[1].digitToInt()
            require(winnerSeat in 0..3) { "trick $trickNumber: winner seat must be 0-3, got '$last'" }
            tokens.removeAt(tokens.lastIndex)
        }

        var leaderSeat: Int? = null
        val first = tokens.firstOrNull()
        if (first != null && first.length == 1 && first[0].isDigit()) {
            leaderSeat = first.toInt()
            require(leaderSeat in 0..3) { "trick $trickNumber: leader seat must be 0-3, got '$first'" }
            tokens.removeAt(0)
        }

        require(tokens.isNotEmpty()) { "trick $trickNumber has no cards" }
        require(tokens.size <= 4) { "trick $trickNumber has more than 4 cards: $tokens" }
        val cards = tokens.map(CardTokens::parseCard)
        require(cards.toSet().size == cards.size) { "trick $trickNumber has a duplicate card: $cards" }

        return ParsedTrick(cards, leaderSeat, winnerSeat)
    }
}
