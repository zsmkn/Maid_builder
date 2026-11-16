# Maid Builder 1.20.1（Forge）— 移植 1.21.1 v1.1.0 計畫

> 現況：**1.1.0 移植完成（2026-10-27）**，47 個 GameTest 連跑 3 次全過；改動留在工作區未 commit。移植前為 1.20.1 Forge 版 1.0.0（`bd9987d`，分支 `1.20.1`，26 個 GameTest）。
> 目標：把 1.21.1 版 **release 1.1.0**（`Maid_builder` 的 `06cb298`）的全部新功能移植過來，玩家體驗、指令、按鍵、語言 key、設定項、NBT／存檔欄位、資料包格式（模板／等級 JSON）都和 1.21.1 一致；產出 `maidbuilder-release_1.1.0_forge1.20.1.jar`。
> 1.0.0 移植時的完整 API 對照與踩坑紀錄在 [docs/port-1.0.0-plan.md](docs/port-1.0.0-plan.md)（§3 API 對照、§7 移植紀錄），**本檔只寫 1.1.0 新增的部分**，基本對照（套件、事件、網路、Capability、SavedData、渲染）一律沿用那份。

## ▶ 一句話啟動

在本資料夾對 Claude Code 說：**「依照 plan.md 把 1.1.0 移植到 1.20.1」**。

Claude 從 §4 第一個未打勾的 Phase 開始依序做到最後，**中途不需要詢問使用者**；每個 Phase 完成就在本檔打勾、在 §6 記錄。只有真正無法自行決定的事（API 完全不存在且沒有等價做法、需要新增依賴）才停下來問。§7 的實機項目最後列給使用者。

### 這次的工作規則（優先於 CLAUDE.md 的 branch 規則）

- **不 commit、不開 branch、不打 tag**：全部改動留在工作區（分支 `1.20.1`），由使用者自己 commit。（使用者在 1.21.1 專案明確要求過，1.0.0 移植的多個 commit 也被使用者壓成一個。）
- `git apply -3` 會把檔案加進 index；全部做完後執行 `git reset -q` 讓改動回到未暫存狀態（新檔變 untracked，正常）。
- `refs/` 只讀；不複製 Litematica／Forgematica／Structurize／TLM 程式碼；移植本專案自己的 1.21.1 程式碼可以。
- GameTest 不得為了通過而放寬斷言；1.20.1 行為真的不同時，在測試旁註解原因並記在 §6。
- 不確定 API 時查 `forge/build/moddev/artifacts/forge-1.20.1-47.4.26-sources.jar` 與 `refs/TouhouLittleMaid-1.20.1/`，不憑記憶。

---

## 0. 移植來源（都已放在本資料夾，不需要再去 1.21.1 資料夾）

| 檔案 | 內容 |
|---|---|
| `docs/port-1.1.0/v1.0.0-to-v1.1.0.patch` | 1.21.1 `292e740`（release 1.0.0）→ `06cb298`（release 1.1.0）的 `core/`、`neoforge/`、`tools/` 完整差異（含二進位），路徑已從 `neoforge/` 改寫成 `forge/` |
| `docs/port-1.1.0/1.21.1-plan.md` | 1.21.1 的精簡計畫（1.1.0 摘要、待驗收、交接、踩坑速查、效能基準） |
| `docs/port-1.1.0/1.21.1-plan_done.md` | 已完成項目的詳細設計（7-7 拆除在 Phase 7 段落；§9 模板 v2；§10 鷹架橫向延伸） |
| `docs/port-1.1.0/1.21.1-plan_gameplay.md` | 領地玩法 G0–G4 的完整設計、實作紀錄、程式結構速查、已知限制 |
| `docs/port-1.1.0/1.21.1-README.md`、`README-diff-v1.0.0-to-v1.1.0.diff` | 1.21.1 v1.1.0 使用說明，與 1.0.0 → 1.1.0 的 README 差異 |
| `docs/port-1.1.0/1.21.1-changelog_zh.md`、`_en.md` | 1.1.0 changelog（功能清單即驗收範圍） |

- 本 repo 歷史中已有 `292e740`（1.0.0 移植的基準 `52e61fe` 與它只差 `neoforge/build.gradle` 一行），所以 patch 可以 **3-way 套用**：沒被 1.20.1 改過的地方自動合併，有改過的地方留下衝突標記。
- 若 patch 有問題，可直接從 1.21.1 repo 取：`git fetch C:/Users/USER/Desktop/code/Maid_builder main` 後 `git diff --binary 292e740 06cb298`（`06cb298` 即 FETCH_HEAD）。1.21.1 的工作區之後可能繼續變動，**以 `06cb298` 為準**。

### 試套結果（2026-10-27 在暫存 clone 實測）

`git apply -3 docs/port-1.1.0/v1.0.0-to-v1.1.0.patch`：新增 83 檔、自動合併 27 檔、**衝突 16 檔共 23 處**：

`forge/build.gradle`(1)、`MaidBuilder`(4)、`MaidBuilderConfig`(1)、`client/ClientModEvents`(1)、`client/ClientSelfTest`(1)、`client/MaterialListScreen`(1)、`client/PreviewManager`(4)、`client/preview/AlphaVertexConsumer`(1)、`client/preview/GhostPreview`(2)、`common/job/BuildJob`(1)、`common/maid/BuilderScaffoldTask`(1)、`common/maid/BuilderTeardownTask`(1)、`common/wand/WandActions`(1)、`init/ModItems`(1)、`network/ModNetwork`(1)、`network/Payloads`(2)。

