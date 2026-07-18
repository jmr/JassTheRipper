package to.joeli.jass.game.mode;

import com.pholser.junit.quickcheck.ForAll;
import to.joeli.jass.client.game.Game;
import to.joeli.jass.client.game.Move;
import to.joeli.jass.client.game.Player;
import to.joeli.jass.game.cards.Card;
import to.joeli.jass.game.cards.CardSet;
import to.joeli.jass.game.cards.Color;
import org.junit.Test;
import org.junit.contrib.theories.Theories;
import org.junit.contrib.theories.Theory;
import org.junit.runner.RunWith;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static to.joeli.jass.game.cards.Card.*;
import static to.joeli.jass.game.cards.Color.*;
import static java.util.Arrays.asList;
import static java.util.Collections.emptyList;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.not;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeThat;
import static org.junit.Assume.assumeTrue;

@RunWith(Theories.class)
public class TrumpfColorModeTest {

    @Test
    public void calculateScore_noCardHasBeenPlayedYet() {

        int result = Mode.trump(CLUBS).calculateScore(EnumSet.noneOf(Card.class));

        assertEquals(0, result);
    }

    @Test
    public void calculateScore_someNoTrumpfCardsWerePlayed() {

        final Set<Card> playedCards = EnumSet.of(Card.DIAMOND_ACE, Card.DIAMOND_KING, Card.SPADE_TEN);

        final int score = Mode.trump(HEARTS).calculateScore(playedCards);

        assertThat(score, equalTo( 11 + 4 + 10));
    }

    @Test
    public void calculateScore_trumpfCardsHaveSpecialScores() {

        final Set<Card> playedCards = EnumSet.of(Card.HEART_JACK, Card.HEART_NINE);

        final int score = Mode.trump(HEARTS).calculateScore(playedCards);

        assertThat(score, equalTo(20+14));
    }

    @Test
    public void calculateScore_blackColorIsTrumpf() {

        final Set<Card> playedCards = EnumSet.of(Card.SPADE_ACE, Card.SPADE_EIGHT);

        final int score = Mode.trump(SPADES).calculateScore(playedCards);

        assertThat(score, equalTo(11));
    }

    @Test
    public void calculateScore_lastRoundWasPlayed() {

        final EnumSet<Card> cards = EnumSet.of(DIAMOND_ACE, HEART_SIX, HEART_TEN);

        final int score = Mode.trump(SPADES).calculateRoundScore(Game.LAST_ROUND_NUMBER, cards);

        assertThat(score, equalTo(26));
    }

    @Test
    public void calculateScore_eightWasPlayed() {
        Set<Card> playedCards = EnumSet.of(HEART_QUEEN, HEART_TEN, HEART_EIGHT, DIAMOND_SIX);

        final int score = Mode.trump(SPADES).calculateRoundScore(2, playedCards);

        assertThat(score, equalTo((3+10)));
    }

    @Test
    public void determineWinner_noMovesWereMade_returnsNull() {

        assertNull(Mode.trump(CLUBS).determineWinningMove(emptyList()));
    }

    @Test
    public void canPlayCard_whenTrumpfIsRoundColor_underTrumpfAllowed() {

        final Set<Card> playedCards = EnumSet.of(SPADE_ACE);
        final Set<Card> playerCards = EnumSet.of(
                SPADE_TEN,SPADE_EIGHT,SPADE_SEVEN,
                DIAMOND_QUEEN
        );

        final boolean canPlayCard = Mode.trump(SPADES).canPlayCard(SPADE_TEN, playedCards, SPADES, playerCards);

        assertTrue(canPlayCard);
    }

    @Test
    public void determineWinner_noTrumpfHasBeenPlayed() {

        final Player playerA = new Player("a");
        final Player playerB = new Player("b");
        final Player playerC = new Player("c");
        final Player playerD = new Player("d");
        final List<Move> moves = asList(
                new Move(playerA, HEART_TEN),
                new Move(playerB, HEART_QUEEN),
                new Move(playerC, HEART_SEVEN),
                new Move(playerD, HEART_JACK));

        Player winner = Mode.trump(CLUBS).determineWinningMove(moves).getPlayer();

        assertThat(winner, equalTo(playerB));
    }

    @Test
    public void determineWinner_onlyTrumpfHasBeenPlayed() {
        final Player playerA = new Player("a");
        final Player playerB = new Player("b");
        final Player playerC = new Player("c");
        final Player playerD = new Player("d");
        final List<Move> moves = asList(
                new Move(playerA, HEART_TEN),
                new Move(playerB, HEART_QUEEN),
                new Move(playerC, HEART_SEVEN),
                new Move(playerD, HEART_JACK));

        Player winner = Mode.trump(HEARTS).determineWinningMove(moves).getPlayer();

        assertThat(winner, equalTo(playerD));
    }

