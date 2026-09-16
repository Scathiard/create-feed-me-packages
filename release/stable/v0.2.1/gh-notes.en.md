# 0.2.1

- **No more silent refilling of the crafting grid.** Taking a crafted result used to pull ingredients from the cache and quietly put them back into the grid, so a single shift-click could consume far more than the grid actually held. Taking the result now behaves like vanilla again: one craft, settled only from the materials really in the grid.

## Known issue

- In the **Mechanomania** modpack, JEI's recipe-transfer "+" does not take cached items into account, and our transfer handler is never called. The root cause is still undetermined; JEI version differences and our handler registration being overridden have both been ruled out. The vanilla recipe book and panel withdrawal are unaffected - this release's fix does not depend on the JEI path.
