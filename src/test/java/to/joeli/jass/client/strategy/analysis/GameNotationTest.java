package to.joeli.jass.client.strategy.analysis;

import org.junit.Test;
import to.joeli.jass.client.game.Game;
import to.joeli.jass.client.game.GameSession;
import to.joeli.jass.client.game.Player;
import to.joeli.jass.client.strategy.helpers.CardSelectionHelper;
import to.joeli.jass.client.strategy.helpers.GameSessionBuilder;
import to.joeli.jass.game.cards.Card;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static java.util.Arrays.asList;
import static java.util.Collections.singletonList;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static to.joeli.jass.game.cards.Card.*;

/**
 * Model-free tests for {@link GameNotation}'s parser and its POV-only replay-based position
 * reconstruction (see that class's doc for the notation and why the replay mirrors the HSLU wire
 * protocol). No SavedModel needed -- these only exercise parsing and engine reconstruction, not
 * the pgx net itself (that's covered, gated on the model being present, by
 * {@link PgxPositionAnalyzerTest}-style tests below).
 */
public class GameNotationTest {

    private static final List<Set<Card>> HANDS = GameSessionBuilder.shiftCards;

    private static String cardsToken(Iterable<Card> cards) {
        List<String> tokens = new ArrayList<>();
        for (Card card : cards) tokens.add(card.toString());
        return String.join(" ", tokens);
    }

    private static String trickToken(List<Card> cards, Integer leaderSeat, Integer winnerSeat) {
        List<String> tokens = new ArrayList<>();
        if (leaderSeat != null) tokens.add(String.valueOf(leaderSeat));
        tokens.addAll(cards.stream().map(Card::toString).collect(Collectors.toList()));
        if (winnerSeat != null) tokens.add("=" + winnerSeat);
        return String.join(" ", tokens);
    }

    // ---- trump-cursor parsing ----

    @Test
    public void noTrumpSectionMeansForehandMustAnalyzeItsOwnDecision() {
        ParsedGame parsed = GameNotation.parse(cardsToken(HANDS.get(0)) + " / 0 0");
        assertEquals(0, parsed.getPovSeat());
        assertEquals(0, parsed.getForeSeat());
        assertFalse(parsed.getShifted());
        assertTrue(parsed.getMode() == null);
    }

    @Test(expected = IllegalArgumentException.class)
    public void noTrumpSectionRejectsPovOtherThanForehand() {
        // Seat 1 has no information about seat 0's (the forehand's) trump decision.
        GameNotation.parse(cardsToken(HANDS.get(1)) + " / 1 0");
    }

    @Test
    public void bareShiftMeansForehandsPartnerMustAnalyzeItsOwnDecision() {
        ParsedGame parsed = GameNotation.parse(cardsToken(HANDS.get(2)) + " / 2 0 / G");
        assertTrue(parsed.getShifted());
        assertTrue(parsed.getMode() == null);
    }

    @Test(expected = IllegalArgumentException.class)
    public void bareShiftRejectsPovOtherThanForehandsPartner() {
        GameNotation.parse(cardsToken(HANDS.get(1)) + " / 1 0 / G");
    }

    @Test
    public void shiftFollowedByDeclarationResolvesTrumpRegardlessOfPov() {
        // Once trump is resolved, any seat can ask "what happens next" -- the decider check only
        // applies while the decision is still open.
        ParsedGame parsed = GameNotation.parse(cardsToken(HANDS.get(3)) + " / 3 0 / G H");
        assertTrue(parsed.getShifted());
        assertEquals(to.joeli.jass.game.mode.Mode.trump(to.joeli.jass.game.cards.Color.HEARTS), parsed.getMode());
    }

    @Test
    public void aloneColorLetterDeclaresNonShiftedTrump() {
        ParsedGame parsed = GameNotation.parse(cardsToken(HANDS.get(0)) + " / 0 0 / C");
        assertFalse(parsed.getShifted());
        assertEquals(to.joeli.jass.game.mode.Mode.trump(to.joeli.jass.game.cards.Color.CLUBS), parsed.getMode());
    }

    @Test(expected = IllegalArgumentException.class)
    public void tricksBeforeTrumpDeclarationFailToParse() {
        GameNotation.parse(cardsToken(HANDS.get(0)) + " / 0 0 / 0 " + CLUB_QUEEN + " " + CLUB_NINE + " " + CLUB_EIGHT + " " + CLUB_SIX);
    }

    @Test(expected = IllegalArgumentException.class)
    public void onlyTheLastTrickMayBePartial() {
        String spec = String.join(" / ",
                cardsToken(HANDS.get(0)), "0 0", "C",
                trickToken(asList(CLUB_QUEEN, CLUB_NINE, CLUB_EIGHT), 0, null), // only 3 cards, not last
                trickToken(singletonList(CLUB_SIX), null, null));
        GameNotation.parse(spec);
    }

    @Test(expected = IllegalArgumentException.class)
    public void handMustHaveNineDistinctCards() {
        GameNotation.parse(CLUB_QUEEN + " " + CLUB_NINE + " / 0 0");
    }

    @Test(expected = IllegalArgumentException.class)
    public void seatMustBeInRange() {
        GameNotation.parse(cardsToken(HANDS.get(0)) + " / 4 0");
    }

    // ---- card-cursor reconstruction, checked against a direct GameSessionBuilder replay ----

