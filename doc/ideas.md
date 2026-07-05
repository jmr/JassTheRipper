# Ideas

## Goals (current priority order)

1. **Quality.** Maximize bot strength against equal-strength baselines and humans.
2. **Efficiency.** Once quality is at target, reduce compute/energy at deployment so e.g.
   phones don't overheat — without sacrificing quality (or with minimal sacrifice).

Negative results below are tagged as "not a *quality* lever" rather than "dead" — several
findings (especially DNN-as-leaf-evaluator at modest iteration counts) may be revived as
efficiency-mode levers later: "quality-equivalent at 10× fewer playouts" is a real win
even if it can't beat full-compute MCTS on raw strength.

## Experiment log

Measured results and settled findings — the pgx-lineage calibration saga, thesis negatives,
the UCB / tree-stats / strength-curve measurements, perf wins, and the trump-model / AR /
heuristic-prior-PUCT investigations — live in [experiment_log.md](experiment_log.md). This
file is the untested backlog: proposals and hypotheses, each with its own go/no-go signal.

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

**Status:** empirically on hold — findings and verdict in
[experiment_log.md](experiment_log.md) (k=1 washed at 256 games; not a quality lever with the
current net). Revive only after re-establishing that the baseline CardsEstimator carries signal
at 256+ games; then Option 2 (retrain with partial-assignment inputs) becomes worthwhile.

## Pre-AZ intermediate experiments (cheaper bridges to AZ)

Three intermediate experiments that share infrastructure with full AZ, each with its own
go/no-go signal. Each one's failure is informative; each one's success is shippable.

**Experiment 1 (heuristic-prior PUCT) is done** — see [experiment_log.md](experiment_log.md).
Takeaways that set the bar for the experiments below: PUCT plumbing is sound (uniform-prior PUCT
ties baseline); a confident-wrong prior hurts badly (Light α=0.7: −30 pts/game); better priors
help (Heavy beats Light at high α); and `getBestMove`-style stochastic priors carry a ~7–13
pt/game noise floor. Net read: moderately AZ-positive — a trained policy net must beat Heavy by
≥~13 pts/game just to break even with baseline.

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
