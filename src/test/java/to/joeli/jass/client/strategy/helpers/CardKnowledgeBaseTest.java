package to.joeli.jass.client.strategy.helpers;

import to.joeli.jass.client.game.*;
import to.joeli.jass.game.Trumpf;
import to.joeli.jass.game.cards.Card;
import to.joeli.jass.game.cards.Color;
import to.joeli.jass.game.mode.Mode;
import org.junit.Before;
import org.junit.Test;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import static java.util.Arrays.asList;
import static org.junit.Assert.*;

public class CardKnowledgeBaseTest {

	private Set<Card> allCards = EnumSet.copyOf(Arrays.asList(Card.values()));
	private Set<Card> cards1 = EnumSet.of(Card.CLUB_EIGHT, Card.CLUB_JACK, Card.DIAMOND_SIX, Card.DIAMOND_SEVEN, Card.SPADE_QUEEN, Card.HEART_TEN, Card.SPADE_NINE, Card.SPADE_KING);
	private Set<Card> cards2 = EnumSet.of(Card.CLUB_KING, Card.CLUB_EIGHT, Card.CLUB_JACK, Card.DIAMOND_SIX, Card.DIAMOND_SEVEN, Card.SPADE_QUEEN, Card.HEART_TEN, Card.SPADE_NINE, Card.SPADE_KING);
	private Player player0 = new Player("0", "player0", 0);
	private Player player1 = new Player("1", "player1", 1);
	private Player player2 = new Player("2", "player2", 2);
	private Player player3 = new Player("3", "player3", 3);
	private PlayingOrder order = PlayingOrder.createOrder(asList(player0, player1, player2, player3));
	private Team Team0 = new Team("Team0", asList(player0, player2));
	private Team Team1 = new Team("Team1", asList(player1, player3));
	private Game diamondsGame = Game.startGame(Mode.from(Trumpf.TRUMPF, Color.DIAMONDS), order, asList(Team0, Team1), false);
	private Game obeAbeGame = Game.startGame(Mode.topDown(), order, asList(Team0, Team1), false);

	@Before
	public void setUp() {
		assertEquals(36, allCards.size());
	}

	@Test
	public void testFullyPlayedRound() {
		obeAbeGame.makeMove(new Move(player0, Card.CLUB_SIX));
		obeAbeGame.makeMove(new Move(player1, Card.CLUB_SEVEN));
		obeAbeGame.makeMove(new Move(player2, Card.HEART_EIGHT)); // player 2 did not follow suit
		obeAbeGame.makeMove(new Move(player3, Card.CLUB_KING));
		obeAbeGame.startNextRound();

		CardKnowledgeBase.sampleCardDeterminizationToPlayers(obeAbeGame, cards1, null);

		assertEquals(8, player0.getCards().size());
		assertEquals(8, player1.getCards().size());
		assertEquals(8, player2.getCards().size());
		assertEquals(8, player3.getCards().size());

		assertFalse(player2.getCards().contains(Card.CLUB_SIX));
		assertFalse(player2.getCards().contains(Card.CLUB_SEVEN));
		assertFalse(player2.getCards().contains(Card.CLUB_EIGHT));
		assertFalse(player2.getCards().contains(Card.CLUB_NINE));
		assertFalse(player2.getCards().contains(Card.CLUB_TEN));
		assertFalse(player2.getCards().contains(Card.CLUB_JACK));
		assertFalse(player2.getCards().contains(Card.CLUB_QUEEN));
		assertFalse(player2.getCards().contains(Card.CLUB_KING));
		assertFalse(player2.getCards().contains(Card.CLUB_ACE));
	}

	@Test
	public void testPartiallyPlayedRound() {
		diamondsGame.makeMove(new Move(player0, Card.CLUB_SIX));
		diamondsGame.makeMove(new Move(player1, Card.CLUB_SEVEN));
		diamondsGame.makeMove(new Move(player2, Card.HEART_EIGHT)); // player 2 did not follow suit

		CardKnowledgeBase.sampleCardDeterminizationToPlayers(diamondsGame, cards2, null);

		assertEquals(8, player0.getCards().size());
		assertEquals(8, player1.getCards().size());
		assertEquals(8, player2.getCards().size());
		assertEquals(9, player3.getCards().size());

		assertFalse(player2.getCards().contains(Card.CLUB_SIX));
		assertFalse(player2.getCards().contains(Card.CLUB_SEVEN));
		assertFalse(player2.getCards().contains(Card.CLUB_EIGHT));
		assertFalse(player2.getCards().contains(Card.CLUB_NINE));
		assertFalse(player2.getCards().contains(Card.CLUB_TEN));
		assertFalse(player2.getCards().contains(Card.CLUB_JACK));
		assertFalse(player2.getCards().contains(Card.CLUB_QUEEN));
		assertFalse(player2.getCards().contains(Card.CLUB_KING));
		assertFalse(player2.getCards().contains(Card.CLUB_ACE));
	}

