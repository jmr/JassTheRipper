package to.joeli.jass.client.strategy.mcts;

import org.junit.Before;
import org.junit.Test;
import to.joeli.jass.client.game.*;
import to.joeli.jass.client.strategy.helpers.CardKnowledgeBase;
import to.joeli.jass.client.strategy.helpers.CardSelectionHelper;
import to.joeli.jass.client.strategy.helpers.GameSessionBuilder;
import to.joeli.jass.client.strategy.mcts.src.CallLocation;
import to.joeli.jass.game.Trumpf;
import to.joeli.jass.game.cards.Card;
import to.joeli.jass.game.cards.Color;
import to.joeli.jass.game.mode.Mode;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static java.util.Arrays.asList;
import static org.junit.Assert.*;

public class JassBoardTest {

	private Set<Card> allCards = EnumSet.copyOf(Arrays.asList(Card.values()));
	private Set<Card> cards1 = EnumSet.of(Card.CLUB_ACE, Card.CLUB_EIGHT, Card.CLUB_JACK, Card.DIAMOND_SIX, Card.DIAMOND_SEVEN, Card.SPADE_QUEEN, Card.HEART_TEN, Card.SPADE_NINE, Card.SPADE_KING);
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
	public void testRefineMovesWithJassKnowledgeWhenNotFiltering() {
		Set<Card> possibleCards = EnumSet.of(Card.CLUB_ACE, Card.CLUB_KING, Card.CLUB_QUEEN, Card.CLUB_JACK, Card.CLUB_TEN, Card.CLUB_NINE, Card.CLUB_EIGHT, Card.CLUB_SEVEN, Card.CLUB_SIX);
		assertEquals(CardSelectionHelper.refineCardsWithJassKnowledge(possibleCards, diamondsGame), possibleCards);
	}

	@Test
	public void testRefineMovesWithJassKnowledgeNeverRemovesAllCards() {
		Set<Card> possibleCards = EnumSet.of(Card.CLUB_ACE, Card.CLUB_KING, Card.CLUB_QUEEN, Card.CLUB_JACK, Card.CLUB_TEN, Card.CLUB_NINE, Card.CLUB_EIGHT, Card.CLUB_SEVEN, Card.CLUB_SIX);
		assertEquals(CardSelectionHelper.refineCardsWithJassKnowledge(possibleCards, diamondsGame), possibleCards);
	}

	@Test
	public void testGetMoves() {
		for (int i = 0; i < 100; i++) {
			JassBoard jassBoard = JassBoard.constructCardSelectionJassBoard(cards1, diamondsGame, false, false, null, null);
			jassBoard.sampleCardDeterminizationToPlayersInCardPlay();

			assertFalse(jassBoard.getMoves(CallLocation.PLAYOUT).isEmpty());
			assertFalse(jassBoard.getMoves(CallLocation.TREE_POLICY).isEmpty());
		}
	}

	@Test
	public void testGetMovesObeAbe() {
		for (int i = 0; i < 100; i++) {
			JassBoard jassBoard = JassBoard.constructCardSelectionJassBoard(cards1, obeAbeGame, false, false, null, null);
			jassBoard.sampleCardDeterminizationToPlayersInCardPlay();
			// should not get filtered
			assertEquals(9, jassBoard.getMoves(CallLocation.PLAYOUT).size());
			assertEquals(9, jassBoard.getMoves(CallLocation.TREE_POLICY).size());
		}
	}

	@Test
	public void testGetMovesNeverReturnsEmptyArrayList() {
		for (int i = 0; i < 100; i++) {
			JassBoard jassBoard = JassBoard.constructCardSelectionJassBoard(cards1, obeAbeGame, false, false, null, null);
			jassBoard.sampleCardDeterminizationToPlayersInCardPlay();
			// should not get filtered
			JassBoard jassBoard2 = JassBoard.constructCardSelectionJassBoard(CardKnowledgeBase.pickRandomSubSet(allCards, 9), obeAbeGame, false, false, null, null);
			jassBoard2.sampleCardDeterminizationToPlayersInCardPlay();
			assertFalse(jassBoard2.getMoves(CallLocation.PLAYOUT).isEmpty());
			assertFalse(jassBoard2.getMoves(CallLocation.TREE_POLICY).isEmpty());
		}
	}

