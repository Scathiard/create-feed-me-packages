# Changelog

All notable changes to this project are documented here. The format is based on
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project adheres to
[semantic-ish versioning](https://semver.org/) with `MAJOR.MINOR.PATCH` where `MINOR` marks a
gameplay milestone.

本文件记录本项目的所有重要变更。格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，
版本号沿用 `MAJOR.MINOR.PATCH`，其中 `MINOR` 对应一次玩法里程碑。

## [0.2.3] — 2026-09-19

**Two things you can feel: the cache no longer shuffles when it grows, and a stack dropped anywhere on the panel lands by itself.** / **两处手感：扩容不再挪动已有格子；物品丢在面板任意处就能存。**

### Changed / 改动

- **Growing a cache adds rows and columns instead of re-flowing the cells / 扩容＝加行加列，不再顺序顺延**:
  each level now has its own rectangle (3x3, 4x4, 4x6, 5x6, 6x6) and **a cell that already existed keeps its row and column for good**; the old rule recomputed the columns from the cell count, so an upgrade moved 7 of 9 / 13 of 16 / 20 of 24 / 25 of 30 cells — the "everything jumped" you saw. Saves are untouched: an upgrade still appends empty cells at the end, so nothing migrates or re-orders.
  每一级有自己的矩形（3×3、4×4、4×6、5×6、6×6），**已经存在的格子永远待在原来的行/列**；旧规则按格数重算列数，所以升级会挪动 7/9、13/16、20/24、25/30 个格子。**存档未动**：升级仍只在末尾补空格。
- **Drop a stack anywhere on the panel / 物品丢在面板任意处即可存入**:
  a cache holds one cell per exact item, so the item itself picks the cell - the one already filtering it, else the first empty cell (which then gets that filter). Aiming at a specific cell, right-click for a single item, shift-take, the threshold dot and ctrl+right to clear a filter all behave exactly as before. If nothing can take the stack, the panel says so instead of staying silent.
  缓存一格只放一种精确物品，所以**物品自己挑格子**（已有该格⇒存进去；否则第一个空格⇒自动设过滤并存入）。对准某格的点击、右键单件、Shift 取料、阈值小圆点、Ctrl+右键清过滤**都照旧**；放不下时面板明确提示，不静默。

### Notes / 说明

- Cache schema stays **8** and the panel protocol stays **4**; the server keeps validating every deposit.
  缓存 schema 仍 **8**、面板协议仍 **4**；服务端照旧逐条校验存入。
- After updating, existing caches are drawn once in the new arrangement (the items stay in their cells); later upgrades no longer move anything.
  更新后既有缓存的排布会**按新规则画一次**（物品仍在原来的格子里），此后升级不再移动。
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

[0.2.3]: https://github.com/Scathiard/create-feed-me-packages/releases/tag/v0.2.3
[0.2.2]: https://github.com/Scathiard/create-feed-me-packages/releases/tag/v0.2.2
[0.2.1]: https://github.com/Scathiard/create-feed-me-packages/releases/tag/v0.2.1
[0.2.0]: https://github.com/Scathiard/create-feed-me-packages/releases/tag/v0.2.0
[0.1.1]: https://github.com/Scathiard/create-feed-me-packages/releases/tag/v0.1.1
[0.1.0]: https://github.com/Scathiard/create-feed-me-packages/releases/tag/v0.1.0
