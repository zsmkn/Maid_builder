# Maid Builder 1.20.1（Forge）— 移植 1.21.1 v1.2.0 計畫

> 現況：1.20.1 Forge 版 **1.1.0 已完成並 commit**（`210fb76` release 1.1.0，分支 `1.20.1`，47 個 GameTest）。1.1.0 移植的紀錄已移到 [docs/port-1.1.0/plan-1.20.1.md](docs/port-1.1.0/plan-1.20.1.md)。
> 目標：把 1.21.1 版 `06cb298`（release 1.1.0）→ `3d1b661`（release 1.2.0 + 需玩家處理）的全部內容移植過來，玩家體驗、指令、按鍵、語言 key、設定項、NBT／存檔欄位、資料包格式都和 1.21.1 一致；產出 `maidbuilder-release_1.2.0_forge1.20.1.jar`。
> 基本 API 對照沿用 [docs/port-1.0.0-plan.md](docs/port-1.0.0-plan.md) §3 與 [docs/port-1.1.0/plan-1.20.1.md](docs/port-1.1.0/plan-1.20.1.md) §3、§6（事件、網路、SavedData、GUI、渲染的 1.20.1 寫法都已在那裡定案），**本檔只寫 1.2.0 新增的部分**。

## ▶ 一句話啟動

在本資料夾對 Claude Code 說：**「依照 plan.md 把 1.2.0 移植到 1.20.1」**。

Claude 從 §4 第一個未打勾的 Phase 開始依序做到最後，**中途不需要詢問使用者**；每個 Phase 完成就在本檔打勾、在 §6 記錄。只有真正無法自行決定的事（API 完全不存在且沒有等價做法、需要新增依賴）才停下來問。§7 的實機項目最後列給使用者。

### 工作規則（同 CLAUDE.md）

- **不 commit、不開 branch、不打 tag**；全部做完後 `git reset -q`，讓改動回到未暫存（`git apply -3` 會把檔案加進 index）。
- `git fetch` 進來的 1.21.1 物件只存在 `FETCH_HEAD`，不要建立分支或 tag。
- `refs/` 只讀；不複製 Litematica／Forgematica／Structurize／TLM 程式碼；移植本專案自己的 1.21.1 程式碼可以。
- GameTest 不得為了通過而放寬斷言；1.20.1 行為真的不同時，在測試旁註解原因並記在 §6。
- 不確定 API 時查 `forge/build/moddev/artifacts/forge-1.20.1-47.4.26-sources.jar` 與 `refs/TouhouLittleMaid-1.20.1/`，不憑記憶。

---

## 0. 移植來源（都在 `docs/port-1.2.0/`，不需要再去 1.21.1 資料夾）

| 檔案 | 內容 |
|---|---|
| `v1.1.0-to-v1.2.0.patch` | 1.21.1 `06cb298` → `3d1b661` 的 `core/`、`neoforge/`、`tools/` 差異（含二進位），路徑已改寫成 `forge/`。core 沒有變動。 |
| `1.21.1-commits.bundle` | 1.21.1 `292e740..3d1b661` 的 git 物件，讓 patch 可以 3-way 套用（本 repo 沒有 `06cb298`） |
| `uncommitted-2026-10-28.patch` | 1.21.1 在 `3d1b661` 之上**尚未 commit、尚未編譯**的改動（食堂卡住修正，見 §1.5），Phase G 才用 |
| `fine_templates/*_fine.litematic` | 11 個精緻版模板（暫緩功能，沒有任何模板 JSON 引用），Phase G 才用 |
| `1.21.1-summary_2026-10-28.md` | 1.21.1 這段工作的總結（含已知問題、待驗收） |
| `1.21.1-plan.md`、`1.21.1-plan_done.md`（§11–§14）、`1.21.1-plan_gameplay.md`、`1.21.1-plan_acceptance.md` | 1.21.1 的計畫、詳細設計、領地玩法、實機驗收順序 |
| `1.21.1-README.md`、`README-diff-v1.1.0-to-v1.2.0.diff` | 1.21.1 使用說明與 1.1.0 → 1.2.0 的 README 差異 |
| `1.21.1-changelog_zh.md`、`_en.md` | 1.2.0 changelog（功能清單即驗收範圍） |

### 試套結果（2026-11-04 在暫存 clone 實測）

```bash
git fetch docs/port-1.2.0/1.21.1-commits.bundle main   # 之後 git cat-file -e 06cb298 成功
git apply -3 docs/port-1.2.0/v1.1.0-to-v1.2.0.patch
```

新增 32 檔、自動合併 21 檔、**衝突 5 檔共 6 處**：`MaidBuilderConfig`(1)、`common/maid/BuilderTeardownTask`(1)、`gametest/MaidBuilderGameTests`(1)、`network/ModNetwork`(1)、`network/TerritoryPayloads`(2)。

衝突原則：**保留 1.20.1 這側的 API 寫法，加入 1.2.0 這側的新邏輯**。自動合併與新增的檔案仍是 1.21.1 API，要逐一改寫（§3）。

試套後仍含 1.21.1 API 的檔案（grep `fromNamespaceAndPath|StreamCodec|getFirst(|getLast(|net.neoforged|ModConfigSpec|...`）：`JobProblemsScreen`、`ProblemHighlights`、`ShopScreen`、`TravelScreen`、`TerritorySelfTest`、`BuilderTeardownTask`、`JobProblems`、`ServiceBuildings`、`ShopActions`、`TravelActions`、`MaidBuilderGameTests`、`ServiceGameTests`、`TerritoryGameTests`、`MaidBuilderConfig`、`ModNetwork`、`TerritoryPayloads`。