衝突原則：**保留 1.20.1 這側的 API 寫法，加入 1.1.0 這側的新邏輯**。自動合併的 27 檔與新增的 83 檔仍是 1.21.1 API，要逐一改寫（§3）。

---

## 1. 1.1.0 的內容（全部都要移植）

詳細行為以 `docs/port-1.1.0/` 為準。

1. **7-7 拆除錯誤方塊**：`ClearMode`（OFF／REPLACE 預設／ALL，每份工作各自保存，舊存檔讀進來是 OFF）、`BlockBreaker`（不拆容器、太硬、`#maidbuilder:never_break`；以假玩家發出 `BreakEvent` 讓保護模組能擋）、ALL 只清框內同層、水平 `clearRadius` 內的空氣格（由上往下）、預覽橘框、`ClearSettingsSync`、指令 `/maidbuilder job clearing <id> [off|replace|all]`、`BuildPhase.CLEAR`、從鷹架上替換高處錯誤方塊。
2. **領地 G0–G4**：領地旗幟（方塊＋物品＋配方＋掉落表）、`TerritoryData`（`maidbuilder_territories.dat`）、11 個內建模板（資料包 `data/maidbuilder/maidbuilder/templates|structures`）、等級表（`territory_levels`）、建造模式（Caps Lock）、邊界顯示（`G`／手持旗幟）、領地介面（概況／建設／建築／女僕分頁）、模板目錄、工作地點選單、繁榮度與升級、完整度與修復、加成（回血、溫室作物、牧場繁殖、不生成敵對生物）、女僕工作地點與隱藏任務 `maidbuilder:deposit`（存放倉庫）、領地材料箱、`/maidbuilder territory ...`、core `territory/` 規則與 `TerritoryRulesTest`。
3. **7-8 鷹架橫向延伸**：`BuilderScaffoldTask` 的 `SHIFT`／`RETURN`、`ReachPlanner.planShift/planReturn/grounded`、懸空鷹架一起回收。
4. **模板 v2**：`tools/TemplateGenerator.java` 產生的 11 個 `.litematic`、模板工作一律放水、女僕放水後保留空桶（`MaidInventory`）、`templateShowcase` run（`TemplateShowcase`）。
5. 其他：`MaterialRules`（耕地、土徑需要泥土）、設定項（`clearMode`、`clearRadius`、`maxBreakHardness`、`territory.*`、`territory.bonuses.*`、`placeFluids` 說明）、三語語言檔、`TerritorySelfTest` 截圖、`mod_version=1.1.0`。

---

## 2. 1.20.1 版已有、合併時必須保留的東西

（見 `docs/port-1.0.0-plan.md` §7；合併衝突與自動合併後都要確認還在）

- `ReachPlanner.findStand` 不回傳「目前所在但實際搆不到」的格子（1.20.1 女僕停在格子邊緣的修正）。
- `MaidBuilderGameTests`：`maidBuildsFarFromWorkPoint` 結束時 `maid.discard()`；`coreTransformMatchesVanilla` 的 `KNOWN_VANILLA_QUIRKS`（鐘、飾紋陶罐）；1.20.1 專屬測試 `newerBlockNamesResolve`、`jobDataRoundTrip`、`payloadsRoundTrip`。
- `StateResolver` 降版別名表（`short_grass` → `grass`）。
- 渲染：幽靈方塊用事件 `poseStack.last().pose()` 當 model-view；`AFTER_LEVEL` 時用 `Camera` 重建旋轉（X 俯仰、Y 偏航 +180°）；框線用事件 pose stack 再平移 `-camera`；共用 render thread 的 `BufferBuilder`。
- `ModItemData`（取代 Data Components，存在 `stack.tag.maidbuilder.*`）、`ModNetwork` SimpleChannel `maidbuilder:main`、`MaterialContainers.handler`（Forge capability）、`Convert.min/max`。
- 自寫設定畫面 `client/config/`、開著畫面時不畫 HUD、`ShaderCompat`（Oculus）、`runClientNetTest`／`runClientShaderTest` 與 `-PshaderTest`、`reobfJar` 檔名。

---

## 3. 1.1.0 新程式需要的 1.21.1 → 1.20.1 對照（補充 docs/port-1.0.0-plan.md §3）

### 3.1 已用 Forge 47.4.26 sources 與 TLM 1.20.1 原始碼確認存在

