# Card desync bug — long-match `availableCards` corruption

## Current status (2026-06-01)

**Two bugs found. Bug #1 (MCTS simulation crash) fixed. Bug #2 (live state corruption) instrumented but root cause still unknown.**

### Bug #1 — Determinization overconstrain → 0-card MCTS simulation player — FIXED

Root cause: `Distribution.deleteEventAndReBalance(player)` returns `false` (without
removing the player) when `probabilities.size() == 1`. This is called from
`CardKnowledgeBase.deletePlayerFromRemainingDistributions` when a player reaches their
card quota and we try to remove them from all remaining card distributions.

If a card's distribution has been narrowed to `{playerA: 1.0}` by impossible-card
constraints (suit-voiding inference) AND playerA has just reached quota, the deletion
silently fails. On the next main-loop iteration the same distribution is picked again,
`sample()` returns playerA again, and playerA receives a second card beyond quota.
Another player ends up with 0 cards → `AssertionError: The current player's cards are
empty` in `JassBoard.getMoves`.

This caused 26–66 `AssertionError` fires per run in tournament logs (2026-06-01).

**Fix:** in `deletePlayerFromRemainingDistributions`, when deletion fails, reopen the
distribution to all other eligible players (those not at quota, excluding the bot and
the just-full player) with uniform probability. The card then gets assigned to one of
them on the next main-loop iteration. The impossible-card constraint is violated (a card
goes to a player who "shouldn't" hold it per suit-voiding inference), but all players
end up with the correct number of cards and the simulation runs to completion.

### Bug #2 — Live state corruption (`localPlayer.cards`) — active, root cause unknown

- The bug reproduces reliably in long matches (every few hundred games at FAST/TEST,
  occasionally at POWERFUL).
- Five major hypotheses have been ruled out empirically by instrumentation that has
  fired zero times across thousands of `BUG:` events.
- The corruption path is NOT in any of: `Player.onMoveMade`, `Player.equals`-degradation,
  `Round.makeMove` bits-vs-moves divergence, `Round.getWinner` null-fallback,
  `PlayingOrder.createOrderStartingFromPlayer` equals-failure.
- A new **content invariant** was added to `JassTheRipperJassStrategy.chooseCard`: at
  entry, verify that no card in `availableCards` appears in `game.getAlreadyPlayedCards()`.
  If this fires, it directly identifies the stale card and when the corruption was
  observed (unlike the BUG log which fires later inside MCTS simulation).
- A separate MCTS bug in trumpf-selection determinization was found and fixed during
  this investigation (see "Sibling bug: fixed" below) but it is NOT the production bug
  — production card-play MCTS shares determinization across iterations via `MCTSTask`
  and is unaffected by the fix.

## Symptom

In long matches, the bot's view of its own hand gets out of sync with the actual game
state. The `BUG: current player ... has no cards in getMoves` log (added in commit
`pmorwlmp`, "JassBoard: debug logging when current player has no cards") fires repeatedly.

Concrete pattern from a 547-game FAST run (2026-05-31, `/tmp/jass-fast-*.log`):

```
mode=OBEABE, roundNumber=8
currentRoundPlayed=[]
alreadyPlayed=[H9, H8, HA, H6, HK, HJ, C10, HQ, SA, S9, S6, SQ, C7, CQ, C9, D10,
               H10, D9, D7, H7, DA, DJ, DQ, S8, S10, SK, SJ, S7, D8, DK, CJ, CA]
availableCards=[CK, CA]
allPlayerCards=Fast:[], Powerful:[C6, C8], Fast:[CK], Powerful:[D6]
```

Card-conservation violated:
- `availableCards` has 2 cards, but at round-8 start the bot should have exactly 1 (= 9 −
  roundNumber).
- `CA` appears in both `availableCards` and `alreadyPlayed` (impossible).
- `CK` appears in both `availableCards` and another player's hand after determinization.

