## 0.2.2

**Works together with the Create: Storage backpack now.**
With it installed, pressing "+" in JEI can pull materials from your own inventory, its worn backpack, and our logistics cache at the same time - items taken from the cache are really deducted.

**Fixed: one cache cell above 64 could disable that cell entirely.**
A cell holding more than a stack (say 65 iron ingots) became unusable; it now serves up to the normal stack size.

**Removed two things we should not have done.**
We used to take over JEI's "+" button and to touch the storage mod's internal state. Neither happens any more - we only read other mods, never change them.

**Known issue (not caused by us)**
With Create: Storage installed its "+" sometimes does not grey out right away when materials run out. That is its own view not refreshing - open its backpack screen once. Our own logistics panel and the vanilla recipe book are unaffected.

**Note**
Create: Storage 1.1.x is not supported yet (verified on 1.3.4 only).