| 1.21.1（NeoForge） | 1.20.1（Forge 47） |
|---|---|
| `net.neoforged.neoforge.event.level.block.CropGrowEvent.Pre`，`Result.GROW/DEFAULT` | `net.minecraftforge.event.level.BlockEvent.CropGrowEvent.Pre`（`@HasResult`）：`setResult(Event.Result.ALLOW)` = 長大、`DEFAULT` = 照原版 |
| `MobSpawnEvent.PositionCheck`，`Result.FAIL` | `net.minecraftforge.event.entity.living.MobSpawnEvent.PositionCheck`（`@HasResult`）：`setResult(Event.Result.DENY)`；GameTest 的 `spawnCheck` 改成 post 後讀 `getResult()` 並比對 `DENY` |
| `MobSpawnType.TRIAL_SPAWNER` | 1.20.1 沒有 → 只判斷 `SPAWNER` |
| `BabyEntitySpawnEvent` | 同名，`net.minecraftforge.event.entity.living` |
| `OnDatapackSyncEvent`、`AddReloadListenerEvent`、`ServerStoppedEvent`、`PlayerInteractEvent.RightClickBlock`、`PlayerEvent.*` | 同名，`net.minecraftforge.event...`，註冊在 `MinecraftForge.EVENT_BUS` |
| `EventHooks.onEntityDestroyBlock` | `ForgeEventFactory.onEntityDestroyBlock(LivingEntity, BlockPos, BlockState)` |
| `NeoForge.EVENT_BUS.post(e).isCanceled()` | `MinecraftForge.EVENT_BUS.post(e)`（回傳 boolean：true = 被取消） |
| `FakePlayerFactory.get(ServerLevel, GameProfile)` | 同名，`net.minecraftforge.common.util` |
| `InputEvent.InteractionKeyMappingTriggered` | 同名，`net.minecraftforge.client.event.InputEvent` |
| `SimpleJsonResourceReloadListener`、`FileToIdConverter`、`ContainerObjectSelectionList`、`Tooltip` | 1.20.1 都有（建構子／參數以 sources 為準） |
| TLM：`getAvailableBackpackInv()`、`getAvailableInv`、`getData/setData/getOrCreateData/setAndSyncData`、`getSchedule()`、`getSchedulePos()`、`setTask`、`TaskManager.findTask/getIdleTask`、`TaskIdle.UID`、`IMaidTask.isHidden/enableLookAndRandomWalk/enablePanic` | 1.20.1 都存在（回傳型別是 Forge 的 `CombinedInvWrapper`） |
| TLM 工作任務 id：`feed_animal`、`fishing`、`honey`、`sugar_cane` | 1.20.1 都有；`touhou_little_maid:farm` 要在 TLM 1.20.1 原始碼確認實際 UID（`TaskNormalFarm` 一類），不同的話改模板 JSON 並記錄 |

### 3.2 需要逐一改寫的寫法

- `ResourceLocation.fromNamespaceAndPath(ns, p)` / `withDefaultNamespace(p)` / `parse(s)` → `new ResourceLocation(ns, p)` / `new ResourceLocation(p)` / `new ResourceLocation(s)`（`tryParse` 1.20.1 有）。
- **方塊**（`TerritoryFlagBlock`）：刪掉 `MapCodec CODEC` 與 `codec()`；`useWithoutItem(state, level, pos, player, hit)` → `use(state, level, pos, player, hand, hit)`（只處理主手，避免觸發兩次）；其他覆寫方法（`playerWillDestroy` 1.20.1 回傳 `void` 等）照 sources 調整。`ModBlocks`：`DeferredRegister.create(ForgeRegistries.BLOCKS, MOD_ID)` + `RegistryObject<Block>`，在 `MaidBuilder` 用 mod bus 註冊。
- **物品**：`TerritoryFlagItem.appendHoverText(stack, @Nullable Level, list, flag)`；`ModItems` 的旗幟物品沿用 1.20.1 的 `RegistryObject`；1.21.1 若用 Data Components 存東西，改用 `ModItemData`。
- **SavedData**（`TerritoryData`）：`save(CompoundTag)`、`load(CompoundTag)`，`level.getDataStorage().computeIfAbsent(TerritoryData::load, TerritoryData::new, NAME)`；GameTest 的 `TerritoryData.FACTORY.deserializer().apply(tag, registries)` → `TerritoryData.load(tag)`。
- **網路**（`TerritoryPayloads`、`Payloads` 新增項、`ClearSettingsSync`）：`StreamCodec`／`ByteBufCodecs`／`RegistryFriendlyByteBuf` → record + `encode(FriendlyByteBuf)` / `static decode(FriendlyByteBuf)` + handler，比照 1.20.1 現有的 `Payloads` 與 `ModNetwork.messageBuilder`；清單長度與位元組上限照 1.21.1 的數字；客戶端處理走 `ClientPayloadHandler`／`DistExecutor`。模板結構下載（`TemplateStructure`，1.21.1 單包上限 1 MiB）：確認 1.20.1 clientbound custom payload 的上限並保留同樣的「模板檔超過 1000 KiB 拒絕載入」。`payloadsRoundTrip` 要加入所有新封包。
- **Capability**：`level.getCapability(Capabilities.ItemHandler.BLOCK, pos, null)` → `MaterialContainers.handler(level, pos)`；`IItemHandler`、`ItemHandlerHelper` 在 `net.minecraftforge.items`。
- **HUD**：`BuildModeHud.render(GuiGraphics, DeltaTracker)` → `IGuiOverlay`（`RegisterGuiOverlaysEvent.registerAboveAll`），與 `WandHud` 一樣在開著畫面時不畫。
- **邊界／橘框／泛紅預覽**：`TerritoryBorderRenderer` 用到 `RenderSystem.getModelViewStack()`（1.21.1 是 `Matrix4fStack`，1.20.1 是 `PoseStack`）→ 改成和 1.20.1 `GhostPreview` 框線相同的做法（事件 pose stack、`AFTER_LEVEL` 時重建相機旋轉、平移 `-camera`）。1.1.0 對 `AlphaVertexConsumer` 的改動（頂點顏色相乘做泛紅）要套進 1.20.1 版的 `vertex/color/.../endVertex` 介面。
- **GUI**：`renderBackground(graphics)` 單參數、`mouseScrolled(x, y, delta)`、`ObjectSelectionList`／`ContainerObjectSelectionList` 建構子 `(mc, width, height, top, bottom, itemHeight)` 並關掉泥土背景，比照 1.20.1 已移植的畫面。
- **按鍵**（`TerritoryKeys`：建造模式 Caps Lock、邊界 `G`）：`RegisterKeyMappingsEvent`、`KeyConflictContext` 用 Forge 套件。
- **Java 17**：合併後的程式有 `getFirst()/getLast()`（`BuilderScaffoldTask`、`BuilderTeardownTask`、`ReachPlanner`、`TemplateCatalogScreen`、`TemplateShowcase`、`ClientSelfTest`、`TerritoryGameTests`），全部改成 `get(0)` / `get(size()-1)`；完成後再 grep 一次 `getFirst|getLast|removeFirst|removeLast|addFirst|addLast`。`Comparator.reversed()` 可用。
- **設定**：`ModConfigSpec` → `ForgeConfigSpec`；`clearMode` 是 `defineEnum`。

