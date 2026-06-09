package to.joeli.jass.client.strategy.helpers;

import com.google.common.collect.ImmutableMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import to.joeli.jass.client.game.*;
import to.joeli.jass.client.strategy.training.networks.CardsEstimator;
import to.joeli.jass.game.cards.Card;
import to.joeli.jass.game.cards.CardValue;
import to.joeli.jass.game.cards.Color;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Can infer knowledge about which player might hold which card based on the course of a given game so far.
 * Can then sample from the given distributions to deal the remaining cards to the players.
 */
public class CardKnowledgeBase {

	public static final Logger logger = LoggerFactory.getLogger(CardKnowledgeBase.class);

	// Re-run the cards network every this many assignments (auto-regressive approach A).
	// k=1: fully auto-regressive (~27 calls/det); k=3: ~9 calls; k=27: no re-inference (1 call).
	static final int REINFERENCE_EVERY_K = 3;

	private CardKnowledgeBase() {

	}

	/**
	 * Distribute the unknown cards to the other players at the beginning of the game, when a player is choosing a trumpf.
	 * IMPORTANT: To be used before the game started, during trumpf selection!
	 *
	 * When shifted=true the current player is the partner of the original selector. The original selector
	 * revealed a weak hand by shifting, so we rejection-sample their cards until the sampled hand would
	 * also shift according to the heuristic. This makes determinizations realistic after a pass.
	 *
	 * @param gameSession
	 * @param availableCards cards held by the player who is now choosing trump
	 * @param shifted        whether the original selector already passed
	 */
	public static void sampleCardDeterminizationToPlayers(GameSession gameSession, Set<Card> availableCards, boolean shifted) {
		Player currentPlayer = shifted
				? gameSession.getPartnerOfPlayer(gameSession.getTrumpfSelectingPlayer())
				: gameSession.getTrumpfSelectingPlayer();
		currentPlayer.setCards(EnumSet.copyOf(availableCards));

		Player originalSelector = gameSession.getTrumpfSelectingPlayer();

		Set<Card> remainingCards = EnumSet.allOf(Card.class);
		remainingCards.removeAll(availableCards);
		if (remainingCards.isEmpty()) throw new AssertionError();

		List<Player> others = new ArrayList<>();
		for (Player player : gameSession.getPlayersInInitialPlayingOrder())
			if (!player.equals(currentPlayer))
				others.add(player);

		if (shifted) {
			// Assign the two non-selector, non-current players first (no constraint on them).
			Set<Card> pool = EnumSet.copyOf(remainingCards);
			for (Player player : others) {
				if (player.equals(originalSelector)) continue;
				Set<Card> cards = pickRandomSubSet(pool, 9);
				player.setCards(cards);
				pool.removeAll(cards);
			}
			// pool now has exactly 9 cards for the original selector; reject if too strong.
			// Retry the whole assignment up to MAX_REJECTION_ATTEMPTS times.
			for (int attempt = 0; attempt < MAX_REJECTION_ATTEMPTS; attempt++) {
				if (TrumpfSelectionHelper.wouldShift(pool)) break;
				// Re-draw all three non-current players together and re-split.
				pool = EnumSet.copyOf(remainingCards);
				for (Player player : others) {
					if (player.equals(originalSelector)) continue;
					Set<Card> cards = pickRandomSubSet(pool, 9);
					player.setCards(cards);
					pool.removeAll(cards);
				}
			}
			originalSelector.setCards(pool);
		} else {
			for (Player player : others) {
				Set<Card> cards = pickRandomSubSet(remainingCards, 9);
				player.setCards(cards);
				remainingCards.removeAll(cards);
			}
		}

		for (Player player : gameSession.getPlayersInInitialPlayingOrder())
			if (player.getCards().isEmpty()) throw new AssertionError("Player " + player + " has no cards after determinization");
		// Invariant: hands must be pairwise disjoint and union to all 36 cards.
		assertHandsArePartition(gameSession.getPlayersInInitialPlayingOrder(), "trumpf-selection determinization");
	}