---

## 1. 1.2.0 的內容（全部都要移植）

詳細行為以 `docs/port-1.2.0/1.21.1-plan_done.md` §11–§14 為準。

1. **取料繞路**（`c5794a2`，§11）：`ReachPlanner.planWalk`（地面 A*，上 1 下 3、木門可過，上限 16384 格）、`RouteFollower`（每 20 步一個中繼點交給原版走）、`BuilderFetchMaterialTask` 走不到時改用規劃路線、在空中不算失敗、`MAX_RUN_TICKS` 1200。
2. **服務建築**（`61fa9c5`，§12）：新分類 `service`；食堂（Lv2）、村民集會所（Lv2）、驛站（Lv3，流浪商人＋羊駝、製圖台傳送 `TravelActions`／`TravelScreen`）、瞭望塔（Lv3，發光、襲擊通知）、神社（Lv4，TLM 好感度）；`ServiceBuildings`、`TerritoryBonuses` 擴充、`RegisteredBuilding.nextEvent`（NBT `NextEvent`）；Lv5 需 2 種服務建築（`level_5.json`）；設定 `territory.services.*`。順帶修：`ReachPlanner.pathTo` 拒絕以工作鷹架當梯子、拆鷹架用 `RouteFollower`、`BuildJob.recordWait`（支撐未蓋好先等）。
3. **建材行**（併入 1.2.0，§13）：`builders_shop` 模板、店員女僕（`touhou_little_maid:idle`，12 格內）、`ShopCatalog`（重新載入監聽，資料包 `maidbuilder/shop/*.json` 7 個貨架）、`ShopActions`／`ShopScreen`（分類、搜尋、缺料分頁、訂購缺料）、`RegisteredBuilding` 的 `Sold`／`SoldDay`、`#maidbuilder:not_for_sale`、`tools/shop_shelves.py`。
4. **需玩家處理**（`3d1b661`，§14）：有 NEEDS_PLAYER 的工作留在佇列、`BuildJob.recheckNeedsPlayer` 每秒重查、`BuildJob.Problem` 原因（NBT `Problems`）、`JobProblems` + `TerritoryPayloads.JobProblems/ProblemRow`、動作 `JOB_PROBLEMS`／`JOB_RETRY`／`JOB_SKIP`、`JobProblemsScreen`、`ProblemHighlights`（隔牆紅框 60 秒）。
5. **（Phase G，視 1.21.1 狀態）食堂卡住修正**：`ReachPlanner.columnAt` 判定在鷹架上要更嚴格、`planWalk` 可站樓梯／半磚並穿過鷹架、取料連續 2 次走不到就放掉認領並隨機走開再試；`TemplateGenerator` 的精緻版模板。1.21.1 端**尚未編譯、未測試**。
6. 其他：6 個新模板 `.litematic`＋JSON、三語語言檔、`TemplateGenerator` 新模板、`mod_version=1.2.0`、`TerritorySelfTest` 新截圖（服務分頁、`maidbuilder_selftest_territory_shop.png`、`maidbuilder_selftest_territory_problems.png`）。

---

## 2. 1.20.1 版已有、合併時必須保留的東西

（完整清單見 docs/port-1.1.0/plan-1.20.1.md §2、§6；衝突與自動合併後都要確認還在）

- `ReachPlanner.findStand` 不回傳「目前所在但實際搆不到」的格子 —— `ReachPlanner` 這次自動合併，Phase G 的 patch 在這裡會衝突，要特別確認。
- `MaidBuilderGameTests`：`KNOWN_VANILLA_QUIRKS`、`newerBlockNamesResolve`、`jobDataRoundTrip`、`payloadsRoundTrip`；`TerritoryGameTests`：`territoryDataResolves`、`spawnCheck` 讀 `getResult()`、`TerritoryData.load`、`#forge:crops`／`#forge:seeds`。
- 模板 `products` 的 `forge:` 標籤與生肉／生魚物品 id（1.20.1 唯一允許的資料差異）。
- 網路：`encode/decode(FriendlyByteBuf)`、`Payloads.writeList/readList`、新封包**附加在 `ModNetwork.register()` 最後**（舊 id 不變）、`DistExecutor`、`ModNetwork.sendToAll`。
- 渲染：`GhostPreview.viewPose(event)`、`AFTER_LEVEL` 重建相機旋轉、共用 `BufferBuilder`、`ShaderCompat`。
- 設定畫面 `client/config/`（`ConfigEntry.isEnum()`、`Section.subsections`、領地頁與加成頁）。
- `TerritorySelfTest` 建造模式期間清空主手、俯角 15°；`ModItemData`、`MaterialContainers.handler`。

---

## 3. 1.2.0 新程式的 1.21.1 → 1.20.1 對照（補充前兩份）

### 3.1 已確認存在（TLM 1.20.1 原始碼／原版）