### 3.3 資料包與資源（1.20.1 特有，最容易漏）

- 資料夾複數：`data/maidbuilder/recipe/` → **`recipes/`**、`loot_table/` → **`loot_tables/`**、`tags/block/` → **`tags/blocks/`**（patch 套用後用 `git mv` 或直接搬）。`data/maidbuilder/maidbuilder/templates|structures|territory_levels/` 是模組自己的路徑，不改。
- 旗幟配方 `result`：`{"id": …}` → `{"item": …}`；`{"tag": "minecraft:banners"}` 在 1.20.1 有。
- **標籤裡不存在的方塊會讓整個標籤載入失敗**：
  - `never_break.json`：`minecraft:trial_spawner`、`minecraft:vault` 在 1.20.1 不存在 → 改成 `{"id": "minecraft:trial_spawner", "required": false}`（或刪除），其他項確認存在。
  - `integrity_ignored.json`：`minecraft:short_grass` → `minecraft:grass`（1.20.1 的草叢就叫 grass）。
- **`c:` 標籤在 1.20.1 Forge 不存在**（Forge 用 `forge:`）。模板 `workplace.products` 與 GameTest 用到的：`#c:crops`、`#c:seeds`、`#c:leathers`、`#c:feathers`、`#c:eggs`、`#c:foods/raw_meat`、`#c:foods/raw_fish`（`#minecraft:wool`、`#minecraft:fishes`、`#minecraft:logs` 1.20.1 有）。在 sources jar 的 `net/minecraftforge/common/Tags.java` 與 Forge 資料檔查實際 `forge:` 標籤（例如 `forge:crops`、`forge:seeds`、`forge:leather`、`forge:feathers`、`forge:eggs`）；Forge 沒有對應的（生肉、生魚）改列原版物品 id（`beef`、`porkchop`、`chicken`、`mutton`、`rabbit`；`cod`、`salmon`、`tropical_fish`、`pufferfish`），確認 `products` 支援直接寫物品 id（不支援就在 `TemplateDef` 補上）。`TerritoryGameTests` 第 496 行附近的 `#c:crops`／`#c:seeds` 同步修改。這是 1.20.1 與 1.21.1 唯一允許的資料差異，記在 §6。
- 模板 `.litematic` 只用基本方塊（橡木、圓石、玻璃、泥土、農地、門、床、營火、蜂箱、木桶、花、燈籠、甘蔗等），1.20.1 全部有；它們的 DataVersion 是 1.21.1 的，`StateResolver` 對「較新」版本不做升級，直接用名稱解析 → 用 GameTest 確認 11 個模板 `unknownBlocks` 都是空的。（可選：用 `tools/TemplateGenerator.java` 以 1.20.1 的 core 重產；它的用法註解裡的路徑改成 `forge/`。）
- 語言檔已自動合併；確認 1.20.1 自己加的 key（`maidbuilder.configuration.edit/range/readonly.*`）還在，三語 JSON 都是合法 JSON。
- `blockstates/territory_flag.json`、`models/block|item/territory_flag.json` 在 1.20.1 格式相同，照搬。

---

## 4. 移植階段與驗收

每個 Phase 完成就在這裡打勾並在 §6 寫紀錄。不開 branch、不 commit。

### Phase A — 套用 patch 與資源整理
1. 確認 `git status` 除了 `plan.md`、`docs/port-1.0.0-plan.md`、`docs/port-1.1.0/`（本計畫本身）之外是乾淨的，`git cat-file -e 292e740` 成功。
2. `git apply -3 docs/port-1.1.0/v1.0.0-to-v1.1.0.patch`（預期結果見 §0；新檔與自動合併的檔會被加進 index）。
3. `gradle.properties`：`mod_version=1.1.0`（patch 不含根目錄檔案）。`forge/build.gradle` 衝突：保留 `reobfJar` 的 1.20.1 檔名設定（**不要**採用 1.21.1 在 `jar` 上加的 `_neoforge` 檔名），加入 `templateShowcase` run（`client()`、`--quickPlaySingleplayer selftest`、`maidbuilder.showcase=true`，遊戲目錄 `run`）。
4. 依 §3.3 搬資料夾、改配方、修標籤與 `c:` 標籤。
- [x] 驗收：沒有殘留的衝突標記（`git diff --check`、grep `^<<<<<<<|^>>>>>>>`），資源路徑正確。