    @Test
    public void determineWinner_trumpfAndRegularColorsHaveBeenPlayed() {
        final Player playerA = new Player("a");
        final Player playerB = new Player("b");
        final Player playerC = new Player("c");
        final Player playerD = new Player("d");
        final List<Move> moves = asList(
                new Move(playerA, CLUB_TEN),
                new Move(playerB, CLUB_EIGHT),
                new Move(playerC, CLUB_ACE),
                new Move(playerD, HEART_SIX));

        Player winner = Mode.trump(HEARTS).determineWinningMove(moves).getPlayer();

        assertThat(winner, equalTo(playerD));
    }

    @Test
    public void determineWinner_trumpfJackBeatsAce() {
        final Player playerA = new Player("a");
        final Player playerB = new Player("b");
        final Player playerC = new Player("c");
        final Player playerD = new Player("d");
        final List<Move> moves = asList(
                new Move(playerA, CLUB_TEN),
                new Move(playerB, HEART_NINE),
                new Move(playerC, CLUB_ACE),
                new Move(playerD, HEART_ACE));

        Player winner = Mode.trump(HEARTS).determineWinningMove(moves).getPlayer();

        assertThat(winner, equalTo(playerB));
    }

    @Theory
    public void canPlayCard_whenNoCardIsPlayed_everyCardIsAllowed(
            @ForAll Card cardToPlay) {

        final Set<Card> alreadyPlayedCards = EnumSet.noneOf(Card.class);
        final Set<Card> playerCards = EnumSet.of(cardToPlay);

        final boolean canCardBePlayed = Mode.trump(HEARTS).canPlayCard(cardToPlay, alreadyPlayedCards, null, playerCards);

        assertTrue(canCardBePlayed);
    }

    @Theory
    public void canPlayCard_withPlayedCards_allowsCardsOfSameColor(
            @ForAll(sampleSize = 20) Card playedCard,
            @ForAll(sampleSize = 20) Card cardToPlay) {

        assumeThat(playedCard, not(equalTo(cardToPlay)));
        assumeThat(playedCard.getColor(), equalTo(cardToPlay.getColor()));

        final Set<Card> alreadyPlayedCards = EnumSet.of(playedCard);
        final Color roundColor = playedCard.getColor();
        final Set<Card> playerCards = EnumSet.of(cardToPlay);

        final boolean canCardBePlayed = Mode.trump(HEARTS).canPlayCard(cardToPlay, alreadyPlayedCards, roundColor, playerCards);

        assertTrue(canCardBePlayed);
    }

    @Test
    public void canPlayCard_withOnlyTrumpfInHand_underTrumpfIsAllowed() {

        final Set<Card> alreadyPlayedCards = EnumSet.of(DIAMOND_ACE, HEART_NINE);
        final Set<Card> playerCards = EnumSet.of(HEART_ACE, HEART_SEVEN);

        final boolean canCardBePlayed = Mode.trump(HEARTS).canPlayCard(HEART_SEVEN, alreadyPlayedCards, Color.DIAMONDS, playerCards);

        assertTrue(canCardBePlayed);
    }

    @Test
    public void canPlayCard_withNoCardsOfRoundColor_undertrumpfStillNotAllowed() {

        final Set<Card> alreadyPlayedCards = EnumSet.of(DIAMOND_SIX, HEART_SEVEN, HEART_EIGHT);
        final Set<Card> playerCards = EnumSet.of(HEART_QUEEN, HEART_SIX, HEART_KING, CLUB_SEVEN, CLUB_KING, SPADE_SIX, SPADE_TEN, SPADE_JACK, SPADE_KING);

        final boolean canCardBePlayed = Mode.trump(HEARTS).canPlayCard(HEART_SIX, alreadyPlayedCards, Color.DIAMONDS, playerCards);

        assertFalse(canCardBePlayed);
    }

    @Test
    public void canPlayCard_withPlayedCards_underTrumpfIsNotAllowed() {

        final Set<Card> alreadyPlayedCards = EnumSet.of(DIAMOND_SIX, HEART_SIX, HEART_EIGHT);
        final Set<Card> playerCards = EnumSet.of(HEART_ACE, DIAMOND_JACK, HEART_SEVEN);

        final boolean canCardBePlayed = Mode.trump(HEARTS).canPlayCard(HEART_SEVEN, alreadyPlayedCards, Color.DIAMONDS, playerCards);

        assertFalse(canCardBePlayed);
    }

    @Test
    public void canPlayCard_withOnlyJackOfTrumpfColor() {

        final Set<Card> alreadyPlayedCards = EnumSet.of(CLUB_SEVEN);
        final Set<Card> playerCards = EnumSet.of(CLUB_JACK, HEART_SEVEN);

        final boolean canCardBePlayed = Mode.trump(CLUBS).canPlayCard(HEART_SEVEN, alreadyPlayedCards, CLUBS, playerCards);

        assertTrue(canCardBePlayed);
    }