| 1.21.1 | 1.20.1 |
|---|---|
| TLM `maid.getFavorabilityManager().apply(Type)`、`favorability.Type(name, point, cooldown)` | 同名（`refs/TouhouLittleMaid-1.20.1/.../entity/favorability/Type.java`，有 `SLEEP = new Type("Sleep", 2, 24000)`）。`apply` 簽名以原始碼為準 |
| `EntityType.X.spawn(ServerLevel, BlockPos, MobSpawnType)`、`WanderingTrader`、`TraderLlama`、`ServerLevel.getRaidAt`、`MobEffects.GLOWING` | 1.20.1 都有（`WanderingTrader.setDespawnDelay`、羊駝 `setLeashedTo` 以 sources 確認） |
| `ServerPlayer.teleportTo(ServerLevel, x, y, z, yRot, xRot)`（跨維度） | 1.20.1 同名同參數 |
| `PlayerInteractEvent.RightClickBlock`（製圖台、切石機攔截） | 同名，`MinecraftForge.EVENT_BUS`；取消用 `setCanceled(true)` + `setCancellationResult(InteractionResult.SUCCESS)`（1.21.1 若用 `setCanceled` 以外的寫法照 sources 改） |
| `ServerTickEvent.Post`（`ServiceBuildings.onServerTick`） | `TickEvent.ServerTickEvent`，`phase == END`（比照 `TerritoryBonuses`） |
| `RenderLevelStageEvent`、`RenderType.create(..., CompositeState)`（`ProblemHighlights` 隔牆線） | 1.20.1 都有；`RenderType.create` 與 `CompositeState.builder()` 的可見性、`NO_DEPTH_TEST`／`LINES` shader 名稱照 sources；若不能直接呼叫就寫成 `extends RenderType` 的內部類取用 protected 常數（不要用 AT） |
| `Codec`／`RecordCodecBuilder`／`optionalFieldOf`（`ShopCatalog`） | DFU 在 1.20.1 一樣；JSON 解析用 `JSON.parse(JsonOps.INSTANCE, ...)`，錯誤處理照 1.21.1 |
| `BuiltInRegistries.ITEM.getTag/getHolder` | 1.20.1 有（`getTag` 回傳 `Optional<HolderSet.Named<Item>>`）；`ShopCatalog` 第一次查詢才展開標籤，標籤要在資料包載入後才可用，照原設計 |

### 3.2 需要逐一改寫的寫法

- **網路**（`TerritoryPayloads` 新增 `TravelDestination`、`TravelMenu`、`Travel`、`ShopRow`、`MissingRow`、`ShopMenu`、`ShopBuy`、`ProblemRow`、`JobProblems`，及 `TerritoryAction` 新動作）：比照 1.1.0 已移植的 `TerritoryPayloads` 改成 `encode/decode`；清單上限照 1.21.1 的數字。
  - `ProblemRow` 的 `BlockState`：用 `Block.getId(state)` / `Block.stateById(id)`（1.21.1 若用 `ByteBufCodecs.idMapper(Block.BLOCK_STATE_REGISTRY)` 即等價）。
  - `Optional<ResourceLocation>`（`ShopBuy.item`）：`writeOptional/readOptional` 或 boolean + RL。
  - enum（`MissingStatus`、`BuildJob.Problem`）：`writeEnum/readEnum`。
  - `ModNetwork`：5 個新封包（`Travel`、`ShopBuy` 送伺服器；`ShopMenu`、`JobProblems`、`TravelMenu` 送客戶端）加在 `register()` 最後；客戶端開畫面走 `ClientPayloadHandler` + `DistExecutor`。
  - `payloadsRoundTrip` 加入全部新封包（含 200 格的 `JobProblems`、7 個貨架份量的 `ShopMenu`，確認編碼大小在上限內）。
- **設定**（`MaidBuilderConfig` 衝突）：`ModConfigSpec` → `ForgeConfigSpec`；新增 `territory.services`（`tradingPostTraders`、`tradingPostInterval`、`tradingPostStay`、`travel`、`travelCostItem`、`travelCostCount`、`villageHallVillagers`、`villageHallInterval`、`villageHallCap`、`watchtower`、`watchtowerRadius`、`shrineFavorability`、`canteenRegen`、`shop` 等，以 patch 為準）。
- **設定畫面**（`client/config/`）：領地頁加一列「Services...」子頁（比照 1.1.0 的「Level bonuses...」），顯示全部 `services` 項；`travelCostItem` 是字串設定 —— 確認 `ConfigEntry` 支援字串（不支援就補 `EditBox`）。名稱與 tooltip 用 1.21.1 的 `maidbuilder.configuration.*` key，缺的三語補上。
- **SavedData**：`RegisteredBuilding` 的 `NextEvent`、`Sold`、`SoldDay`，`BuildJob` 的 `Problems` 照 1.21.1 NBT 名稱；舊存檔（沒有這些欄位）要讀得進來。`jobDataRoundTrip` 加入 `Problems`；領地存讀測試加入 `NextEvent`／`Sold`。
- **GUI**（`ShopScreen`、`TravelScreen`、`JobProblemsScreen`、`TerritoryScreen`／`TemplateCatalogScreen` 新分頁）：`renderBackground(graphics)` 單參數、`mouseScrolled(x, y, delta)`、列表建構子 `(mc, width, height, top, bottom, itemHeight)` 並關掉泥土背景、`EditBox`（搜尋）的 1.20.1 寫法，比照 1.1.0 已移植的畫面。
- **Java 17**：grep `getFirst|getLast|removeFirst|removeLast|addFirst|addLast|reversed()`（`List.reversed()` 是 Java 21；`Comparator.reversed()` 可以）、`switch` 模式比對、record pattern、`SequencedCollection`。
- `ResourceLocation.fromNamespaceAndPath/withDefaultNamespace/parse` → `new ResourceLocation(...)`；`setTame`、`getDefaultMaxStackSize` 等同 1.1.0 §6。