	private static void assertHandsArePartition(List<Player> players, String context) {
		Set<Card> seen = EnumSet.noneOf(Card.class);
		for (Player player : players) {
			for (Card card : player.getCards()) {
				if (!seen.add(card)) {
					throw new IllegalStateException(String.format(
							"INVARIANT VIOLATED in %s: card %s appears in multiple hands. Hands: %s",
							context, card,
							players.stream().map(p -> p.getName() + ":" + p.getCards())
									.reduce((a, b) -> a + ", " + b).orElse("")));
				}
			}
		}
	}

	/** Backward-compatible overload for the non-shifted case. */
	public static void sampleCardDeterminizationToPlayers(GameSession gameSession, Set<Card> availableCards) {
		sampleCardDeterminizationToPlayers(gameSession, availableCards, false);
	}

	private static final int MAX_REJECTION_ATTEMPTS = 20;


	/**
	 * Samples a card determinization, optionally rejecting at round 0 if the sampled forehand hand
	 * would not have produced the observed shift/no-shift action under the rule-based heuristic.
	 * Mirrors the rejection in {@link #sampleCardDeterminizationToPlayers(GameSession, Set, boolean)}.
	 */
	public static void sampleCardDeterminizationToPlayers(Game game, Set<Card> availableCards,
														 CardsEstimator cardsEstimator,
														 boolean trumpConditioned) {
		sampleCardDeterminizationToPlayers(game, availableCards, cardsEstimator);
		if (!trumpConditioned) return;
		for (int attempt = 1; attempt < MAX_REJECTION_ATTEMPTS; attempt++) {
			if (isConsistentWithTrumpAction(game)) return;
			sampleCardDeterminizationToPlayers(game, availableCards, cardsEstimator);
		}
		// Fall through: accept the last sample even if inconsistent (matches shifted-trumpf-phase behavior).
	}

	/**
	 * Round-0 check: the forehand's reconstructed original 9-card hand is consistent with whether
	 * they shifted or not. Outside round 0 (or in unexpected states) the predicate returns true so
	 * the sampler accepts immediately.
	 */
	private static boolean isConsistentWithTrumpAction(Game game) {
		Round round = game.getCurrentRound();
		if (round.getRoundNumber() != 0) return true;
		Player forehand = round.getPlayingOrder().getPlayersInInitialOrder().get(0);
		Set<Card> forehandOriginalHand = EnumSet.copyOf(forehand.getCards());
		Card playedByForehand = round.getCardOfPlayer(forehand);
		if (playedByForehand != null) forehandOriginalHand.add(playedByForehand);
		if (forehandOriginalHand.size() != 9) return true; // unexpected state — fail open
		return TrumpfSelectionHelper.wouldShift(forehandOriginalHand) == game.isShifted();
	}

