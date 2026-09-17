## 0.2.2 — JEI integration stays on the official API; the verdict is now visible

**What this version does**

- **JEI integration uses the official API only** (`IRecipeTransferRegistration`): **no mixin and no reflection targets JEI**, and **no handler registered by anyone else is replaced, vetoed or wrapped**.
- **The verdict is visible**: for a transfer started from JEI, the outcome is now also spoken on the **action bar** — a **failure always says our own reason** and, where possible, **names what is missing** (`Missing: Iron Plate ×2`); a **success stays silent** (the materials appearing is the message). The panel's own notice is unchanged.
- **Hover preview v3 — "the button must not lie"**: **clickable means it will work; if it cannot work, grey it and name what is missing; only what cannot be named is released** (`no space / unsupported recipe / too many variants` are provable here and now, so they grey with their own reason).

**Correction of the record (important, not glossed over)**: one 0.2.2 build **hijacked** JEI's handler lookup with a client-side mixin, replacing the handler that owned that slot whenever our panel was live. **The user forbade this** ("so this directly hijacks JEI's own +? This must not be done"), and **this build removes all of it**: the source, the mixin config and its `[[mixins]]` entry are gone, guarded by a unit test (the jar carries exactly one mixin config, ours, and it never mentions `mezz.jei`). **The earlier claim "fixed in 0.2.2: the grey + with Create: Storage installed" is withdrawn.**

**Known issue (not fixed here, and no longer attempted)**: **with "Create: Storage" (`fxntstorage`) installed**, clicking the "+" to craft from a recipe does not use items in the cache. The cause is known: that mod **unconditionally takes** the single "crafting-table recipe transfer" registration slot (JEI allows one handler per slot, last registration wins) and only looks at the backpack you are wearing, so the "+" shows **grey** with JEI's own **"Missing Items"** tooltip. **This is not caused by this mod, and it is not limited to it** — any mod registering that slot later behaves the same.

**The cost (stated plainly, now that the hijack is gone)**: in such a pack, JEI's "+" **is answered by that other mod, not by us** ⇒ **cache supply does not go through the "+"**. Use **our logistics panel** or the **vanilla recipe book** instead (cached materials are still used there).

**Verification**: JUnit 40/40 (including 3 guard tests for "JEI integration is official-API only"), GameTest 155/155 (`-PwithMobile`), packaged smoke `developmentOutputs=0` with `0.2.2` in the mod list.

## Same-day second cut: the whole "do JEI's job for it" layer is gone

The user's ruling: **"JEI does not pop up a sentence for you to read when ingredients are missing - it marks the missing ones red. There should not be a layer that does JEI's job for it."** So the entire layer is deleted: this mod **no longer registers any JEI recipe transfer handler** (`FmpRecipeTransfer` is gone, along with the plugin's registration method and field), together with the preview policy (`PreviewPolicy`) and the action-bar authoring layer (`ClientNotice` / `NoticeText`) and the three lang keys written only for them.

**The cost**: **in any pack, this mod no longer makes JEI's "+" use the cache** - the "+" is answered by JEI itself (or by whoever owns that slot in the pack). The cache is reached through **our own logistics panel** and the **vanilla recipe book**, both unchanged in capability.

**What stays**: the logistics panel (cache withdrawal + panel fill), the vanilla recipe book (it shows a recipe as craftable and the click really does take from the cache), the `MaterialHints` / `ClientMaterials` cache view, and the three JEI-plugin jobs that only make JEI work with our own screens (panel session, exclusion area, showing our smithing recipe). The guard test now also pins that this layer must not come back.
## Same-day follow-up: Create: Storage compatibility - their "+" can use our cache too

**The problem**: with Create: Storage (`fxntstorage`) installed, JEI's crafting "+" is answered by **them** (they register later and take the single `(CraftingMenu, CRAFTING)` slot), so **our cache cannot be used through the "+"**.

**What we do (no takeover, no replacement - we simply become a third source for their own code)**: two pinpoint injections inside **their own** JEI client preview and **their own** server transfer path replace, for the duration of that one call, the local variable holding the backpack item handler with a wrapper: their occupied slot => their stack, untouched; their **empty item slot** => a **copy of a cache stack**; on their write-back the amount actually taken (`presented - remaining`) is **debited from our real cache** through the existing ledger path - **their container is never written**. Deciding, placing and displaying all stay in **their** code: no JEI registration, no extra text, no button changes.