### 3.3 資料包與資源（1.20.1 特有，最容易漏）

- **`tags/item/` → `tags/items/`**：patch 新增的 `data/maidbuilder/tags/item/not_for_sale.json` 要搬到 `tags/items/`（1.20.1 的 `tags/blocks/` 已是複數）。搬完確認 `tags/item/` 不存在。
- **建材行貨架裡 1.21 才有的物品**：`04_brick.json` 有 `chiseled_tuff`、`chiseled_tuff_bricks`、`polished_tuff*`、`tuff_slab/stairs/wall`、`tuff_brick*`、`tuff_bricks`（1.20.1 只有 `tuff`）。`ShopCatalog.unknownEntries()` 會把它們列出來，`shopCatalogLoads` 斷言為空 → **從 1.20.1 的貨架 JSON 刪除這些項**（不可放寬斷言），並把 `tools/shop_shelves.py` 同步（加註 1.20.1 不存在）；其他貨架也用 `shopCatalogLoads` 找出所有 1.21 物品一併刪除。這是第二個允許的資料差異，記在 §6，並記下 1.20.1 實際的商品種類數（1.21.1 是 328 種）。
- 貨架與模板若用到 `c:` 標籤，改成 `forge:` 或原版物品 id（試套時 grep 只有 `#forge:*` 與 `#minecraft:*`，Phase A 再確認一次）。`#minecraft:` 標籤（`wooden_doors`、`fence_gates`、`small_flowers`、`saplings`、`leaves` 等）在 1.20.1 都有，但確認沒有 1.21 新增的原版標籤。
- **6 個新模板 `.litematic`**（`builders_shop`、`canteen`、`shrine`、`trading_post`、`village_hall`、`watchtower`）：用 `territoryDataResolves` 確認 `unknownBlocks` 為空（17 個模板）、`workplace.task` 存在（`builders_shop` 用 `touhou_little_maid:idle` —— 在 TLM 1.20.1 確認 `TaskIdle.UID`）。
- `territory_levels/level_5.json` 的服務建築條件格式照 1.21.1。
- 語言檔自動合併；確認 1.20.1 自己的 key（`maidbuilder.configuration.edit/range/readonly.*`）還在，三語 JSON 合法。

---

## 4. 移植階段與驗收

每個 Phase 完成就在這裡打勾並在 §6 寫紀錄。不開 branch、不 commit。

### Phase A — 套用 patch 與資源整理
1. `git status` 除了 `plan.md`、`CLAUDE.md`、`docs/port-1.1.0/plan-1.20.1.md`（舊 plan 搬來的）、`docs/port-1.2.0/` 之外是乾淨的。
2. `git fetch docs/port-1.2.0/1.21.1-commits.bundle main`，`git cat-file -e 06cb298` 成功。
3. `git apply -3 docs/port-1.2.0/v1.1.0-to-v1.2.0.patch`（預期結果見 §0）。
4. `gradle.properties`：`mod_version=1.2.0`。
5. 依 §3.3 搬 `tags/item/`、處理貨架的 1.21 物品與標籤。
- [x] 驗收：沒有殘留衝突標記（`git diff --check`、grep `^<<<<<<<|^>>>>>>>`），資源路徑正確。

### Phase B — core
- 1.21.1 這段沒有改 core。
- [x] 驗收：`./gradlew :core:test` 全過，數量仍是 39。

### Phase C — forge 編譯通過
- 解 5 個衝突檔，再依 §3 改寫新增的 32 檔與自動合併的 21 檔。順序建議：`network/` → `MaidBuilderConfig` → `common/job`（`BuildJob.Problem`）→ `common/territory`（`ShopCatalog`、`ShopActions`、`TravelActions`、`ServiceBuildings`、`JobProblems`、`TerritoryBonuses`、`TerritoryTicker`）→ `common/maid`（`RouteFollower`、`ReachPlanner`、`BuilderFetchMaterialTask`、`BuilderTeardownTask`、`BuilderPlaceTask`、`BuilderScaffoldTask`、`BuilderFindTargetTask`）→ `client/` → `gametest/`。
- `MaidBuilder`：`AddReloadListenerEvent` 加 `ShopCatalog`；`ServiceBuildings`、`TravelActions`、`ShopActions` 的事件註冊在 `MinecraftForge.EVENT_BUS`。
- 依 §2 逐項確認 1.20.1 的修正都還在。
- [x] 驗收：`./gradlew build` 成功；grep 不到 `net.neoforged`（`build.gradle` 的 plugin id 除外）、`fromNamespaceAndPath`、`withDefaultNamespace`、`ResourceLocation.parse`、`StreamCodec`、`CustomPacketPayload`、`ModConfigSpec`、`DataComponents`、`HolderLookup`、`getFirst`／`getLast`。

