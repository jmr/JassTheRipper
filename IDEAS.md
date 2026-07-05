# Ideas

## Goals (current priority order)

1. **Quality.** Maximize bot strength against equal-strength baselines and humans.
2. **Efficiency.** Once quality is at target, reduce compute/energy at deployment so e.g.
   phones don't overheat — without sacrificing quality (or with minimal sacrifice).

Negative results below are tagged as "not a *quality* lever" rather than "dead" — several
findings (especially DNN-as-leaf-evaluator at modest iteration counts) may be revived as
efficiency-mode levers later: "quality-equivalent at 10× fewer playouts" is a real win
even if it can't beat full-compute MCTS on raw strength.

## Real PUCT (pgx policy prior + visit aggregation) — gen3 > gen2 reproduced (2026-06-20)

The pgx PolicyValueNet generations climb almost entirely in the **policy/priors**, visible
only under proper PUCT (full softmax prior + summed-visit-count aggregation across
determinizations). JTR's prior integration used the policy as a single **argmax "tip"** with
**Q-sum** cross-determinization aggregation — both of which neutralize the policy signal — so
gen2-vs-gen3 washed (p=0.65, 100 games).

Implemented "real PUCT": `PgxPlayoutSelectionPolicy` now exposes the full softmax `P(s,a)`
(`PuctPriorPolicy`), `MCTS.findChildrenPuct` uses it as the prior, and `MCTS` aggregates across
determinizations by **summed root visit counts** (not Q) when a policy prior is active.

**Result — gen2 vs gen3, both sides real PUCT, RUNS mode, swapped-deal paired t-test**
(negative = gen3 ahead; mean_diff is per *pair* = 2 games, so per-game ≈ half):

| runs/det | seed=42 | seed=43 | seed=44 | verdict |
|:--|:--|:--|:--|:--|
| 20  | −6.7 p=0.19 (100p) | — | — | trend |
| **64**  | −14.6 p=0.003 (100p) | −6.4 p=0.020 (300p) | **−9.5 p<0.0001 (500p)** | **gen3 ahead, confirmed** |
| 128 | +0.3 p=0.95 (100p) | −3.5 p=0.32 (200p) | — | weak / wash |
| 256 | −9.0 p=0.034 (100p) | — | — | gen3 ahead |

(runs/det = `StrengthLevel.numRuns / 10` for network leaves; SWEEP_* levels added for this.
mean_diff is per pair = 2 games; negative = gen3 ahead.)

**Takeaways:**
- **It works, confirmed:** gen3 > gen2 at 64 runs/det across three independent seeds, decisively
  at 500 pairs (−9.5/pair, p<0.0001) — where the old argmax-tip/Q-sum setup washed (p=0.65).
  Real PUCT is the mechanism that surfaces the gain.
- **Modest magnitude:** settled best estimate ~−9.5/pair (**~4.75 pts/game**) at 64/det — well
  under pgx's own +14/game (JTR's determinized search dilutes the policy gain), and gen3 still
  loses to classical POWERFUL (see calibration below).
- **No clean depth crossover:** 64/det is the sweet spot; 128 is oddly weak in both seeds; 256
  moderate. Not the tidy monotonic curve pgx's sims-sweep showed.
- **Method note:** always use the Arena's built-in **paired** test (it pairs swapped deals). A
  naive per-game t-test ignores the pairing (sd ~75/game vs ~48/pair) and washes everything.
  Need ≥300 pairs to resolve a ~3 pt/game effect.
- The crossover is **model-specific** and rises as models strengthen (pgx: sims=16 positive at
  gen-0, negative at gen-1); re-sweep when models change. gen3 is early-project, so this is a
  lower bound.

### Absolute strength vs JassTheRipper's classical bot (calibration, 2026-06-20)

gen3 > gen2 (above) is a *relative* result among weak agents. Absolute calibration triangle
(100 games each, paired test; mean_diff per pair = 2 games; negative = name1 behind):

| matchup | name1 % of name2 | mean_diff/pair (per-game) | p | sign |
|:--|:--|:--|:--|:--|
| gen3 (POWERFUL, value+policy) vs **POWERFUL** (classical) | 75.6% | −43.6 (−22/game) | <0.0001 | 9–41 |
| gen3 vs **FAST_TEST** (weakest MCTS) | 93.5% | −10.6 (−5/game) | 0.14 (ns) | 19–30 |
| **POWERFUL** vs **FAST_TEST** | 128.9% | +39.6 (+20/game) | <0.0001 | 39–10 |
| gen3 @ **SWEEP_256** (256 runs/det) vs **POWERFUL** | 89.2% | −18.0 (−9/game) | 0.006 | 16–31 |

**Strength ordering: POWERFUL ≫ FAST_TEST ≳ gen3.** The pgx agent is **weak in absolute terms** —
it loses decisively to the classical bot and is even nominally behind the weakest MCTS. Giving
gen3 ~13× more search (POWERFUL's 20 runs/det → SWEEP_256's 256) halves the deficit vs POWERFUL
(−22 → −9 pts/game) but does NOT close it, even though 256 runs/det exceeds POWERFUL's own 200.

**Conclusion: the *model* is the limiter, not the JTR integration or search depth.** The
integration is correct (gen3>gen2 is visible, pipeline works); gen3 is just an early (3rd-gen),
pgx-simplified-self-play net. This is pgx jass_plan Step 4's external benchmark — answer: not yet
competitive. The lever is **stronger pgx models** (more generations / net scaling), not more JTR
search. (FAST_TEST is the weakest CLI baseline; a true random player isn't wired into the arena.)

### gen-4 / gen-5b re-calibration (2026-07-02) — gap to POWERFUL roughly halved

Two generations later (gen-4, then gen-5b — pgx's `docs/jass_experiment_log.md` has the full
training history), same export pipeline, same real-PUCT harness, re-run to answer the question
the 2026-06-20 DECISION left open: does pgx's self-relative policy climb since gen-3 (gen-3→gen-4
raw +15, gen-4→gen-5b raw +27–31 via a step2-mix-ablation fix) convert into absolute strength
against POWERFUL, or wash out like gen-2-vs-gen-3 did under the old argmax-tip+Q-sum setup?

