package to.joeli.jass.client.strategy.mcts.src;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.DoubleAdder;
import java.util.concurrent.atomic.LongAdder;
import java.util.stream.Collectors;

/**
 * Thread-safe accumulators for MCTS instrumentation. Aggregates across root-parallelized
 * determinizations within a single move decision. Reset between moves via {@link #reset}.
 *
 * Tree-policy depth = number of moves made during the selection/expansion phase before
 * handing off to rollout. "Reached end-of-game" means the tree traversal arrived at a
 * terminal state (so the rollout phase had nothing to do).
 *
 * Root-child visits aggregate the games count of each first-move child across all
 * determinizations — i.e., how often each candidate move was chosen as the tree-policy's
 * preferred descent at the root.
 */
public class MctsStats {
	private final LongAdder depthSum = new LongAdder();
	private final LongAdder depthSumSq = new LongAdder();
	private final LongAdder depthCount = new LongAdder();
	private final LongAdder reachedEndOfGameCount = new LongAdder();
	private final AtomicLong maxDepth = new AtomicLong(0);
	private final Map<Move, LongAdder> rootChildVisits = new ConcurrentHashMap<>();
	private final Map<Move, DoubleAdder> rootChildScoreSums = new ConcurrentHashMap<>();

	public void recordTreePolicy(int depth, boolean reachedEndOfGame) {
		depthSum.add(depth);
		depthSumSq.add((long) depth * depth);
		depthCount.increment();
		if (reachedEndOfGame) reachedEndOfGameCount.increment();
		maxDepth.accumulateAndGet(depth, Math::max);
	}

	/**
	 * Record one root-child observation from one determinization tree.
	 * meanQ is the choosing-player's mean score at this child (Node.scores[player] / games).
	 * Aggregated across determinizations as a visit-weighted mean: sum(meanQ * visits) / sum(visits).
	 */
	public void recordRootChild(Move move, double visits, double meanQ) {
		if (visits <= 0) return;
		long roundedVisits = Math.round(visits);
		rootChildVisits.computeIfAbsent(move, k -> new LongAdder()).add(roundedVisits);
		rootChildScoreSums.computeIfAbsent(move, k -> new DoubleAdder()).add(meanQ * visits);
	}

	public void reset() {
		depthSum.reset();
		depthSumSq.reset();
		depthCount.reset();
		reachedEndOfGameCount.reset();
		maxDepth.set(0);
		rootChildVisits.clear();
		rootChildScoreSums.clear();
	}

	public String summary() {
		long n = depthCount.sum();
		if (n == 0) return "[mcts-stats] no playouts recorded";
		double mean = depthSum.sum() / (double) n;
		double meanSq = depthSumSq.sum() / (double) n;
		double std = Math.sqrt(Math.max(0, meanSq - mean * mean));
		double endPct = 100.0 * reachedEndOfGameCount.sum() / n;
		long totalVisits = rootChildVisits.values().stream().mapToLong(LongAdder::sum).sum();
		String visits = totalVisits == 0 ? "" : " | root: " + rootChildVisits.entrySet().stream()
				.sorted((a, b) -> Long.compare(b.getValue().sum(), a.getValue().sum()))
				.map(e -> {
					long v = e.getValue().sum();
					double scoreSum = rootChildScoreSums.getOrDefault(e.getKey(), new DoubleAdder()).sum();
					double meanQ = v > 0 ? scoreSum / v : Double.NaN;
					return String.format("%s=%d(%.0f%%,Q=%.1f)",
							e.getKey(), v, 100.0 * v / totalVisits, meanQ);
				})
				.collect(Collectors.joining(" "));
		return String.format(
				"[mcts-stats] tree-depth: mean=%.2f std=%.2f max=%d (n=%d playouts) reached-end-of-game: %.1f%%%s",
				mean, std, maxDepth.get(), n, endPct, visits);
	}
}