### Phase B — core
- 新增／修改的 core（`BuildPhase`、`BuildPlanner`、`MaterialRules`、`Schematic`、`SchematicRegion`、`StructureReader`、`core/territory/*`）確認沒有 Java 21 API（`javac --release 17` 由 toolchain 保證）。
- [x] 驗收：`./gradlew :core:test` 全過；數量 = 1.20.1 原本 29 個 + 1.1.0 新增（`TerritoryRulesTest`、`BuildPlannerTest`／`StructureReaderTest` 新增的案例），和 1.21.1 `06cb298` 的 core 測試數一致。

### Phase C — forge 編譯通過
- 解 16 個衝突檔（§0），再依 §3.1、§3.2 改寫新增的 83 檔與自動合併的 27 檔。順序建議：`init/`（`ModBlocks`、`ModItems`）→ `block/`、`item/` → `network/` → `common/job`、`common/territory`、`common/maid` → `common/command` → `client/` → `gametest/`。
- `MaidBuilder`：mod bus 註冊方塊；`AddReloadListenerEvent` 註冊 `TemplateRegistry`、`TerritoryLevels`；TLM `registerTaskData` 加上 `maidbuilder:workplace`、`addMaidTask` 加上隱藏任務 `TaskDeposit`（`MaidBuilderExtension`）。
- 依 §2 逐項確認 1.20.1 的修正都還在。
- [x] 驗收：`./gradlew build` 成功；grep 不到 `net.neoforged`（`build.gradle` 的 plugin id 除外）、`fromNamespaceAndPath`、`withDefaultNamespace`、`ResourceLocation.parse`、`StreamCodec`、`DeferredBlock`、`DataComponents`、`HolderLookup`、`getFirst`／`getLast`。

### Phase D — GameTest
- 全部 GameTest：1.20.1 原有 26 個 + 1.1.0 新增 20 個 = **46 個**（名單 §5），再加本次 1.20.1 專屬測試：
  - `territoryDataResolves`（新增）：`never_break`、`integrity_ignored` 標籤非空且含預期項；11 個模板都載入、結構 `unknownBlocks` 為空、`products` 每一項都解析到至少一個物品、`workplace.task` 都能 `TaskManager.findTask`；5 個等級的 `upgrade_items` 都解析得到；旗幟配方與掉落表載入。
  - `jobDataRoundTrip` 加入 `clearMode`；`payloadsRoundTrip` 加入所有新封包（`TerritoryPayloads`、`ClearSettings` 等）。
  - 1.21.1 `TerritoryGameTests` 內的領地資料存讀測試改用 `TerritoryData.load`。
- `TerritoryGameTests` 每個測試獨立 batch、開始前清掉附近殘留領地 —— 1.20.1 的 GameTest 排列與 1.21.1 不同（1.0.0 移植時曾因此偶發失敗），若有測試互相干擾，用 `discard()`／清理解決，不放寬斷言。
- `maidsHealAtLevel` 約 0.1% 機率因 TLM 隨機回血誤判（1.21.1 已知），偶發一次可重跑。
- [x] 驗收：`./gradlew :forge:runGameTestServer` 全過，**連跑 3 次**都全過；BENCH 填入 §5 表格，與 1.21.1 差距 ≤10%（宿舍本身波動 ±8%，看 3 次平均）。

### Phase E — 客戶端與設定畫面
- 設定畫面（`client/config/`）：支援 enum 設定（`clearMode` 用 `CycleButton` 顯示 OFF／REPLACE／ALL）；伺服器設定新增「領地」頁，巢狀的 `territory.bonuses` 也要顯示（分組比照 1.21.1 NeoForge `ConfigurationScreen` 的結構：建造／藍圖／領地，領地內含加成）；名稱與 tooltip 用 1.21.1 已有的 `maidbuilder.configuration.*` key，缺的三語都補。
- 渲染：橘框（ALL 清除預覽）、泛紅模板預覽、領地邊界、建造模式 HUD，照 §3.2 與 §2 的 1.20.1 渲染結論；光影下（`AFTER_LEVEL`）也要對。
- `runClientSelfTest`：`ClientSelfTest` + `TerritorySelfTest` 的所有截圖（含 `maidbuilder_selftest_clear.png`、`maidbuilder_selftest_territory_*.png`：概況、建設、建築、女僕、目錄、建造模式正確／泛紅、工作地點選單）。`TerritorySelfTest` 結束要清掉旗幟、建築與女僕。
- `runTemplateShowcase`：11 個模板各兩張 `maidbuilder_showcase_<id>_a|b.png`，結束後清除並把玩家放回原位。
- `runClientShaderTest -PshaderTest=true`（Oculus + BSL/Complementary）：預覽、橘框、邊界在光影下正確。
- `runClientNetTest`：原流程仍 PASS（先 `runServer`，見 docs/port-1.0.0-plan.md Phase 4）；可行的話加一段「放旗幟 → 開領地介面 → 收到模板清單與一個模板結構」。
- [x] 驗收：**Claude 用 Read 逐張看截圖**，與 1.21.1 的外觀一致（邊界線、泛紅、橘框、各分頁排版、中文字不溢出）；網路測試 PASS；專用伺服器啟動不崩潰（客戶端類別沒被載入）。