	/**
	 * Samples a card determinization for a player with the given cards for the current player in a given game.
	 * If a player did not follow suit in the game so far, the player will not be distributed any cards of this suit.
	 * IMPORTANT: To be used during a game!
	 *
	 * @param game
	 * @param availableCards
	 */
	public static void sampleCardDeterminizationToPlayers(Game game, Set<Card> availableCards, CardsEstimator cardsEstimator) {
		// INFO: This method should only be used when new cards are distributed (at the beginning of a move).

		/*
		boolean inPerfectInformationSetting = false; // Determines if the method is invoked from a perfect information setting (aka from the Arena)
		int[] numCards = new int[4];
		for (int i = 0; i < 4; i++) {
			numCards[i] = game.getPlayers().get(i).getCards().size();
			if (numCards[i] > 0)
				inPerfectInformationSetting = true;
		}
		*/

		Map<Card, Distribution> cardKnowledge;
		if (cardsEstimator == null) {
			cardKnowledge = CardKnowledgeBase.initCardKnowledge(game, availableCards);
		} else {
			// The cards estimator extends this with a belief distribution: we can assume that a player has/has not some cards based on the game.
			// For example when a player did not take a very valuable stich he probably does not have any trumpfs or higher cards of the given suit.
			// This could also be solved with rule based approaches but we hope that the learning based one is superior
			cardKnowledge = cardsEstimator.predictCardDistribution(game, availableCards);
		}

		// Delete all the cards of the players so we can distribute the determinization
		game.getPlayers().forEach(player -> player.setCards(EnumSet.noneOf(Card.class)));

		int assignedCount = 0;
		while (cardsNeedToBeDistributed(cardKnowledge)) {
			// Auto-regressive re-inference: every REINFERENCE_EVERY_K assignments, re-run the
			// network conditioned on already-assigned cards (which appear as 1-hot certainties
			// in the feature input). Skip on the first iteration (nothing assigned yet).
			if (cardsEstimator != null && assignedCount > 0 && assignedCount % REINFERENCE_EVERY_K == 0) {
				cardsEstimator.refineCardDistribution(game, cardKnowledge);
			}

			Optional<Map.Entry<Card, Distribution>> best;
			Stream<Map.Entry<Card, Distribution>> stream = getStreamWithNotSampledDistributions(cardKnowledge);
			if (cardsEstimator != null) {
				// Commit to highest-confidence assignments first so downstream re-inference is most useful
				best = stream.max(Comparator.comparingDouble(e -> e.getValue().maxProbability()));
			} else {
				// Arc-consistency: fewest possible players first (best for uniform distributions)
				best = stream.min(Comparator.comparingInt(e -> e.getValue().size()));
			}

			if (best.isPresent()) {
				Map.Entry<Card, Distribution> entry = best.get();
				Card card = entry.getKey();
				Player player = entry.getValue().sample();
				player.addCard(card);
				// Collapse to 1-hot so subsequent re-inference encodes this assignment
				// as a certainty in CARDS_DISTRIBUTION rather than the prior soft distribution.
				entry.setValue(new Distribution(Collections.singletonMap(player, 1f), true));
				deletePlayerFromRemainingDistributions(game, availableCards, cardKnowledge, player);
				assignedCount++;
			}
		}

		/*
		if (inPerfectInformationSetting)
			for (int i = 0; i < 4; i++) {
				if (game.getPlayers().get(i).getCards().size() != numCards[i]) {
					logger.error("Some weird coincidence made it impossible to sample the cards for the other players validly");
				}
			}
		*/
	}

	/**
	 * As soon as a player has enough cards, delete him from all remaining distributions
	 *
	 * @param game
	 * @param availableCards
	 * @param cardKnowledge
	 * @param player
	 */
	private static void deletePlayerFromRemainingDistributions(Game game, Set<Card> availableCards, Map<Card, Distribution> cardKnowledge, Player player) {
		final double numberOfCards = getRemainingCards(availableCards, game).size() / 3.0;
		if (player.getCards().size() == getNumberOfCardsToAdd(game, numberOfCards, player)) {
			getStreamWithNotSampledDistributions(cardKnowledge)
					.filter(entry -> entry.getValue().hasPlayer(player))
					.forEach(entry -> {
						boolean deleted = entry.getValue().deleteEventAndReBalance(player);
						if (!deleted) {
							// If this is a current-player card (from availableCards), the
							// numberOfCards quota (computed over the three *other* players) fires
							// one card too early. The current player still needs this card — leave
							// the singleton intact so the main loop assigns it next iteration.
							if (availableCards.contains(entry.getKey())) return;

							// Otherwise: player is already at quota but is the sole possible
							// holder of this card. The constraint system is overconstrained (more
							// cards forced to one player than their quota, likely from over-strict
							// suit-voiding inference). Reopen to all other eligible players so the
							// card still gets assigned and no player is left with 0 cards.
							List<Player> candidates = game.getPlayers().stream()
									.filter(p -> !p.equals(game.getCurrentPlayer())
											&& !p.equals(player)
											&& p.getCards().size() < getNumberOfCardsToAdd(game, numberOfCards, p))
									.collect(Collectors.toList());
							if (!candidates.isEmpty()) {
								Map<Player, Float> newProbs = new HashMap<>();
								float prob = 1f / candidates.size();
								candidates.forEach(p -> newProbs.put(p, prob));
								entry.setValue(new Distribution(newProbs, false));
							} else {
								// No eligible recipient — mark sampled to prevent an infinite loop.
								entry.getValue().setSampled(true);
							}
						}
					});
		}
	}