### Phase D — GameTest
- 全部 GameTest：1.20.1 原有 47 個 + 1.2.0 新增 19 個 = **66 個**（名單 §5）。
- 擴充 1.20.1 專屬測試：`territoryDataResolves` 涵蓋 17 個模板、`not_for_sale` 標籤非空、`shop/*.json` 7 個貨架都載入；`jobDataRoundTrip` 加 `Problems`；`payloadsRoundTrip` 加全部新封包。
- `ServiceGameTests`、新的 `maidFetches*` 都是獨立 batch、平台超出結構會強制載入區塊 —— 1.20.1 的 GameTest 排列不同，若互相干擾用清理／batch 解決，不放寬斷言。
- 1.21.1 已知偶發：`villageHallOneMaid` 在 37 項同批並行時偶發逾時（131/366 後無 log，原因未明）、`maidsHealAtLevel` 約 0.1%。1.20.1 也發生的話記在 §6，可重跑；**若 1.20.1 能穩定重現**，查出原因（很可能就是 §1.5 的食堂類卡住），修法要同時回報給 1.21.1。
- [x] 驗收：`./gradlew :forge:runGameTestServer` **連跑 3 次**全過（上述已知偶發除外，需記錄）；BENCH 填入 §5，與 1.21.1 差距 ≤10%。

### Phase E — 客戶端與設定畫面
- 設定畫面：領地頁「Services...」子頁（§3.2）。
- 渲染：`ProblemHighlights` 隔牆紅框（一般與 `AFTER_LEVEL`／光影下）。
- `runClientSelfTest`：`ClientSelfTest` + `TerritorySelfTest` 的所有截圖，新增的：模板目錄「服務」分頁、`maidbuilder_selftest_territory_shop.png`、`maidbuilder_selftest_territory_problems.png`（以 1.21.1 `TerritorySelfTest` 實際存的檔名為準），設定畫面新子頁另存 `config7`。
- `runTemplateShowcase`：17 個模板各兩張。
- `runClientShaderTest -PshaderTest=true`：紅框在光影下正確。
- `runClientNetTest`（先 `runServer`）：原流程 PASS；可行的話加「開建材行 `ShopMenu` → 買一組」與「`JobProblems` 收到詳情」。
- [x] 驗收：**Claude 用 Read 逐張看截圖**，與 1.21.1 的外觀一致（建材行分頁、搜尋框、詳情列表、傳送畫面、中文不溢出）；網路測試 PASS；專用伺服器啟動不崩潰。

### Phase F — 收尾
- `./gradlew clean build` → `forge/build/libs/` 只有 `maidbuilder-release_1.2.0_forge1.20.1.jar`（reobf）。
- 乾淨環境（同 1.1.0：Forge 1.20.1-47.4.26 伺服器 + TLM 1.5.3 + 發佈 jar）：啟動、`Loaded 5 territory levels`、`Loaded 17 building templates`、建材行貨架載入訊息正確，沒有標籤／配方／模板錯誤；`/maidbuilder territory list` 正常。
- 文件：`README.md` 依 `README-diff-v1.1.0-to-v1.2.0.diff` 加入服務建築、建材行、取料繞路、需玩家處理，保留 1.20.1 差異段並補上貨架差異；`curseforge/changelog_zh.md`／`_en.md` 改寫成 1.2.0 Forge 1.20.1 版（需求列 Minecraft 1.20.1、Forge 47.2.0+、TLM 1.5.3+；1.21.1 的 changelog 沒寫「需玩家處理」，Forge 版要補上，因為 `3d1b661` 也包含在內）；`CLAUDE.md` 的版本與 GameTest 數量。
- `git reset -q`，`git status` 列給使用者看。
- [x] 驗收：Phase A–F 打勾、§5 數字填好、§6 有紀錄、§7 列給使用者。

### Phase G —（條件式）1.21.1 未 commit 的食堂卡住修正與精緻版模板
先到 1.21.1 repo 看狀態（`git -C C:/Users/USER/Desktop/code/Maid_builder log --oneline -5` 與 `status`）：
- **已在 1.21.1 commit 並測過**：用 `git fetch C:/Users/USER/Desktop/code/Maid_builder main` 取那個 commit，`git diff --binary 3d1b661 <commit>`（路徑改 `forge/`）3-way 套用，照 Phase C–D 的方式驗證；版本號依 1.21.1。
- **仍未 commit**：用 `docs/port-1.2.0/uncommitted-2026-10-28.patch`（`BuilderFetchMaterialTask`、`ReachPlanner`、`TemplateGenerator`）。試套時 `ReachPlanner` 會衝突（1.20.1 的 `findStand` 修正），保留 `findStand` 修正。套完必須編譯、66 個 GameTest 連跑 3 次全過、BENCH 不變慢；最好把食堂重現（女僕在室內鷹架柱底、門口台階是樓梯）加成 GameTest，並把結果回報給使用者讓 1.21.1 同步。**若 GameTest 失敗或 BENCH 變慢超過 10%，整段還原**（`git checkout` 這 3 個檔），記在 §6，不要在 1.20.1 自己發明不同的修法。
- 精緻版模板：把 `fine_templates/*.litematic` 複製到 `forge/src/main/resources/data/maidbuilder/maidbuilder/structures/`（沒有模板 JSON 引用，不影響遊戲；只為與 1.21.1 工作區一致）。用 `TemplateGenerator` 以 1.20.1 core 重產時位元組應相同。
- [x] 驗收：§6 記錄採用了哪一種、測試結果。（結論：試套後 GameTest 失敗 → 整段還原，見 §6 Phase G）

---

## 5. GameTest 清單與效能基準

**1.20.1 v1.1.0 原有 47 個**：見 docs/port-1.1.0/plan-1.20.1.md §5。