### Phase F — 收尾
- `./gradlew clean build` → `forge/build/libs/` 只有 `maidbuilder-release_1.1.0_forge1.20.1.jar`（reobf）。
- 乾淨環境：同 1.0.0（官方安裝器裝 Forge 1.20.1-47.4.26 伺服器到暫存資料夾，`mods/` 只放 TLM 1.5.3 與發佈 jar）→ 啟動、`/maidbuilder list`、`/maidbuilder job list`、`/maidbuilder territory` 指令正常，log 沒有標籤／配方／模板載入錯誤。
- 文件：`README.md`（依 `README-diff-v1.0.0-to-v1.1.0.diff` 加入拆除、領地、模板、鷹架橫向延伸，指令改 `:forge:`，保留 1.20.1 差異段並加上 §3.3 的標籤差異）；`curseforge/changelog_zh.md`／`_en.md` 改寫成 1.1.0 Forge 1.20.1 版（需求列 Minecraft 1.20.1、Forge 47.2.0+、TLM 1.5.3+；本機檔，被 .gitignore）；`CLAUDE.md` 的 GameTest 數量。
- 執行 `git reset -q`（改動留在工作區、不暫存），`git status` 列給使用者看。
- [x] 驗收：本檔所有 Phase 打勾、§5 數字填好、§6 有紀錄、§7 列給使用者。

---

## 5. GameTest 清單與效能基準

**1.20.1 原有 26 個**：`coreTransformMatchesVanilla`、`pastePlacesTransformedStates`、`vanillaStructureRoundTrip`、`wandSelectAdjustConfirm`、`recipesLoaded`、`maidBuildsHut`、`maidFetchesFromMaterialChest`、`maidUsesScaffoldingForHighBlocks`、`maidBuildsFarFromWorkPoint`、`cancelStopsMaidAndClearsScaffolding`、`captureRoundTrip`、`quillSelectsBuilding`、`hallOneMaid`、`hallThreeMaids`、`towerOneMaidWithScaffolding`、`towerThreeMaidsWithScaffolding`、`tallPillar`、`tallTowerOneMaid`、`tallTowerThreeMaids`、`lateJoinerSharesTeammateMaterials`、`doubleChestLateJoiner`、`doubleChestBindsOnce`、`materialReportCountsAllSources`、`newerBlockNamesResolve`、`jobDataRoundTrip`、`payloadsRoundTrip`

**1.1.0 新增 20 個**：
- `MaidBuilderGameTests`：`clearingSettingsKeepProgressAndSave`、`maidReplacesWrongBlocks`、`maidReplacesHighWrongBlockFromScaffold`、`maidClearsSchematicAirNearBuilding`、`maidClearsAndFetchesFromChest`、`maidKeepsEmptyBucket`、`wideTowerOneMaid`、`dormitoryOneMaid`
- `TerritoryGameTests`：`flagLimitAndSpacing`、`flagRemoveAndRetake`、`templatesQueuedAndBuiltInOrder`、`buildingIntegrityAndRepair`、`residentsComeAndGo`、`upgradeHandsInItemsAndUnlocks`、`noHostileSpawnsAtLevel`、`maidsHealAtLevel`、`greenhouseCropsGrowFaster`、`ranchBreedingCooldown`、`workplaceFarmPauseDepositResume`、`workplaceDetectedWhenSetUpByHand`

**本次 1.20.1 專屬新增**：`territoryDataResolves`（共 47 個）

| GameTest（tick，log 搜尋 `BENCH`） | 1.21.1 v1.1.0 | 1.20.1 v1.0.0 | 1.20.1 v1.1.0 |
|---|---|---|---|
| `hallOneMaid` | 1892 | 1839 | 1839 |
| `hallThreeMaids` | 583 | 591 | 591 |
| `towerOneMaidWithScaffolding` | 1457 | 1454 | 1454（下來 2 次） |
| `towerThreeMaidsWithScaffolding` | 574 | 578 | 578（3 次） |
| `tallPillar` | 404 | 405 | 405 |
| `tallTowerOneMaid` | 3088 | 3082 | 3082（3 次） |
| `tallTowerThreeMaids` | 1112 | 1116 | 1116（3 次） |
| `wideTowerOneMaid`（13×13×8，下來次數） | 3945（6 次） | — | 3929（6 次） |
| `dormitoryOneMaid` | 約 2000～2300 | — | 2261／2261／2126（平均 2216） |

塔類測試的 BENCH 另印「trips down」，一併記錄。

---

## 6. 移植紀錄（做的時候填）

- 1.20.1 與 1.21.1 行為／資料不同之處、查到的 API 結論、測試穩定性問題，格式比照 docs/port-1.0.0-plan.md §7。

### Phase A（2026-10-27）
- `git apply -3` 結果與 §0 試套一致：16 檔衝突，全部以「1.20.1 API + 1.1.0 邏輯」解掉；`forge/build.gradle` 保留 `reobfJar` 檔名，刪掉自動合併進來的 `jar` `_neoforge` 檔名，加入 `templateShowcase` run。
- 資料夾改成 `recipes/`、`loot_tables/blocks/`、`tags/blocks/`；旗幟配方 `result` 用 `item`。
- `never_break`：`trial_spawner`、`vault` 改成 `required: false`；`integrity_ignored`：`short_grass` → `grass`。
- **唯一的資料差異（c: 標籤）**：Forge 47 資料檔裡有 `forge:crops`、`forge:seeds`、`forge:leather`、`forge:feathers`、`forge:eggs`（`Tags.Items`），模板改用這些；Forge 沒有生肉／生魚標籤，`large_ranch`／`ranch` 改列 `beef`、`porkchop`、`chicken`、`mutton`、`rabbit`，`fishing_dock` 改列 `cod`、`salmon`、`tropical_fish`、`pufferfish`（`TemplateDef.Workplace.products` 本來就接受物品 id）。`TerritoryGameTests` 的測試模板同步改 `#forge:crops`／`#forge:seeds`。
- TLM 1.20.1 的任務 id：`farm`（`TaskNormalFarm.NAME`）、`feed_animal`、`fishing`、`honey`、`sugar_cane` 都與 1.21.1 相同；`touhou_little_maid:hakurei_gohei` 存在。

