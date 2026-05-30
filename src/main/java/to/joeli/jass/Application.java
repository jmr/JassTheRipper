package to.joeli.jass;

import to.joeli.jass.client.RemoteGame;
import to.joeli.jass.client.game.Player;
import to.joeli.jass.client.strategy.JassTheRipperJassStrategy;
import to.joeli.jass.client.strategy.config.Config;
import to.joeli.jass.client.strategy.config.MCTSConfig;
import to.joeli.jass.client.strategy.config.RunMode;
import to.joeli.jass.client.strategy.config.StrengthLevel;
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
 *   name (e.g. FAST, STRONG, POWERFUL, EXTREME). Default: POWERFUL (1000ms/move).
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
			logger.info("Human mode: starting 3 bots, leaving one slot for a human player.");
			startHumanGame(url);
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
		JassTheRipperJassStrategy strategy = new JassTheRipperJassStrategy(new Config(mctsConfig));
		Player player = new Player(name, strategy);
		new RemoteGame(url, player, SessionType.SINGLE_GAME, session, team, advisedPlayer).start();
		if (flags.containsKey("quit")) {
			System.exit(0);
		}
	}

	private static void startHumanGame(String url) {
		ExecutorService executorService = Executors.newFixedThreadPool(3);
		List<Future<?>> futures = new ArrayList<>();
		int[] teamIndices = {0, 1, 1};
		for (int teamIndex : teamIndices) {
			final int ti = teamIndex;
			futures.add(executorService.submit(() -> {
				new RemoteGame(url, new Player(BOT_NAME, new JassTheRipperJassStrategy()), SessionType.SINGLE_GAME, "Java Client Session", ti, null).start();
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