	@Test
	public void testGetImPossibleCardsForPlayerNormalNotFollowSuit() {
		obeAbeGame.makeMove(new Move(player0, Card.CLUB_SIX));
		obeAbeGame.makeMove(new Move(player1, Card.CLUB_SEVEN));
		obeAbeGame.makeMove(new Move(player2, Card.HEART_EIGHT)); // player 2 did not follow suit
		obeAbeGame.makeMove(new Move(player3, Card.CLUB_KING));
		obeAbeGame.startNextRound();

		Set<Card> impossibleCardsForPlayer = CardKnowledgeBase.getImpossibleCardsForPlayer(obeAbeGame, player2);
		assertEquals(9, impossibleCardsForPlayer.size());

		assertTrue(impossibleCardsForPlayer.contains(Card.CLUB_SIX));
		assertTrue(impossibleCardsForPlayer.contains(Card.CLUB_SEVEN));
		assertTrue(impossibleCardsForPlayer.contains(Card.CLUB_EIGHT));
		assertTrue(impossibleCardsForPlayer.contains(Card.CLUB_NINE));
		assertTrue(impossibleCardsForPlayer.contains(Card.CLUB_TEN));
		assertTrue(impossibleCardsForPlayer.contains(Card.CLUB_JACK));
		assertTrue(impossibleCardsForPlayer.contains(Card.CLUB_QUEEN));
		assertTrue(impossibleCardsForPlayer.contains(Card.CLUB_KING));
		assertTrue(impossibleCardsForPlayer.contains(Card.CLUB_ACE));
	}

	@Test
	public void testGetImPossibleCardsForPlayerTrumpfNotFollowSuit() {
		diamondsGame.makeMove(new Move(player0, Card.DIAMOND_EIGHT));
		diamondsGame.makeMove(new Move(player1, Card.DIAMOND_NINE));
		diamondsGame.makeMove(new Move(player2, Card.HEART_EIGHT)); // player 2 did not follow suit
		diamondsGame.makeMove(new Move(player3, Card.DIAMOND_QUEEN));
		diamondsGame.startNextRound();

		Set<Card> impossibleCardsForPlayer = CardKnowledgeBase.getImpossibleCardsForPlayer(diamondsGame, player2);
		assertEquals(8, impossibleCardsForPlayer.size());

		assertTrue(impossibleCardsForPlayer.contains(Card.DIAMOND_SIX));
		assertTrue(impossibleCardsForPlayer.contains(Card.DIAMOND_SEVEN));
		assertTrue(impossibleCardsForPlayer.contains(Card.DIAMOND_EIGHT));
		assertTrue(impossibleCardsForPlayer.contains(Card.DIAMOND_NINE));
		assertTrue(impossibleCardsForPlayer.contains(Card.DIAMOND_TEN));
		assertFalse(impossibleCardsForPlayer.contains(Card.DIAMOND_JACK)); // special rule for jack
		assertTrue(impossibleCardsForPlayer.contains(Card.DIAMOND_QUEEN));
		assertTrue(impossibleCardsForPlayer.contains(Card.DIAMOND_KING));
		assertTrue(impossibleCardsForPlayer.contains(Card.DIAMOND_ACE));
	}

	@Test
	public void testDuplicateFalsePreservesTrumpfDeterminization() {
		// Regression: before the fix, constructTrumpfSelectionJassBoard() unconditionally
		// called sampleCardDeterminizationToPlayersInTrumpfSelection(), and duplicate() called
		// that factory regardless of newRandomCards. So every tree-policy duplicate(false) got
		// a fresh random deal, meaning stored tree moves referenced (player, card) pairs from
		// a stale determinization. Cards were silently un-removable, and the same card could
		// end up in two players' hands and appear in Round.moves twice.
		//
		// With the fix: duplicate(false) only copies; determinization happens only on
		// duplicate(true), matching the card-play pattern.
		GameSession gameSession = GameSessionBuilder.newSession().createGameSession();
		Set<Card> availableCards = gameSession.getTrumpfSelectingPlayer().getCards();

		JassBoard root = JassBoard.constructTrumpfSelectionJassBoard(
				availableCards, gameSession, false, false, false, null, null);

		// MCTSTask: one determinization per task
		JassBoard task = (JassBoard) root.duplicate(true);
		List<Set<Card>> handsAfterDeterminize = task.snapshotPlayerCards();

		// Tree policy: duplicate(false) must NOT re-randomize
		JassBoard treePolicy = (JassBoard) task.duplicate(false);
		List<Set<Card>> handsAfterTreePolicy = treePolicy.snapshotPlayerCards();

		assertEquals("duplicate(false) in trumpf selection must preserve the determinization, not re-randomize",
				handsAfterDeterminize, handsAfterTreePolicy);
	}
}