### Phase B
- core 與 1.21.1 `06cb298` 相同（patch 無衝突），`:core:test` 39 個全過（`LitematicReaderTest` 的 `@TestFactory` 需要本機 `local-schematics/`，沒有時產生 0 個，與 1.0.0 相同）。

### Phase C
- 網路：`TerritoryPayloads`、`TemplateDef`（含 `Workplace`）、`Payloads.JobStatus/ClearSettings/RequestMaterialReport` 都改成 `encode/decode(FriendlyByteBuf)`，欄位順序與清單上限（4096／65536／256／1024 等）照 1.21.1；`Payloads.writeList/readList` 改成 public 共用。新封包附加在 `ModNetwork.register()` 最後（舊封包 id 不變），客戶端處理一律 `DistExecutor`；新增 `ModNetwork.sendToAll`。`TemplateStructure` 單包 `readByteArray(1 MiB)`，模板檔仍限 1000 KiB（`TemplateRegistry.MAX_STRUCTURE_BYTES`），加上 id／sha1 仍低於 1.20.1 clientbound 上限 1048576。
- `TerritoryFlagBlock`：刪 `CODEC/codec()`，`useWithoutItem` → `use(..., hand, hit)` 只處理主手；`getShape/getCollisionShape/onRemove` 改 public。`ModBlocks` 用 `DeferredRegister<Block>` + `RegistryObject`。
- `TerritoryData`：`save(CompoundTag)`、`public static load(CompoundTag)`，`computeIfAbsent(TerritoryData::load, TerritoryData::new, NAME)`。
- 事件：`ServerTickEvent.Post`/`ClientTickEvent.Post` → `TickEvent.*`（`phase == END`）；`CropGrowEvent.Pre` → `BlockEvent.CropGrowEvent.Pre`（`Event.Result.ALLOW`）；`MobSpawnEvent.PositionCheck` 用 `Event.Result.DENY`，1.20.1 沒有 `TRIAL_SPAWNER`（只判斷 `SPAWNER`）；`OnDatapackSyncEvent.getRelevantPlayers()` → `getPlayer()`（null = 全部，`getPlayerList()`）；`EventHooks.onEntityDestroyBlock` → `ForgeEventFactory`；`EVENT_BUS.post()` 回傳 boolean。
- GUI：三個列表用 `(mc, width, height, top, bottom, itemHeight)` 並關掉泥土背景；`render` 先 `renderBackground(graphics)`。`BuildModeHud.render(GuiGraphics, float)` 以 `IGuiOverlay` 註冊（`build_mode_hud`），除錯畫面判斷改 `options.renderDebug`。
- 渲染：`AlphaVertexConsumer.color/defaultColor` 乘上色調；`TerritoryBorderRenderer` 改用 `GhostPreview.viewPose(event)`（改成 public）＋平移 `-camera`，線段用 `vertex/color/normal/endVertex`。
- `setTame(true, false)` → `setTame(true)`；`getDefaultMaxStackSize()` → `getMaxStackSize()`；GameTest 的 `Capabilities.ItemHandler.BLOCK` → `MaterialContainers.handler`。
- §2 的 1.20.1 修正逐項確認仍在（`findStand`、`KNOWN_VANILLA_QUIRKS`、`newerBlockNamesResolve`、`jobDataRoundTrip`、`payloadsRoundTrip`、`StateResolver` 別名、`viewPose`、共用 `BufferBuilder`、`ModItemData`、`ClientNetSelfTest`）。

### Phase D
- 新增 `territoryDataResolves`（`TerritoryGameTests`，batch `territory_data`）：`never_break`／`integrity_ignored` 含預期方塊、11 個模板載入且 `StateResolver.unknownBlocks()` 為空、圖示存在、`workplace.task` 都 `TaskManager.findTask` 得到、每個 `products` 至少比對到一個物品、5 個等級的 `upgrade_items` 都解析得到、旗幟配方與掉落表存在。為此 `TerritoryUpgrades.matcher` 改成 public。
- `jobDataRoundTrip`：加 `setClearing(ALL, 3)` 存讀檢查，與「沒有 `ClearMode` 欄位的舊工作讀成 OFF」。
- `payloadsRoundTrip`：加入 `ClearSettings`、`RequestMaterialReport`（有／無 job）、全部 `TerritoryPayloads`（含巢狀 row、`TemplateDef`/`Workplace`、1000 KiB 的 `TemplateStructure`，並確認其編碼後加上頻道索引仍 ≤ 1048576 bytes）。
- `TerritoryGameTests` 的領地存讀改用 `TerritoryData.load`；`spawnCheck` 改成 post 後讀 `getResult()` 比對 `DENY`（測試旁有註解）。
- 結果：`runGameTestServer` 連跑 3 次都是 **All 47 required tests passed**，沒有互相干擾，不需要改 batch。BENCH 見 §5，除宿舍（隨機波動）外 3 次完全相同，與 1.21.1 差距都在 3% 內。
- 日誌只剩與本模組無關的 `patchouli:guide_book`（TLM 的掉落表，開發環境沒裝 Patchouli，1.0.0 就有）；`Loaded 5 territory levels`、`Loaded 11 building templates`。