The corruption is in `localPlayer.cards` at MCTS-kickoff time. The bot's `cards` Set
genuinely holds cards that have already been played in the live game's `Round`. The
bug surfaces in determinization (which can't reconcile inconsistent state) and
eventually escalates to a server-side `Card was rejected` RuntimeException that
terminates the bot connection.

## Hypotheses ruled out

### 1. `onMoveMade` missing the bot's own moves (equals-degradation)

**Hypothesis:** `GameHandler.resetPlayerMapper` sets `localPlayer.setId(null)` at
session end. `Player.equals` (id + name) degrades to name-only when id is null. With
two bots per team sharing a name (e.g., two "Fast" bots), `localPlayer.equals(...)`
could mismatch transiently, causing `onMoveMade` to silently skip the bot's own play.

**Ruled out by:** added `DESYNC:` log in `Player.onMoveMade` that fires when (a) cards
are matched but `cards.remove` fails, or (b) names match but equals returns false.
Across thousands of `BUG:` events across multiple match runs, the "had no card to
remove" branch fired **zero** times. The name-match-but-equals-false branch fires for
the partner's normal plays (legit) and was removed from the logging.

**Conclusion:** `cards.remove` always succeeds when called. The bot's own onMoveMade is
firing correctly and decrementing properly.

### 2. `Round.makeMove` bits-vs-moves divergence

**Hypothesis:** `Round.playedCardBits` and `Round.moves` are maintained in parallel. If
they diverge, `Round.getWinner` produces a null winner that silently falls back to
index 0 in `PlayingOrder.createOrderStartingFromPlayer`, breaking subsequent rounds.

**Ruled out by:** added `INVARIANT:` check in `Round.makeMove` and `Round(Round)` copy
ctor that asserts `Long.bitCount(playedCardBits) == moves.size()`. Zero fires in
production runs. (It DID catch a sibling bug in MCTS trumpf-selection — see "Sibling
bug: fixed" below.)

**Conclusion:** Round's two card representations stay in sync.

### 3. `Round.getWinner` null-fallback

**Hypothesis:** If `getWinner` returns null (winning card not found in moves), the next
round starts with wrong player.

**Ruled out by:** added `DESYNC:` log when `mode.determineWinningCard` returns null or
when the winning card isn't found in moves. Zero fires.

**Conclusion:** Round winners are always identified correctly.

### 4. `PlayingOrder.createOrderStartingFromPlayer` equals-failure

**Hypothesis:** If equals fails to match `startFrom` in the player list, the fallback to
index 0 silently picks the wrong starting player.

**Ruled out by:** added `DESYNC:` log on both fallback paths (null startFrom and
not-found). Zero fires.

**Conclusion:** equals always matches correctly here.

### 5. Bot's hand-size off at `chooseCard` entry

**Hypothesis:** The bot's hand might be wrong as it enters `chooseCard`.

**Ruled out by:** added invariant in `JassTheRipperJassStrategy.chooseCard`:
`availableCards.size() == 9 - roundNumber`. Zero fires.

**Conclusion:** Whenever `chooseCard` is called by the bot, the hand size matches the
round number. So the corruption happens *between* `chooseCard` calls in the same game.

## Where the corruption must be

Logically: `Player.cards` (the EnumSet) can only be mutated via three methods:
- `setCards(Set)` — clear + addAll
- `addCard(Card)` — add
- `onMoveMade(Move)` — remove (inside the equals-true branch)

Known callers:
- `setCards` from `GameHandler.onDealCards` (server-driven deal, fires once per game start)
- `setCards` from `GameSession.dealCards` (test setup only)
- `setCards` from `CardKnowledgeBase.sampleCardDeterminizationToPlayers` (multiple sites)
  but those operate on **deep-copied** Player objects inside `JassBoard`'s copied
  `Game`/`GameSession`, not on `localPlayer`.
- `addCard` from `CardKnowledgeBase.sampleCardDeterminizationToPlayers` (line 161) —
  again on deep-copied players.
- `onMoveMade` from `GameHandler.onPlayedCards` (line 152) — on `localPlayer` only.

For `localPlayer.cards` to contain played cards, one of these must be true:
- A deep-copy chain incorrectly shares the `cards` Set reference between live and
  copied Players. (Spot-checked: `Player(Player)` copy ctor does `EnumSet.copyOf` and
  the chain through `Game → Round → PlayingOrder → Player` is deep at every level.)
- `setCards` is called on `localPlayer` mid-game with extra cards. (No obvious caller
  identified.)
- `addCard` is called on `localPlayer` mid-game. (No obvious caller identified; the
  only known caller is in determinization, which operates on copies.)
- `onMoveMade` for the bot's own plays does NOT remove the card (refuted by
  instrumentation).
- The corruption happens during the JVM's race window when websocket threads and MCTS
  pool threads access shared state concurrently. (Worth examining; deep-copy chain is
  not threadsafe by construction.)

## Instrumentation currently in place

| Location | Trigger | Status |
|---|---|---|
| `Player.onMoveMade` | bot's own play but card missing from hand | 0 fires |
| `Player.onMoveMade` | hand size > 9 after onMoveMade | 0 fires |
| `Round.makeMove` | `bitCount(playedCardBits) != moves.size()` | 0 fires |
| `Round(Round)` ctor | same bit/moves divergence | 0 fires |
| `Round.getWinner` | winning card null | 0 fires |
| `Round.getWinner` | winning card not in moves | 0 fires |
| `PlayingOrder.createOrderStartingFromPlayer` | startFrom == null | 0 fires |
| `PlayingOrder.createOrderStartingFromPlayer` | startFrom not equals-matched | 0 fires |
| `JassTheRipperJassStrategy.chooseTrumpf` | hand size != 9 | 0 fires |
| `JassTheRipperJassStrategy.chooseCard` | hand size != 9 − roundNumber | 0 fires |
| `JassTheRipperJassStrategy.chooseCard` | availableCards ∩ alreadyPlayed ≠ ∅ | **NEW** — targets Bug #2 directly |
| `CardKnowledgeBase.sampleCardDeterminizationToPlayers` (trumpf phase) | hand-overlap between players | 0 fires |
| `JassBoard.getMoves` (pre-existing, commit `pmorwlmp`) | current player has empty hand | **fires** — Bug #1 root-cause fixed |

The `BUG:` log + `AssertionError` in `JassBoard.getMoves` was the symptom of Bug #1
(determinization overconstraint). Bug #1 is now fixed; that log should stop firing.

The new content invariant in `chooseCard` directly targets Bug #2: if `localPlayer.cards`
contains a card that was already played in the live game, it will fire immediately with
the specific stale card, before MCTS runs. This gives direct evidence of the corruption
path that all previous instrumentation missed.

## Sibling bug: fixed (NOT the production bug)

While instrumenting, the `Round.makeMove` invariant uncovered a different MCTS bug in
trumpf-selection determinization. `JassBoard.constructTrumpfSelectionJassBoard` always
called `sampleCardDeterminizationToPlayersInTrumpfSelection()`, and `JassBoard.duplicate()`
called the constructor regardless of `newRandomCards`. So every tree-policy descent in
trumpf-selection MCTS re-determinized, creating fresh hands for non-bot players. Tree-
stored moves from earlier iterations referenced (player, card) combinations from a stale
determinization; `cards.remove` silently failed; eventually two stored moves played the
same card in the same round.

**Bug age:** present in the very first commit touching `JassBoard.java`
(`yyrzknrsykpo`, 2019-07-13) — ~7 years in the codebase.

**Fix:** removed unconditional determinization from `constructTrumpfSelectionJassBoard`;
made `JassBoard.duplicate()` conditionally determinize for trumpf-selection based on
`newRandomCards`, matching the existing card-play pattern. Now: determinization happens
once per `MCTSTask` (via `duplicate(true)`), and tree-policy descents (`duplicate(false)`)
preserve the task's determinization.

**Why it's not the production bug:** the production crash is in *card-play* MCTS, where
determinization is already shared across iterations within a task (MCTSTask does
`duplicate(true)`, treePolicy does `duplicate(false)` which preserved the determinization
even before the fix). The trumpf-selection MCTS bug never propagated to live state — all
MCTS work happens on deep-copied players in the JassBoard's copied `Game`/`GameSession`.

## Other observations from older logs (pre-instrumentation)

Three non-fatal `"Your strategy tried to play an invalid card. Playing random card
instead!"` errors found in earlier PUCT matches:

- `/tmp/jass-pucth01-1.log` @ 16:52:33 — chose CQ from `[HA, CQ, CK]`, rejected. Next
  move had `[CQ, CK]` (the random fallback played HA).
- `/tmp/jass-pucth07-1.log` @ 17:12:18 — chose S6 from `[DK, DA, CA, S6, SQ]`, rejected.
  Next move had `[DK, DA, S6, SQ]` (CA was played by fallback).
- `/tmp/jass-pucth07-2.log` @ 17:13:44 — chose C10 from `[HJ, D8, D9, DA, C7, C8, C10,
  CK, CA]`, rejected. Next move had `[HJ, D9, DA, C7, C8, C10, CK, CA]` (D8 played).

In each: MCTS's view (via `CardSelectionHelper.getCardsPossibleToPlay`) and the
validation view (via `Mode.canPlayCard` from `Player.chooseCardWithFallback`) disagree.
Rate: ~3 errors across ~9000+ moves per 256-game match. Catastrophic accumulation rate
(one in 547 fatal) is roughly 200× lower — consistent with the cumulative-state-leakage
hypothesis where most occurrences are caught by the random fallback and only the
worst-corrupted state (round 8) escalates to server rejection.

These occurrences would now be caught by the `chooseCard` invariant if they fire again
— a `hand size != 9 - roundNumber` IllegalStateException would precede them.

## Next debugging steps

1. **Re-run compare-strengths.sh** after the Bug #1 fix. The 26–66 `AssertionError`
   fires per run should disappear. If `Card was rejected` crashes still occur, Bug #2
   is still active and the new content invariant should now catch it with a
   `CONTENT INVARIANT VIOLATED` log that names the specific stale card.

2. **Add stack-trace logging to every `Player.cards` mutation** with
   `System.identityHashCode(this)`. Specifically:
   - At entry/exit of `setCards`: log `this.identity, name, id, this.cards.size() before, parameter.size()`.
   - At entry/exit of `addCard`: log `this.identity, name, id, this.cards.size() before, card`.
   - Throw a `RuntimeException(...)` immediately if called on a Player whose
     `identity` matches `localPlayer`'s identity from outside `onDealCards`. (Need a
     way to obtain localPlayer's identity at the throw site — possibly via static
     registration in GameHandler.)

