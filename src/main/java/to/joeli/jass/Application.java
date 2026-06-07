package to.joeli.jass;

import to.joeli.jass.client.RemoteGame;
import to.joeli.jass.client.game.Player;
import to.joeli.jass.client.strategy.JassTheRipperJassStrategy;
import to.joeli.jass.client.strategy.config.Config;
import to.joeli.jass.client.strategy.config.MCTSConfig;
import to.joeli.jass.client.strategy.config.RunMode;
import to.joeli.jass.client.strategy.config.RunsScaling;
import to.joeli.jass.client.strategy.config.StrengthLevel;
import to.joeli.jass.client.strategy.mcts.HeavyJassPlayoutSelectionPolicy;
import to.joeli.jass.client.strategy.mcts.LightJassPlayoutSelectionPolicy;
import to.joeli.jass.client.strategy.mcts.src.PlayoutSelectionPolicy;
import to.joeli.jass.messages.type.SessionType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Starts one bot in tournament mode.
 * <br><br>
 * To start from CLI use
 * <pre>
 *     gradlew run -Pmyargs="--url=ws://127.0.0.1:3000,--name=MyBot,--team=1"
 * </pre>
 * Supported flags: --url, --name, --team, --session, --advised-player, --quit,
 *   --strength=&lt;level&gt; where level is any {@link to.joeli.jass.client.strategy.config.StrengthLevel}
 *   name (e.g. FAST, STRONG, POWERFUL, EXTREME). Default: POWERFUL (1000ms/move). Also applies in --human mode.
 *   --cards-estimator=&lt;episode&gt; load the cards neural network from the given episode (e.g. 0).
 *   --ucb=&lt;value&gt; UCB exploration constant (default: sqrt(2) ≈ 1.414).
 *   --puct enable PUCT selection with a heuristic prior (default: heavy).
 *   --puct-prior=light|heavy which playout-selection heuristic to use as the PUCT prior (default: heavy).
 *   --puct-alpha=&lt;value&gt; PUCT prior weight on heuristic-best move (default: 0.7).
 *   --puct-c=&lt;value&gt; PUCT exploration constant (default: 100.0).
 *   --timeout=&lt;minutes&gt; WebSocket close timeout in minutes (default: 720 = 12h).
 */
public class Application {
	private static final String BOT_NAME = "JassTheRipper";
	private static final String LOCAL_URL = "ws://127.0.0.1:3000";

	public static final Logger logger = LoggerFactory.getLogger(Application.class);

	public static void main(String[] args) {
		logger.info("Arguments: {}", Arrays.toString(args));
		Map<String, String> flags = parseFlags(args);

		String url = flags.getOrDefault("url", LOCAL_URL);

		if (flags.containsKey("human")) {
			MCTSConfig humanMctsConfig = flags.containsKey("strength")
					? new MCTSConfig(StrengthLevel.valueOf(flags.get("strength")))
					: new MCTSConfig();
			logger.info("Human mode: starting 3 bots at strength {}, leaving one slot for a human player.",
					humanMctsConfig.getCardStrengthLevel());
			startHumanGame(url, humanMctsConfig);
			return;
		}

		String name = flags.getOrDefault("name", BOT_NAME);
		int team = Integer.parseInt(flags.getOrDefault("team", "1"));

		logger.info("Connecting... Server socket URL: {}", url);

		String session = flags.getOrDefault("session", "Java Client Session");
		String advisedPlayer = flags.getOrDefault("advised-player", null);
		MCTSConfig mctsConfig = flags.containsKey("strength")
				? new MCTSConfig(StrengthLevel.valueOf(flags.get("strength")))
				: new MCTSConfig();
		if (flags.containsKey("mode"))
			mctsConfig.setRunMode(RunMode.valueOf(flags.get("mode")));
		if (flags.containsKey("runs-scaling"))
			mctsConfig.setRunsScaling(RunsScaling.valueOf(flags.get("runs-scaling")));
		if (flags.containsKey("ucb"))
			mctsConfig.setExplorationConstant(Double.parseDouble(flags.get("ucb")));
		if (flags.containsKey("puct")) {
			mctsConfig.setPuctEnabled(true);
			String prior = flags.getOrDefault("puct-prior", "heavy").toLowerCase();
			PlayoutSelectionPolicy priorPolicy = prior.equals("light")
					? new LightJassPlayoutSelectionPolicy()
					: new HeavyJassPlayoutSelectionPolicy();
			mctsConfig.setPuctPriorPolicy(priorPolicy);
		}
		if (flags.containsKey("puct-alpha"))
			mctsConfig.setPuctAlpha(Double.parseDouble(flags.get("puct-alpha")));
		if (flags.containsKey("puct-c"))
			mctsConfig.setPuctC(Double.parseDouble(flags.get("puct-c")));

		Config config = new Config(mctsConfig);
		if (flags.containsKey("cards-estimator"))
			config.setCardsEstimatorUsed(true);

		JassTheRipperJassStrategy strategy = new JassTheRipperJassStrategy(config);

		if (flags.containsKey("cards-estimator")) {
			int episode = Integer.parseInt(flags.get("cards-estimator"));
			strategy.getCardsEstimator().loadModel(episode);
			logger.info("Loaded cards estimator from episode {}", episode);
		}

		int closeTimeoutMin = Integer.parseInt(flags.getOrDefault("timeout", "720"));
		Player player = new Player(name, strategy);
		new RemoteGame(url, player, SessionType.SINGLE_GAME, session, team, advisedPlayer, closeTimeoutMin).start();
		if (flags.containsKey("quit")) {
			System.exit(0);
		}
	}

	private static void startHumanGame(String url, MCTSConfig mctsConfig) {
		ExecutorService executorService = Executors.newFixedThreadPool(3);
		List<Future<?>> futures = new ArrayList<>();
		int[] teamIndices = {0, 1, 1};
		for (int teamIndex : teamIndices) {
			final int ti = teamIndex;
			futures.add(executorService.submit(() -> {
				new RemoteGame(url, new Player(BOT_NAME, new JassTheRipperJassStrategy(new Config(mctsConfig))), SessionType.SINGLE_GAME, "Java Client Session", ti, null, 720).start();
			}));
			try {
				Thread.sleep(500);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}
		futures.forEach(f -> {
			try {
				f.get();
			} catch (InterruptedException | ExecutionException e) {
				e.printStackTrace();
			}
		});
		executorService.shutdown();
	}

	static Map<String, String> parseFlags(String[] args) {
		Map<String, String> flags = new HashMap<>();
		for (String arg : args) {
			if (arg.startsWith("--")) {
				int eq = arg.indexOf('=');
				if (eq > 0) {
					flags.put(arg.substring(2, eq), arg.substring(eq + 1));
				} else {
					flags.put(arg.substring(2), "true");
				}
			}
		}
		return flags;
	}
}
