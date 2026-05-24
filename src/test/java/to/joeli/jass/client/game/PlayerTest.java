package to.joeli.jass.client.game;

import to.joeli.jass.client.strategy.helpers.GameSessionBuilder;
import to.joeli.jass.game.cards.Card;
import to.joeli.jass.game.mode.Mode;
import to.joeli.jass.client.strategy.JassStrategy;
import org.junit.Test;
import org.mockito.Matchers;

import java.util.EnumSet;
import java.util.Set;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class PlayerTest {

    @Test
    public void chooseCard_strategyReturnsInvalidCard() {
        final GameSession gameSession = GameSessionBuilder
                .newSession()
                .withStartedGame(Mode.bottomUp())
                .withCardsPlayed(Card.CLUB_EIGHT)
                .createGameSession();

        final JassStrategy invalidPlayingStrategy = mock(JassStrategy.class);
        when(invalidPlayingStrategy.chooseCard(Matchers.<Set<Card>>any(), any(GameSession.class))).thenReturn(Card.HEART_EIGHT);
        Player player = new Player("test", invalidPlayingStrategy);
        player.setCards(EnumSet.of(Card.HEART_EIGHT, Card.CLUB_ACE));

        final Card card = player.makeMove(gameSession).getPlayedCard();

        assertThat(card, equalTo(Card.CLUB_ACE));
    }

    // Regression: in production the move's Player is a freshly-deserialized object —
    // a different reference than the local player, but equal by id+name.
    // onMoveMade must use equals(), not ==, to remove the card.
    @Test
    public void onMoveMade_removesCard_whenMovePlayerEqualsByValue() {
        Player local = new Player("seat0", "Alice", 0);
        local.setCards(EnumSet.of(Card.CLUB_ACE, Card.HEART_SIX));

        Player remote = new Player("seat0", "Alice", 0);
        local.onMoveMade(new Move(remote, Card.CLUB_ACE));

        assertThat(local.getCards(), equalTo(EnumSet.of(Card.HEART_SIX)));
    }

    @Test
    public void onMoveMade_doesNotRemoveCard_whenDifferentPlayer() {
        Player alice = new Player("seat0", "Alice", 0);
        alice.setCards(EnumSet.of(Card.CLUB_ACE));
        Player bob = new Player("seat1", "Bob", 1);

        alice.onMoveMade(new Move(bob, Card.CLUB_ACE));

        assertThat(alice.getCards(), equalTo(EnumSet.of(Card.CLUB_ACE)));
    }

    @Test
    public void onMoveMade_ownMove_handShrinksByOne() {
        Player alice = new Player("seat0", "Alice", 0);
        alice.setCards(EnumSet.of(Card.CLUB_ACE, Card.HEART_SIX, Card.DIAMOND_TEN));
        Player aliceRemote = new Player("seat0", "Alice", 0);

        alice.onMoveMade(new Move(aliceRemote, Card.CLUB_ACE));

        assertThat(alice.getCards().size(), equalTo(2));
    }

    @Test
    public void onMoveMade_otherPlayersMove_handUnchanged() {
        Player alice = new Player("seat0", "Alice", 0);
        alice.setCards(EnumSet.of(Card.CLUB_ACE, Card.HEART_SIX, Card.DIAMOND_TEN));
        Player bob = new Player("seat1", "Bob", 1);

        alice.onMoveMade(new Move(bob, Card.HEART_SIX));

        assertThat(alice.getCards().size(), equalTo(3));
    }

}