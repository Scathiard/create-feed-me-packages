English | [简体中文](README.md)

# Create: Feed Me Packages!

[![CurseForge](https://img.shields.io/badge/CurseForge-Download-F16436?logo=curseforge&logoColor=white)](https://www.curseforge.com/minecraft/mc-mods/create-feed-me-packages) [![Modrinth](https://img.shields.io/badge/Modrinth-Download-00AF5C?logo=modrinth&logoColor=white)](https://modrinth.com/mod/create-feed-me-packages)

Downloads: [GitHub Releases](https://github.com/Scathiard/create-feed-me-packages/releases)

## About

Your factory can produce building supplies automatically, but you still have to run home to collect them. **Create: Feed Me Packages!** saves you that trip!

Wear a Supply Chain Pendant, connect it to your factory's logistics network, and choose what to keep stocked. As you use supplies, the pendant requests replacements. Your packagers box them up, couriers bring them over, and the contents go into a cache beside your inventory. You can also draw from it to craft what you need on the spot.
You'll need an extra mod with remote delivery, unless your friends are willing to be your couriers.

The idea comes from personal logistics in *Dyson Sphere Program*. Here, we want keeping yourself supplied to be another job for the Create factory you've built. The mod runs on **Minecraft 1.21.1 / NeoForge** and requires **Create** and **Curios**.

## Where it helps

- Keep common blocks and machines on your restocking list when building away from base, with fewer trips back to storage.
- Use cached ingredients while expanding your factory, without sending a manual request through a portable terminal.
- Keep arrows and other consumables in the cache and let your factory replenish them, leaving inventory space for things you find along the way.
- Hand a pendant, its supplies, and its restocking settings to a friend.

## Features

### The Supply Chain Pendant

Supplies and settings travel with the pendant. Give it to a friend and they can pick up where you left off. Destroying it removes access to that cache.

You can wear one pendant at a time. Taking it off stops cache access, restocking, receiving, and automatic material use. Your stock and settings stay there for when you put it back on.

### A separate cache with automatic restocking

Equip a pendant and cache cells appear to the left of your inventory, in both Survival and Creative. Put an item into an empty cell to choose what goes there, then click the small dot in the corner to set how much to keep stocked.

For example, set a minimum of two stacks of logs. Whenever the cache falls below that amount, the pendant requests the difference from your network. Only cached stock counts toward this minimum, not items in your normal inventory.

Open **cell controls** using the corner dot to adjust restock and return thresholds and minimum batch sizes together. Scroll to adjust by one item, or Shift-scroll to the next whole stack; sliders gently snap near stack multiples. A minimum batch of 64 requests at least 64 even when only one item is missing, and waits if the network has too little stock. The center package shows outstanding items; clicking resets only this cell. Requests never expire automatically.

Player requests use the native `@playername` address. A delivered package enters the cache when every material matches an existing filter and each matching cell is below its return threshold and capacity. Manually sent matching packages qualify too. If even one material has no matching filter, the whole package is left for you to handle.

Anything that will not fit stays in a package until there is room. Existing full-inventory receiving hooks remain available, but some transports reject delivery or drop the package before reaching those hooks. In that case, free an inventory slot first.

Stored items keep data such as enchantments, with different variants kept in separate cells. Items that can't be saved safely are rejected, not silently turned into plain versions.

### Automatic returns

When you've finished building, put the spare materials back in the cache. Set how much you want to keep, a minimum return batch, and a return address. Eligible surplus gets packed for a paper courier, bee, phantom, or Allay to carry back.

Returns need the corresponding carrier item. Without a suitable carrier, or if dispatch cannot start, the materials stay in the cache. Flight, return trips, and carrier recovery follow each transport mod's own rules.

### Pocket Pallet and fluids

The **Pocket Pallet** brings cached supplies within reach. Select a material to place blocks, interact with the world, eat, drink, or use potions. You can also pour bucketed fluids or use fluids stored directly in the cache. Both the arc and grid menus support search, and you can change the selection keybind.

Caches can store fluids, receive FluidLogistics packages, and automatically request or return fluids by quantity. One fluid stack group holds 8 buckets. Using buckets, bottles, or other containers in the panel still follows the fluid's own container rules.

### A supply chain for maids

A maid wearing an ordinary Supply Chain Pendant can request supplies, return surplus, and use cached food and ammunition. Owners can manage her cache, filters, and logistics settings directly from the maid screen.

With automatic storage enabled, newly picked-up items matching existing filters enter her cache, where surplus can trigger returns. The switch does not collect items already in her backpack or equipment.

### Five levels of capacity

Your factory has a part in upgrades, too. Make Upgrade Links through Create sequenced assembly, then upgrade the pendant at a smithing table to carry more kinds of supplies and more of each.

| Level | Cache cells | Capacity per cell |
| --- | --- | --- |
| 1 | 9 | 2 stacks |
| 2 | 16 | 4 stacks |
| 3 | 24 | 8 stacks |
| 4 | 30 | 16 stacks |
| 5 | 36 | 32 stacks |

Stacks follow the item's own stack limit. A level 1 cell holds 128 logs, 32 ender pearls, or 2 milk buckets.

Modpack authors can configure cell counts, capacity, integration toggles, and admission lists. Stock exceeding reduced limits remains available for withdrawal; restoring the configuration restores normal access.

### Crafting and ammunition

You don't need to rummage through the cache whenever a recipe is short on ingredients. Vanilla 2×2 inventory crafting, 3×3 workbench crafting, the recipe book, and JEI or EMI recipe transfer can all use cached materials when your inventory doesn't have enough. Crafting upgrades in Sophisticated Backpacks and Sophisticated Storage can draw from the cache too. Each pendant has a panel toggle for crafting access.

Vanilla bows and crossbows, TaCZ, and Superb Warfare can draw cached ammo when your inventory runs out. Superb Warfare also supports vehicle weapons operated by a player; ammo loaded into a vehicle stays there. Construction Wand, Building Gadgets 2, and Effortless Building can use cached building materials. Support for other items and machines depends on their own integration.

The panel remembers whether you left it folded or open. Folding only hides the interface; logistics keep running. Collect All stores matching items from your inventory into existing cache filters, even without a bound network.

## Mod integrations

| Mod | Requirement | Integration |
| --- | --- | --- |
| **Create** | Required | Supplies the goods. The pendant binds to a logistics network, checks stock, and requests materials for your packagers to box up. Sequenced assembly also produces Upgrade Links. |
| **Curios API** | Required | Provides the equipment slot for the pendant. |
| **JEI / EMI** | Optional | Fill recipes from cached materials and select item or fluid filters through a keybind. |
| **Create More: Package Couriers** | Optional | Paper courier deliveries and active returns for players and maids. |
| **Create: Mobile Packages** | Optional | Bee deliveries and active returns for players and maids. |
| **Create: Phantom** | Optional | Phantom deliveries and active returns for players and maids. |
| **Create: Biotech** | Optional | Allay deliveries and active returns for players and maids. |
| **Create: Fluid Logistics** | Optional | Fluid package receipt, restocking, and returns. |
| **Create: Factory Logistics** | Optional | Ordinary-item request routing and package receipt. |
| **Touhou Little Maid** | Optional | Maid cache management, food and ammo use, and automatic storage of newly picked-up items. |
| **Sophisticated Backpacks / Sophisticated Storage** | Optional | Crafting upgrades draw missing materials from the cache. |
| **Create: Storage** | Optional | Crafting access across the worn backpack and cache, with JEI / EMI recipe transfer. |
| **Crafting Tweaks** | Optional | Workbench rotate, balance, and clear operations. |
| **TaCZ** | Optional | Cached ammo for either the official release or unofficial port, including per-round reloads. |
| **Superb Warfare** | Optional | Ammo for handheld guns and vehicle weapons operated by a player. |
| **Construction Wand / Building Gadgets 2 / Effortless Building** | Optional | Cached building materials. |

Your factory needs stock, and the packages need a way to reach you. Transport mods still need their own infrastructure, addresses, and delivery conditions. The pendant won't build those for you or teleport materials out of storage. Without these optional mods, you can still use the cache and core features, and receive packages that meet the receipt rules through other means. Or maybe you'd like to consider becoming a courier yourself?
