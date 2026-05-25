package to.joeli.jass.client.strategy.training;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import to.joeli.jass.client.game.*;
import to.joeli.jass.client.strategy.config.Config;
import to.joeli.jass.client.strategy.helpers.GameSessionBuilder;
import to.joeli.jass.client.strategy.helpers.TrumpfSelectionHelper;
import to.joeli.jass.game.cards.Card;
import to.joeli.jass.game.cards.Color;
import to.joeli.jass.game.mode.Mode;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.EnumSet;
import java.util.stream.Collectors;

/**
 * Generates trump-selection training data by playing out games under each of the
 * heuristic's top-3 trump candidates and recording which one actually scored best.
 *
 * Output: CSV written directly to the path supplied at construction time.
 *   deal_id, hand card flags (36 binary), suit counts (4), top-3 modes with heuristic
 *   scores, actual team scores for each mode, winner index (0=heuristic #1 was best).
 */
public class TrumpDataCollector {

    private static final Logger logger = LoggerFactory.getLogger(TrumpDataCollector.class);

    private final String outputPath;
    private final GameSession gameSession;
    private final Random random;

    public TrumpDataCollector(Config cardConfig, long seed, String outputPath) {
        this.outputPath = outputPath;
        gameSession = GameSessionBuilder.newSession().createGameSession();
        gameSession.setConfigs(new Config[]{cardConfig, cardConfig});
        random = new Random(seed);
    }

    public void collect(int numDeals) {
        try (PrintWriter out = new PrintWriter(Files.newBufferedWriter(Paths.get(outputPath)))) {
            out.println(csvHeader());
            List<Card> cards = new ArrayList<>(Arrays.asList(Card.values()));
            for (int i = 0; i < numDeals; i++) {
                Collections.shuffle(cards, random);
                try {
                    collectDeal(i, new ArrayList<>(cards), out);
                } catch (Exception e) {
                    logger.warn("Skipping deal {}: {}", i, e.getMessage());
                }
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to write " + outputPath, e);
        }
        gameSession.resetResult();
    }

    private void collectDeal(int dealId, List<Card> cards, PrintWriter out) {
        gameSession.dealCards(cards);
        Player selector = gameSession.getTrumpfSelectingPlayer();
        Set<Card> hand = EnumSet.copyOf(selector.getCards()); // snapshot before games modify the hand

        LinkedHashMap<Mode, Integer> ratings = TrumpfSelectionHelper.rateAll(hand, false);
        List<Map.Entry<Mode, Integer>> top3 = ratings.entrySet().stream()
                .filter(e -> !e.getKey().equals(Mode.shift()))
                .limit(3)
                .collect(Collectors.toList());
        if (top3.size() < 3) return;

        int[] scores = new int[3];
        for (int i = 0; i < 3; i++)
            scores[i] = playGameWithMode(cards, top3.get(i).getKey(), selector);

        int winner = indexOfMax(scores);
        out.println(formatRow(dealId, hand, top3, scores, winner));
        gameSession.resetResult();
    }

    private int playGameWithMode(List<Card> cards, Mode mode, Player selector) {
        gameSession.dealCards(cards);
        gameSession.startNewGame(mode, false);
        Game game = gameSession.getCurrentGame();
        while (!game.gameFinished()) {
            while (!game.getCurrentRound().roundFinished()) {
                Player p = game.getCurrentPlayer();
                Move move = p.makeMove(gameSession);
                gameSession.makeMove(move);
                p.onMoveMade(move);
            }
            gameSession.startNextRound();
        }
        return game.getResult().getTeamScore(selector);
    }

    private static int indexOfMax(int[] arr) {
        int best = 0;
        for (int i = 1; i < arr.length; i++)
            if (arr[i] > arr[best]) best = i;
        return best;
    }

    private static String modeKey(Mode mode) {
        if (mode.isTrumpfMode()) return mode.getTrumpfColor().toString();
        return mode.getTrumpfName().toString();
    }

    private static String csvHeader() {
        StringBuilder sb = new StringBuilder("deal_id");
        for (Card c : Card.values()) sb.append(",has_").append(c.name());
        for (Color col : Color.values()) sb.append(",n_").append(col.name().toLowerCase());
        sb.append(",mode_1,heur_1,mode_2,heur_2,mode_3,heur_3");
        sb.append(",score_1,score_2,score_3,winner");
        return sb.toString();
    }

    private static String formatRow(int dealId, Set<Card> hand,
                                    List<Map.Entry<Mode, Integer>> top3,
                                    int[] scores, int winner) {
        StringBuilder sb = new StringBuilder(String.valueOf(dealId));
        for (Card c : Card.values()) sb.append(",").append(hand.contains(c) ? 1 : 0);
        for (Color col : Color.values()) {
            long cnt = hand.stream().filter(c -> c.getColor() == col).count();
            sb.append(",").append(cnt);
        }
        for (Map.Entry<Mode, Integer> e : top3)
            sb.append(",").append(modeKey(e.getKey())).append(",").append(e.getValue());
        for (int s : scores) sb.append(",").append(s);
        sb.append(",").append(winner);
        return sb.toString();
    }
}
