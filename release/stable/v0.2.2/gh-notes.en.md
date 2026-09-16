## 0.2.2 — Fix: with Create: Storage (FXNT Storage) installed, JEI's crafting "+" no longer uses cache supply

**Symptom**: in a pack that also ships FXNT Storage, the "+" on JEI's crafting page is grey with vanilla "missing items" even though the material is in the supply-chain cache; with material in the backpack everything behaves normally.

**Cause**: the other mod's JEI transfer handler registers on the **same key** we use (`(CraftingMenu, minecraft:crafting)`), and JEI's registry is last-write-wins; it registers later, so it replaces our handler entirely — and its handler only accepts material from the worn backpack.

**Fix (no changes to the other mod, no writes into JEI's table)**: a new **client-only** soft-failing mixin hooked on JEI's public handler lookup —
- **logistics panel live** ⇒ **we answer** (cache supply works);
- **panel not live** ⇒ **we do nothing**, the previous handler keeps answering (crafting from backpack material as before);
- keyed on the exact `CraftingMenu` class ＋ `minecraft:crafting`; **no protocol change, no save-format change, no change to server-side arbitration**;
- if a JEI update breaks the injection, we log one warning and the patch silently does nothing — the game will not crash.

**Material sources**: while the panel is live, **the crafting grid, the player inventory and the cache all count**, and the grid/inventory are spent first with the cache filling the gap — a click **first consults the handler we displaced** (it may know sources we cannot see, e.g. a worn backpack) and then asks the server to top the remaining gap up from the cache. **The preview defers to the server** (a live panel no longer lets the client pre-declare "missing"), and only the provably hopeless case — an empty cache view plus a grid/inventory that already failed — shows "not enough available materials in the backpack, the cache and the crafting grid".

**Failures are no longer silent**: a JEI recipe page covers the logistics panel, so the verdict is now spoken on the **action bar** — a failure states **our own reason** and, where possible, **names what is missing** (`Missing: Iron Plate ×2`); a success says nothing extra. The panel's own notice is unchanged, and that "what is missing" list is a client-side estimate for explanation only — the server still decides.

**Compatibility**: clean packs behave exactly like 0.2.1; the "unusable with FXNT Storage" note from the previous release is **fixed in 0.2.2**. If JEI's own UI is broken for unrelated reasons, the **logistics panel** and the **vanilla recipe book** remain available as supply paths.

**Verification**: JUnit 25/25; GameTest 155/155 (`-PwithMobile`); packaged smoke `developmentOutputs=0` with `0.2.2` in the mod list.