**1.2.0 新增 19 個**：
- `MaidBuilderGameTests`：`maidFetchesOverLowWall`、`maidFetchesAroundHouse`、`maidFetchesAroundBigHouse`、`canteenOneMaid`、`villageHallOneMaid`、`tradingPostOneMaid`、`watchtowerOneMaid`、`shrineOneMaid`、`buildersShopOneMaid`
- `ServiceGameTests`：`tradingPostTraderComes`、`travelBetweenTradingPosts`、`villageHallVillagersMoveIn`、`watchtowerMakesMonstersGlow`、`canteenHealsMaids`、`shrineRaisesFavorability`、`shopCatalogLoads`、`shopSellsWithClerk`、`shopOrdersMissingMaterials`
- `TerritoryGameTests`：`blocksLeftForPlayerStayQueued`

（所在檔案以 patch 為準。）共 **66 個**。

| GameTest（tick，log 搜尋 `BENCH`） | 1.21.1 | 1.20.1 v1.1.0 | 1.20.1 v1.2.0 |
|---|---|---|---|
| `hallOneMaid` | 1892 | 1839 | 1839 |
| `hallThreeMaids` | 583 | 591 | 591 |
| `towerOneMaidWithScaffolding` | 1457 | 1454 | 1454 |
| `towerThreeMaidsWithScaffolding` | 574 | 578 | 578 |
| `tallPillar` | 404 | 405 | 405 |
| `tallTowerOneMaid` | 3088 | 3082 | 3082 |
| `tallTowerThreeMaids` | 1112 | 1116 | 1116 |
| `wideTowerOneMaid`（下來次數） | 3945（6 次） | 3929（6 次） | 3929（6 次） |
| `dormitoryOneMaid` | 約 2000～2300 | 平均 2216 | 2007～2112 |
| `maidFetchesOverLowWall` | 48 | — | 47 |
| `maidFetchesAroundHouse` | 122 | — | 123 |
| `maidFetchesAroundBigHouse` | 約 195 | — | 194 |
| `tradingPostOneMaid` | 約 1700 | — | 1806；女僕卡在柵欄邊走開重試時 2446、2849 |
| `canteenOneMaid` | 約 2100 | — | 2119～2309 |
| `watchtowerOneMaid` | 約 2650 | — | 2637～2749 |
| `shrineOneMaid` | 約 3000 | — | 2919～3273 |
| `villageHallOneMaid` | 約 3500～3850 | — | 3236～3767 |
| `buildersShopOneMaid` | 約 1500 | — | 1440～1875 |

取料繞路「只在走不到時才規劃」，所以 1.1.0 的舊基準數字應該完全不變；若變了要查原因。

---

## 6. 移植紀錄（做的時候填）

- 1.20.1 與 1.21.1 行為／資料不同之處、查到的 API 結論、測試穩定性問題，格式比照 docs/port-1.1.0/plan-1.20.1.md §6。

### Phase A
- 套用結果與 §0 試套一致：新增 32 檔、自動合併 21 檔、衝突 5 檔 6 處。`mod_version=1.2.0`。
- `tags/item/not_for_sale.json` → `tags/items/`（`git mv`），`tags/item/` 已刪除。
- 貨架對照 1.20.1 原版物品表（client jar 的 `models/item/`）：只有 `04_brick` 的 13 個凝灰岩衍生物（`tuff_stairs/slab/wall`、`polished_tuff*`、`tuff_brick*`、`tuff_bricks`、`chiseled_tuff*`）不存在 → 刪除，只留 `tuff`；`03_nature` 的 `short_grass` 在 1.20.1 叫 `grass` → **改名**（同一物品，不是刪除）。`tools/shop_shelves.py` 同步修改並加註；重產後其他 5 個貨架與 patch 位元組相同。
- 貨架／新模板只用 `#forge:*` 與 1.20.1 已有的 `#minecraft:*` 標籤，沒有 `c:`。
- 這是 §3.3 的第二個允許資料差異。商品種類數見 Phase D（log `SHOP`）。

### Phase B
- core 未變動，`:core:test` 39 個全過。

