package to.joeli.jass.client.game;

import to.joeli.jass.game.cards.Card;
import to.joeli.jass.game.mode.Mode;
import org.junit.Test;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static to.joeli.jass.game.cards.Card.*;
import static to.joeli.jass.game.cards.Color.DIAMONDS;
import static java.util.Arrays.asList;
import static java.util.stream.Collectors.toList;
import static org.hamcrest.core.IsEqual.equalTo;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThat;

public class RoundTest {

    @Test
    public void getRoundColor_returnsColorOfFirstCard() {
        List<Player> players = asList(new Player("P0"), new Player("P1"), new Player("P2"), new Player("P3"));
        final Round round = Round.createRound(Mode.topDown(), 0, PlayingOrder.createOrder(players));
        round.makeMove(new Move(players.get(0), DIAMOND_ACE));
        assertThat(round.getRoundColor(), equalTo(DIAMONDS));
    }

    @Test
    public void getRoundColor_noCardHasBeenPlayed_returnsNull() {
        List<Player> players = asList(new Player("P0"), new Player("P1"), new Player("P2"), new Player("P3"));
        final Round round = Round.createRound(Mode.topDown(), 0, PlayingOrder.createOrder(players));
        assertNull(round.getRoundColor());
    }

    @Test(expected = RuntimeException.class)
    public void makeMove_whenAlreadyEnoughCardsWerePlayed() {
        List<Player> players = asList(new Player("P0"), new Player("P1"), new Player("P2"), new Player("P3"));
        final Round round = createRoundWithCardsPlayed(0, PlayingOrder.createOrder(players),
                EnumSet.of(HEART_ACE, HEART_SIX, HEART_TEN, HEART_JACK));
        round.makeMove(new Move(players.get(0), HEART_SEVEN));
    }

    @Test(expected = RuntimeException.class)
    public void makeMove_isNotPlayersTurn() {

        final Player secondPlayer = new Player("test 2");
        final Round round = createRoundWithCardsPlayed(0, PlayingOrder.createOrder(asList(
                new Player("Test 1"),
                secondPlayer,
                new Player("test 3"),
                new Player("test 4"))), EnumSet.noneOf(Card.class));

        round.makeMove(new Move(secondPlayer, HEART_EIGHT));
    }

    private static Round createRoundWithCardsPlayed(int roundNumber, PlayingOrder playingOrder, Set<Card> playedCards) {
        List<Move> moves = playedCards.stream().map(card -> {
            final Player currentPlayer = playingOrder.getCurrentPlayer();
            playingOrder.moveToNextPlayer();
            return new Move(currentPlayer, card);
        }).collect(toList());
        return createRoundWithMoves(roundNumber, playingOrder, moves);
    }

    private static Round createRoundWithMoves(int roundNumber, PlayingOrder playingOrder, List<Move> moves) {
        final Round round = Round.createRound(Mode.topDown(), roundNumber, playingOrder);
        moves.forEach(round::makeMove);
        return round;
    }

}