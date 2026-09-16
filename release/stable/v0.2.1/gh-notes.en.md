# 0.2.1

- **No more silent refilling of the crafting grid.** Taking a crafted result used to pull ingredients from the cache and quietly put them back into the grid, so a single shift-click could consume far more than the grid actually held. Taking the result now behaves like vanilla again: one craft, settled only from the materials really in the grid.

## Known issue

- **With "Create: Storage" (`fxntstorage`) installed**, JEI's recipe-transfer "+" does not use items from the cache. The cause is now known: that mod unconditionally takes the single "crafting-table recipe transfer" registration slot (JEI allows one handler per slot, and the last registration wins), and it only looks at the backpack you are wearing - so the "+" on a crafting-table recipe stays **grey** with JEI's own "Missing Items" tooltip. **This is not caused by this mod, and it is not limited to it** - any mod that registers that slot earlier gets overridden. **Workaround:** the logistics panel and the vanilla recipe book still work (cached materials are still used).
