# TODOs

## Undertrump bug: `validCardsBits` skips the restriction when void in the led suit

**Symptom** (observed 2026-07-18, belief external-check arenas, but predates those changes —
both arms hit it): ~1 in 40 games, `Player.chooseCardWithFallback` logs
`Your strategy tried to play an invalid card. Playing random card instead!` even though the
MCTS-chosen card was in `getCardsPossibleToPlay`'s set. 12+8 occurrences over 2×500 games
(logs from that run: `/tmp/claude-501/belief_runA_paired.log`, `belief_runB_powerful.log`).

**Reproduction state** (from the 23:58:36 instance in run A): trump ♠, hearts led, ♠Q already
played into the trick (an earlier player trumped), current player holds
`[D6, D7, DJ, DK, CQ, S10]` — void in hearts. `getCardsPossibleToPlay` returns **all six**
cards; playing ♠10 under ♠Q while holding non-trump discards is undertrumping and illegal.
`Mode.canPlayCard` correctly rejects it (`isHighestTrumpf` path), so Player's final validation
disagrees with the move generator and plays a random legal card.

**Root cause**: `TrumpfColorMode.validCardsBits` only applies `validStechenTrumpfBits` (the
undertrump filter) on the can-follow-suit path:

- `roundColorBits != 0` → `roundColorBits | validStechenTrumpfBits(...)` — correct;
- `roundColorBits == 0` (void in led suit) → `return availableBits` — **wrong**: it should be
  `playerNonTrumpf | validStechenTrumpfBits(playerTrumpf, playedBits, trumpfMask)`.

`canPlayCard` (the object/bit variants in the same class) already implements the restriction,
which is exactly why the two disagree. Note the two rule implementations are used by different
layers: `validCardsBits` feeds `CardSelectionHelper.getCardsPossibleToPlay` → strategy logging,
`JassBoard.getMoves` (so the MCTS **searches and can prefer the illegal move**, wasting its
visit mass before the fallback randomizes), and playouts; `canPlayCard` guards the real move in
`Player`. Long-term the two should share one implementation.

**Fix sketch**: change the void branch as above; regression test with the reproduction state
(trump ♠, ♥ led, ♠Q played, hand `[D6 D7 DJ DK CQ S10]` → ♠10 excluded, five cards legal), plus
the boundary cases: higher trump stays legal (♠K over ♠Q), undertrump legal when holding ONLY
trumps, and the trump-jack exceptions already covered in `validStechenTrumpfBits`.

## Advisor identity model

`GameHandler.replacePlayerWithAdvisor` (GameHandler.java) overwrites `localPlayer.id` with the
advised player's id while leaving the name as `"X-Advisor"`. `Player.equals` compares both id
AND name, so `localPlayer` is unequal to a separately-constructed `Player` for that seat.

This works today only because `PlayerMapper.mapPlayer` always returns the cached `localPlayer`
object whenever it sees the advised player's id — so equality is never actually tested across
two different Player instances for that seat.

The correct fix is to keep `localPlayer` as the strategy executor (own id, name `"X-Advisor"`)
and let `PlayerMapper` produce a distinct `Player` for the advised seat that the strategy is
bound to. This also lets `PlayingOrder.createOrderStartingFromPlayer` find players reliably
without the silent fallback to index 0.

Prerequisite: tighten `PlayingOrder.createOrderStartingFromPlayer` (PlayingOrder.java) to throw
instead of silently falling back to index 0 when the start player is not found by `equals`.

## Determinization RNG: finish the reproducibility refactor

**Goal:** make MCTS runs bit-reproducible for a given seed, so arena results can be
re-run exactly. (Two identical-seed 10-game arenas have produced different results,
e.g. 104.69% vs 123.01%.)

**Assessment (2026-06-20) — reproducibility-only, LOW PRIORITY.** This buys *nothing* but
repeatability:
- **No performance.** Determinization runs on the main thread (sequential, in the MCTSTask
  constructor), so there is no cross-thread contention to remove. (The worker-thread contention
  was already fixed by the tree-search SplittableRandom.)
- **No correctness.** The current global-RNG sampling produces valid determinizations.
- **No experiment-variance reduction.** The arena's swapped-deal pairing already cancels the
  *deal* luck; two different agents each sample their own determinizations and can't/shouldn't
  share draws, so seeding wouldn't tighten A-vs-B error bars.

The only real uses are bug reproduction and mechanically verifying that a "behavior-preserving"
search refactor truly is one (bit-identical games on the same seed). The refactor itself is
large/risky (threads RNG through Distribution + the Board interface + CardKnowledgeBase's CSP +
a hand-rolled Fisher-Yates). **Do it only on a concrete need** (an unreproducible heisenbug, or
to validate a hairy search change) — not speculatively.

**Already done** (commit "perf: per-determinization SplittableRandom for MCTS tree search"):
the *tree-search* RNG is now a per-task `SplittableRandom` (split from a seeded root in the
deterministic submit loop), so the 40 determinization threads no longer share a `Random`.
That removed the cross-thread RNG contention that mattered. For the pgx arena specifically,
this is the only worker-thread randomness (playouts are skipped under the value head and the
pgx prior is deterministic), so the remaining gap is reproducibility, not correctness.

**What's left — the determinization sampler still uses unseeded/global RNGs:**
- `Distribution.sample()` — the randomness primitive for the **card-play** determinization
  (`CardKnowledgeBase.sampleCardDeterminizationToPlayers(Game, Set, CardsEstimator)` at
  line ~210). This is the main one used during games.
- `Collections.shuffle(list)` in `CardKnowledgeBase.pickRandomSubSet` (trumpf-selection path).
- `new Random()` in `CardSelectionHelper` (~line 40).

**Why it's a sizeable change:** thread a `java.util.random.RandomGenerator` through
- the generic `Board.duplicate(boolean)` interface (add an rng-accepting overload; only
  `duplicate(true)` determinizes, called only from `MCTS.MCTSTask`'s constructor),
- `JassBoard.duplicate` + `sampleCardDeterminizationToPlayersInCardPlay/InTrumpfSelection`,
- `CardKnowledgeBase`'s 4 `sampleCardDeterminizationToPlayers` overloads + the CSP assignment
  helper + `pickRandomSubSet`,
- the `Distribution` class API (`sample(rng)`) and all its callers,
- `CardSelectionHelper`.
- Replace `Collections.shuffle(list)` with a hand-rolled Fisher-Yates driven by the
  `RandomGenerator` (`Collections.shuffle` only accepts `java.util.Random`, which
  `SplittableRandom` is not).

**Simplifying fact:** determinization (`duplicate(true)`) runs only on the main thread
(the `MCTSTask` constructor executes in the sequential submit loop), so the per-task rng
from `rootRandom.split()` can be passed straight in — no concurrency concerns on this path.
Keep the existing no-rng helper signatures (delegating to a fresh generator) so tests that
call them don't need to change.

**Verification:** after the refactor, two identical-seed arena runs must produce identical
results. Run the full test suite (`Distribution`/`CardKnowledgeBase` are core) to guard
against regressions.
