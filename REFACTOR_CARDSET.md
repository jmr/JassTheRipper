# Card set long-bitmask refactor

## Motivation

`Set<Card>` (EnumSet) in the hot playout path causes:
- Iterator allocation on every `hasOnlyTrumpf`, `hasOtherCardsOfRoundColor`, `hasOnlyJackOfTrumpf` call
- `EnumSet.copyOf` allocation at every `getCardsPossibleToPlay` call site
- `new ArrayList<>(possible)` in benchmark harness to do random index access

36 cards fit in a `long` (bits 0–35). Cards are grouped by color in the enum:
  HEARTS 0–8 | DIAMONDS 9–17 | CLUBS 18–26 | SPADES 27–35

So color membership is a mask + bitwise AND — O(1) with no allocation.

## Encoding

```
cardBit(card)       = 1L << card.ordinal()
colorMask(color)    = 9 consecutive bits starting at color's first card ordinal
jackBit(color)      = cardBit(JACK of that color)   [ordinal offset 5]
nineBit(color)      = cardBit(NINE of that color)   [ordinal offset 3]
```

## isHighestTrumpf — O(1) bit algorithm

Trumpf ranks: 6<7<8<10<Q<K<A<9<J (J=13, 9=12, rest follow bit-position order)

```
trumpfPlayed = alreadyPlayed & trumpfMask
if trumpfPlayed == 0 → true

if trumpfPlayed & jackBit → return card == jack    // J played; only J wins
if card == jack → true                             // card is J, none played

if trumpfPlayed & nineBit → return card == nine    // 9 played; only 9 wins
if card == nine → true                             // card is 9, none played

// Remaining: card and played are all in normal rank order by bit position.
// "Higher" means strictly higher bit within the color group, minus J/9 bits.
higherBits = trumpfMask & ~jackBit & ~nineBit & highMask(cardBit)
return (trumpfPlayed & higherBits) == 0
```
where `highMask(cardBit) = -(cardBit << 1)` (two's complement: all bits above cardBit).

## Commit plan

### Commit 1 (current): improve test coverage + regression test
Files: `TrumpfColorModeTest.java`, new `PlayoutRegressionTest.java`

- Add `isHighestTrumpf` unit tests: J always wins, 9 wins when no J, A beats K, under-trumpf rejected
- Add deterministic regression test: fixed seed, fixed deal, assert exact 36-card sequence
  This gives a behavioral oracle for the subsequent refactor commits.

### Commit 2: bump source/target to Java 21
Files: `build.gradle`

- Change `javaVersion` from `"1.8"` to `"21"`

### Commit 3: add `CardSet` utility
Files: new `src/main/java/to/joeli/jass/game/cards/CardSet.java`

Static utility — no instances. Precomputed arrays indexed by `Color.ordinal()`:
- `long[] COLOR_MASKS`    — 9-bit mask per color
- `long[] JACK_BITS`      — jack bit per color
- `long[] NINE_BITS`      — nine bit per color
- `Card[] CARDS`          — `Card.values()` cached
- `long toBits(Set<Card>)`
- `EnumSet<Card> toEnumSet(long bits)`
- `int size(long bits)` — `Long.bitCount`
- `boolean isEmpty(long bits)`
- `boolean contains(long bits, Card c)`
- `Card pickRandom(long bits, Random rng)` — bit iteration, no allocation

### Commit 4: `long`-based `canPlayCard` in hot path
Files: `Mode.java`, `TrumpfColorMode.java`, `TopDownMode.java`, `BottomUpMode.java`,
       `ShiftMode.java`, `GeneralRules.java`

- Add overload `canPlayCard(Card card, long alreadyPlayed, Color roundColor, long playerCards)`
  alongside existing `Set<Card>` signature (old one becomes a converting wrapper)
- Implement all `has*` methods as O(1) bit ops
- Implement `isHighestTrumpf` with the O(1) algorithm above

### Commit 5: `Round.playedCardsCache` → `long`
Files: `Round.java`, `CardSelectionHelper.java`

- Change `playedCardsCache` from `EnumSet<Card>` to `long`
- Add `getPlayedCardBits()` returning `long`; keep `getPlayedCards()` materializing via `CardSet.toEnumSet`
  (used only for scoring and winner determination — not hot path)
- `CardSelectionHelper.getCardsPossibleToPlay` uses `round.getPlayedCardBits()` internally,
  returns `long`; keep `Set<Card>` overload as converting wrapper for non-hot callers

### Commit 6: `Player.cardBits` field
Files: `Player.java`

- Add `long cardBits` field alongside `Set<Card> cards`
- Keep both in sync in `addCard`, `setCards`, `onMoveMade`
- Add `getCardBits()` accessor
- Keep `getCards()` returning `Set<Card>` — all neural net / CardKnowledgeBase code unaffected

### Commit 7: wire hot path end-to-end
Files: `CardSelectionHelper.java`, `JassBoard.java`, `JassTheRipperJassStrategy.java`,
       `PerfectInformationGameSolver.java`, `PlayoutBenchmarkTest.java`

- `getCardsPossibleToPlay` hot path uses `player.getCardBits()` and `round.getPlayedCardBits()`
  — eliminates `EnumSet.copyOf(player.getCards())` at every call site
- Benchmark harness uses `CardSet.pickRandom` — eliminates `new ArrayList<>(possible)`
- Remove `LEGACY_PLAYOUT` flag and dead branches

### Commit 8: remove old `Set<Card>` overloads
Files: `Mode.java`, `CardSelectionHelper.java`

- Delete the converting wrapper overloads added in commits 4/5 once all callers are on the `long` path
- Run full test suite to confirm no regressions

## Files touched (estimated)

Hot path (core of refactor):
  Card.java, CardSet.java (new), Round.java, Mode.java, TrumpfColorMode.java,
  TopDownMode.java, BottomUpMode.java, ShiftMode.java, GeneralRules.java,
  CardSelectionHelper.java, Player.java, JassBoard.java

Callers updated:
  JassTheRipperJassStrategy.java, PerfectInformationGameSolver.java,
  ScoreEstimator.java, PlayoutBenchmarkTest.java

Tests:
  TrumpfColorModeTest.java, PlayoutRegressionTest.java (new),
  CardSelectionHelperTest.java (minor), CardTest.java (minor)

~15 files, ~300 lines changed/added.