	/**
	 * Picks a random sub set out of the given cards with the given size.
	 *
	 * @param cards
	 * @param numberOfCards
	 * @return
	 */
	public static Set<Card> pickRandomSubSet(Set<Card> cards, int numberOfCards) {
		if ((numberOfCards <= 0 || numberOfCards > 9)) throw new AssertionError();
		if (numberOfCards > cards.size()) throw new AssertionError();
		List<Card> listOfCards = new LinkedList<>(cards);
		Collections.shuffle(listOfCards);
		List<Card> randomSublist = listOfCards.subList(0, numberOfCards);
		Set<Card> randomSubSet = EnumSet.copyOf(randomSublist);
		if ((!cards.containsAll(randomSubSet))) throw new AssertionError();
		return randomSubSet;
	}

	public static Map<Card, Distribution> initCardKnowledge(Game game, Set<Card> availableCards) {
		return initCardKnowledge(game, availableCards, null);
	}

	/**
	 * Initializes a basic card distribution based only on the information we know for sure. Only certainties are encoded.
	 * This could be extended with rule based knowledge or with learning based approaches.
	 *
	 * @param game
	 * @param availableCards
	 * @return
	 */
	public static Map<Card, Distribution> initCardKnowledge(Game game, Set<Card> availableCards, List<Color> colors) {
		assert !availableCards.isEmpty();

		Map<Card, Distribution> cardKnowledge = new EnumMap<>(Card.class);

		// Set simple distributions for the cards of the current player
		availableCards.forEach(card -> {
			final Card respectiveCard = DataAugmentationHelper.getRespectiveCard(card, colors);
			cardKnowledge.put(respectiveCard, new Distribution(ImmutableMap.of(game.getCurrentPlayer(), 1f), false));
		});


		// Init remaining unknown cards with equal probability for the other players
		for (Card card : getRemainingCards(availableCards, game)) {
			Map<Player, Float> probabilitiesMap = new HashMap<>();
			List<Player> players = new ArrayList<>(game.getPlayers());
			players.remove(game.getCurrentPlayer());
			for (Player player : players) {
				probabilitiesMap.put(player, 1.0f / players.size());
			}
			cardKnowledge.put(DataAugmentationHelper.getRespectiveCard(card, colors), new Distribution(probabilitiesMap, false));
		}

		deleteImpossibleCardsFromCardKnowledge(game, colors, cardKnowledge);

		final List<Move> historyMoves = DataAugmentationHelper.getRespectiveMoves(game.getAlreadyPlayedMovesInOrder(), colors);
		// Add already played moves to card knowledge
		historyMoves.forEach(move -> cardKnowledge.put(move.getPlayedCard(), new Distribution(Collections.singletonMap(move.getPlayer(), 1f), true)));

		return cardKnowledge;
	}


	private static int getNumberOfCardsToAdd(Game game, double numberOfCards, Player player) {
		if (game.getCurrentRound().hasPlayerAlreadyPlayed(player))
			return (int) Math.floor(numberOfCards);
		return (int) Math.ceil(numberOfCards);
	}

	private static Stream<Map.Entry<Card, Distribution>> getStreamWithNotSampledDistributions(Map<Card, Distribution> cardDistributionMap) {
		return cardDistributionMap.entrySet().stream().filter(entry -> !entry.getValue().isSampled());
	}

	private static boolean cardsNeedToBeDistributed(Map<Card, Distribution> cardDistributionMap) {
		return getStreamWithNotSampledDistributions(cardDistributionMap).count() > 0;
	}

