# Create: Feed Me Packages! 0.2.1（正式版）

- 版本：**`0.2.1`**（`gradle.properties` 的 `mod_version`、jar 文件名、包内 `neoforge.mods.toml` 的 `version` 三者逐字一致）
- 基线：已发布的 **0.2.0**
- 分支：`release/v0.2.1`（其 `src/` 与用户实机验收过的测试包 `0.2.1-test.62` 的源码 **逐字相同**，只差版本号）
- 用途：**正式发布**（推送／tag／GitHub Release 由队长执行）

## 包内文件

本目录共 **16 个文件**：**记录 8 份 ＋ 证据 6 份（3 条命令日志 ＋ 3 份 JUnit XML）**，另加**模组本体 jar** 与**源码快照 zip**。

| 文件 | 说明 |
| --- | --- |
| `create-feed-me-packages-0.2.1.jar` | 模组本体，**就在本目录**（**366,915 B**；SHA-256 见 `SHA256SUMS.txt`）。`.gitignore` 有意忽略 `release/**/*.jar`，故它不入库——但目录文件表与实际一致；构建产物同时留在共享构建目录 `.fmp-build-safe\libs\`（两处逐字节相同）。 |
| `v0.2.1-source.zip` | 源码快照，**就在本目录**（`git archive` 本分支 HEAD；哈希见 `SOURCE_SHA256SUMS.txt`）。 |
| `SHA256SUMS.txt` | jar 的 SHA-256 |
| `SOURCE_SHA256SUMS.txt` | 源码快照的 SHA-256 |
| `README.md` | 本文件 |
| `变更说明.md` | 0.2.1 相对 0.2.0 改了什么（含已知限度） |
| `依赖说明.md` | 运行前置与可选模组 |
| `测试清单.md` | **怎么验**（三步实机验法＋该看哪两行日志） |
| `验证记录.md` | 三条合同命令的实跑结果、条目差集、未覆盖项 |
| `gh-notes.md` | **中文 Release 正文草稿**（给队长发 Release 用） |
| 3 条命令日志 | `t69-test-build.log`、`t69-gametest.log`、`t69-packaged-server-smoke.log` |
| 3 份 JUnit XML | `TEST-…PanelLayoutTest.xml`、`TEST-…CacheStateTest.xml`、`TEST-…ReturnServiceTest.xml` |

## 安装

把 `create-feed-me-packages-0.2.1.jar` 放进 `mods/`，**删掉或停用旧的 `create-feed-me-packages-0.2.0.jar`**（同一 modId 不要并存）。依赖见《依赖说明.md》。

## 兼容

缓存存档格式 **schema 8**、面板网络协议 **`"4"`**；客户端与服务端使用同一版本。**比 0.2 更新的存档格式会被锁定以保护数据**（见《变更说明.md》的"缓存锁定会说人话"）。
