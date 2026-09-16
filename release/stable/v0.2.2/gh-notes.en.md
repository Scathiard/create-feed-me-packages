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