**Soft and degradable**: its own config with `required:false` + `defaultRequire:0` and three `@Pseudo` mixins. **With the mod absent there is zero impact**; if they move their internals it quietly degrades (one log line `FMP compat: degraded (...)`) - no crash, no free items. Evidence line: `FMP compat: exposed=<n> cache stacks to <their class>; served=<m> items`.

**Cost / fragility**: this compatibility depends on their internal shape, so **an update on their side can disable it** (in which case we fall back to today's behaviour).
## Follow-up fix (F-1): the injection type was wrong - corrected against their LVT and proven locally

The previous build declared its hook parameter as `ItemStackHandler`, while their real local/argument types are **`IItemHandlerModifiable`** (their preview and their placement) and **`IItemHandler`** (their max-count helper) - so Mixin matched nothing and silently skipped: not a single `FMP compat:` line appeared. The injection points are now pinned to what `javap -l` shows (server side injects on the **arguments** with `argsOnly`, client side on the local's STORE), and every point self-reports: `FMP compat: hooked <method>(<type>)` when it fires, plus `pass-through …` / `degraded …` for each silent branch. **Proven locally** with their 1.3.4 jar (copied into a temp dev run dir, removed afterwards): the Mixin apply line plus both `hooked` lines, and **161/161 tests green in both environments**.
## Follow-up ruling (2026-09-17): the mechanism may be stated from decompiled evidence - the known issue, as published

**Ruling**: the previous restriction is lifted. The mechanism **may** be claimed, but the wording must say **"per decompilation: ..."** and must also carry the user's control experiment. What still stands: **comparing our probe's two client-side copies can never decide which side is stale**.

### Known issue: with Create: Storage installed, the "+" state may lag

- **Symptom (the provable claim)**: with Create: Storage (`fxntstorage`) installed, JEI's crafting "+" **may lag** and needs **one UI toggle** to update; **disabling this mod entirely reproduces it, so it is unrelated to this mod**.
- **Attribution (their bug - Create: Storage's client-side view has no refresh path while the backpack is worn)**: **per decompilation** of their **1.3.4** (all **439** classes walked with `javap -p -c`):
  1. **No automatic invalidation point exists while the backpack is worn** - `invalidateWornBackpack` has exactly **two** call sites: `EventHandler.onPlayerTick` (**and only when no backpack is worn**) and `BackpackContainer.setChanged()` (whose only caller is the **server-side** `UpgradeHelper.toggleWornUpgrade`).
  2. **Reusing the cached container never re-reads its contents on the client** - the reuse branch only calls `setContext`, which **returns immediately when `level().isClientSide`** (only the server calls `readInventory`).
  3. **The only caller of `loadItemsFromStack` is their own constructor.**
  4. Their inner handler (`BackpackContainer$1`) **does not override `onContentsChanged`**, so container changes trigger no invalidation either.
  => That client-side view is refreshed **only by opening their backpack screen once** (menu slot sync), and it is exactly the view their JEI preview reads (`getEquippedBackpackStack` -> `getOrCreateWornBackpack` -> `getItemHandler`).
- **Citable evidence**: (1) the **user's control run** - **disabling this mod entirely reproduces it** (that run's `create-feed-me-packages-0.2.2.jar.disabled` is **byte-identical** to this build: 391,975 B / `dd61204f57f4cea439e1404da2b527db978433af8f8376042c87b5955552e177`); (2) our log line `FMP compat: their preview returned=null missing=0`; (3) our probe sampling `their container fresh=true stale=0 slots=156` **51**/54 (`stale=1` **3**/54) - **only to show that two client-side copies cannot reveal the problem, not as attribution evidence**.
- **Workaround**: **toggle their backpack screen once**; **prefer our logistics panel or the vanilla recipe book** - both read authoritative data and are **unaffected** by that bug.
- **Our boundaries**: we never refresh their state for them (the F-11 ban), and no new entity is added; the F-10 probe has been removed entirely.

**Same version, same bytes**: this is a wording addition made before publication; the jar is **untouched** (391,975 B / `dd61204f57f4cea439e1404da2b527db978433af8f8376042c87b5955552e177`).
