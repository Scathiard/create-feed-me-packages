# Changelog

All notable changes to this project are documented here. The format is based on
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project adheres to
[semantic-ish versioning](https://semver.org/) with `MAJOR.MINOR.PATCH` where `MINOR` marks a
gameplay milestone.

本文件记录本项目的所有重要变更。格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，
版本号沿用 `MAJOR.MINOR.PATCH`，其中 `MINOR` 对应一次玩法里程碑。

## [0.3.0] - 2026-11-13

### English

#### Added

- **Pocket Pallet**: Use supplies directly from your cache without moving them into your inventory first. Hold the selection keybind (middle mouse button by default) with the Pocket Pallet in hand to open the arc or grid menu (with search and view switching), release to confirm, and right-click to place blocks, eat food, drink potions, or pour cached fluids.
- **Fluid Caches**: Cache cells can now store fluids (8 buckets per stack group). Left- or right-click a fluid cell with buckets, bottles, or other fluid containers to deposit or withdraw fluids directly, following each fluid's standard container rules.
- **Mod Integrations**:
  - **Touhou Little Maid**: Maids can equip a Supply Chain Pendant to request refills, return surplus items, and consume cached food, arrows, and gun ammo directly. Their cache is managed on the left side of the maid GUI; when Auto-Store is enabled, newly picked-up items matching existing filters are stored into the cache automatically.
  - **Create: Fluid Logistics**: Receive fluid packages into the cache, request fluid refills, and return excess fluids automatically.
  - **Create: Phantom**: Support Phantom couriers for player and maid deliveries and returns.
  - **Create: Biotech**: Support Allay couriers for player and maid deliveries and returns.
  - **Create More: Package Couriers**: Added maid delivery and return support, plus `@playername` address compatibility for older versions.
  - **Create: Mobile Packages**: Added maid delivery and return support, with compatibility across multiple versions of its range-check interface.
  - **Create: Factory Logistics**: Support factory logistics request routing and package receipt.
  - **EMI**: Support recipe filling from cached materials and selecting items or fluids from EMI via keybind to set cache filters.
  - **JEI**: Added keybind support (middle mouse button by default) for setting cache filters from JEI items or fluids, and expanded recipe transfer coverage.
  - **Sophisticated Backpacks**: Support drawing cached materials when using the crafting upgrade inside backpacks.
  - **Sophisticated Storage**: Support drawing cached materials when using the crafting upgrade inside storage containers.
  - **Create: Storage**: Added crafting material support when used alongside JEI or EMI.
  - **Crafting Tweaks**: Compatible with workbench rotate, balance, and clear buttons.
  - **TaCZ**: Support drawing ammo from the cache on both the official 1.21.1 release and the unofficial port.
  - **Superb Warfare**: Support drawing ammo from the cache for handheld guns and player-operated vehicle weapons.
  - **Construction Wand**: Support consuming building blocks directly from the cache.
  - **Building Gadgets 2**: Support consuming building blocks directly from the cache.
  - **Effortless Building**: Support consuming building blocks directly from the cache.

#### Improved

- **Cell Settings Panel**: Click the corner dot on a cell to configure its restock threshold, return threshold, minimum restock batch, and minimum return batch in one panel. Supports slider dragging (with stack-multiple snapping) and scroll adjustment (Scroll ±1, Shift + Scroll ±1 stack). Click the center package icon to clear pending requests for that cell.
- **Updated Package Receipt & Addressing**: Restock requests now use the `@playername` address; return addresses can be customized. Package receipt now automatically unpacks any package addressed to the player whose contents all match existing cache filters, provided the target cells are not full and remain below their return thresholds; matching packages sent manually are also accepted.
- **Panel Controls**:
  - Added a **Crafting Access toggle** at the bottom of the panel to enable or disable crafting access per pendant.
  - The panel remembers its folded or unfolded state, and adds a pendant level display in the top-right corner along with cell visual effects on transfers.

#### Changed

- **Removed the Personal Pendant**: Existing personal cache stock is transferred once and losslessly to an ordinary Supply Chain Pendant upon entering the world. If the player's inventory and Curios slots are full, the transfer waits for a free slot without clearing stock.
- **Server Configuration**: Cell counts, stack capacities for Levels 1–5, mod integration toggles, and item/fluid allowlists and denylists are now configurable in `serverconfig`. Stock exceeding reduced capacity limits is kept read-only and can still be withdrawn.

**Please back up existing worlds before upgrading. Create and Curios API are required; all integrated mods above are optional.**

### 简体中文

#### 新增

- **拣货夹（Pocket Pallet）**：直接调用缓存中的物资，无需先取回背包。手持拣货夹按住快捷键（默认鼠标中键）打开圆弧或网格菜单（支持搜索与视图切换），松开确认选择后，右键即可放置方块、进食、饮用药水，或直接倒出缓存中的流体。
- **流体缓存**：缓存格现可存放流体（每组容量为 8 桶）。支持手持空桶、玻璃瓶等流体容器在面板中直接存取流体，容器规则与对应流体本身一致。
- **模组联动**：
  - **Touhou Little Maid（车万女仆）**：女仆可佩戴供应链坠，自动请求补货、退回多余物资，并在进食或战斗时直接取用缓存中的食物、箭矢与枪械弹药。可在女仆界面左侧直接管理其缓存；开启「自动入库」后，女仆新拾取且匹配已有过滤的物品会自动存入缓存。
  - **Create: Fluid Logistics（机械动力：流体物流）**：支持接收流体包裹入库，并按设定数量自动请求流体补货或打包退回多余流体。
  - **Create: Phantom（机械动力：幻翼物流）**：支持幻翼快递员向玩家与女仆配送补货包裹及运回退货。
  - **Create: Biotech（机械动力：生物科技）**：支持悦灵快递员向玩家与女仆配送补货包裹及运回退货。
  - **Create More: Package Couriers（纸飞机）**：新增女仆补货配送与退货发运支持，并兼容旧版玩家地址解析。
  - **Create: Mobile Packages（运输蜂）**：新增女仆补货配送与退货发运支持，并兼容多个版本的距离检查接口。
  - **Create: Factory Logistics（机械动力：工厂物流）**：兼容工厂物流的补货请求与拆包收件。
  - **EMI**：支持配方填充在背包缺料时从缓存补齐，支持通过快捷键从 EMI 列表选取物品或流体设置缓存过滤。
  - **JEI**：新增通过快捷键（默认中键）从 JEI 列表选取物品或流体设置缓存过滤，补齐多处配方转移入口的缓存取料。
  - **Sophisticated Backpacks（精妙背包）**：支持背包内的合成升级在背包缺料时从缓存取料。
  - **Sophisticated Storage（精妙存储）**：支持存储容器内的合成升级在缺料时从缓存取料。
  - **Create: Storage（机械动力：存储）**：补充与 JEI、EMI 同时启用时的合成取料支持。
  - **Crafting Tweaks**：兼容工作台旋转、平衡与清空操作。
  - **TaCZ（永恒枪械工坊）**：同时支持 1.21.1 官方版与非官方移植版从缓存取用弹药。
  - **Superb Warfare（卓越前线）**：支持手持枪械与玩家实际操作的载具武器在背包缺弹时从缓存取用弹药。
  - **Construction Wand（建筑魔杖）**：支持直接从缓存消耗建造方块。
  - **Building Gadgets 2（建造小帮手 2）**：支持直接从缓存消耗建造方块。
  - **Effortless Building（简易建造）**：支持直接从缓存消耗建造方块。

#### 改进

- **单格数量设置面板**：点开缓存格角落的小点，可集中调整补货下限、退货上限、单次最少补货量与单次最少退货量。支持滑条拖动（在整组倍数处吸附）与滚轮调整（滚轮 ±1，按住 Shift ±1 组）；点击中央包裹图标可手动清零该格的在途记录。
- **收件规则与地址更新**：补货请求改为使用 `@玩家名` 地址，退货地址可自定义。包裹入库规则改为寄给玩家的包裹只要所有内容物均匹配已有缓存过滤，且对应缓存格未满、未达退货上限，即可自动拆包入库；手动寄出的同类包裹同样适用。
- **面板操作**：
  - 面板底部新增**合成取用开关**，可按坠子单独开启或关闭缓存参与合成。
  - 面板记住上次展开或收起状态，新增右上角链坠等级显示与收发货时的格子光效。

#### 调整

- **移除私人供应链坠**：旧存档中的个人缓存会在玩家进入世界后一次性无损转移至普通供应链坠；若背包与饰品栏已满，会等待腾出空位后再发放，不清空库存。
- **新增服务端配置**：可在存档 `serverconfig` 中配置 1～5 级坠子的格数、单格组数上限、各模组联动开关及物品/流体存入黑白名单。调低容量配置后，超出新上限的原有库存保留为只读状态，仍可正常取出。

**升级旧存档前请先备份。必需前置仍为 Create 与 Curios API，其余联动模组按需安装。**

## [0.2.3] — 2026-09-20

**One-key collect and a panel you can fold away.**
**一键收取、面板可完全收起。**

### Added / 新增

- **One-key collect / 一键收取** (the square on the left of the panel's bottom border): moves everything in your
  inventory that already belongs to a cache cell into it, up to that cell's capacity; anything above your return
  limit is handled by the existing return settings.
  **一键收取**（面板下边框左侧方块）：把背包里已对应到缓存格的物品一次收进去，收到该格容量为止；超出退货上限的部分按原有的退货设置处理。
- **The panel folds away completely / 面板完全收起** (the square at the bottom right): folding leaves one small
  entry in the same place, and **that area goes back to JEI's bookmarks**; click the entry and the panel comes
  back, cell for cell identical to before.
  **面板完全收起**（右下角方块）：收起后只在原位留一个小入口，**那片区域交还给 JEI 收藏夹**；再点入口面板回来，与收起前逐格一致。

### Changed / 改进

- **Cells no longer move when you upgrade the cache / 升级缓存后格子位置固定**: an older save re-arranges once the
  first time you open the panel (nothing is lost and no counts change; a level-1 item does not change), and no
  later upgrade ever moves anything.
  **升级缓存后格子位置固定**：旧存档第一次打开会重排一次（东西不丢、数量不变，1 级物品无变化），此后升级永不动位。
- **Drop an item anywhere on the panel and it stores itself / 丢在面板任意空白处即可存入**; one-key collect also
  works in **creative mode**.
  Both mouse buttons deposit (a right click moves one item); a click that lands on a cell stays there when that cell
  already filters this item or is empty with no other cell claiming the item, and otherwise falls back to the item's
  own cell and then to the first empty cell - only then does it say there is no room.
  **丢在面板任意空白处即可存入**；**创造模式**下也能一键收取。
  **左右键都能存入（右键 1 件）；点在某格上时——该格若正是该物品的过滤格就存那里，空落点格且别处无人占也存那里，否则回落到该物品自己的格、再回落第一个空格，仍无处可放才提示。**

## [0.2.2] — 2026-09-17

**Compatibility with Create: Storage, plus a cache-cell fix.**
**兼容「机械动力：存储」，并修复缓存格的问题。**

### Added / 新增

- **Create: Storage compatibility / 兼容机械动力：存储**: with that mod installed, crafting can take materials
  from your own inventory and from its worn backpack at the same time.
  装了它的时候，合成可以同时从你自己的背包和它背着的背包抓取材料。

### Fixed / 修复

- **A cache cell holding more than one stack could not be used for crafting / 缓存物品数量大于一组时无法参与合成**:
  a cell holding more than 64 items (say 65 iron ingots) was unusable; it now works up to the normal stack size.
  某一格超过一组（比如 65 个铁锭）时无法参与合成；现在按单堆上限正常工作。

### Known issue / 已知问题

- With Create: Storage installed, when items from its worn backpack take part in crafting and run out, JEI's state
  does not refresh — open its backpack screen again. This is not this mod's problem; please do not file an issue
  for it.
  机械动力：存储中的背包物品参与合成且被耗尽时，JEI 状态不会刷新，需要重新开关背包。这不是本模组的问题，请不要为此提 issue。
- Create: Storage 1.1.x is not supported yet (verified on 1.3.4 only). / Create: Storage 1.1.x 暂不支持（只在 1.3.4 上验证过）。

## [0.2.1] — 2026-09-16

This is a **patch release on the 0.2 line**: no new gameplay, only fixes that were verified by hand
before release. Its source is byte-for-byte the tree the user accepted as the `0.2.1-test.62`
package — the only difference is the version number.

本版是 **0.2 线的补丁版**：不含新玩法，只有发布前经手工实机验收的修复。源码与用户验收的
`0.2.1-test.62` 测试包**逐字相同**，唯一差别是版本号。

### Fixed / 修复

- **No more silent restocking of the crafting grid / 不再暗中续填合成格**: taking a crafted result
  used to pull material out of the cache and put it back into the grid, so one shift-click could
  consume far more than the grid really held. Taking a result is back to vanilla behaviour: only what
  the grid really contains is consumed, exactly once.
  以前取一次成品会从缓存取料并暗中填回合成格，一次 Shift 点击可能消耗远超网格实际材料的量。现在
  取成品回到原版行为：**只按网格里真实存在的材料结算一次**。
- **Honest wording when the cache cannot help / 缓存帮不上忙时如实说明**: the JEI transfer path no
  longer reports a generic "missing items" when the client simply has no cache material view. Each
  outcome — panel not ready, hints not active, unbound pendant, stale session, genuinely insufficient
  material — now has its own message in both languages.
  JEI 转移路径不再在"客户端没有缓存材料视图"时含糊地报"缺少物品"：**面板未就绪／提示未生效／坠子
  未绑定网络／会话过期／材料确实不足**各自有独立中英文案。
- **The cache lock explains itself / 缓存锁定会说人话**: when a world's cache data was written by a
  newer version and this build refuses it, the lock keeps the original data untouched **and** tells
  the player why — action bar on joining the world (and about every five seconds while the pendant is
  worn), plus the logistics panel's status tooltip.
  当存档的缓存数据由更新版本写过、本版拒读时，缓存会被**锁定以保护原数据**（数据不丢），并且**在
  进世界时、以及佩戴期间每约 5 秒用动作栏**＋**面板状态提示**把原因与处置办法说清。

### Known limitation / 已知限度

- **JEI transfer in a heavy modpack / 重度整合包里的 JEI 转移**: in large packs the JEI "+" button
  can stay greyed out for crafting-table recipes and the transfer handler is never asked. The cause is
  still open — a JEI version difference has been ruled out **by a static check only** (19.51 was
  compared class by class along the transfer chain against 19.39.0.369; the two instances actually
  played both ran **19.39.0.369**, so this is not a comparison of two in-game runs), as has a
  handler-registration collision. The vanilla recipe book and the panel's own taking are unaffected.
  **重度整合包里 JEI「+」可能对工作台配方一直是灰的、处理器不会被询问**；原因尚未定论——已排除
  **JEI 版本差异**，但**只凭静态核对**（19.51 是对转移链逐类比对的；**现场两个实例实测加载的都是
  `19.39.0.369`**，所以这不是两次实机运行的对照），也排除了**处理器注册被覆盖**。原版配方书与
  面板取物不受影响。

### Compatibility / 兼容

- Cache schema **8**; panel network protocol **4**. Use the same version on client and server.
  缓存 schema 为 **8**，面板网络协议为 **4**；客户端与服务端请使用同一版本。

## [0.2.0] — 2026-09-09

### Added / 新增

- **Automatic returns / 自动退货**: each cache can store a default return address. When a cell
  exceeds its configured maximum, the surplus is packed and dispatched by a paper plane or a
  robo-bee. The cache and the carrier are debited only after the carrier really accepts the
  package; a failed dispatch changes nothing.
  每个缓存可设默认退货地址；某格超过最高值时，超出部分自动打包并交给纸飞机或运输蜂；只有载体真实受理后才扣缓存与载体，失败不扣。
- **Take-reserve / 取物预留**: taking from a cache cell first shows a preview on the cursor.
  The cache is debited only when the items are really placed; putting them back is net zero, and
  closing the screen or removing the pendant cancels an unplaced preview and leaves the items in
  the cache.
  从缓存格取出时先在光标上预览，真正放下才扣缓存；放回净零；关闭界面或卸下饰品会取消未落位预览并留在缓存。
- **Uniform stack-group capacity / 统一组容量**: level 1–5 cells hold 2 / 4 / 8 / 16 / 32 stacks,
  converted with each item's own stack size. The slider works in stacks, with a lower handle
  (restock) and an upper handle (return; fully right = no return).
  1—5 级单格容量为 2／4／8／16／32 组，按物品自身堆叠上限换算；滑条以“组”为刻度，下端点设最低值（补货）、上端点设最高值（退货，最右为不退货）。
- **Full-inventory receiving / 满背包收件**: dedicated parcels still enter the cache when the
  player inventory and hotbar are full. Anything that does not fit stays in a per-cell residual
  package with a small green dot, and is absorbed automatically when space frees up.
  背包与快捷栏全满时专用包裹仍能进入缓存；装不下的部分按物品格保留为残包（左下角绿点提示），有空间时自动续收。

### Changed / 变更

- Return-arrow colour now reflects the real dispatch result: red only after a carrier accepted the
  surplus while the cell is still over its maximum, grey otherwise, and it disappears once the
  cell is back at its maximum.
  退货箭头颜色改为反映真实发运结果：仅在载体受理且仍超额时为红色，其余为灰色，退到最高值后消失。
- Cache schema is **8** (per-cache return address); panel network protocol is **4**. Use the same
  version on client and server.
  缓存 schema 为 **8**（每缓存默认退货地址）；面板网络协议为 **4**，客户端与服务端请使用同一版本。

### Fixed / 修复

- Creative-mode store/take no longer duplicates items.
- Held-item icon no longer renders offset from the cursor.
- Panel, slider and tooltip layering corrected.
- Slider endpoints no longer overshoot the track by one pixel; dragging follows the cursor, and
  pressing an endpoint without moving no longer rewrites the value.
  修复创造模式存取复制、手持图标错位、面板／滑条／提示层级、滑条端点超出轨道 1px，以及按住端点不动却改数值。

## [0.1.1] — 2026-09-07

### Added / 新增

- **Ownerless personal pendant / 无主私人定坠**: a personal pendant without an owner is claimed by
  its first wearer and from then on belongs to them. Pendants that already have an owner still
  degrade to a fresh ordinary pendant for other players.
  无主私人定坠由第一个佩戴者认领并归其所有；已有物主的坠子对其他玩家仍退化为全新普通坠。

## [0.1.0] — 2026-09-07

### Added / 新增

- First local playable release: supply-chain cache with pendant binding, real Create restock
  requests, package receiving with residuals, crafting / JEI / standard-projectile material
  drawing, five-level growth and owner-locked personal caches.
  首个本地可玩版本：坠子绑定缓存、真实 Create 补货请求、含残包的收件、合成／JEI／标准投射物取料、五级成长与物主锁定的个人缓存。

[0.3.0]: https://github.com/Scathiard/create-feed-me-packages/releases/tag/v0.3.0
[0.2.3]: https://github.com/Scathiard/create-feed-me-packages/releases/tag/v0.2.3
[0.2.2]: https://github.com/Scathiard/create-feed-me-packages/releases/tag/v0.2.2
[0.2.1]: https://github.com/Scathiard/create-feed-me-packages/releases/tag/v0.2.1
[0.2.0]: https://github.com/Scathiard/create-feed-me-packages/releases/tag/v0.2.0
[0.1.1]: https://github.com/Scathiard/create-feed-me-packages/releases/tag/v0.1.1
[0.1.0]: https://github.com/Scathiard/create-feed-me-packages/releases/tag/v0.1.0
