package to.joeli.jass;

import to.joeli.jass.client.strategy.config.MCTSConfig;
import to.joeli.jass.client.strategy.config.Config;
import to.joeli.jass.client.strategy.config.RunMode;
import to.joeli.jass.client.strategy.config.RunsScaling;
import to.joeli.jass.client.strategy.config.StrengthLevel;
import to.joeli.jass.client.strategy.mcts.HeavyJassPlayoutSelectionPolicy;
import to.joeli.jass.client.strategy.mcts.LightJassPlayoutSelectionPolicy;
import to.joeli.jass.client.strategy.mcts.RoundConditionalPlayoutSelectionPolicy;
import to.joeli.jass.client.strategy.training.Arena;

import java.util.Map;

/**
 * Runs a strength-comparison match entirely in-process using Arena (no server needed).
 *
 * <pre>
 *   JassTheRipperArena --strength1=INSANE --strength2=POWERFUL [options]
 * </pre>
 *
 * Flags:
 * <pre>
 *   --strength1=&lt;level&gt;        StrengthLevel for team 0 (default: POWERFUL)
 *   --strength2=&lt;level&gt;        StrengthLevel for team 1 (default: POWERFUL)
 *   --name1=&lt;name&gt;             Display name for team 0 (default: strength level name)
 *   --name2=&lt;name&gt;             Display name for team 1 (default: strength level name)
 *   --games=&lt;n&gt;                Number of games; must be even for orthogonal pairing (default: 10240)
 *   --mode=RUNS|TIME           RunMode (default: RUNS)
 *   --scaling1=&lt;scaling&gt;       RunsScaling for team 0 (default: FLAT)
 *   --scaling2=&lt;scaling&gt;       RunsScaling for team 1 (default: FLAT)
 *   --ucb1=&lt;val&gt;               UCB exploration constant for team 0
 *   --ucb2=&lt;val&gt;               UCB exploration constant for team 1
 *   --puct1                    Enable PUCT for team 0
 *   --puct2                    Enable PUCT for team 1
 *   --puct-prior1=light|heavy  PUCT prior heuristic for team 0 (default: heavy)
 *   --puct-prior2=light|heavy  PUCT prior heuristic for team 1 (default: heavy)
 *   --puct-alpha1=&lt;val&gt;        PUCT prior weight for team 0 (default: 0.7)
 *   --puct-alpha2=&lt;val&gt;        PUCT prior weight for team 1 (default: 0.7)
 *   --puct-c1=&lt;val&gt;            PUCT exploration constant for team 0 (default: 100.0)
 *   --puct-c2=&lt;val&gt;            PUCT exploration constant for team 1 (default: 100.0)
 *   --heavy-rounds1=&lt;n&gt;        Use heavy rollouts for tricks 0..n-1 (random after) for team 0
 *   --heavy-rounds2=&lt;n&gt;        Use heavy rollouts for tricks 0..n-1 (random after) for team 1
 *   --trump-cond1              Round-0 trump-conditioned determinization for team 0
 *   --trump-cond2              Round-0 trump-conditioned determinization for team 1
 *   --seed=&lt;n&gt;                 Random seed (default: 42)
 * </pre>
 */
public class ApplicationArena {

	public static void main(String[] args) {
		Map<String, String> flags = Application.parseFlags(args);

		int seed = Integer.parseInt(flags.getOrDefault("seed", "42"));
		int numGames = Integer.parseInt(flags.getOrDefault("games", "10240"));
		if (numGames % 2 != 0) {
			numGames++;
			System.err.println("Warning: --games must be even for orthogonal pairing, rounded up to " + numGames);
		}

		MCTSConfig mc1 = buildMctsConfig(flags, "1");
		MCTSConfig mc2 = buildMctsConfig(flags, "2");
		String name1 = flags.getOrDefault("name1", mc1.getCardStrengthLevel().name());
		String name2 = flags.getOrDefault("name2", mc2.getCardStrengthLevel().name());

		System.out.printf("=== %s vs %s | %d games | seed=%d ===%n", name1, name2, numGames, seed);

		Config[] configs = { new Config(mc1), new Config(mc2) };
		Arena arena = new Arena(Arena.IMPROVEMENT_THRESHOLD_PERCENTAGE, seed, false);
		double improvement = arena.runMatchWithConfigs(configs, numGames);

		System.out.printf("=== %s scored %.2f%% of %s's points ===%n", name1, improvement, name2);
	}

	private static MCTSConfig buildMctsConfig(Map<String, String> flags, String suffix) {
		MCTSConfig mc = flags.containsKey("strength" + suffix)
				? new MCTSConfig(StrengthLevel.valueOf(flags.get("strength" + suffix)))
				: new MCTSConfig();
		if (flags.containsKey("mode"))
			mc.setRunMode(RunMode.valueOf(flags.get("mode")));
		if (flags.containsKey("scaling" + suffix))
			mc.setRunsScaling(RunsScaling.valueOf(flags.get("scaling" + suffix)));
		if (flags.containsKey("ucb" + suffix))
			mc.setExplorationConstant(Double.parseDouble(flags.get("ucb" + suffix)));
		if (flags.containsKey("puct" + suffix)) {
			mc.setPuctEnabled(true);
			String prior = flags.getOrDefault("puct-prior" + suffix, "heavy").toLowerCase();
			mc.setPuctPriorPolicy(prior.equals("light")
					? new LightJassPlayoutSelectionPolicy()
					: new HeavyJassPlayoutSelectionPolicy());
		}
		if (flags.containsKey("puct-alpha" + suffix))
			mc.setPuctAlpha(Double.parseDouble(flags.get("puct-alpha" + suffix)));
		if (flags.containsKey("puct-c" + suffix))
			mc.setPuctC(Double.parseDouble(flags.get("puct-c" + suffix)));
		if (flags.containsKey("heavy-rounds" + suffix)) {
			int n = Integer.parseInt(flags.get("heavy-rounds" + suffix));
			mc.setPlayoutSelectionPolicy(new RoundConditionalPlayoutSelectionPolicy(
					new HeavyJassPlayoutSelectionPolicy(), n));
		}
		if (flags.containsKey("trump-cond" + suffix))
			mc.setTrumpConditionedDeterminization(true);
		return mc;
	}
}
