# Card desync bug — long-match `availableCards` corruption

## Symptom

In long matches, the bot's view of its own hand gets out of sync with the actual game
state. The `BUG: current player ... has no cards in getMoves` log (added in commit
`pmorwlmp`, "JassBoard: debug logging when current player has no cards") fires repeatedly.

Concrete pattern from a 547-game FAST run (2026-05-31, `/tmp/jass-fast-*.log`):

```
roundNumber=8
alreadyPlayed=[..., DA, C7, DJ, ..., D7, DQ]   ← 34 cards played total
availableCards=[DJ, DQ, C7, C8]                ← 4 cards, of which DJ/DQ/C7 are in alreadyPlayed
allPlayerCards=Fast:[], Powerful:[], Fast:[S8, S9], Powerful:[]
```

Card conservation is violated: 4 (availableCards) + 34 (alreadyPlayed) = 38 cards, with
3 cards appearing in both lists. The bot has 4 cards "in hand" at round 8 when it should
have 1. Determinization then fails to deal cards correctly and leaves players empty.

Eventually escalates to a fatal `Card was rejected` RuntimeException from the server:
- MCTS picks a card from "possible cards" (stale view)
- `Player.chooseCardWithFallback` validates via `Mode.canPlayCard(...)` — disagrees
- Random fallback also rejected by server because the bot's hand has diverged from server

## Hypothesis: `Player.equals` + nullable id + name collisions

The most plausible mechanism, in order of evidence:

**1. `Player.equals` (Player.java:209-217) uses id + name. With nullable id, equals
degrades to name-only matching:**

```java
if (id != null ? !id.equals(player.id) : player.id != null) return false;
return name != null ? name.equals(player.name) : player.name == null;
```

If `this.id == null` and `other.id == null`, the first check passes and equals becomes
name-only.

**2. `GameHandler.resetPlayerMapper` (GameHandler.java:110-113) sets `localPlayer.setId(null)`
at session end:**

```java
private void resetPlayerMapper(Player localPlayer) {
    localPlayer.setId(null);
    this.playerMapper = new PlayerMapper(localPlayer);
}
```

Called from `onBroadCastWinnerTeam` (line 181). After this, localPlayer is in
name-only-equals state until a new session sets id.

**3. In the tournament setup, two bots per team share the same name** (e.g., two "Fast"
players). After id reset, the two Fast Player objects can equals-match each other despite
being distinct objects with separate `cards` Sets.

**4. `onMoveMade` is only called on `localPlayer` (GameHandler.java:152), and uses
`this.equals(move.getPlayer())` to decide whether to remove the card:**

```java
public void onMoveMade(Move move) {
    if (this.equals(move.getPlayer())) {
        if (!cards.remove(move.getPlayedCard()))
            logger.error("The player {} did not have card {}", ...);
        else
            cardBits &= ~(1L << move.getPlayedCard().ordinal());
    }
    jassStrategy.onMoveMade(move);
}
```

If the equals check returns the wrong answer at a moment when ids transiently diverge
(one player has id, the other has null), `localPlayer.cards` either:
- Drops a card it never played (then later "did not have card" errors fire), or
- Retains a card it did play (which is the observed symptom: too many cards at round 8).

Both failures are silent under normal logging; they only manifest catastrophically when
determinization runs out of cards to deal.

## Other suspicious code (not necessarily the root cause)

### `Player.cardBits` is dead code

`getCardBits()` is never called from outside `Player.java`. The field is maintained in
lockstep with `cards` by every mutation method, but nothing downstream reads it.
Vestigial state — should probably be deleted after the bug is fixed.

### `onMoveMade` has an asymmetric update

```java
if (!cards.remove(move.getPlayedCard()))
    logger.error(...);
else
    cardBits &= ~(...);
```

If `cards.remove` fails (returns false), `cardBits` is NOT cleared. If `cardBits` had
the bit set (out of sync), the divergence persists. Mostly cosmetic since cardBits
is unread, but if `getCardBits` ever gets a caller, this asymmetry would bite.

### `Player.setCards` computes `cardBits` from the parameter, not `this.cards`

```java
public void setCards(Set<Card> cards) {
    this.cards.clear();
    this.cards.addAll(cards);
    this.cardBits = CardSet.toBits(cards);   // <-- parameter, not this.cards
}
```

Subtle. If a caller ever passes `player.cards` itself back to `player.setCards(...)`
(perhaps after a roundabout reference chain), `this.cards.clear()` empties both the
field and the parameter, `addAll` adds zero, and `toBits` returns 0. The player ends
up with an empty hand silently.

## Diagnostic next steps

When the bug reproduces again, grep the bot logs for:

- `"did not have card"` — the error from `Player.onMoveMade` when `cards.remove` fails.
  Confirms onMoveMade is firing for moves the bot didn't actually play (wrong player
  matched via equals).
- `"Order differed between remote and local state"` — the `checkEquals` log from
  `GameHandler.onRequestCard:136`. Fires when `getCurrentPlayer() != localPlayer`
  via equals at a moment the bot is supposed to play. Direct confirmation of the
  identity-mismatch hypothesis.
- `"BUG: ... has no cards"` — the existing instrumentation that catches the symptom.
- The full `availableCards` and `allPlayerCards` from each `BUG:` fire; correlate with
  the cards the bot actually played earlier in the same game.

## Possible fixes

In order of preference:

1. **Remove identity matching from `onMoveMade` routing**: instead of
   `localPlayer.onMoveMade(move)` doing an equals check, have `Round.makeMove` directly
   call `onMoveMade` on `move.getPlayer()` (the player from the round's playing order,
   who actually played the card). Eliminates the question of identity matching entirely.
   The "localPlayer" is just the user-facing API target; the cards-tracking should be
   tied to the *player who actually played*, not "the bot we registered."
2. **Stop resetting `localPlayer.id` to null at session end**. The id is set once when
   the bot joins; resetting it serves no purpose I can see and creates the equals
   degradation window.
3. **Use object identity in `Player.equals`** (i.e., revert to `Object.equals`). Card
   identity should be by reference. Currently equals is used for cross-game player
   comparison (e.g., the assertion in `onRequestCard`); those would need to be updated
   to match by id/name explicitly where intended.
4. **Delete `cardBits`** and the parallel-state machinery in `Player`. Reduces surface
   area for sync bugs even if it doesn't fix the current one.

## Reference

- Symptom first caught reliably in: 547-game FAST run, 2026-05-31 ~17:45-17:51.
- Logging that caught it: commit `pmorwlmp`, "JassBoard: debug logging when current
  player has no cards" (2026-05-30).
- Why FAST and not POWERFUL: faster game throughput → more sessions per unit time →
  more session-end → more id resets → more time spent in name-only-equals window.

## Other observations from /tmp/jass-*.log archives

Three non-fatal `"Your strategy tried to play an invalid card. Playing random card
instead!"` errors found across earlier PUCT matches (now-overwritten FAST logs aside).
Each shows MCTS choosing a card from its declared "available cards" list, but
`Mode.canPlayCard` rejecting it. The random fallback succeeded each time; the bot
recovered and continued. These are the same bug class as the fatal FAST crash, just at
lower severity.

- `/tmp/jass-pucth01-1.log` @ 16:52:33 — chose CQ from `[HA, CQ, CK]`, rejected. Next
  move had `[CQ, CK]` (the random fallback played HA).
- `/tmp/jass-pucth07-1.log` @ 17:12:18 — chose S6 from `[DK, DA, CA, S6, SQ]`, rejected.
  Next move had `[DK, DA, S6, SQ]` (CA was played by fallback).
- `/tmp/jass-pucth07-2.log` @ 17:13:44 — chose C10 from `[HJ, D8, D9, DA, C7, C8, C10,
  CK, CA]`, rejected. Next move had `[HJ, D9, DA, C7, C8, C10, CK, CA]` (D8 played).

In each: MCTS's view (via `CardSelectionHelper.getCardsPossibleToPlay`) and the
validation view (via `Mode.canPlayCard` from `Player.chooseCardWithFallback`) disagree.
Both paths derive from `playedCardBits` / `playedCards` but the answer differs.

Rate: ~3 errors across ~9000+ moves per 256-game match in those PUCT runs.
Catastrophic accumulation rate (one in 547 fatal) is roughly 200x lower. This is
consistent with the cumulative-state-leakage hypothesis: most occurrences are caught
by the random fallback, only the worst-corrupted state (round 8, last possible chance)
escalates to a server rejection that closes the connection.

Note: none of the searchable logs contained `"did not have card"` (Player.onMoveMade's
`cards.remove`-returned-false error). That's significant: if the equals-mismatch
hypothesis is right, we'd expect that error to fire whenever `localPlayer` got an
onMoveMade for a card that isn't in its hand. Its absence suggests either:
- The equals match is consistently *wrong-direction* (passing when it shouldn't), so
  cards get removed but from the wrong Player object, leaving localPlayer's Set with
  cards it never played, OR
- The bug is elsewhere — not in `onMoveMade` at all but in some other path that adds
  cards to `localPlayer.cards`. Worth grepping for any code path that calls `addCard`
  or `setCards` outside the well-known deal-cards / determinization paths.