### Phase C
- 衝突：`MaidBuilderConfig` 取 1.2.0 欄位並改 `ForgeConfigSpec`；`BuilderTeardownTask` 取 1.2.0 繞路邏輯，`getFirst()` → `get(0)`；`MaidBuilderGameTests.listenToBreaks` 保留 Forge 事件、加入 `watchBreaksIn` 過濾；`ModNetwork` 保留 1.20.1 寫法，5 個新封包附加在 `register()` 最後（`Travel`、`ShopBuy` → 伺服器；`ShopMenu`、`JobProblems`、`TravelMenu` → 客戶端走 `DistExecutor`）；`TerritoryPayloads` 新 record 全改 `encode/decode`，清單上限照 1.21.1（1024／8192／1024），`BlockState` 用 `Block.getId/stateById`，`Optional<RL>` 用 `writeOptional`，enum 用 ordinal + `floorMod`（與既有封包一致）。
- `ServiceBuildings.onServerTick`：`TickEvent.ServerTickEvent` + `phase == END`。
- `ProblemHighlights`：1.20.1 的 `RenderStateShard` 常數與 `LineStateShard` 是 protected → 放在 `extends RenderType` 的巢狀類裡用公開的 7 參數 `RenderType.create`（沒有用 AT）；相機改用 `GhostPreview.viewPose(event)`（同 `TerritoryBorderRenderer`），不再操作 `Matrix4fStack`。
- 新畫面（`ShopScreen`、`TravelScreen`、`JobProblemsScreen`）：列表建構子改 `(top, bottom)` 並關掉泥土背景、`render` 先 `renderBackground(graphics)`，同 1.1.0。
- `ShopCatalog`：`ITEM.getHolder(ResourceKey)`（1.20.1 沒有 `getHolder(ResourceLocation)`）。
- GameTest：1.20.1 的 `GameTestHelper.getBounds()` 是 private → `MaidBuilderGameTests.structureBounds(helper)` 用公開的 `forEveryBlockInStructure` 算出結構範圍；方塊容器用 `MaterialContainers.handler`；`EVENT_BUS.post(...)` 在 1.20.1 直接回傳 boolean（是否取消）。
- §2 的 1.20.1 修正都還在（`findStand` 的 `feet.equals(here)`、`KNOWN_VANILLA_QUIRKS` 等）。
- 1.20.1 專屬測試擴充：`jobDataRoundTrip` 加 `Problems`（含沒有 `Problems` 的舊存檔 → `UNKNOWN`）；`payloadsRoundTrip` 加全部新封包、200 列 `JobProblems`、整個商品目錄份量的 `ShopMenu`（並確認 ≤ 1048576 bytes）；`territoryDataResolves` 涵蓋 17 個模板（排除 `test/` 模板避免並行干擾）、`not_for_sale` 非空、7 個貨架分類、`unknownEntries` 為空、level 5 需要 2 個 service、`RegisteredBuilding` 的 `NextEvent`／`Sold`／`SoldDay` 存讀與舊存檔。
### Phase D
- 最終：66 個 GameTest **連跑 3 次全過**（`gt27`～`gt29`）；1.1.0 的 BENCH 與 v1.1.0 完全相同（§5）。`territoryDataResolves` log：`SHOP 475 goods on 7 shelves`（1.20.1 實際商品種類數 475；1.21.1 記錄的 328 種與此計法不同，貨架只刪了 13 個凝灰岩類、改名 1 個）。
- **測試環境（主要原因）**：`runGameTestServer` 原本和 `runServer` 共用 `forge/run/`，`server.properties` 的 `level-name=networld` 讓 GameTest 直接用那個世界 —— 它是 peaceful（`watchtowerMakesMonstersGlow` 的苦力怕一生成就消失，必失敗），而且每次失敗的測試都把 BuildJob／領地存在 `maidbuilder_jobs.dat`，綁著舊工作的女僕留在 entities 裡，下次同一個測試又放在同一個座標 → 舊女僕和舊工作在新測試區域裡動手，造成「女僕不再有任何 log」的停工（`shrineOneMaid` 卡 20/320、`buildersShopOneMaid` 卡 13/234 等），越跑越糟（連 1.1.0 的 `towerThreeMaidsWithScaffolding` 都失敗）。
  - 改法（`forge/build.gradle`）：gameTestServer 用自己的資料夾 `runs/gametest/`；第一次把 `run/networld` 複製成 `runs/gametest/world`；**每次執行前刪掉 `entities/`、`data/maidbuilder_jobs.dat`、`maidbuilder_territories.dat`、TLM 的世界資料與 `maid_backups`**（地形保留）。
  - 試過讓 GameTest 伺服器自己建新世界：新世界的區塊光照來不及（`greenhouseCropsGrowFaster` 0 vs 0、`hallOneMaid` 卡 169/273、`workplaceFarmPauseDepositResume`、`maidFetchesAroundBigHouse` 穩定失敗，與時間、難度無關），所以沿用 networld 的複本。
  - `watchtowerMakesMonstersGlow`：世界是 peaceful → 測試開頭暫時 `setDifficulty(NORMAL)`、結束改回（各 batch 依序執行，不影響別的測試）；斷言不變。
- **1.20.1 實際重現、已修的 1.2.0 女僕卡住（要回報給 1.21.1）**：
  1. `BuilderScaffoldTask.descend`：女僕站在鷹架頂端、身體一部分壓在旁邊的牆上（瞭望塔 x=9.88，牆在 x=10）→ 潛行也掉不下去，DESCEND 121 tick 逾時 → WORK → DESCEND 無限循環（`watchtowerOneMaid` 181/232）。修法：還站著時若身體不完全在柱子那格內，先往柱子中心推（`insideCell`），站穩在中間再往下。
  2. `BuilderFindTargetTask`：驛站的女僕卡在柵欄排的缺口（146.5, 24.075），每 ~64 tick 重新認領同一格、設同一個站位、原版 `MoveToTargetSink` 走不動又清掉 WALK_TARGET，`BuilderPlaceTask` 的「走不到」保護沒觸發（觀察到 `TARGET_POS` 不在），永遠不計失敗（`tradingPostOneMaid` 178/238）。修法：同一格從同一個方塊連續出發 ≥200 tick → 記一次失敗（UNREACHABLE）、放掉認領、用 `LandRandomPos` 走開 8 格內再試。只在真的卡住時觸發（設成「第二次就算失敗」時 hall／wideTower 變慢 8～11%，改成 200 tick 後舊 BENCH 完全不變）。
  - 1.21.1 的同類偶發（`villageHallOneMaid` 131/366 後無 log）很可能就是第 2 類，建議 1.21.1 也加。