3. **Check for concurrent mutation**. Add a `Thread.currentThread().getName()` tag to
   every Player.cards mutation log. If two threads modify `localPlayer.cards`
   simultaneously, the per-thread tagged logs would reveal it. Possible interleaving
   patterns:
   - WebSocketClient thread calling `setCards` (deal) while another WebSocketClient
     thread is reading `cards` for MCTS feature extraction.
   - MCTS pool thread reading from `localPlayer.cards` during a deep-copy
     (`Player(Player)` ctor calls `EnumSet.copyOf(player.getCards())` which iterates
     the live Set; concurrent modification would be undefined behavior in the JVM).

4. **Check whether `availableCards` passed to `MCTSHelper.predictMove` is a reference
   to `localPlayer.cards` or a defensive copy**. If a reference, MCTS pool threads
   reading from it during deep-copy would race with the WebSocket thread's
   `cards.remove` in `onMoveMade`. The chain:
   - `Player.makeMove(gameSession)` calls `chooseCardWithFallback`.
   - `chooseCardWithFallback` calls `jassStrategy.chooseCard(cards, session)` where
     `cards` IS `this.cards` (Player.java:165 — no defensive copy).
   - `JassTheRipperJassStrategy.chooseCard` passes `availableCards` to
     `mctsHelper.predictMove(availableCards, ...)`.
   - `MCTSHelper.predictMove` passes to `JassBoard.constructCardSelectionJassBoard`
     which does `EnumSet.copyOf(availableCards)` — defensive copy at this point.
   - BUT before that point, MCTS may have already been invoked on background threads
     via `numDeterminizations` parallel tasks that each call `board.duplicate(true)`
     → `Game(game)` → `Round(Round)` → `PlayingOrder(PlayingOrder)` → `Player(Player)`
     → `EnumSet.copyOf(player.getCards())` for each player.

   The `Player(Player)` deep-copy on a Player object accessed concurrently with
   `onMoveMade`'s `cards.remove` could produce an inconsistent snapshot. The JVM
   might leave the EnumSet in a transient state during structural updates.