    @Theory
    public void canPlayCard_withPlayedCards_allowsNoCardsOfOtherColor(
            @ForAll(sampleSize = 20) Card playedCard,
            @ForAll(sampleSize = 20) Card cardToPlay) {

        assumeThat(playedCard.getColor(), equalTo(HEARTS));
        assumeThat(playedCard, not(equalTo(cardToPlay)));
        assumeThat(cardToPlay.getColor(), not(equalTo(HEARTS)));
        assumeThat(cardToPlay.getColor(), not(equalTo(CLUBS)));

        final Set<Card> alreadyPlayedCards = EnumSet.of(playedCard);
        final Set<Card> playerCards = EnumSet.of(HEART_ACE, cardToPlay);

        final boolean canCardBePlayed = Mode.trump(CLUBS).canPlayCard(cardToPlay, alreadyPlayedCards, playedCard.getColor(), playerCards);

        assertFalse(canCardBePlayed);
    }

    @Theory
    public void canPlayCard_noTrumpfWasPlayed_trumpfCanAlwaysBePlayed(
            @ForAll(sampleSize = 20) Card playedCard,
            @ForAll(sampleSize = 20) Card cardToPlay) {

        assumeThat(cardToPlay.getColor(), equalTo(CLUBS));
        assumeTrue(cardToPlay.isHigherTrumpfThan(playedCard));

        final Set<Card> alreadyPlayedCards = EnumSet.of(playedCard);
        final Set<Card> playerCards = EnumSet.of(cardToPlay);

        final boolean canCardBePlayed = Mode.trump(CLUBS).canPlayCard(cardToPlay, alreadyPlayedCards, playedCard.getColor(), playerCards);

        assertTrue(canCardBePlayed);
    }

    // isHighestTrumpf: J is unconditionally highest

    @Test
    public void canPlayCard_jackOvertrumpfsNine() {
        Set<Card> played = EnumSet.of(DIAMOND_SIX, HEART_NINE);
        Set<Card> playerCards = EnumSet.of(HEART_JACK);
        assertTrue(Mode.trump(HEARTS).canPlayCard(HEART_JACK, played, DIAMONDS, playerCards));
    }

    @Test
    public void canPlayCard_nineCannotOvertrumpfJack() {
        Set<Card> played = EnumSet.of(DIAMOND_SIX, HEART_JACK);
        Set<Card> playerCards = EnumSet.of(HEART_NINE, SPADE_SIX); // SPADE_SIX: not trumpf, not round color
        assertFalse(Mode.trump(HEARTS).canPlayCard(HEART_NINE, played, DIAMONDS, playerCards));
    }

    @Test
    public void canPlayCard_aceCannotOvertrumpfJack() {
        Set<Card> played = EnumSet.of(DIAMOND_SIX, HEART_JACK);
        Set<Card> playerCards = EnumSet.of(HEART_ACE, SPADE_SIX);
        assertFalse(Mode.trump(HEARTS).canPlayCard(HEART_ACE, played, DIAMONDS, playerCards));
    }

    // isHighestTrumpf: 9 is second highest when J absent

    @Test
    public void canPlayCard_nineOvertrumpfsAce() {
        Set<Card> played = EnumSet.of(DIAMOND_SIX, HEART_ACE);
        Set<Card> playerCards = EnumSet.of(HEART_NINE);
        assertTrue(Mode.trump(HEARTS).canPlayCard(HEART_NINE, played, DIAMONDS, playerCards));
    }

    @Test
    public void canPlayCard_aceCannotOvertrumpfNine() {
        Set<Card> played = EnumSet.of(DIAMOND_SIX, HEART_NINE);
        Set<Card> playerCards = EnumSet.of(HEART_ACE, SPADE_SIX);
        assertFalse(Mode.trump(HEARTS).canPlayCard(HEART_ACE, played, DIAMONDS, playerCards));
    }

    // isHighestTrumpf: normal rank order for non-J non-9 cards (A > K > Q > 10 > 8 > 7 > 6)

    @Test
    public void canPlayCard_aceOvertrumpfsKing() {
        Set<Card> played = EnumSet.of(DIAMOND_SIX, HEART_KING);
        Set<Card> playerCards = EnumSet.of(HEART_ACE);
        assertTrue(Mode.trump(HEARTS).canPlayCard(HEART_ACE, played, DIAMONDS, playerCards));
    }

    @Test
    public void canPlayCard_kingCannotOvertrumpfAce() {
        Set<Card> played = EnumSet.of(DIAMOND_SIX, HEART_ACE);
        Set<Card> playerCards = EnumSet.of(HEART_KING, SPADE_SIX);
        assertFalse(Mode.trump(HEARTS).canPlayCard(HEART_KING, played, DIAMONDS, playerCards));
    }