	@Test
	public void testDeterminizationOverconstrainedNeverLeavesZeroCardPlayer() {
		// Regression: deletePlayerFromRemainingDistributions called deleteEventAndReBalance
		// which silently returned false (no-op) when the distribution had only one possible
		// player. If a player reached quota but was the sole possible holder of another card
		// (overconstrained by suit-voiding inference), the main loop assigned that card to
		// them again — leaving a different player with 0 cards → AssertionError in getMoves.
		//
		// Setup: player2 and player3 void clubs in rounds 0-2 (clubs led, they play off-suit).
		// At round 8, CLUB_KING and CLUB_QUEEN are both forced to player1 ({player1:1.0}),
		// but player1's quota = 1. Without the fix, player1 gets 2 cards, player3 gets 0.
		//
		// Card accounting for rounds 0-7 (32 cards, none of {HEART_ACE, CLUB_KING, CLUB_QUEEN, DIAMOND_SIX}):
		// player0 always leads the highest remaining card of the led suit and wins every round.

		// Round 0: clubs led; player2/3 play hearts → club void established.
		obeAbeGame.makeMove(new Move(player0, Card.CLUB_ACE));
		obeAbeGame.makeMove(new Move(player1, Card.CLUB_SEVEN));
		obeAbeGame.makeMove(new Move(player2, Card.HEART_SIX));
		obeAbeGame.makeMove(new Move(player3, Card.HEART_SEVEN));
		obeAbeGame.startNextRound();
		// Round 1: clubs led; player2/3 play diamonds (void confirmed).
		obeAbeGame.makeMove(new Move(player0, Card.CLUB_JACK));
		obeAbeGame.makeMove(new Move(player1, Card.CLUB_EIGHT));
		obeAbeGame.makeMove(new Move(player2, Card.DIAMOND_SEVEN));
		obeAbeGame.makeMove(new Move(player3, Card.DIAMOND_EIGHT));
		obeAbeGame.startNextRound();
		// Round 2: clubs led; player2/3 play diamonds (void confirmed).
		obeAbeGame.makeMove(new Move(player0, Card.CLUB_TEN));
		obeAbeGame.makeMove(new Move(player1, Card.CLUB_NINE));
		obeAbeGame.makeMove(new Move(player2, Card.DIAMOND_NINE));
		obeAbeGame.makeMove(new Move(player3, Card.DIAMOND_TEN));
		obeAbeGame.startNextRound();
		// Rounds 3-7: use up remaining cards.
		obeAbeGame.makeMove(new Move(player0, Card.DIAMOND_ACE));
		obeAbeGame.makeMove(new Move(player1, Card.DIAMOND_JACK));
		obeAbeGame.makeMove(new Move(player2, Card.HEART_EIGHT));
		obeAbeGame.makeMove(new Move(player3, Card.HEART_NINE));
		obeAbeGame.startNextRound();
		obeAbeGame.makeMove(new Move(player0, Card.DIAMOND_KING));
		obeAbeGame.makeMove(new Move(player1, Card.DIAMOND_QUEEN));
		obeAbeGame.makeMove(new Move(player2, Card.HEART_TEN));
		obeAbeGame.makeMove(new Move(player3, Card.HEART_JACK));
		obeAbeGame.startNextRound();
		obeAbeGame.makeMove(new Move(player0, Card.HEART_KING));
		obeAbeGame.makeMove(new Move(player1, Card.HEART_QUEEN));
		obeAbeGame.makeMove(new Move(player2, Card.SPADE_SIX));
		obeAbeGame.makeMove(new Move(player3, Card.SPADE_SEVEN));
		obeAbeGame.startNextRound();
		obeAbeGame.makeMove(new Move(player0, Card.SPADE_ACE));
		obeAbeGame.makeMove(new Move(player1, Card.SPADE_EIGHT));
		obeAbeGame.makeMove(new Move(player2, Card.SPADE_NINE));
		obeAbeGame.makeMove(new Move(player3, Card.SPADE_TEN));
		obeAbeGame.startNextRound();
		obeAbeGame.makeMove(new Move(player0, Card.SPADE_KING));
		obeAbeGame.makeMove(new Move(player1, Card.SPADE_JACK));
		obeAbeGame.makeMove(new Move(player2, Card.SPADE_QUEEN));
		obeAbeGame.makeMove(new Move(player3, Card.CLUB_SIX));
		obeAbeGame.startNextRound();

		// Round 8: player0 holds HEART_ACE. Others need CLUB_KING, CLUB_QUEEN, DIAMOND_SIX.
		// Constraint: CLUB_KING and CLUB_QUEEN each have distribution {player1:1.0}
		// (player2 and player3 voided clubs). quota=1 for all → overconstrained.
		assertEquals(8, obeAbeGame.getCurrentRound().getRoundNumber());
		Set<Card> availableCards = EnumSet.of(Card.HEART_ACE);
		for (int i = 0; i < 100; i++) {
			CardKnowledgeBase.sampleCardDeterminizationToPlayers(obeAbeGame, availableCards, null);
			assertEquals(1, player1.getCards().size());
			assertEquals(1, player2.getCards().size());
			assertEquals(1, player3.getCards().size());
		}
	}

	@Test
	public void testInitCardKnowledge() {
		final Game game = GameSessionBuilder.startedClubsGame();
		final Map<Card, Distribution> cardKnowledge = CardKnowledgeBase.initCardKnowledge(game, game.getCurrentPlayer().getCards());

		final float delta = 0.001f;
		// The current player has the HEART_SIX
		assertArrayEquals(new float[]{1.0f, 0.0f, 0.0f, 0.0f}, cardKnowledge.get(Card.HEART_SIX).getProbabilitiesInSeatIdOrder(), delta);
		// The current player does not have the HEART_SEVEN
		assertArrayEquals(new float[]{0.0f, 0.33333f, 0.33333f, 0.33333f}, cardKnowledge.get(Card.HEART_SEVEN).getProbabilitiesInSeatIdOrder(), delta);
	}

}