5. **Examine the `BUG:` per-strength distribution shift**: pre-fix, FAST had most fires;
   post-fix, POWERFUL has the most (15 in `jass-powerful-1` vs 0 in FAST/TEST during
   a 24-game smoke). This is unexpected if the bug is purely volume-correlated.
   Possible explanations:
   - Sample variance at N=24.
   - The trumpf-MCTS fix changed timing in a way that shifted the race window.
   - The bug actually has multiple flavors and the trumpf-MCTS fix eliminated one
     class of fires that previously dominated FAST.

## Reference logs and commits

- Symptom first caught reliably: 547-game FAST run, 2026-05-31 ~17:45-17:51, log files
  now overwritten.
- Pre-existing instrumentation: commit `pmorwlmp`, "JassBoard: debug logging when current
  player has no cards" (2026-05-30).
- New instrumentation (most recent commits):
  - Round bits-vs-moves invariant + diagnostic logging
  - PlayingOrder fallback logging
  - Player.onMoveMade DESYNC logging + asymmetric cardBits fix
  - chooseCard / chooseTrumpf hand-size invariants
  - CardKnowledgeBase hands-disjoint assertion (trumpf phase)
- Trumpf-MCTS bug fix (NOT the production bug, separate issue): made `duplicate()`
  conditionally determinize based on `newRandomCards` for trumpf-selection, matching
  card-play.