- 偵錯過程用的暫時診斷（`DBGSTALL`、`DBGWATCH`、`DBGFIND` 等）都已移除。
### Phase E
- 設定畫面：「領地」頁多一列「Service buildings...」子頁（`config7` 上半、`config8` 捲到底），14 個 `territory.services` 項全在；`ConfigEntry` 新增字串設定（`isString()`，`travelCostItem` 用 `EditBox`，長度上限 256，用 spec 驗證）。名稱／tooltip 的語言 key 三語都已存在，不用補。
- `runClientSelfTest`：全部截圖正常（逐張看過）；新的 `territory_shop`（分頁、搜尋框、庫存顏色）、`territory_problems`（原因、四個按鈕）、模板目錄「Service」分頁。另外加了 1.20.1 自己的一步 `maidbuilder_selftest_territory_problems_outline.png`：在視線前 3 格立一面石磚牆，`ProblemHighlights` 標示牆後的方塊 → 紅框穿牆可見。（log 的 `no button ...tab.overview` 是自測按已選取的分頁，不影響截圖。）
- `runClientShaderTest -PshaderTest=true`：26 張，`problems_outline` 在光影（AFTER_LEVEL、重建的相機旋轉）下紅框穿牆、位置正確。
- `runTemplateShowcase`：17 個模板 34 張，6 個新模板外觀正確。
- `runServer` + `runClientNetTest`：原流程全部 PASS（`ALL CHECKS PASSED`，`TemplateList received: 17 templates`），伺服器啟動顯示 `Loaded 7 shop shelves`。沒有另外加商店／詳情的網路自測（封包編解碼已由 `payloadsRoundTrip` 覆蓋）。
### Phase F
- `./gradlew clean build`：`clean` 刪不掉 `forge/build/moddev/artifacts`（網路自測的客戶端視窗還開著、佔用檔案），`build/libs` 已清空 → 接著 `./gradlew build` 成功，`forge/build/libs/` 只有 `maidbuilder-release_1.2.0_forge1.20.1.jar`（reobf，`mods.toml` version 1.2.0；含 7 個貨架、`tags/items/not_for_sale.json`、17 個結構，沒有 `tags/item/`）。
- 乾淨環境：官方安裝器裝 Forge 1.20.1-47.4.26 伺服器到暫存資料夾，`mods/` 只放 TLM `1.5.3-forge+mc1.20.1` 與發佈 jar → `Loaded 5 territory levels`／`Loaded 17 building templates`／`Loaded 7 shop shelves`，沒有標籤／配方／模板錯誤（只有 TLM 自己的 `patchouli:guide_book`）；`/maidbuilder list`、`job list`、`territory list` 正常回應，`stop` 正常存檔。
- 文件：`README.md`（1.2.0 說明、服務建築表、建材行、繞路取料、需玩家處理、與 1.21.1 的差異加上「Service buildings...」與貨架差異、GameTest 66 個）；`curseforge/changelog_zh.md`／`_en.md` 改寫成 1.2.0 Forge 版（含需玩家處理、Forge 版兩個卡住修正、差異段）；`CLAUDE.md` 版本與 GameTest 數量。
- `git reset -q` 讓改動回到未暫存；沒有 commit、branch、tag。
### Phase G
- 1.21.1 repo 狀態（`git -C ../Maid_builder status`）：`3d1b661` 之後**仍未 commit**，工作區改動與 `uncommitted-2026-10-28.patch` 逐行相同 → 用該 patch。
- 試套：`ReachPlanner` 只在 `columnAt` 的 `getFirst()` 一處衝突（1.20.1 的 `findStand` 修正沒被動到），改 `get(0)` 後編譯通過。
- 結果：66 項中 `tradingPostOneMaid`、`watchtowerOneMaid` 仍會失敗；而且 Phase G 的取料「seems stuck → wandering」在瞭望塔門口同一格 (9,-57,69) 連續觸發十幾次、女僕始終沒離開，工作停在 218/232。依規則**整段還原**（3 個檔反向套用 patch，`TemplateGenerator` 一併還原），沒有自己改寫它的修法。
- 還原後只保留 Phase D 的兩個修正（`descend`、`FindTarget` 走不動就走開），66 項連跑 3 次全過。
- 精緻版模板 `fine_templates/*.litematic`：屬於同一段未完成的功能、沒有模板 JSON 引用，`TemplateGenerator` 已還原、無法重產比對 → **沒有複製進 jar**，等 1.21.1 正式 commit 再一起移植。

---

## 7. 留給使用者的實機驗收（Claude 做完後列出來）

照 `docs/port-1.2.0/1.21.1-plan_acceptance.md` 的順序，重點：

- [ ] 驛站：商人與羊駝會不會跑出驛站、製圖台傳送畫面、跨維度傳送、付費與潛行右鍵
- [ ] 村民集會所：村民入住、搶床與工作站；瞭望塔：發光範圍、襲擊通知；神社好感度在 TLM 介面的顯示；食堂回血
- [ ] 建材行：店員在場才營業、分類／搜尋、每日限量與隔天補貨、訂購缺料送進領地材料箱、1.20.1 少掉的凝灰岩類商品
- [ ] 取料繞路：女僕從蓋到一半的房子裡繞出去拿材料（門、樓梯、多層地形）
- [ ] 需玩家處理：修復時放箱子擋住一格 → 佇列顯示且不消失、詳情畫面、紅框隔牆可見（含光影）、拿走箱子後女僕補上、「交回女僕重試」與「略過」
- [ ] 設定畫面的「Services」子頁；專用伺服器上的商店與傳送封包、重啟後 `NextEvent`／限量／`Problems` 仍在
