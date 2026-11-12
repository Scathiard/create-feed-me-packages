English | [简体中文](README.md)

# Create: Feed Me Packages!

[![CurseForge](https://img.shields.io/badge/CurseForge-Download-F16436?logo=curseforge&logoColor=white)](https://www.curseforge.com/minecraft/mc-mods/create-feed-me-packages) [![Modrinth](https://img.shields.io/badge/Modrinth-Download-00AF5C?logo=modrinth&logoColor=white)](https://modrinth.com/mod/create-feed-me-packages)

Downloads: [GitHub Releases](https://github.com/Scathiard/create-feed-me-packages/releases)

## About

Your factory can produce building supplies automatically, but you still have to run home to collect them. **Create: Feed Me Packages!** saves you that trip!

Wear a Supply Chain Pendant, connect it to your factory's logistics network, and choose what to keep stocked. As you use supplies, the pendant requests replacements. Your packagers box them up, couriers bring them over, and the contents go into a cache beside your inventory. You can also draw from it to craft what you need on the spot.
You'll need an extra mod with remote delivery, unless your friends are willing to be your couriers.

The idea comes from logistics bots in *Dyson Sphere Program*. Here, we want automatic resupply to be another job for the Create factory you've built. The mod runs on **Minecraft 1.21.1 / NeoForge** and requires **Create** and **Curios**.

## Where it helps

- Keep common blocks and machines on your restocking list when building away from base, with fewer trips back to storage.
- Use cached ingredients while expanding your factory, without sending a manual request through a portable terminal.
- Keep arrows, food, and other consumables in the cache and let your factory replenish them, leaving inventory space for things you find along the way.
- Hand a pendant, its supplies, and its restocking settings to a friend.

## Features

### The Supply Chain Pendant

Supplies and settings travel with the pendant. Give it to a friend and they can pick up where you left off. Destroying it removes access to that cache.

You can wear one pendant at a time. Taking it off stops cache access, restocking, receiving, and automatic material use. Your stock and settings stay there for when you put it back on.

### A separate cache with automatic restocking

Equip a pendant and cache cells appear to the left of your inventory. Put an item into an empty cell, or press the shortcut key (Middle Mouse Button by default) over an item or fluid in JEI or EMI, to choose what goes there; then click the small dot in the corner to set how much to keep stocked.

If you want to keep two stacks of logs stocked, set the restock minimum to two stacks. Whenever the cache falls below that amount, the pendant requests the difference from your network. Only cached stock counts toward this minimum, not items in your normal inventory.

Open **cell controls** using the corner dot to configure the restock minimum, return maximum, minimum restock batch size, and minimum return batch size in one place. Both slider dragging (snapping at whole-stack multiples) and mouse wheel adjustment (scroll ±1, hold Shift ±1 stack) are supported. If you set the minimum restock batch size to 64, it requests at least 64 even when only 1 item is missing, waiting if the network has less in stock. The center package icon shows pending items; clicking it resets pending packages, and pending counts never expire automatically.

Restock requests default to the `@playername` address. Any package addressed to the player whose contents all match existing cache filters—while the target cells are not full and below their return limits—is unpacked straight into the cache without emptying it into your inventory first; manually sent matching packages work the same way. Anything that doesn't fit yet stays in the package until room is freed.

Stored items keep data such as enchantments, with different variants kept in separate cells. Items that can't be saved safely are rejected, not silently turned into plain versions. **Collect All** on the panel pulls inventory items matching existing filters into the cache even when no network is bound; folding the panel only hides the UI without pausing background logistics.

### Automatic returns

When you've finished building, put the spare materials back in the cache. Set how much you want to keep, a minimum return batch size, and a return address. Once the surplus meets those conditions, it gets packed for a paper plane, robo-bee, phantom courier, or allay courier to carry back.

Returns require the corresponding courier carrier. Without a suitable carrier, or if dispatch cannot start, the materials stay in the cache without being dispatched.

### Pocket Pallet and fluid cache

Hold the **Pocket Pallet** to use supplies straight from the cache without pulling them into your inventory first. Hold the shortcut key (Middle Mouse Button by default) to open the arc or grid menu (with search and view switching), release to confirm your selection, and right-click to place blocks, eat food, drink potions, or pour fluids directly from the cache.

Cache cells can also store fluids directly (8 buckets per stack group). You can fill or drain fluids directly in the panel using fluid containers such as empty buckets or glass bottles, following the fluid's own container rules. With Create: Fluid Logistics installed, the cache can also receive fluid packages and automatically request fluid refills or pack and return surplus fluids by quantity.

### Touhou Little Maid personal logistics

Maids from Touhou Little Maid can wear a Supply Chain Pendant to request refills, return surplus supplies, and consume food, arrows, and gun ammo directly from the cache while eating or fighting.

You can manage a maid's cache and logistics settings directly on the left side of the maid GUI. When **Auto-Store** is enabled, items newly picked up by the maid that match existing filters are stored into the cache automatically, and any surplus over the return maximum is packed for automatic return.

### Five levels of capacity

Your factory has a part in upgrades, too. Make Upgrade Links through Create sequenced assembly, then upgrade the pendant at a smithing table to carry more kinds of supplies and more of each.

| Level | Cache cells | Capacity per cell |
| --- | --- | --- |
| 1 | 9 | 2 stacks |
| 2 | 16 | 4 stacks |
| 3 | 24 | 8 stacks |
| 4 | 30 | 16 stacks |
| 5 | 36 | 32 stacks |

Stacks follow the item's own stack limit (or 8 buckets per stack group for fluids). A level 1 cell holds 128 logs, 32 ender pearls, 2 milk buckets, or 16 buckets of pure fluid.

Cell counts, per-cell stack limits for levels 1–5, mod integration toggles, and item/fluid allowlists and denylists can be configured in the world's `serverconfig`. When capacity limits are lowered in config, existing stock exceeding the new limit is kept in a read-only state and can still be withdrawn normally.

### Crafting, ammunition, and building

You don't need to rummage through the cache whenever a recipe is short on ingredients. Vanilla 2×2 inventory crafting, 3×3 workbench crafting, the recipe book, JEI and EMI recipe transfer, crafting upgrades in Sophisticated Backpacks and Sophisticated Storage, and Create: Storage backpack crafting can all draw missing ingredients from the cache when your inventory doesn't have enough. A **Crafting Access Toggle** at the bottom of the panel lets you enable or disable cache crafting access per pendant.

Vanilla bows and crossbows, TaCZ (both official and unofficial port builds), and Superb Warfare (both handheld guns and vehicle weapons actively operated by the player) can draw ammo from the cache when your inventory runs out. Construction Wand, Building Gadgets 2, and Effortless Building can also consume building blocks directly from the cache. Your inventory is always used first, and the cache supplies the shortfall.

## Mod integrations

| Mod | Requirement | Integration |
| --- | --- | --- |
| **Create** | Required | Supplies the goods. The pendant binds to a logistics network, checks stock, and requests materials for your packagers to box up. Sequenced assembly also produces Upgrade Links. |
| **Curios API** | Required | Provides the equipment slot for the pendant. |
| **Create More: Package Couriers** | Optional | Delivers restocking packages to players and maids and carries return packages back using paper planes. |
| **Create: Mobile Packages** | Optional | Delivers restocking packages to players and maids and carries return packages back using robo-bees. |
| **Create: Phantom** | Optional | Delivers restocking packages to players and maids and carries return packages back using phantom couriers. |
| **Create: Biotech** | Optional | Delivers restocking packages to players and maids and carries return packages back using allay couriers. |
| **Create: Fluid Logistics** | Optional | Receives fluid packages into the cache and automatically requests fluid refills or packs and returns surplus fluids by quantity. |
| **Create: Factory Logistics** | Optional | Supports ordinary-item restock requests and package unpacking with Factory Logistics networks. |
| **Touhou Little Maid** | Optional | Maids wearing a pendant automatically restock and return supplies, consume cached food and ammo, and auto-store newly picked-up matching items. |
| **JEI** | Optional | Fills missing recipe ingredients from the cache and sets item or fluid cache filters via shortcut key (Middle Mouse Button by default). |
| **EMI** | Optional | Fills missing recipe ingredients from the cache and sets item or fluid cache filters via shortcut key (Middle Mouse Button by default). |
| **Sophisticated Backpacks** | Optional | Crafting upgrades inside backpacks draw missing ingredients from the cache. |
| **Sophisticated Storage** | Optional | Crafting upgrades inside storage containers draw missing ingredients from the cache. |
| **Create: Storage** | Optional | When a backpack is worn, crafting and JEI / EMI recipe transfer can draw from both the backpack and the cache. |
| **Crafting Tweaks** | Optional | Supports workbench rotate, balance, and clear operations. |
| **TaCZ** | Optional | Draws ammo from the cache when the inventory runs out on both official and unofficial 1.21.1 port builds (including per-round reload guns). |
| **Superb Warfare** | Optional | Draws ammo from the cache for handheld guns and vehicle weapons actively operated by the player when the inventory runs out. |
| **Construction Wand** | Optional | Consumes building blocks directly from the cache. |
| **Building Gadgets 2** | Optional | Consumes building blocks directly from the cache. |
| **Effortless Building** | Optional | Consumes building blocks directly from the cache. |

Your factory needs stock, and the packages need a way to reach you. Paper planes, robo-bees, phantoms, and allays still need their own infrastructure, addresses, and delivery conditions. The pendant won't build those for you or teleport materials out of storage. Without these optional mods, you can still use the cache and core features, and receive packages that meet the receipt rules through other means. Or maybe you'd like to consider becoming a courier yourself?
