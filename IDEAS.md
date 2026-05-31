# Ideas

## Thesis findings — already ruled out at ≥1000 rounds (do not re-test)

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

**Conclusion:** AR direction is effectively dead with the current network. To revive,
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

The UCB c-tuning lever is genuinely dead in this codebase, but **not because exploration is
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

UCB c-tuning is dead. To make the tree policy load-bearing, you'd need to change the
**aggregation method** (Q-sum → argmax-visits), which only works if visit distribution is
shaped by something better than UCB (i.e., a policy prior via PUCT). That's the AlphaZero
direction. See below.

## Tree depth / breadth diagnostic — proposed

Before committing weeks to AZ, instrument MCTS to log:

- Average depth reached per playout (across the tree, not just deepest leaf)
- Visit-count distribution at the root (how concentrated vs uniform)
- Average depth at which a node's visit count exceeds, say, 50

What this tells you:

- **If trees already reach end-of-game on most branches at POWERFUL** → PUCT can't deepen
  what's already maxed out. The structural argument for AZ doesn't apply in this codebase,
  and the ceiling is something else (leaf noise, branching at choice points).
- **If average depth is 2–4 with massive breadth at the root** → PUCT has plenty of room
  to help by concentrating visits on promising branches. The AZ case is strong.

Few hours of instrumentation + 256-game RUNS-mode logging run. Should precede any commitment
to a multi-week AZ implementation.

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
deepen what's already maxed out and this whole direction is dead before it starts.
