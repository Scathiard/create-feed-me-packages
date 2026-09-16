## 0.2.2 — Fix: with Create: Storage (FXNT Storage) installed, JEI's crafting "+" no longer uses cache supply

**Symptom**: in a pack that also ships FXNT Storage, the "+" on JEI's crafting page is grey with vanilla "missing items" even though the material is in the supply-chain cache; with material in the backpack everything behaves normally.

**Cause**: the other mod's JEI transfer handler registers on the **same key** we use (`(CraftingMenu, minecraft:crafting)`), and JEI's registry is last-write-wins; it registers later, so it replaces our handler entirely — and its handler only accepts material from the worn backpack.

**Fix (no changes to the other mod, no writes into JEI's table)**: a new **client-only** soft-failing mixin hooked on JEI's public handler lookup —
- **logistics panel live** ⇒ **we answer** (cache supply works);
- **panel not live** ⇒ **we do nothing**, the previous handler keeps answering (crafting from backpack material as before);
- keyed on the exact `CraftingMenu` class ＋ `minecraft:crafting`; **no protocol change, no save-format change, no change to server-side arbitration**;
- if a JEI update breaks the injection, we log one warning and the patch silently does nothing — the game will not crash.

**The button must not lie (preview v3, final rule)**: **clickable means it will work; if it cannot work, grey it and say what is missing.** A click **first consults the handler we displaced** (it may know sources we cannot see, e.g. a worn backpack) and then asks the server to top the remaining gap up from the cache. The **preview** is: ① if the displaced handler can do it, the button is usable; ② otherwise our own estimate (crafting grid + player's own slots + cache view, the same planner the server's fill uses) decides — **OK ⇒ usable**, **a miss we can name ⇒ grey with `Missing: Iron Plate ×2` in the tooltip** (plus a note that a worn backpack was not consulted, when another handler was displaced), **a miss we cannot name ⇒ released** (what cannot be named must not be refused); ③ `no space / unsupported recipe / too many variants` are provable here and now, so they grey with their own reason.
> Correction: an earlier build briefly released everything while the panel was live — that was **wrong**. The user's own log and save prove that client really had **no iron plates anywhere** (cache = spruce logs + iron ingots), so the refusal had been correct. This build restores "grey it and name what is missing whenever we can prove it".

**Failures are no longer silent**: a JEI recipe page covers the logistics panel, so the verdict is now spoken on the **action bar** — a failure states **our own reason** and, where possible, **names what is missing** (`Missing: Iron Plate ×2`); a success says nothing extra. The panel's own notice is unchanged. If the server still refuses after a click (a race), that same action-bar sentence is the fallback.

**Compatibility**: clean packs behave exactly like 0.2.1; the "unusable with FXNT Storage" note from the previous release is **fixed in 0.2.2**. If JEI's own UI is broken for unrelated reasons, the **logistics panel** and the **vanilla recipe book** remain available as supply paths.

**Verification**: JUnit 25/25; GameTest 155/155 (`-PwithMobile`); packaged smoke `developmentOutputs=0` with `0.2.2` in the mod list.