	/**
	 * Deletes all the cards which are not possible to be held by players given the previous rounds.
	 *
	 * @param game
	 * @param colors
	 * @param cardKnowledge
	 */
	private static void deleteImpossibleCardsFromCardKnowledge(Game game, List<Color> colors, Map<Card, Distribution> cardKnowledge) {
		for (Player player : game.getPlayers()) {
			Set<Card> impossibleCardsForPlayer = getImpossibleCardsForPlayer(game, player);
			for (Card card : impossibleCardsForPlayer) {
				card = DataAugmentationHelper.getRespectiveCard(card, colors);
				if (cardKnowledge.containsKey(card))
					cardKnowledge.get(card).deleteEventAndReBalance(player);
			}
		}
	}

	/**
	 * Composes a set of cards which are impossible for a given player to be held at a given point in a game
	 * If player did not follow suit earlier in the game, add all cards of this suit to this set.
	 *
	 * @param game
	 * @param player
	 * @return
	 */
	public static Set<Card> getImpossibleCardsForPlayer(Game game, Player player) {
		Set<Card> impossibleCards = EnumSet.noneOf(Card.class);
		game.getPreviousRounds().forEach(round -> addImpossibleCardsFromRoundForPlayer(impossibleCards, round, player));
		addImpossibleCardsFromRoundForPlayer(impossibleCards, game.getCurrentRound(), player);
		return impossibleCards;
	}

	/**
	 * Adds cards which are impossible for a player to hold based on a given round
	 *
	 * @param impossibleCards
	 * @param round
	 * @param player
	 */
	private static void addImpossibleCardsFromRoundForPlayer(Set<Card> impossibleCards, Round round, Player player) {
		if (!round.hasPlayerAlreadyPlayed(player))
			return;
		Color playerCardColor = round.getCardOfPlayer(player).getColor();
		Color trumpfColor = round.getMode().getTrumpfColor();
		Color leadingColor = round.getMoves().get(0).getPlayedCard().getColor();
		boolean playerPlayedTrumpf = playerCardColor.equals(trumpfColor);
		boolean playerFollowedSuit = playerCardColor.equals(leadingColor);
		if (!player.wasStartingPlayer(round) && !playerFollowedSuit && !playerPlayedTrumpf) {
			Set<Card> impossibleCardsToAdd = EnumSet.allOf(Card.class).stream()
					.filter(card -> !cardIsPossible(trumpfColor, leadingColor, card))
					.collect(Collectors.toSet());
			impossibleCards.addAll(impossibleCardsToAdd);
		}
	}

	/**
	 * Determines if it is allowed by the rules to play a card based on the trumpf color and the color of the leading card
	 *
	 * @param trumpfColor
	 * @param leadingColor
	 * @param card
	 * @return
	 */
	private static boolean cardIsPossible(Color trumpfColor, Color leadingColor, Card card) {
		if (card.getColor().equals(leadingColor)) {
			boolean cardIsTrumpfJack = card.getColor().equals(trumpfColor) && card.getValue().equals(CardValue.JACK);
			return leadingColor.equals(trumpfColor) && cardIsTrumpfJack;
		}
		return true;
	}

	/**
	 * Get the cards remaining to be split up on the other players.
	 * All cards - already played cards - available cards
	 *
	 * @param availableCards
	 * @return
	 */
	private static Set<Card> getRemainingCards(Set<Card> availableCards, Game game) {
		Set<Card> cards = EnumSet.allOf(Card.class);
		if (cards.size() != 36) throw new AssertionError();
		cards.removeAll(availableCards);
		Set<Card> alreadyPlayedCards = game.getAlreadyPlayedCards();
		Round round = game.getCurrentRound();
		if (alreadyPlayedCards.size() != round.getRoundNumber() * 4 + round.getPlayedCards().size())
			throw new AssertionError();
		cards.removeAll(alreadyPlayedCards);
		return cards;
	}

}