    @Test
    public void canPlayCard_sixCannotOvertrumpfSeven() {
        Set<Card> played = EnumSet.of(DIAMOND_SIX, HEART_SEVEN);
        Set<Card> playerCards = EnumSet.of(HEART_SIX, SPADE_SIX);
        assertFalse(Mode.trump(HEARTS).canPlayCard(HEART_SIX, played, DIAMONDS, playerCards));
    }

    @Test
    public void canPlayCard_firstTrumpfOfRoundAlwaysAllowed() {
        Set<Card> played = EnumSet.of(DIAMOND_SIX);
        Set<Card> playerCards = EnumSet.of(HEART_SIX);
        assertTrue(Mode.trump(HEARTS).canPlayCard(HEART_SIX, played, DIAMONDS, playerCards));
    }

    // ── validCardsBits: undertrump restriction when void in the led suit ─────
    // Regression for the doc/todo.md bug (observed 2026-07-18): the void branch
    // returned availableBits wholesale, so undertrumps stayed "possible" and MCTS
    // could select a move Player.canPlayCard then rejected (random-card fallback).

    /** Runs validCardsBits and asserts it agrees card-by-card with canPlayCard. */
    private static Set<Card> validCardsCheckedAgainstCanPlayCard(
            Mode mode, Set<Card> hand, Set<Card> played, Color roundColor) {
        Set<Card> valid = CardSet.toEnumSet(
                mode.validCardsBits(CardSet.toBits(hand), CardSet.toBits(played), roundColor));
        for (Card card : hand) {
            assertEquals("validCardsBits and canPlayCard disagree on " + card,
                    valid.contains(card), mode.canPlayCard(card, played, roundColor, hand));
        }
        return valid;
    }

    @Test
    public void validCardsBits_voidInLedSuit_forbidsUndertrump() {
        // The arena state that exposed the bug: trump ♠, hearts led, ♠Q already
        // trumped into the trick, hand void in hearts holding one low trump.
        Set<Card> hand = EnumSet.of(DIAMOND_SIX, DIAMOND_SEVEN, DIAMOND_JACK,
                DIAMOND_KING, CLUB_QUEEN, SPADE_TEN);
        Set<Card> played = EnumSet.of(HEART_SIX, SPADE_QUEEN);

        Set<Card> valid = validCardsCheckedAgainstCanPlayCard(Mode.trump(SPADES), hand, played, HEARTS);

        assertEquals(EnumSet.of(DIAMOND_SIX, DIAMOND_SEVEN, DIAMOND_JACK, DIAMOND_KING, CLUB_QUEEN), valid);
    }

    @Test
    public void validCardsBits_voidInLedSuit_allowsOvertrump() {
        Set<Card> hand = EnumSet.of(DIAMOND_SIX, CLUB_QUEEN, SPADE_KING);
        Set<Card> played = EnumSet.of(HEART_SIX, SPADE_QUEEN);

        Set<Card> valid = validCardsCheckedAgainstCanPlayCard(Mode.trump(SPADES), hand, played, HEARTS);

        assertEquals(hand, valid);
    }

    @Test
    public void validCardsBits_voidInLedSuit_allowsUndertrumpWithOnlyTrumpsInHand() {
        Set<Card> hand = EnumSet.of(SPADE_SIX, SPADE_TEN);
        Set<Card> played = EnumSet.of(HEART_SIX, SPADE_QUEEN);

        Set<Card> valid = validCardsCheckedAgainstCanPlayCard(Mode.trump(SPADES), hand, played, HEARTS);

        assertEquals(hand, valid);
    }

    @Test
    public void validCardsBits_voidInLedSuit_allowsAnyTrumpWhenNoTrumpPlayedYet() {
        Set<Card> hand = EnumSet.of(DIAMOND_SIX, SPADE_SIX);
        Set<Card> played = EnumSet.of(HEART_SIX, HEART_KING);

        Set<Card> valid = validCardsCheckedAgainstCanPlayCard(Mode.trump(SPADES), hand, played, HEARTS);

        assertEquals(hand, valid);
    }

    @Test
    public void validCardsBits_voidInLedSuit_trumpfNineOnlyBeatenByJack() {
        // ♠9 (nell) already played: only the ♠J may still trump; ♠A would undertrump.
        Set<Card> hand = EnumSet.of(DIAMOND_SIX, SPADE_JACK, SPADE_ACE);
        Set<Card> played = EnumSet.of(HEART_SIX, SPADE_NINE);

        Set<Card> valid = validCardsCheckedAgainstCanPlayCard(Mode.trump(SPADES), hand, played, HEARTS);

        assertEquals(EnumSet.of(DIAMOND_SIX, SPADE_JACK), valid);
    }
}