**gen-5b vs gen-4, real PUCT, SWEEP_64 (500 pairs / 1000 games, seed 42):**
**+16.7/pair (+8.35 pts/game), t=7.53, p=0.0000, sign 297W-174L-29T** — the internal pgx climb
(gen-5b +11.8/+16.2 raw vs gen-4) reproduces externally, same pattern as gen3>gen2.

**gen-5b vs POWERFUL (classical), 250 paired games across 2 seeds:**

| matchup | pairs | mean_diff/pair | per-game | p |
|:--|:--|:--|:--|:--|
| gen-5b vs POWERFUL (seed 42) | 50 | −12.6 | −6.3 | 0.089 (ns) |
| gen-5b vs POWERFUL (seed 43) | 200 | −20.7 | −10.35 | 0.0000 |
| **combined (pair-weighted)** | 250 | **≈−19.1** | **≈−9.5** | decisive |

**The absolute-strength gap to POWERFUL has roughly HALVED since gen-3: −22/game → ≈−9.5/game.**
Answer to the open question: **yes, the self-relative policy gains substantially convert to
absolute strength** — gen-5b is still a clear, significant loser to POWERFUL, but far closer than
gen-3 was. gen-4's own POWERFUL calibration was never run (deferred straight to gen-5b), so this
is a two-point trendline (gen-3, gen-5b), not a full per-generation curve. Models exported to
`src/main/resources/models/{pv_gen4_s128,pv_gen5b_s128}/export` (gitignored, regenerate via
pgx's `scripts/extract_pv_weights.py` → `scripts/export_pv_savedmodel.py`).

### gen-6b_es / gen-7b_es re-calibration (2026-07-05) — gap to POWERFUL closes to ZERO

Two more generations later, both attn-architecture (`PolicyValueNetAttn`), same real-PUCT
harness. gen-6b_es and gen-7b_es exported to
`src/main/resources/models/{pv_gen6b_es_s128,pv_gen7b_es_s128}/export`; `pgx_pv/export` symlinked
to gen-7b_es for the unit tests. All four matches below: 250 pairs / 500 games, seed 42.

| matchup | mean_diff/pair | per-game | t | p | sign test | verdict |
|:--|:--|:--|:--|:--|:--|:--|
| gen-6b_es vs gen-5b (SWEEP_64) | +13.5 | +6.75 | 4.894 | 0.0000 | 141W-93L-16T, p=0.0021 | gen6 significantly stronger |
| gen-7b_es vs gen-6b_es (SWEEP_64) | +2.0 | +1.0 | 0.699 | 0.4849 | 124W-108L-18T, p=0.3247 | wash |
| gen-6b_es vs **POWERFUL** (classical) | +0.7 | +0.35 | 0.236 | 0.8135 | 118W-122L-10T, p=0.8465 | tied |
| gen-7b_es vs **POWERFUL** (classical) | −0.5 | −0.25 | −0.184 | 0.8542 | 112W-120L-18T, p=0.6459 | tied |

**Headline: the absolute-strength gap to POWERFUL has closed to zero.** Trendline: gen-3
(≈−22/game) → gen-5b (≈−9.5/game) → **gen-6b_es / gen-7b_es (flat, both ns)**. Both generations
are now statistically indistinguishable from classical POWERFUL under JTR's real-PUCT harness —
first time the pgx lineage has caught up to the classical baseline rather than just narrowing the
gap.

**gen-7b_es vs gen-6b_es washing under PUCT matches pgx's own finding**
(`docs/jass_experiment_log.md`, 2026-07-04): gen-7's promotion gate against gen-6b_es was flat
under PUCT@64 (+1.1, p=0.61) even though gen-7's *raw* policy beat gen-6b_es decisively (+5.2 to
+10.2 across seeds, p<0.003). pgx separately measured gen-7 PUCT@64 vs its own raw policy at
**−6.3/game (p=0.0033) — search actively hurts** at this model strength, and pgx's own DECISION is
to deploy raw policy (~65× cheaper per move) rather than PUCT@64 from gen-7 onward. Our external
real-PUCT arena reproducing the same wash is independent confirmation of that mechanism, not a
contradiction of gen-7's real (raw-policy) strength gain over gen-6b_es.

**Next:** raw-policy arena comparison (gen-7b_es raw vs gen-6b_es raw, and raw vs POWERFUL) to see
whether the PUCT harness is now *understating* gen-7 relative to gen-6, and whether raw policy
alone already beats or matches POWERFUL more cheaply than PUCT@64 does.

## Thesis findings — already ruled out as quality levers at ≥1000 rounds

Joel Niklaus's MSc thesis (`MSc__Joel_Niklaus.pdf`) ran these comparisons at 10 × 100 rounds
(= 1000 games each). Treat as solid negatives unless the integration is suspected buggy
(see "DNN as value estimator inside MCTS" caveat below).

- **ISMCTS substrate vs DMCTS:** ISMCTS underperformed DMCTS. Substrate is not the lever.
- **CardsEstimator + sampling for belief:** p=0.046 (P-DMCTS vs I-DMCTS, variance *increased*),
  p=0.17 (P-ISMCTS vs ISMCTS). No card-belief improvement found.
- **Rule-based / heavy rollouts:** "Replacing the random rollout with a rule-based rollout
  did not improve the performance of DMCTS." Heavy rollouts ruled out.
- **DNN as value estimator (Section 12, Fig 12.1):** MSE on validation set: DNN Value ≈ 10
  random rollouts; DNN Max Policy Rollout ≈ 1000 random rollouts; **100 MCTS iterations
  beats both, and keeps improving with iterations while rollouts/DNN plateau.** At our
  iteration counts (POWERFUL = thousands+), the network cannot compete with MCTS for leaf
  value estimation. **Caveat:** this measured the DNN's value-prediction MSE standalone,
  not the DNN wired as the MCTS *leaf evaluator* inside the search loop. The conclusion
  ("net is worse than 100-iter MCTS at predicting value") still implies plugging it in as
  leaf eval won't help.