### Phase E
- 設定畫面：`ConfigEntry.isEnum()`，`clearMode` 用 `CycleButton` 顯示 OFF／REPLACE／ALL；`Section` 加 `subsections`，伺服器設定新增「領地」頁（`maxFlagsPerPlayer`、`minFlagDistanceChunks`、`resumeFreeFraction`），頁內一列「Level bonuses...」按鈕開巢狀的加成頁（8 項）；「建造」頁末尾加 `clearMode`、`clearRadius`、`maxBreakHardness`。名稱與 tooltip 都用既有的 `maidbuilder.configuration.*`（三語已有，不需補）。
- `ClientSelfTest` 多存 `maidbuilder_selftest_config4..6.png`（領地頁、加成頁、建造頁捲到底的拆除設定），`TerritorySelfTest` 從第 310 tick 開始。
- `TerritorySelfTest`（只影響截圖）：1.20.1 的 selftest 世界裡玩家手上還拿著已放置的魔杖，魔杖 HUD 會和建造模式 HUD 疊在一起（1.21.1 截圖時手上是羽毛筆，所以沒有）；建造模式期間暫時清空主手、結束後還原。合法位置的截圖俯角 35° → 15°：這個世界的玩家朝向讓小溫室的長邊碰到旗幟，35° 時兩張都是紅色。**附帶發現（1.21.1 同樣如此，未改）**：手拿已放置的魔杖進入建造模式時兩個 HUD 會重疊。
- `forge/run/options.txt`、`forge/runs/shaders/options.txt` 設 `tutorialStep:none`（開發環境，被 git 忽略），截圖不再被教學提示擋住。
- 截圖逐張比對 1.21.1：領地四個分頁、目錄、工作地點選單、建造模式合法／泛紅、橘框（2 格，遠處泥土與箱子不框）、11 個模板展示 22 張都一致；差異只有 1.20.1 原版畫面背景不模糊（聊天訊息透出）與列表沒有 1.21 的深色底。中文 tooltip／按鈕沒有溢出。
- 光影：`runClientShaderTest -PshaderTest=true` 用 Complementary Reimagined r5.9.3 與 BSL v10.1.8 各跑一次（`AFTER_LEVEL`），預覽、橘框、領地邊界、泛紅預覽都對齊。
- `runClientNetTest`（先 `runServer`）：原流程全部 PASS，新增的領地段落（放旗幟 → `TerritoryList` 同步 → `TemplateList` 11 個 → 開啟領地介面 → 建造模式下載 `TemplateStructure` → `/maidbuilder territory remove` 後清單同步）也全部 PASS；專用伺服器啟動、載入 11 個模板與 5 個等級、停止都沒有錯誤（沒有載入客戶端類別）。

### Phase F
- `./gradlew clean build`：`forge/build/libs/` 只有 `maidbuilder-release_1.1.0_forge1.20.1.jar`（reobf，含 core、`mods.toml` version 1.1.0）。
- 乾淨環境：官方安裝器把 Forge 1.20.1-47.4.26 伺服器裝到暫存資料夾，`mods/` 只放 TLM `1.5.3-forge+mc1.20.1`（Modrinth 原檔）與發佈 jar → 啟動完成，`Loaded 5 territory levels`／`Loaded 11 building templates`，沒有標籤／配方／模板錯誤（只有 TLM 自己的 `patchouli:guide_book`）；`/maidbuilder list`、`/maidbuilder job list`、`/maidbuilder territory list` 正常回應，`stop` 正常存檔。（該機器預設 Java 是 21，模組以 17 編譯，無影響。）
- 文件：`README.md` 以 3-way 合併 1.21.1 v1.1.0 的 README，保留 1.20.1 差異段並加上 §3.3 的標籤差異、設定畫面差異、試煉生怪磚；開發指令改 `:forge:`、GameTest 47 個、加 `runTemplateShowcase`。`curseforge/changelog_zh.md`／`_en.md` 改寫成 1.1.0 Forge 1.20.1 版（本機檔）。`CLAUDE.md` 更新狀態與測試數。`tools/TemplateGenerator.java` 用法註解路徑改 `forge/`。
- `git reset -q`：改動都在工作區、未暫存；新檔為 untracked。

---

## 7. 留給使用者的實機驗收（Claude 做完後列出來）

- [ ] 領地旗幟：合成、放置、`G` 與手持旗幟顯示邊界、每人上限與間距提示、拆旗幟後重新接手
- [ ] 建造模式：Caps Lock（注意輸入法是否攔截）、準星跟隨、排入佇列、`B` 材料清單、泛紅原因
- [ ] 領地介面各分頁、模板目錄、升級繳交物品、修復、工作地點選單、存放倉庫
- [ ] 女僕實際蓋完各模板（水、門、床、營火、蜂箱）；甘蔗田、溫室、牧場、養蜂、漁場的工作與產物存放
- [ ] 拆除：REPLACE 實際替換、ALL 橘框與實際清除範圍一致、`/maidbuilder job clearing`
- [ ] 鷹架橫向延伸：女僕在懸空鷹架上行走不掉落、走回柱子、完工回收
- [ ] 設定畫面新增的拆除與領地設定；光影（Oculus）下的邊界與橘框
- [ ] 專用伺服器：領地同步、模板下載、多人同時開領地介面、重啟後領地與工作仍在
