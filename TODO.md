# TODOs

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