**What the thesis did NOT test:** DNN as *tree policy* via PUCT (the AZ structural
innovation, distinct from the leaf-evaluator question). Self-play training of policy+value
heads (the thesis's DNN was trained supervised on logged games). These remain unexplored.

## `& 3` in getBoundIndex + roundColor field cache — implemented, +15%

Done: `getBoundIndex` changed from `% playersInInitialPlayingOrder.size()` to `& 3`; `roundColor`
cached as a field in `Round` (was re-derived from `moves.get(0)` on every call).

Benchmarks (30s runs, MacBook Air, back-to-back):
- Baseline (`rwqowrsz`, pickRandom + `% size()`): 369k rollouts/sec
- With both changes (`tpvuytpn`): 425k rollouts/sec (+15%) — NOTE: measured with `==` bug (see below)
- With `.equals()` correctness fix (`xkrklnpy`): 374–393k rollouts/sec (thermal noise dominates; true
  cost of `.equals()` vs `==` is ~1–2% per the original commit that introduced the bug)

Player[] was also tried as storage for PlayingOrder (commit `vqvvlkzu`) but reverted: it required
a package-private `getPlayersArray()` accessor and a second overload of
`createOrderStartingFromPlayer`, adding complexity for 0% net gain. The JIT also de-inlined
`getRoundColor` when the class shape changed, which the roundColor field cache fixed.

## Round.makeMove: use identity (`!=`) instead of `.equals()` for player validation

`Round.makeMove` calls `!move.getPlayer().equals(playingOrder.getCurrentPlayer())`. In the hot
playout path the move was just created from `game.getCurrentPlayer()` — the same object — so
identity check is correct. `Player.equals` (string comparison on id + name) shows at ~3% in the
CPU profile. Blocker: `DeepCopyTest.testCopyRound` creates a Move with the *original* game's player
and applies it to a *deep-copied* round. Either change the test to use the copy's player, or confirm
that MCTS never actually crosses player contexts (it likely doesn't — moves are created from the
copied game's `getCurrentPlayer()`).

## pickRandom in real MCTS — not yet done

`CardSet.pickRandom` (multiply-shift, O(1)) is called in the benchmark harness but NOT in the real
MCTS playout. `MCTS.getRandomMove` builds a `List<Move>` and calls `random.nextInt(moves.size())`.
Changing it to operate on the card bitmask directly would bring the +4.4% pickRandom gain into
production. Requires threading the `RandomGenerator` into `MCTS` (currently uses `new Random(42)`).

## Round.makeMove: use identity (`!=`) instead of `.equals()` for player validation

`Round.makeMove` calls `!move.getPlayer().equals(playingOrder.getCurrentPlayer())`. In the hot
playout path the move was just created from `game.getCurrentPlayer()` — the same object — so
identity check is correct. `Player.equals` (string comparison on id + name) shows at ~3% in the
CPU profile. Blocker: `DeepCopyTest.testCopyRound` creates a Move with the *original* game's player
and applies it to a *deep-copied* round. Either change the test to use the copy's player, or confirm
that MCTS never actually crosses player contexts (it likely doesn't — moves are created from the
copied game's `getCurrentPlayer()`).



## Thread pool sizing

Currently TIME mode spawns `10 × trumpfStrengthLevel.numDeterminizationsFactor` threads (e.g. 50 for POWERFUL)
to keep all determinizations in-flight simultaneously. On an 8-core machine this means ~6 threads per core,
causing cache thrashing on the MCTS tree traversal (pointer-chasing, already cache-unfriendly).

Proposal: use an `nproc`-sized pool and give each determinization a time budget of
`totalTime × nproc / numDeterminizations` instead of a shared deadline. Same total CPU work and wall time,
no context-switch or cache overhead. Requires changing `MCTS.runForTime` from a shared absolute deadline
to a per-job duration.

## Hybrid trump selection

MCTS currently never shifts (geschoben) even when it should. Rule-based heuristics handle initial trump
selection reasonably well but are weak when geschoben.

Proposal: use rule-based selection for the initial player; switch to MCTS only for the player who received
the geschoben. This isolates MCTS to the case where it has an advantage (more information, forced to pick)
while avoiding its known weakness on the initial selection.

## Merge / shadow evaluator

Run two strategies in parallel for every decision. When they agree, proceed silently. When they differ, log
the divergence (hand, game state, choice A vs choice B) but play choice A. Useful for:
- Understanding where a new strategy diverges from the current one before committing to it
- Building a dataset of disagreements to audit by hand
- Gaining confidence before promoting a new evaluator

Could be implemented as a thin `ShadowJassStrategy` wrapper in the Java client with no server changes.

## Improved trump heuristic via learned model

The current heuristic is a hand-coded polynomial of card indicators. A decision tree or multinomial
regression could learn the same structure from data (generated by `TrumpDataCollector`) and potentially
fix miscalibrated weights.

**Natural feature set** (all derivable from the 9-card hand):

Per suit C ∈ {H, D, S, C}:
- `n_C` — count of cards of that suit (0–9); note n_H + n_D + n_S + n_C = 9
- `has_jack_C`, `has_nell_C`, `has_ace_C`, `has_king_C`, `has_ten_C` — binary presence indicators
- `sum_trumpf_rank_C` = Σ trumpfRank over cards in C (captures overall suit strength)
- `n_C ≥ k` for k = 2, 3, 4, 5, 6 — threshold indicators (heuristic uses these for bonuses)

Cross-suit:
- `num_aces` — count of aces across all suits (used in Jack+Nell bonus)
- `has_jack_C × has_nell_C` — interaction term the heuristic rewards explicitly
- `has_jack_C × (n_C ≥ 4)` — Jack with length bonus
- OBEABE potential per suit: sequential top-card run (has_ace, has_king, has_ten in order from top)
- UNDEUFE potential per suit: sequential bottom-card run (has_six, has_seven, has_eight in order from bottom)

**Model structure:**
- Binary classifier: shift vs. play (threshold at heuristic score 125)
- Conditional on playing: 6-class multinomial over {H, D, S, C, OBEABE, UNDEUFE}
- Baseline comparison: current heuristic (effectively hardcoded weights on the same features)

The 36 binary card-presence indicators alone are sufficient input; the derived features above help
linear models and shallow trees that can't construct them implicitly.

**Empirical findings (100 FAST + 150 EXTREME deals, 243 clean):**

The heuristic is correct in expectation across all confidence levels:

| Heuristic gap (#1−#2) | n  | mean(score_1−score_2) | #1 win rate |
|---|---|---|---|
| 1–10  (low confidence) | 71 | +5.8 pts  | 45.1% |
| 11–20                  | 53 | +13.5 pts | 45.3% |
| 21–50                  | 64 | +21.0 pts | 56.2% |
| 51+   (high confidence)| 51 | +22.7 pts | 64.7% |

Win rate drops below 50% for small gaps because single-game variance is ~35 pts
(mean|score_1−score_2| ≈ 33–36 across all bands), swamping a 5–13 pt true advantage.
When #1 loses, #2 and #3 win about equally (52/48) — no systematic rank-2 preference.
OBEABE is under-represented as actual winner (11%) vs its heuristic nomination rate.

**Conclusion:** A learned patch is unlikely to improve much — the heuristic ordering is
already correct in expectation; the bottleneck is per-game variance, not miscalibration.
The most actionable specific finding is that OBEABE may be over-rated by the heuristic.

## Auto-regressive "who has card" model

In DMCTS, hidden cards are currently distributed uniformly at random across determinizations.
A learned model could instead estimate P(player p has card c | cards seen so far), then sample from
that distribution to produce more realistic determinizations.

"Auto-regressive" means predicting cards one at a time in some order, conditioning each prediction on
previously predicted cards (so that the joint distribution respects the constraint that each card is held
by exactly one player). This is analogous to language model token generation.

Would replace (or augment) the existing `CardsEstimator` class, which makes independent per-card
predictions and ignores cross-card constraints. A better card-distribution model should narrow the
determinization search space and improve MCTS decision quality, especially early in the game when few
cards have been played and uncertainty is high.

### Implementation options

**Option 1 — Iterative re-inference (implemented, k=3):**
Use the existing single-shot network but call it multiple times per determinization. After every k
card assignments, re-run the network with the partial assignment encoded as 1-hot certainties in the
CARDS_DISTRIBUTION feature rows. This conditions each batch of assignments on prior ones. Also
changed sampling order to highest-confidence-first (max probability) instead of fewest-players-first,
so the most certain assignments happen early and provide the most useful context for re-inference.
Cost: ~9 network calls/determinization at round 0 (k=3) vs 1 previously. REINFERENCE_EVERY_K is
easy to ablate: k=1 is fully auto-regressive (~27 calls), k=27 disables re-inference (1 call).
Mild OOD caveat: the network was trained on fully-uniform hidden-card rows; mid-assignment inputs
(some 1-hot, rest uniform) are a new input pattern. Works because the encoding format is identical.

**Option 2 — Retrain with partial-assignment inputs:**
Generate ~27 training examples per game by randomizing the card reveal order and masking the rest.
Same architecture, same features. Fixes the mild OOD issue of option 1. Worth doing only if
option 1 shows signal. Randomize reveal order at train time (not easiest-first) to avoid
underfitting the hard mid-sequence steps.

**Option 3 — Sequential model (transformer/LSTM):** Skipped. For ≤27 fixed steps with a
bag-of-cards structure, a new architecture buys little over option 2 at high engineering cost.

### Other ideas in this space

- **Sampling order only (free):** Change `CardKnowledgeBase` to pick highest-max-probability card
  first even without re-inference. Already done as part of option 1 above.
- **Importance-weighted resampling:** Generate K full determinizations with a single network call,
  score each by ∏ P(assigned player | card), resample with weights. No OOD issue, one network call.
- **Metropolis-Hastings on the joint:** Propose card swaps between players, accept by network
  probability ratio. Cheap if scored from a single forward pass.

### Empirical findings

- **k=1 (fully auto-regressive), 256 games, RUNS mode, POWERFUL vs POWERFUL+AR:**
  Powerful 21285 vs AutoRegCardEstK1 19907. t=-0.96 (p=0.34), sign 122W/134L (p=0.49).
  Clean wash with a small (~5 pts/game) numeric lean toward AR being slightly worse —
  consistent with OOD-compounding hurting at every step but too small to act on.
- **Prior context (thesis, 1k games):** baseline single-shot CardsEstimator showed no
  advantage over null (uniform) determinization. AR variants are refinements of a signal
  that may not exist.
- **k=3 / k=9 ablations:** k=3 currently set in code; pending run. Only diagnostic if
  k=1 had been *significantly worse* than baseline (which it wasn't). Useful only to
  rule out OOD compounding as the explanation for the small negative lean.

**Conclusion:** AR direction is not a quality lever with the current network. To revive,
would need first to re-establish that the baseline CardsEstimator carries useful signal
at 256+ games (the original thesis test at 1k was negative, but the integration may have
been buggy in ways analogous to the 1-hot collapse bug found here). Option 2 (retraining
with partial-assignment inputs) is on hold pending that.

## UCB exploration constant — likely real bug, effectively untested

### Scale mismatch (the bug)

`Node.upperConfidenceBound` computes `scores[parent.player] / games + c * sqrt(log(parent.games+1) / games)`,
and `JassBoard.getScore` returns unnormalized team scores in the range 0–157. The standard
c = √2 is calibrated for rewards in [0, 1] — on the 0–157 scale, the exploration term at
typical visit counts is ~0.5 while exploitation differences are tens of points. So c=√2 is
effectively zero exploration on this scale.

The puzzle: c=0 *clearly* underperforms c=√2 in prior runs despite both being essentially
zero exploration in the formula. Likely explanation: unvisited children are expanded first
by the tree policy regardless of c (FPU-like behavior), and the c=0 path may handle the
divide-by-zero / unvisited case differently. Worth verifying in `MCTS.findChildren`.

### Prior win-rate-vs-random data (methodologically limited)

| c | result | methodology |
|---|---|---|
| 0 | 81.7% (clearly worse) | win-rate-style vs random |
| sqrt(2) ≈ 1.41 | baseline | — |
| 157/sqrt(2) ≈ 111 | ~99% | vs random |
| 1000 | 100.3% | vs random |
| 10000 | incomplete | — |

These were benchmarked **vs a random opponent.** Against random, POWERFUL wins so much that
win-rate saturates near 100% and ceiling effects make any improvement below ~5% invisible.
The c=0 row dropped below the ceiling so it's informative; the c=111 / c=1000 rows are not.

### 256-game sweep vs equal-strength POWERFUL baseline (proper N, proper opponent)

| c    | result |
|------|--------|
| 10   | wash |
| 30   | wash |
| 100  | wash |
| 300  | wash |
| 1000 | wash |

All five values, against c=√2 default, RUNS mode, 256 games each (128 swapped-deal pairs).
No effect within noise across 7 orders of magnitude of c.

### What the wash actually means

The UCB c-tuning lever is not a quality lever in this codebase, but **not because exploration is
useless in principle.** The mechanism by which c is neutralized:

`MCTS.java:193` aggregates per-move scores across determinizations by **summing Q values
(`getScoreForCurrentPlayer()`), not by summing visit counts.** AlphaZero-style argmax-visits
selection would make within-tree visit distribution load-bearing. Q-sum aggregation makes it
*almost irrelevant* — within-tree UCB exploration shapes which leaves get visited (so it
affects Q estimates), but the action-selection layer is dominated by aggregated mean Q
across many determinizations, which washes out per-tree tree-policy differences.

So at c=√2 (focused) vs c=1000 (near-uniform within each tree), you get similar move
rankings because (a) the best move's Q dominates in either regime once you average over many
determinizations, and (b) the FPU-style expand-untried-first mechanism in `findChildren`
already provides breadth-1 exploration regardless of c.

### Why c=0 is uniquely broken (the apparent "discontinuity")

c=0 is the one regime where any positive c isn't. Mechanism: after every child has been
visited once (via FPU), c=0 makes the UCB term exactly zero, so the next-most-visits go
entirely to the child with the highest single-rollout Q estimate. That move gets all
subsequent visits and the others are abandoned with one noisy sample each. At c=0.01,
the exploration term is tiny but nonzero, which is enough to occasionally revisit the
alternatives and refine their Q estimates. So the practically meaningful transition is
between c=0 and c=ε — not a smooth curve sliding into c=√2. c=0.1 / c=0.3 would also tie
baseline; they're just locating where the transition happens, which has no operational value.

### Conclusion

UCB c-tuning is not a quality lever. To make the tree policy load-bearing, you'd need to change the
**aggregation method** (Q-sum → argmax-visits), which only works if visit distribution is
shaped by something better than UCB (i.e., a policy prior via PUCT). That's the AlphaZero
direction. See below.

## Tree-stats empirical findings — 256-game POWERFUL A/A baseline

Instrumented `MctsStats` records, per move decision, tree-policy depth distribution, %
playouts that reached end-of-game (tree exhausted remaining game space without rollout),
and per-root-child visit% + mean Q. Aggregated across 256 games × 4 bots = 6789 move
decisions, 38M total playouts. See `analyze_mcts_stats.py`.

### Tree depth

- Mean tree-policy depth: **7.97 plies** (std 1.48, p99=11.3, max 12.8). Very consistent
  across moves.
- Per-move max depth: mean 18.6 (deepest line in any single iteration); p90=27, max=36
  (full game).
- Playout-weighted reached-end-of-game: **11.5%** overall.

### Per-move reached-end-of-game is sharply bimodal

| Bucket | % of moves |
|---|---|
| 0-5% (early/mid game, far from terminals) | 51.7% |
| 5-25% | 18.3% |
| 25-50% | 10.6% |
| 50-75% | 7.2% |
| 75-100% (late game, tree near-exhaustive) | 12.1% |

The middle is sparse — moves are either "tree nowhere near terminals" or "tree mostly at
terminals," not a smooth gradient.

### Stratified by playout budget (game stage proxy)

| Stage | mean depth | reached-end | top1-share |
|---|---|---|---|
| Early (high budget, ~33 remaining plies) | 7.30 | 0.0% | 41.6% |
| Mid | 8.62 | 5.1% | 45.3% |
| Late (low budget, ~10 remaining plies) | 8.07 | 48.5% | 55.6% |

Effective depth fraction in early game ≈ 25% of remaining plies — tree is shallow relative
to game horizon. Late game it's near-exhaustive.

### Root-visit concentration

- Top-1 share: mean 48%, median 47%
- # candidate moves per position: mean 4.1, median 3
- **Decisive (top1 ≥ 60%)**: 25.5% of all moves
- **Close call (top2 within 10pp, both ≥ 30%)**: 23.9% of all moves

### Close calls split ≈ 46% outcome-equivalent / 54% genuine

Of the 1622 close-call positions, classified by `|Q_top1 - Q_top2|`:

| |dQ| range | count | % of close calls |
|---|---|---|---|
| < 0.5 pts | 469 | 28.9% |
| 0.5 – 2.0 | 275 | 17.0% |
| 2.0 – 5.0 | 401 | 24.7% |
| 5.0 – 10.0 | 319 | 19.7% |
| ≥ 10.0 | 158 | 9.7% |

- **Outcome-equivalent** (|dQ| < 2): 11.0% of all moves. PUCT can't help — the moves are
  genuinely tied (AKQ-in-trumps and T98-loser-card type situations).
- **Genuine close call** (|dQ| ≥ 2): 12.9% of all moves. PUCT's clearest addressable subset.
- The 9.7% of close calls with |dQ| ≥ 10 (= 2.3% of all moves) are particularly striking:
  substantial Q gap (6%+ of score range) yet visits are still ~50/50. With c=√2 effectively
  zero, this means **determinizations strongly disagree** on which move is better — not
  within-tree exploration, but across-tree variance.

### What this means for the AZ / PUCT precondition

| Class | % of moves | PUCT room? |
|---|---|---|
| Decisive (top1 ≥ 60%) | 25.5% | No — search converged |
| Outcome-equivalent close call | 11.0% | No — moves are tied |
| Genuine close call | 12.9% | **Yes — clearest case** |
| Other non-decisive (30 ≤ top1 < 60%) | ~50.6% | Plausibly — deepen promising lines |

**Mechanism update from this data:** The genuine close calls with non-trivial |dQ| reveal
that across-tree disagreement (different determinizations preferring different moves) is a
major source of root-visit dispersion, not just within-tree exploration noise. PUCT's value
in this regime isn't only "concentrate visits on promising branches within a tree" — it's
also **"bias all trees toward the structurally-better move consistently, reducing
across-determinization disagreement."** A learned policy that systematically prefers the
right side of close calls would shift the aggregated visit distribution even when no
individual tree changes its decision.

The precondition is met: ~63% of moves have at least some plausible room for PUCT to
affect the outcome. The genuine close calls (12.9%) are the cleanest test case.

## Strength curve: FAST / STRONG / EXTREME vs POWERFUL

Characterises the saturation curve around POWERFUL. All matches are RUNS mode, FLAT
scaling, 2v2 on a running jass-server (ortho24 tournament, ~9984 games per match).
Server reports cumulative team scores and per-game sign/t stats.

| Matchup | Games | FAST/STRONG/EXTREME score | POWERFUL score | Δ pts/game | p (t-test) | p (sign) |
|---|---|---|---|---|---|---|
| FAST vs POWERFUL | 9984 | 788 807 (≈79.0/game) | 821 107 (≈82.2/game) | −3.2 | 0.0004 | 0.0038 |
| STRONG vs POWERFUL | 10240 | 817 349 (≈79.8/game) | 836 631 (≈81.7/game) | −1.9 | 0.0000 | 0.0000 |
| EXTREME vs POWERFUL | 10240 | 828 530 (≈80.9/game) | 825 350 (≈80.6/game) | +0.3 | 0.4700 | 0.0042 |
| INSANE vs POWERFUL | 10240 | 835 638 (≈81.6/game) | 818 542 (≈79.9/game) | +1.7 | 0.0001 | 0.5113 |
| SUPERMAN vs POWERFUL | 9324 | 762 417 (≈81.8/game) | 744 348 (≈79.8/game) | +1.9 | 0.0000 | 0.7808 |
| IRONMAN vs POWERFUL | — | — | — | — | — | — |

FAST loses to POWERFUL at very high confidence (p=0.0004, t=−3.5, sign p=0.0038, N=9984).
STRONG loses to POWERFUL at very high confidence (p≈0.000, t=−4.55, sign p≈0.000, N=10240, Δ=−1.9 pts/game).
The earlier STRONG run (p=0.20, "wash") was the bugged stats — fixing the orthogonal-pair accumulation
reversed the verdict entirely.
EXTREME is statistically indistinguishable from POWERFUL by t-test (p=0.47, Δ=+0.3 pts/game). The sign
test favors POWERFUL (wins=2457 vs losses=2663, p=0.0042), but win frequency is the wrong metric here —
the goal is points, not games. EXTREME scoring more total points while winning fewer pairs means it plays
higher-variance: larger wins, smaller losses. That is the correct strategy in a points-accumulation game.
The t-test on mean point diff is the authoritative measure; sign test is informational only.
INSANE clearly beats POWERFUL by expected score (t=3.94, p=0.0001, Δ=+1.7 pts/game), confirming more
compute still buys something above EXTREME. Sign test not significant (p=0.51) — same high-variance
pattern: INSANE wins fewer pairs but by larger margins.
SUPERMAN also clearly beats POWERFUL (t=4.46, p≈0.000, Δ=+1.9 pts/game, n=9324 games / 4662 pairs —
match ended at MAX_POINTS threshold). Sign test not significant (p=0.78). Gap over INSANE is only
+0.2 pts/game despite 2.3× more compute (8×1000=8000 vs 7×500=3500 units), suggesting the curve is
flattening above INSANE.

**Note:** p-values in the table above were computed with a bug in jass-server's statistical
reporting. With orthogonal cards enabled, the server was accumulating individual game diffs
instead of pair sums, so the t-test and sign test ran on N individual games (df=N−1) rather
than N/2 orthogonal pairs (df=N/2−1). The effect is conservative: p-values are too large
(significance is understated). Bug fixed; future results will be correct.

## Pre-AZ intermediate experiments (cheaper bridges to AZ)

Three intermediate experiments that share infrastructure with full AZ, each with its own
go/no-go signal. Each one's failure is informative; each one's success is shippable.

### 1. Heuristic-prior PUCT (no model needed)

Replace UCB selection inside `MCTS.findChildren` with the PUCT formula:
`Q(s,a) + c_puct · P(s,a) · √N(s) / (1 + N(s,a))`. Use one of the existing rollout
heuristics (`LightJassPlayoutSelectionPolicy` or `HeavyJassPlayoutSelectionPolicy`) as the
prior `P`:

```
P(a) = alpha                       if a == heuristic.getBestMove(board)
     = (1 - alpha) / (n_legal - 1) otherwise
```

Defaults: `alpha ≈ 0.7`, `c_puct ≈ 100` (scaled for 0-157 reward range).

**Purpose:** validate the PUCT plumbing in this codebase before investing in network
training. The heuristic was previously used *only in rollouts* — wiring it into the *tree
policy* is a new use that may or may not help.

**Outcomes:**
- Ties baseline → PUCT formula doesn't break anything, ready for network experiments.
- Beats baseline → unused heuristic signal in tree policy was a real lever.
- Loses → alpha too high (over-committing) or heuristic weaker than UCB's Q estimates. Tune
  or move on.

**Cost:** ~half-day of integration + a 256-game match. No new data, no training.

#### Empirical: alpha=0.7 with LightJassPlayoutSelectionPolicy, 256 games RUNS

Powerful 24589 vs Puct/alpha=0.7 17003. **t=-5.13, p<0.0001, sign 98W/158L.** ~30 pts/game
loss for PUCT. Strongly negative.

Three candidate diagnoses (in order of likelihood):

1. **Alpha too high.** 70% prior weight on a single move is enormous; if the heuristic is
   anti-correlated with truth even in some position types, PUCT funnels visits into bad
   branches and Q-sum aggregation aggregates those bad-branch evaluations. UCB would self-
   correct via Q feedback; PUCT's `P · √N / (1+n)` term keeps the wrong move attractive
   even after Q has spoken.
2. **Heuristic is anti-correlated with Q in close calls.** Heuristic picks the "obvious"
   move (highest card, take the trick); MCTS knows the "non-obvious" move (schmieren,
   ditch a useless card) is better. The prior actively pushes search *away* from the good
   move in exactly the positions where it matters.
3. **PUCT-vs-Q-sum aggregation mismatch (as flagged in the AZ section below).** Within-tree
   visit shaping by PUCT vs across-tree Q-sum aggregation: a confident-wrong prior produces
   consistent visit waste across all determinizations, and the aggregated Q reflects the
   wasted search. UCB+Q-sum doesn't have this failure mode because UCB's exploration is
   uniform.

#### Empirical: alpha=0 (uniform prior, no heuristic), 256 games RUNS

PuctAlpha0 20516 vs Powerful 20476. **t=0.029, p=0.98, sign 127W/129L.** Clean wash.

**Diagnosis confirmed:** the 30-pt loss at alpha=0.7 was specifically because the heuristic
is a misleading prior, not because the PUCT mechanism is broken. PUCT with uniform prior
performs identically to UCB baseline — the structural argument for AZ is empirically
intact in this codebase.

This is the cleanest possible plumbing-validation outcome:
- PUCT formula composes with Q-sum aggregation without pathology.
- The `c · P · √N / (1+n)` selection mechanism is fine when `P` is neutral.
- Both selection rules (UCB and PUCT-with-uniform) converge to ~equivalent visit
  distributions when given equivalent exploration signals.

**Bar established for (2) and (3):** Any learned prior must clearly beat uniform.
Heuristic-with-high-weight clearly lost; uniform clearly ties; the trained policy net needs
to land between "as good as uniform" (safe to deploy, no value) and "better than uniform"
(the actual quality win).

**Updated risk profile:**
- **(2) surgical close-call tiebreaker:** safest next step. Limited blast radius — policy
  net only intervenes in ~12.9% of moves (genuine close calls), and only among MCTS-
  validated top candidates. Mediocre policy net at worst ties baseline in those positions.
- **(3) supervised policy net as PUCT prior:** wider blast radius — applied at every node.
  Need empirical evidence from (2) before committing. A noisy or partially-trained policy
  net here could repeat the alpha=0.7 failure mode at a different scale.

#### Empirical: alpha sweep with both heuristics (256 games each, RUNS)

Followed up with a full 2×4 grid: `LightJassPlayoutSelectionPolicy` and
`HeavyJassPlayoutSelectionPolicy` as priors at `alpha ∈ {0.1, 0.3, 0.5, 0.7}`.

| alpha | Light pts/game | Heavy pts/game | Δ (H − L) |
|---|---|---|---|
| 0.0 | 0 (wash, p=0.98) | same code path | 0 |
| 0.1 | -8.2 (p=0.13) | -12.5 (p=0.03) | -4.3 |
| 0.3 | -7.0 (p=0.26) | -12.9 (p=0.02) | -5.9 |
| 0.5 | -8.6 (p=0.12) | -7.0 (p=0.17) | +1.6 |
| 0.7 | -29.6 (p<0.0001) | -13.0 (p=0.015) | **+16.6** |

**Critical implementation detail clarified:** both `LightJassPlayoutSelectionPolicy` and
`HeavyJassPlayoutSelectionPolicy` end in `chooseRandomCard(set)` — `getBestMove()` is
*stochastic*, returning a random sample from a filtered set:
- Light: random over `refineCardsWithJassKnowledge(possibleCards, game)` (~most legal moves)
- Heavy: random over `getAdvisableCards(game, possibleCards)` (~1-3 cards after stechen/
  schmieren/positional logic in `PerfectInformationGameSolver`)

So the "prior" varies between PUCT selection calls. The *average* prior across calls,
treating "the set" as a single category, ends up biasing for/against the whole set:
- alpha < 1/n: average prior is biased *away* from the heuristic-favored set
- alpha > 1/n: average prior is biased *toward* the heuristic-favored set
- alpha ≈ 1/n: roughly uniform (for typical n=3 Jass positions, crossover at α≈0.33)

Smaller filter set (Heavy) → more concentrated bias → harm/help amplified in both directions.

**Shape of the curves:**
- Light has a clean plateau (-7 to -9 across 0.1–0.5) and then a cliff to -30 at 0.7.
- Heavy is much flatter (-7 to -13 across the full sweep). The high-alpha cliff is largely
  absent — Heavy's stronger toward-bias on advisable cards offsets what would otherwise be
  random-allocation harm at alpha=0.7.

**Key empirical findings:**
1. **Heuristic quality matters substantially in the toward-direction.** Heavy at alpha=0.7
   loses 17 fewer pts/game than Light at alpha=0.7 (-13 vs -30). This is direct evidence
   that better priors produce better PUCT performance, supporting AZ.
2. **Stochastic sampling adds a ~7-13 pt/game noise floor** that no alpha setting overcomes.
   Intrinsic to using `getBestMove()` (single sample) as a substitute for a probability
   distribution.
3. **No alpha+heuristic combination beats baseline.** Best result is Heavy at alpha=0.5
   (-7 pts/game), statistically indistinguishable from baseline noise. So heuristic-prior
   PUCT is not by itself a quality win.

**Synthesis for the AZ direction:**

A trained policy network has two structural advantages over `getBestMove`-style priors:
- **No randomization noise.** Policy nets output a proper softmax distribution per state.
  The ~7-13 pt sampling-noise floor we measured does not apply.
- **Per-position specialization.** Heavy's `getAdvisableCards` applies the same rules
  everywhere; a trained policy can learn position-specific patterns that hand-coded rules
  can't capture.

**Concrete bar for the trained policy net:** at PUCT operating regime (high alpha, toward-
bias), it must beat Heavy by at least 13 pts/game just to break even with baseline, and
substantially more to be a real quality win. Supervised training on MCTS visit
distributions plausibly clears that bar; how comfortably is the empirical question for (2)
and (3) below.

**Net read:** moderately AZ-positive. The heuristic-prior PUCT experiments demonstrated that
priors *can* matter (Heavy vs Light at alpha=0.7) and that the PUCT mechanism *is* fine
(alpha=0 wash). They also established a concrete reference point (Heavy at alpha=0.5/0.7)
that any trained-policy experiment can be compared against. The supervised policy net
investment is reasonable, with realistic expectations: clear room to outperform heuristic,
but not a free win.

### 2. Surgical close-call tiebreaker with supervised policy net

Train a small policy network on `(state, MCTS-visit-distribution)` pairs from logged
POWERFUL self-play games. Don't integrate with PUCT. Instead, at root action selection in
`vote()`, *if* MCTS shows a close call (top-2 visits within 10pp AND |Q_top1 - Q_top2| ≥ 2),
defer to the policy net's pick. Otherwise use existing Q-sum.

**Purpose:** isolate and test the central PUCT hypothesis ("does a learned policy
systematically pick the right side of genuine close calls?") without committing to any
within-MCTS changes.

**Outcomes:**
- Beats baseline → strong direct evidence for the close-call mechanism. Ship it as a
  quality win regardless of whether full AZ comes next.
- Ties / loses → the close-call mechanism isn't the lever, weakens the AZ case
  substantially.

**Cost:** 1–2 days for training pipeline + integration + 256-game match. Reuses the
existing NeuralNetwork / Estimator class hierarchy.

### 3. Supervised policy net as PUCT prior (full integration, no self-play)

Combines (1) plumbing with (2) network: use the trained policy net from (2) as the PUCT
prior `P(s,a)` instead of the heuristic. AlphaGo (not Zero) used this exact pattern.

**Purpose:** test whether a learned policy at every node of the tree (not just at the root
tiebreaker) gives quality lift beyond what the surgical tiebreaker achieves.

**Outcomes:**
- Beats (2) → the policy net is useful throughout the tree, not just at the root. Full AZ
  becomes very compelling.
- Ties (2) → most of the policy net's value was at the root tiebreaker. Full AZ likely
  marginal; stick with the simpler architecture.

**Cost:** ~1 day on top of (2). Re-uses (2)'s trained network.

### Suggested order

1. **(1) first** — free, validates plumbing.
2. **(2) next** — most surgical empirical test of the close-call hypothesis. Either a
   shippable win or a strong negative.
3. **(3) only if (2) lands** — full PUCT integration with the trained net.
4. **Full AZ** only if (3) shows headroom over (2). Self-play loop closes the cycle and
   lets the policy net exceed POWERFUL.

This decomposes the months-long AZ commitment into 2–4 individual experiments, each
~1–3 days, each with its own go/no-go signal.

## AlphaZero-style policy + value, trained via self-play (TPU + JAX)

Distinct from the thesis's DNN-based experiments — the structural innovation that wasn't
tested is **PUCT with a learned policy head guiding tree expansion** combined with
**argmax-visits action selection** (instead of the current Q-sum aggregation).

### Background: PUCT vs UCB, and Q-sum vs argmax-visits

**UCB1 (current):** at node s, select child a maximizing `Q(s,a) + c · √(ln N(s) / n(s,a))`.
Exploration is uniform across children — every untried/under-visited child gets a bonus
purely as a function of visit count.

**PUCT (AlphaZero):** at node s, select child a maximizing
`Q(s,a) + c · P(a|s) · √N(s) / (1 + n(s,a))`, where `P(a|s)` is the policy network's prior
over actions. Exploration is **non-uniform** — children the policy thinks are good get more
exploration bonus, children the policy thinks are bad get almost none. Same Q exploitation
term; the difference is entirely in *where the exploration goes.*

The √N(s) / (1+n(s,a)) shape vs √(ln N / n) also has different growth properties (PUCT
grows faster early, slower late), but the load-bearing change is the policy-weighted
exploration.

**Q-sum aggregation (current, `MCTS.java:193`):** after all root-parallelized trees finish,
for each candidate move m: sum `getScoreForCurrentPlayer()` across all leaf nodes of all
trees, then argmax. Move chosen ≈ "move with the highest aggregated mean Q across
determinizations."

**Argmax-visits aggregation (AlphaZero):** after MCTS, pick the move with the most root
visits. This only produces a sensible policy if the *tree policy concentrates visits on the
best move* — which requires good Q estimates AND good exploration shaping (i.e., PUCT).

### Why this is plausibly different from the thesis's negatives

- Thesis tested DNN as *rollout policy*, *leaf value estimator*, and *belief over hidden
  cards* — all leaf-side. None helped at 1000 rounds.
- Thesis did NOT test DNN as *tree policy* via PUCT. PUCT shapes the visit distribution,
  not the leaf values — different lever, addresses the shallow-tree symptom.
- Thesis's DNNs were trained supervised on logged games. AZ self-play generates targets
  from MCTS-improved policies — qualitatively higher-quality training signal.

### The aggregation question

The 256-game UCB sweep showed Q-sum aggregation washes out within-tree tree-policy
differences (c=√2 and c=1000 tie at 256 games). PUCT-without-changing-aggregation would
likely tie similarly — the policy prior would shape visits within each tree, but the
Q-sum across many determinizations would average it back out.

So the real AZ commitment is *both* PUCT (tree policy) *and* argmax-visits (aggregation).
Either alone is probably insufficient:

- **Argmax-visits with UCB tree policy:** visits are still ~uniform within tree → argmax
  picks a near-random move. Worse than baseline.
- **PUCT with Q-sum aggregation:** policy concentrates within tree but aggregation washes
  it out. Probably no better than baseline.
- **PUCT + argmax-visits + Dirichlet noise at root:** the canonical AlphaZero loop.

This makes the implementation path more invasive than "just plug a network in."

### Implementation path

Estimated 2–4 days for scaffolding on TPU+JAX, plus tuning:

1. JAX policy+value network (small transformer over card embeddings).
2. Self-play loop: MCTS-with-PUCT generates games; (state, MCTS-visits, outcome) → training data.
3. Model export (Flax → SavedModel) for Java-side inference via TF-Java (already in repo).
4. Java-side MCTS modifications:
   - Replace UCB selection with PUCT in `Node.upperConfidenceBound` / `MCTS.findChildren`.
   - Replace Q-sum action selection in `MCTS.java:193` with summed root visit counts.
   - Add Dirichlet noise at the root for exploration during self-play.
   - Query value net at leaves (replacing or augmenting rollouts).

### Risks

- Even with TPU compute, the long tail is *tuning* (self-play schedule, value/policy
  weighting, exploration noise, PUCT c, Dirichlet α). Implementation in days; landing a
  clear win is months.
- The thesis's broader conclusion ("DMCTS at scale beats DNN methods in this codebase") is
  an a-priori-negative on this whole class of approach. PUCT is the specific lever that
  wasn't tested, but the prior is still bearish.
- Aggregation-switch risk: with bad policy net early in training, argmax-visits will be
  worse than Q-sum. There's no smooth interpolation between the two — you commit to
  argmax-visits and hope the policy net converges to something useful before competitive
  evaluation.

### Precondition

Run the tree-depth diagnostic above. If trees are already reaching end-of-game, PUCT can't
deepen what's already maxed out, so the AZ quality lever wouldn't apply.
