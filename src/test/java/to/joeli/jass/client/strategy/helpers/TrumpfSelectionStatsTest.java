package to.joeli.jass.client.strategy.helpers;

import org.junit.Test;
import to.joeli.jass.client.game.GameSession;
import to.joeli.jass.client.game.Player;
import to.joeli.jass.client.strategy.config.Config;
import to.joeli.jass.game.cards.Card;
import to.joeli.jass.game.mode.Mode;

import java.util.*;
import java.util.stream.Collectors;

public class TrumpfSelectionStatsTest {

    @Test
    public void trumpSelectionDistribution() {
        GameSession session = GameSessionBuilder.newSession().createGameSession();
        session.setConfigs(new Config[]{new Config(), new Config()});

        Random random = new Random(42);
        List<Card> cards = new ArrayList<>(Arrays.asList(Card.values()));

        int numDeals = 100_000;
        int shifts = 0;
        Map<String, Integer> initialCounts = new LinkedHashMap<>();
        Map<String, Integer> shiftedCounts = new LinkedHashMap<>();

        for (int i = 0; i < numDeals; i++) {
            Collections.shuffle(cards, random);
            session.dealCards(cards);

            Player selector = session.getTrumpfSelectingPlayer();
            Mode mode = selector.chooseTrumpf(session, false);

            if (mode.equals(Mode.shift())) {
                shifts++;
                Player partner = session.getPartnerOfPlayer(selector);
                mode = partner.chooseTrumpf(session, true);
                shiftedCounts.merge(modeKey(mode), 1, Integer::sum);
            } else {
                initialCounts.merge(modeKey(mode), 1, Integer::sum);
            }
        }

        final int noShifts = numDeals - shifts;
        final int finalShifts = shifts;
        System.out.printf("%nTrump selection stats over %d deals%n", numDeals);
        System.out.printf("Shift rate: %.1f%% (%d/%d)%n%n", 100.0 * finalShifts / numDeals, finalShifts, numDeals);

        System.out.println("Initial selection (no shift):");
        initialCounts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .forEach(e -> System.out.printf("  %-12s %5.1f%%  (%d)%n",
                        e.getKey(), 100.0 * e.getValue() / noShifts, e.getValue()));

        System.out.println("\nAfter shift (geschoben):");
        shiftedCounts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .forEach(e -> System.out.printf("  %-12s %5.1f%%  (%d)%n",
                        e.getKey(), 100.0 * e.getValue() / finalShifts, e.getValue()));
    }

    @Test
    public void tieExamples() {
        GameSession session = GameSessionBuilder.newSession().createGameSession();
        session.setConfigs(new Config[]{new Config(), new Config()});

        Random random = new Random(42);
        List<Card> cards = new ArrayList<>(Arrays.asList(Card.values()));

        int found = 0;
        for (int i = 0; i < 100000 && found < 10; i++) {
            Collections.shuffle(cards, random);
            session.dealCards(cards);

            Player selector = session.getTrumpfSelectingPlayer();
            Set<Card> hand = selector.getCards();
            LinkedHashMap<Mode, Integer> ratings = TrumpfSelectionHelper.rateAll(hand, false);

            int topScore = ratings.entrySet().iterator().next().getValue();
            List<Map.Entry<Mode, Integer>> tied = ratings.entrySet().stream()
                    .filter(e -> e.getValue() == topScore)
                    .collect(Collectors.toList());

            if (tied.size() > 1) {
                found++;
                System.out.printf("%nTie example #%d (score=%d, chosen=%s):%n", found, topScore, tied.get(0).getKey());
                System.out.printf("  Hand: %s%n", hand);
                System.out.printf("  Tied modes: %s%n",
                        tied.stream().map(e -> modeKey(e.getKey())).collect(Collectors.joining(", ")));
            }
        }
        System.out.printf("%nFound %d ties in first 100k deals%n", found);
    }

    private static String modeKey(Mode mode) {
        if (mode.isTrumpfMode()) return mode.getTrumpfColor().toString();
        return mode.getTrumpfName().toString();
    }
}