    /**
     * Builds the reference position via {@link GameSessionBuilder} (independent of
     * {@link GameNotation}) -- two full clubs tricks played, plus one plain (non-trump) card
     * leading trick 3 -- and returns it. The trick winners aren't hardcoded anywhere: they're
     * read back off this reference so the equivalent notation string is correct regardless of who
     * actually wins, keeping the test robust to the fixture's exact cards.
     */
    private GameSession referenceSession() {
        return GameSessionBuilder.newSession()
                .withStartedClubsGameWithRoundsPlayed(2)
                .withCardsPlayed(HEART_EIGHT) // whoever won trick 2 leads trick 3 with a plain heart
                .createGameSession();
    }

    @Test
    public void cardCursorMatchesDirectEngineReplay() {
        GameSession reference = referenceSession();
        Game referenceGame = reference.getCurrentGame();
        int winner1 = referenceGame.getPreviousRounds().get(0).getWinner().getSeatId();
        int winner2 = referenceGame.getPreviousRounds().get(1).getWinner().getSeatId();
        Player toMove = referenceGame.getCurrentPlayer();
        Set<Card> toMoveHand = toMove.getCards();
        Set<Card> expectedPossible = CardSelectionHelper.getCardsPossibleToPlay(toMoveHand, referenceGame);

        String spec = String.join(" / ",
                cardsToken(HANDS.get(toMove.getSeatId())),
                toMove.getSeatId() + " 0",
                "C",
                trickToken(asList(CLUB_QUEEN, CLUB_NINE, CLUB_EIGHT, CLUB_SIX), 0, winner1),
                trickToken(asList(CLUB_JACK, CLUB_KING, CLUB_SEVEN, CLUB_ACE), winner1, winner2),
                trickToken(singletonList(HEART_EIGHT), winner2, null));

        ParsedGame parsed = GameNotation.parse(spec);
        AnalysisCursor cursor = GameNotation.buildCursor(parsed);
        assertTrue(cursor instanceof AnalysisCursor.CardPlay);
        AnalysisCursor.CardPlay cardPlay = (AnalysisCursor.CardPlay) cursor;

        assertEquals(toMove.getSeatId(), cardPlay.getGame().getCurrentPlayer().getSeatId());
        assertEquals(toMoveHand, cardPlay.getAvailableCards());
        assertEquals(expectedPossible, cardPlay.getPossibleCards());
    }

    @Test(expected = IllegalArgumentException.class)
    public void wrongLeaderChecksumThrows() {
        GameSession reference = referenceSession();
        Game referenceGame = reference.getCurrentGame();
        int winner1 = referenceGame.getPreviousRounds().get(0).getWinner().getSeatId();
        int winner2 = referenceGame.getPreviousRounds().get(1).getWinner().getSeatId();
        Player toMove = referenceGame.getCurrentPlayer();

        String spec = String.join(" / ",
                cardsToken(HANDS.get(toMove.getSeatId())),
                toMove.getSeatId() + " 0",
                "C",
                // Wrong: trick 1 is always led by the forehand (seat 0), not seat 1.
                trickToken(asList(CLUB_QUEEN, CLUB_NINE, CLUB_EIGHT, CLUB_SIX), (0 + 1) % 4, winner1),
                trickToken(asList(CLUB_JACK, CLUB_KING, CLUB_SEVEN, CLUB_ACE), winner1, winner2),
                trickToken(singletonList(HEART_EIGHT), winner2, null));

        GameNotation.buildCursor(GameNotation.parse(spec));
    }

    @Test(expected = IllegalArgumentException.class)
    public void wrongWinnerChecksumThrows() {
        GameSession reference = referenceSession();
        Game referenceGame = reference.getCurrentGame();
        int winner1 = referenceGame.getPreviousRounds().get(0).getWinner().getSeatId();
        int winner2 = referenceGame.getPreviousRounds().get(1).getWinner().getSeatId();
        Player toMove = referenceGame.getCurrentPlayer();
        int wrongWinner1 = (winner1 + 1) % 4; // whichever seat actually won trick 1, it isn't this one

        String spec = String.join(" / ",
                cardsToken(HANDS.get(toMove.getSeatId())),
                toMove.getSeatId() + " 0",
                "C",
                trickToken(asList(CLUB_QUEEN, CLUB_NINE, CLUB_EIGHT, CLUB_SIX), 0, wrongWinner1),
                trickToken(asList(CLUB_JACK, CLUB_KING, CLUB_SEVEN, CLUB_ACE), winner1, winner2),
                trickToken(singletonList(HEART_EIGHT), winner2, null));

        GameNotation.buildCursor(GameNotation.parse(spec));
    }

    @Test(expected = IllegalArgumentException.class)
    public void povCardNotInDeclaredHandThrows() {
        // Pov is seat 0 (the forehand), but the trick claims seat 0 played a card that isn't in
        // seat 0's declared hand -- e.g. DIAMOND_ACE belongs to seat 3 in this fixture.
        String spec = String.join(" / ",
                cardsToken(HANDS.get(0)),
                "0 0",
                "C",
                trickToken(singletonList(DIAMOND_ACE), 0, null));

        GameNotation.buildCursor(GameNotation.parse(spec));
    }

    @Test(expected = IllegalArgumentException.class)
    public void analyzingSomeoneElsesTurnThrows() {
        // Trump just declared, nobody has played yet: it's the forehand's (seat 0) turn to lead,
        // not pov's (seat 1) -- this notation only carries pov's own information.
        String spec = cardsToken(HANDS.get(1)) + " / 1 0 / C";

        GameNotation.buildCursor(GameNotation.parse(spec));
    }
}
