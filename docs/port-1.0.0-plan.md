# Maid Builder — 1.20.1（Forge）移植計畫

> 來源：1.21.1 版 `Maid_builder` 專案 **正式版 1.0.0**（2026-10-06，第一～六批全部完成）。
> 目標：把 1.21.1 版的**全部功能與效果**原封不動移植到 Minecraft 1.20.1 + Forge，玩家體驗、指令、按鍵、語言檔、設定項、存檔欄位名稱都一致。
> 1.21.1 版的完整設計與使用說明已轉移到 [docs/plan-1.21.1.md](docs/plan-1.21.1.md)、[docs/README-1.21.1.md](docs/README-1.21.1.md)，**功能細節以它們為準**；本文件只寫「怎麼移植」與「1.20.1 的差異」。

## ▶ 一句話啟動

在本資料夾對 Claude Code 說：**「依照 plan.md 完成 1.20.1 移植」**。
Claude 應從 Phase 0 依序做到 Phase 8，每個 Phase 開 branch、驗收通過就合併回 `main` 再進下一個，**中途不需要詢問使用者**；只有 §9 列的「玩家實機驗收」項目留給使用者最後手動確認。遇到真正無法自行決定的事（例如 API 完全不存在、需要新增依賴）才停下來問。

---

## 0. 已確定的決策

| 項目 | 決定 | 備註 |
|---|---|---|
| MC 版本 | **1.20.1** | |
| 載入器 | **Forge 47.x**（開發用 `47.4.26`，`forge_version_range=[47.2.0,)`） | TLM 1.20.1 只有 Forge 版（不是 NeoForge） |
| Java | **17** | 1.20.1 玩家端是 Java 17；`core` 也降到 17 |
| 建置工具 | **ModDevGradle Legacy**：`net.neoforged.moddev.legacyforge` `2.0.148` | 與 1.21.1 版同一套 MDG，範本在 `refs/MDK-Forge-1.20.1-ModDevGradle` |
| 映射 | Parchment `1.20.1` / `2023.09.03` | |
| Gradle | 沿用專案內 wrapper；若 legacyforge 2.0.148 要求更新，以 MDK 的 `gradle-8.14.5` 為準 | |
| 硬依賴 | 只有 **Touhou Little Maid `1.5.3-forge+mc1.20.1`**（Modrinth maven，已確認存在，2026-05-09） | `tlm_version_range=[1.5.3,)` |
| 子專案 | `core`（純 Java）＋ **`forge`**（把 `neoforge/` 改名為 `forge/`） | 指令改為 `:forge:...` |
| 模組 ID / 套件 | `maidbuilder` / `com.maidbuilder`，**不改** | |
| 授權 | 暫定 All Rights Reserved；不複製 Litematica／Forgematica／Structurize／TLM 程式碼 | |
| 設定畫面 | **自己寫**（Forge 1.20.1 沒有 NeoForge 的 `ConfigurationScreen`），不加 Cloth Config 依賴 | 見 Phase 6 |
| 光影相容 | 1.20.1 Forge 上的光影是 **Oculus**（Iris 的 Forge 移植）＋ Embeddium/Rubidium | 見 Phase 3 |
| 存檔相容 | 1.20.1 為新存檔，不需讀 1.21.1 存檔；但 **NBT 欄位名稱維持一致** | |
| jar 檔名 | `maidbuilder-release_<版本>_forge1.20.1.jar`（`mod_version=1.0.0`） | 必須是 **reobf 後**的 jar |

---

## 1. 本資料夾內容（已備妥）

| 路徑 | 內容 |
|---|---|
| `core/`、`neoforge/`、`gradle/`、根目錄 Gradle 檔 | 1.21.1 版 1.0.0 的完整原始碼（含工作區未提交的修改），**移植起點** |
| `docs/plan-1.21.1.md`、`docs/README-1.21.1.md` | 1.21.1 版完整開發藍圖與使用說明（功能規格依據） |
| `refs/TouhouLittleMaid-1.20.1/` | **TLM 1.5.3 Forge 1.20.1 原始碼**（主要參考） |
| `refs/MDK-Forge-1.20.1-ModDevGradle/` | Forge 1.20.1 + MDG legacyforge 官方範本 |
| `refs/1.21.1/` | 1.21.1 版用過的所有參考（TLM 1.21、MDK、Litematica、Structurize、Create、Patchouli、baritone、MSM…），對照 API 差異用 |
| `Asset/wand.jpg` | 魔杖原圖 |
| `curseforge/` | CurseForge 說明文字（1.21.1 版，Phase 8 更新） |
| `sample_hut.litematic` | 開發用範例藍圖 |

Minecraft / Forge 1.20.1 原始碼：Phase 0 建置後在 Gradle 快取／`forge/build/moddev/artifacts/` 下的 `*-sources.jar`（以實際路徑為準），**不確定 API 時一律查這裡或 `refs/`，不要憑記憶**。

不搬的東西：`.vscode/launch.json`（絕對路徑指向舊專案，IDE 會重新產生）、`neoforge/run/`（1.21.1 世界無法降版開啟，selftest 世界要重建，見 Phase 3）。

---

## 2. 移植範圍（全部都要做到，驗收方式與 1.21.1 相同）

詳細行為見 `docs/plan-1.21.1.md` §5–§8 與 `docs/README-1.21.1.md`。清單：

1. **core**：`.litematic` 讀寫、原版 `.nbt` 讀取（依內容判斷格式）、旋轉／鏡像／屬性轉換、`BuildPlanner` 三輪順序、`MaterialList`。
2. **指令**：`/maidbuilder list / info / paste / save / job create|list|info|retry|bind|wand|remove`。
3. **藍圖魔杖**：選檔畫面（`.litematic` 與 `.nbt`）、原點／旋轉／鏡像／微調按鍵、確認建立工作、潛行右鍵兩次取消、幽靈預覽（半透明＝待放、紅框＝放錯、淺藍框＝方塊實體、範圍框）、HUD、材料清單畫面（`B` 鍵／右鍵空處）。
4. **網路**：分段上傳（伺服器主動要求、30000 bytes/段、SHA-1、大小上限、去重）、擷取下載（256 KiB/段）、進度同步、材料清單快照。
5. **BuildJob**：SavedData 持久化、材料箱、鷹架紀錄、取消流程。
6. **建築師女僕**：任務註冊（含說明與「身上有鷹架」條件列）、扣料放置、材料箱拿料（平均分配、退路）、隊友分料、找站立點、自搭鷹架＋原地加高＋回收、`BuilderRangeHandler`（大型建築不被傳送）、非家模式 16 格、多女僕分散與鷹架不互卡。
7. **藍圖羽毛筆**：自動框選整棟（`StructureDetector`）、空中標記角落、推拉選區、存檔畫面、檔名消毒與不覆蓋、冷卻與體積上限。
8. **設定**：伺服器設定（所有 1.21.1 選項）、客戶端設定（`showHud`、`ghostOpacity`、`previewRenderDistance`、`showWrongBlocks`、`showBoundingBox`、`shaderOverlay`）、設定畫面（模組列表 → 設定）。
9. **光影相容**：偵測到光影包時改在最後階段畫預覽。
10. **資源**：魔杖與羽毛筆貼圖、物品模型、三種語言（`en_us`、`zh_cn`、`zh_tw`，key 不變）、合成配方（羽毛筆工作台、魔杖 TLM 祭壇）。
11. **自動測試**：core JUnit 全部、GameTest **23 個**全部（名單見 §5）、`runClientSelfTest` 截圖。

---

## 3. 1.21.1 → 1.20.1 API 對照（移植時逐項套用，不確定就查原始碼）

### 3.1 建置與中繼資料

| 1.21.1（NeoForge） | 1.20.1（Forge + MDG legacy） |
|---|---|
| plugin `net.neoforged.moddev` 2.0.147，`neoForge { }` | `net.neoforged.moddev.legacyforge` 2.0.148，`legacyForge { version = "1.20.1-47.4.26" }` |
| `implementation "maven.modrinth:touhou-little-maid:…"` | **`modImplementation`**（Forge mod jar 要 remap，見 MDG `LEGACY.md`）；`core` 仍是一般 `implementation project(':core')` |
| `jar { from core output; archiveFileName … }` | `jar` 照樣併入 core，**發佈的是 `reobfJar` 的輸出**；檔名設在最終產物上，確認 `build/libs/` 只有一個可用 jar |
| `META-INF/neoforge.mods.toml`，`type = "required"`，依賴 `neoforge` | `META-INF/mods.toml`，`mandatory = true`，依賴 `forge`（`[47.2.0,)`），`loaderVersion="[47,)"` |
| `neoforge.enabledGameTestNamespaces` | `forge.enabledGameTestNamespaces` |
| GitHub Actions `java-version: 21` | `17` |

### 3.2 Java 21 → 17（`core` 與 `forge` 都要改）

- `NbtIo.java:137-153` 的 **switch 型別模式**（`case Byte b ->`）→ 改成 `if (v instanceof Byte b) … else if …`。
- `List.getFirst()/getLast()`（SequencedCollection）→ `get(0)` / `get(size()-1)`：`BuilderScaffoldTask`、`BuilderTeardownTask`、`ReachPlanner`、`SchematicStore`、`BlockPlacer`、core 測試（`BuildPlannerTest`、`MaterialListTest`、`LitematicReaderTest`、`StructureReaderTest`）。
- `Comparator.reversed()`、`Stream.toList()`、record、`instanceof` 模式在 17 都可用，不用改。
- 編譯後再 `grep` 一次 `getFirst|getLast|removeFirst|removeLast|reversed()` 用在 List 上的情況。

### 3.3 Forge 套件與事件

| 1.21.1 | 1.20.1 |
|---|---|
| `net.neoforged.bus.api.*`、`net.neoforged.fml.*` | `net.minecraftforge.eventbus.api.*`、`net.minecraftforge.fml.*` |
| `NeoForge.EVENT_BUS` | `MinecraftForge.EVENT_BUS` |
| `@EventBusSubscriber(bus = …)` | `@Mod.EventBusSubscriber(modid=…, bus = Mod.EventBusSubscriber.Bus.MOD/FORGE, value = Dist.CLIENT)` |
| `@Mod(dist = CLIENT)` 的 `MaidBuilderClient` | Forge 1.20.1 不支援 dist 參數的第二個 `@Mod` → 從主類別以 `DistExecutor.unsafeRunWhenOn(Dist.CLIENT, …)` 呼叫客戶端初始化 |
| 建構子注入 `IEventBus modBus, ModContainer` | `FMLJavaModLoadingContext.get().getModEventBus()`、`ModLoadingContext.get().registerConfig(...)` |
| `DeferredRegister.Items` / `DeferredItem` / `DeferredHolder` | `DeferredRegister.create(ForgeRegistries.ITEMS, MOD_ID)` / `RegistryObject<Item>` |
| `ServerTickEvent.Post`、`ClientTickEvent.Post` | `TickEvent.ServerTickEvent` / `TickEvent.ClientTickEvent`，判斷 `event.phase == TickEvent.Phase.END` |
| `RegisterGuiLayersEvent` | `RegisterGuiOverlaysEvent`（`IGuiOverlay`，`registerAboveAll`） |
| `RegisterKeyMappingsEvent`、`KeyConflictContext` | 同名，套件 `net.minecraftforge.client…` |
| `ClientPlayerNetworkEvent` | 同名（`LoggingOut` 等） |
| `EventHooks.onBlockPlace(entity, snapshot, dir)` | `ForgeEventFactory.onBlockPlace(entity, BlockSnapshot.create(...), dir)`；`BlockEvent.EntityPlaceEvent` 同樣觸發 |
| `ModConfigSpec` | `ForgeConfigSpec`（API 幾乎相同） |
| `FMLPaths`、`ModList` | 同名（`net.minecraftforge.fml.loading.FMLPaths`） |
| `ResourceLocation.fromNamespaceAndPath(ns, p)` | `new ResourceLocation(ns, p)` |
| `Tags.Blocks.GRAVELS`（`c:` 標籤） | `Tags.Blocks.GRAVEL`、`Tags.Blocks.ORES`（`forge:` 標籤），`StructureDetector` 的地形判定要逐一確認 |

### 3.4 物品資料（Data Components → NBT）

1.20.1 沒有 Data Components。`ModDataComponents` 刪掉，改成 `ItemStack` 的 NBT：
- 統一放在 `stack.getOrCreateTagElement("maidbuilder")` 底下，欄位名稱沿用 1.21.1 component 的名稱。
- `WandPlacement`、`CaptureArea`：`StreamCodec` → 自己寫 `toTag()/fromTag(CompoundTag)` 與 `write(FriendlyByteBuf)/read(FriendlyByteBuf)`；`Codec` 可保留（DFU 在 1.20.1 也有）。`CaptureArea` 的 `anchor`、`airDistance` 仍是選用欄位。
- 受影響檔案：`PreviewManager`、`QuillClient`、`WandHud`、`CaptureActions`、`QuillActions`、`MaidBuilderCommand`、`WandInteractHandler`、`JobStatusSync`、`MaterialReports`、`WandActions`、`MaidBuilderGameTests`、`BlueprintQuillItem`、`BlueprintWandItem`、`MaidBuilder`。
- `appendHoverText(ItemStack, TooltipContext, List, TooltipFlag)` → `appendHoverText(ItemStack, @Nullable Level, List<Component>, TooltipFlag)`。
- `onItemUseFirst` 在 Forge 1.20.1 的 `IForgeItem` 同樣存在（羽毛筆點門／箱子不打開的行為要保留）。

### 3.5 網路（CustomPacketPayload → SimpleChannel）

- `Payloads` 的每個 record 保留，改成 `encode(FriendlyByteBuf)` + `static decode(FriendlyByteBuf)` + `handle(Supplier<NetworkEvent.Context>)`（`ctx.enqueueWork`、`setPacketHandled(true)`）。
- `ModNetwork`：`NetworkRegistry.newSimpleChannel(new ResourceLocation(MOD_ID, "main"), () -> PROTOCOL, …)`，`messageBuilder(…, NetworkDirection.PLAY_TO_SERVER/CLIENT)` 逐一註冊。參考 `refs/TouhouLittleMaid-1.20.1/.../network/NetworkHandler.java`（只看寫法）。
- 傳送：`PacketDistributor.sendToPlayer(p, msg)` → `CHANNEL.send(PacketDistributor.PLAYER.with(() -> p), msg)`；`sendToServer` → `CHANNEL.sendToServer(msg)`。
- 客戶端處理一律經 `DistExecutor` / 獨立的 `ClientPayloadHandler`，避免專用伺服器載入客戶端類別。
- `ByteBufCodecs`、`RegistryFriendlyByteBuf` → `FriendlyByteBuf`；物品用 `buf.writeItem/readItem` 或 registry id。
- 上限：serverbound custom payload 仍是 32767 bytes（SimpleChannel 多 1 byte 索引）→ 上傳每段 30000 bytes 不變；擷取下載 256 KiB/段不變（clientbound 上限 1 MiB）。材料清單上限 4096 列不變。

### 3.6 Capability（材料箱、女僕背包）

- `Capabilities.ItemHandler.BLOCK` → `blockEntity.getCapability(ForgeCapabilities.ITEM_HANDLER, side).resolve()`（`LazyOptional`），只有方塊實體提供；`MaterialContainers` 與 GameTest 要改。
- 大箱子：Forge 1.20.1 的 `ChestBlockEntity` 能力會回傳兩半合併的 handler；`MaterialContainers.canonical/distinct` 的去重邏輯照舊，GameTest `doubleChestBindsOnce`、`doubleChestLateJoiner`、`materialReportCountsAllSources` 驗證。
- `IItemHandler`、`ItemHandlerHelper`、`CombinedInvWrapper` 在 `net.minecraftforge.items`。

### 3.7 SavedData

- `BuildJobData`：`save(CompoundTag, HolderLookup.Provider)` → `save(CompoundTag)`；`SavedData.Factory` → `level.getDataStorage().computeIfAbsent(BuildJobData::load, BuildJobData::new, NAME)`。
- 物品序列化：`ItemStack.save(tag)` / `ItemStack.of(tag)`，不需 registry provider。

### 3.8 TLM 1.20.1（已核對 `refs/TouhouLittleMaid-1.20.1`）

| 用途 | 1.20.1 實際介面 | 與 1.21.1 差異 |
|---|---|---|
| 擴充入口 | `@LittleMaidExtension` + `ILittleMaid.addMaidTask(TaskManager)`、`registerTaskData(TaskDataRegister)` | 相同 |
| 任務 | `IMaidTask.createBrainTasks(EntityMaid)` → `List<Pair<Integer, BehaviorControl<? super EntityMaid>>>`、`getConditionDescription`、`getDescription`、`isEnable` | 相同（逐一比對其餘 default 方法簽名） |
| 女僕資料 | `TaskDataKey<T>`：**`CompoundTag writeSaveData(T)` / `T readSaveData(CompoundTag)`**；`maid.getData/getOrCreateData/setData/setAndSyncData` | 1.21.1 是 codec；`BuilderMaidData` 改寫這兩個方法，欄位名不變 |
| 背包 | `maid.getAvailableInv(boolean)` → `CombinedInvWrapper`（Forge） | 套件不同 |
| 事件 | `InteractMaidEvent`（extends `Event`）、`MaidTickEvent`（extends `LivingEvent`），在 `MinecraftForge.EVENT_BUS` | 套件不同 |
| 家模式 | `maid.getSchedulePos().setHomeModeEnable(maid, pos)` + `maid.setHomeModeEnable(true)`；`SchedulePos.tick(maid)` | 相同，重新確認 `SchedulePos` 每 40 tick 重設範圍與傳送的邏輯，`BuilderRangeHandler` 依此實作 |
| 尋路 | `MaidPathNavigation`、`MaidNodeEvaluator`、`onClimbable()`、`MaidClimbTask` | 確認爬鷹架速度控制方式一致 |
| 祭壇配方 | type **`touhou_little_maid:altar_crafting`**；`output: {type:"minecraft:item", nbt:{Item:{id, Count}}}`、`power`、`ingredients` | 格式與 1.21.1 不同，照 `refs/.../data/touhou_little_maid/recipes/altar/*.json` 改寫 |
| maven | `maven.modrinth:touhou-little-maid:1.5.3-forge+mc1.20.1` | |

### 3.9 資源與資料

- 資料夾複數：`data/maidbuilder/recipe/` → **`recipes/`**；`data/maidbuilder/structure/` → **`structures/`**（GameTest 模板）。
- 工作台配方 result：`{"id": …, "count": 1}` → `{"item": …, "count": 1}`。
- 物品模型 `assets/maidbuilder/models/item/*.json`、貼圖、語言檔照搬（1.20.1 不需要 `assets/<ns>/items/`）。
- `pack.mcmeta`：1.20.1 需要（Forge 47 的 MDK 範本有），`pack_format` = 15。
- GameTest 結構 `floor16.nbt`、`floor16_tall.nbt` 是 1.21.1（DataVersion 3955）存的：1.20.1（3465）不會降版。**在 1.20.1 用程式重新產生**（例如 GameTest 內用 `StructureTemplate` 存檔一次，或 GameTest 直接在空模板上鋪地板），或確認內容只有基本方塊後把 `DataVersion` 改為 3465。

### 3.10 舊／新版藍圖相容（1.20.1 特有）

- 玩家可能拿 1.20.2 以後存的藍圖：`StateResolver` 只在 `dataVersion < current` 時升級，較新的版本不處理 → 已正確。
- 不存在的方塊（1.20.3+ 的 crafter、銅燈、凝灰岩磚、試煉方塊等）→ 現有邏輯會變空氣並記在 `unknownBlocks`；**確認**這份清單有在 `/maidbuilder info`、確認建造時通知玩家（若 1.21.1 已經有就維持）。
- **新增降版別名表**（1.20.1 才需要）：`minecraft:short_grass` → `minecraft:grass`（1.20.3 改名）。只放在 `StateResolver`，附單元或 GameTest 測試。
- `LitematicWriter` 寫 `Version 6 / SubVersion 1`，與 Litematica 1.20.1 相容，不用改；`MinecraftDataVersion` 自動是 3465。

### 3.11 客戶端渲染（最容易壞，Phase 3 專門處理）

- `RenderLevelStageEvent`：Forge 1.20.1 有 `AFTER_TRANSLUCENT_BLOCKS`、`AFTER_LEVEL`（先在 sources jar 確認列舉值）。1.20.1 的 `event.getPoseStack()` **不是 null**、已含相機旋轉；`RenderSystem.getModelViewMatrix()` 的內容與 1.21.1 不同 → `GhostPreview` 的矩陣處理要重新推導，不能直接照抄 1.21.1 註解裡的結論。
- `VertexBuffer(VertexBuffer.Usage.STATIC)`、`BufferBuilder.begin(mode, format)`、`end()` → `RenderedBuffer`、`vertexBuffer.upload(...)`、`drawWithShader(modelView, projection, shader)`；`ShaderInstance` 的 `CHUNK_OFFSET` uniform 在 1.20.1 存在。
- `VertexConsumer` 介面 1.20.1 是 `vertex(x,y,z).color(...).uv(...).overlayCoords(...).uv2(...).normal(...).endVertex()`，`AlphaVertexConsumer` 整個重寫（含 `defaultColor/unsetDefaultColor`）。
- `BlockRenderDispatcher.renderBatched(state, pos, level, pose, consumer, checkSides, random, ModelData, RenderType)`、`ModelData`（`net.minecraftforge.client.model.data`）。
- `LevelRenderer.renderLineBox` 可用於紅框、淺藍框、範圍框、羽毛筆選區框。
- `GuiGraphics` 1.20.1 已存在，但：`Screen.renderBackground(GuiGraphics)` 只有一個參數；`mouseScrolled(x, y, delta)` 只有一個 delta；`renderTooltip` 系列簽名不同；`EditBox`、`Button.builder` 可用。影響 `SchematicSelectScreen`、`CaptureScreen`、`MaterialListScreen`、`WandHud`、`QuillClient`。
- 光影：`ShaderCompat` 以反射找 `net.irisshaders.iris.api.v0.IrisApi`；Oculus 1.20.1 提供同一個 API 類別（確認實際版本的套件名，必要時多試一個舊套件名），偵測到時改在 `AFTER_LEVEL` 畫。

---

## 4. 移植階段與驗收標準

每個 Phase 開 branch（`port/phase-N-<名稱>`），驗收通過合併回 `main`。每次合併前跑 `./gradlew build`。

### Phase 0 — 環境 ✅（2026-10-06 完成）
1. `git init`，把目前內容（1.21.1 原始碼）提交為基準 commit「baseline: 1.21.1 v1.0.0 source」，之後的 diff 才看得出改了什麼。確認 `.gitignore` 排除 `refs/`、`CLAUDE.md`、`run/`、`runs/`。
2. `git mv neoforge forge`，`settings.gradle` 改 `include 'forge'`。
3. 依 §0、§3.1 改 `gradle.properties`、`forge/build.gradle`、`core/build.gradle`（Java 17）、`mods.toml` 範本（參考 `refs/MDK-Forge-1.20.1-ModDevGradle`）。
4. 先讓空殼能編譯：可暫時把 `forge/src/main/java` 搬到暫存資料夾、只留 `MaidBuilder` 入口，確認 `runClient` 能進遊戲、TLM 1.5.3 載入；之後在 Phase 2～7 逐步搬回。
5. ✅ `./gradlew build` 成功；`runClient` 進主選單、模組列表有 Maid Builder 與 TLM；`:core:test` 通過。

### Phase 1 — core（Java 17）✅（2026-10-06 完成）
- 依 §3.2 改寫；其餘不動（core 不碰 MC）。
- ✅ 全部 core 單元測試通過（`NbtIoTest`、`BuildPlannerTest`、`MaterialListTest`、`LitematicReaderTest`、`PackedBitArrayTest`、`StructureReaderTest`、`TransformTest`），數量與 1.21.1 相同。

### Phase 2 — 共用層、指令貼上、GameTest 框架 ✅（2026-10-06 完成）
- `MaidBuilder`、`MaidBuilderConfig`、`Convert`、`StateResolver`（含 §3.10 別名表）、`BlockPlacer`、`SchematicStore`、`MaidBuilderCommand`（先只開 list/info/paste/save）、`init/ModItems`。
- GameTest：Forge 1.20.1 的 `@GameTestHolder(MOD_ID)` + `@PrefixGameTestTemplate(false)`（`net.minecraftforge.gametest`），模板路徑 `structures/`；確認 helper 相對座標與 template 第 0 層的對應（1.21.1 是 y=1，**1.20.1 重新確認**）；依 §3.9 處理 `floor16*.nbt`。
- ✅ GameTest `coreTransformMatchesVanilla`、`pastePlacesTransformedStates`、`vanillaStructureRoundTrip` 通過。

### Phase 3 — 物品、魔杖、預覽、HUD、按鍵（客戶端）✅（2026-10-06 完成）
- §3.4 物品 NBT、§3.11 渲染與 GUI、`WandKeys`（R、M、方向鍵、PgUp/PgDn、B，名稱與預設鍵不變）、`PreviewManager.handleKeys`、`WandHud`、`SchematicSelectScreen`、`GhostPreview`（每 16³ 區段一個 buffer、每 tick 最多比對 8192 格、每幀最多重建 2 區段、160 格距離與視錐裁切、`ghostOpacity` 改變時逐步重建）。
- 配方 §3.8、§3.9。
- 建立 selftest 世界：用 `runClient` 加 `--quickPlaySingleplayer selftest` 無法建立新世界時，改用 `runServer`（`level-name=selftest`、超平坦）產生世界後複製到 `forge/run/saves/selftest`。
- ✅ GameTest `wandSelectAdjustConfirm`、`recipesLoaded`；`runClientSelfTest` 產生 `maidbuilder_selftest*.png`，**Claude 要用 Read 看截圖確認**：半透明方塊、紅框、淺藍框、HUD 都和 1.21.1 一致。

### Phase 4 — 網路、上傳下載、SavedData、BuildJob ✅（2026-10-06 完成）
- §3.5、§3.7；`UploadManager`、`ClientDownloads`、`JobStatusSync`、`BuildJob*`、`WandActions`、取消流程（3 秒內潛行右鍵兩次）。
- ✅ `runServer`（專用伺服器）+ `runClient` 連線可啟動不崩潰（客戶端類別沒被伺服器載入）；GameTest 能建立工作並在重新載入 SavedData 後仍在（可加一個 round-trip 測試：`save` → `load` 比對）。

### Phase 5 — 建築師女僕、材料箱、鷹架、範圍處理 ✅（2026-10-06 完成）
- §3.6、§3.8；移植 `common/maid/` 全部檔案、`MaterialContainers`、`WandInteractHandler`、`BuilderRangeHandler`。任務說明與條件列的語言 key 不變。
- ✅ GameTest `maidBuildsHut`、`maidFetchesFromMaterialChest`、`maidUsesScaffoldingForHighBlocks`、`maidBuildsFarFromWorkPoint`、`cancelStopsMaidAndClearsScaffolding`、`doubleChestBindsOnce`。

### Phase 6 — 多女僕協作、材料清單、設定畫面 ✅（2026-10-06 完成）
- `MaterialReports`、`MaterialListScreen`（排序、搜尋、tooltip、每 20 tick 重新整理、伺服器每人 10 tick 限流、未載入箱子計數、確認前模式由 `GhostPreview` 計算需求）。
- 設定畫面（自寫，取代 NeoForge `ConfigurationScreen`）：
  - `ModLoadingContext.get().registerExtensionPoint(ConfigScreenHandler.ConfigScreenFactory.class, …)`。
  - 總覽頁 → 客戶端設定／伺服器設定（伺服器設定再分頁，與 1.21.1 的分組相同）；布林用切換按鈕、數字用輸入框（範圍檢查）、每項有三種語言的名稱與 tooltip 說明（沿用 1.21.1 的 `maidbuilder.configuration.*` 語言 key）。
  - 伺服器設定只在單人世界（整合伺服器、未開區網）可編輯，其他情況唯讀並顯示原因；修改後 `ConfigValue.set` + `spec.save()`。
  - `ghostOpacity` 等客戶端設定即時生效。
- ✅ GameTest `hallOneMaid`、`hallThreeMaids`、`towerOneMaidWithScaffolding`、`towerThreeMaidsWithScaffolding`、`tallPillar`、`tallTowerOneMaid`、`tallTowerThreeMaids`、`lateJoinerSharesTeammateMaterials`、`doubleChestLateJoiner`、`materialReportCountsAllSources` 通過；**效能不低於 §6 表格（容許 ±10%）**；`runClientSelfTest` 的材料清單與設定畫面截圖（`maidbuilder_selftest_materials.png`、`maidbuilder_selftest_config0..3.png`）內容正確。

### Phase 7 — 藍圖羽毛筆與光影 ✅（2026-10-06 完成）
- `BlueprintQuillItem`、`QuillClient`、`QuillActions`、`CaptureActions`、`SchematicCapture`、`StructureDetector`（§3.3 標籤）、`CaptureScreen`。
- `ShaderCompat` 改為偵測 Oculus（§3.11）。`runClientShaderTest`：遊戲目錄 `forge/runs/shaders`，`mods/` 放 Oculus + Embeddium（1.20.1 Forge 版），`shaderpacks/` 放 BSL 與 Complementary（使用者提供或 Claude 從 Modrinth 下載，**不進 git**）。
- ✅ GameTest `captureRoundTrip`、`quillSelectsBuilding`；自測截圖 `maidbuilder_selftest_quill.png`；有光影時預覽截圖仍為半透明且被前方物體遮擋。

### Phase 8 — 收尾與發佈 ✅（2026-10-06 完成，剩 §9 玩家實機驗收）
- 23 個 GameTest 全部通過、core 測試全部通過、`./gradlew build` 產出 `forge/build/libs/maidbuilder-release_1.0.0_forge1.20.1.jar`（reobf 版本），**放到一個乾淨的 1.20.1 Forge + TLM 1.5.3 實例（`runClient` 以外，例如把 jar 丟進 `run/mods` 前先移除開發 classpath）確認能載入**。
- 更新 `README.md`（版本表改 1.20.1 / Forge 47 / Java 17、指令 `:forge:`、光影段落改 Oculus）、`curseforge/`（中英說明註明 1.20.1 Forge 版）、`.github/workflows/build.yml`（Java 17、路徑 `forge/`）。
- 本檔各 Phase 打勾、§6 填入 1.20.1 實測數字。

---

## 5. GameTest 清單（23 個，1.21.1 全數通過，移植後必須全數通過）

`coreTransformMatchesVanilla`、`pastePlacesTransformedStates`、`vanillaStructureRoundTrip`、`wandSelectAdjustConfirm`、`recipesLoaded`、`maidBuildsHut`、`maidFetchesFromMaterialChest`、`maidUsesScaffoldingForHighBlocks`、`maidBuildsFarFromWorkPoint`、`cancelStopsMaidAndClearsScaffolding`、`captureRoundTrip`、`quillSelectsBuilding`、`hallOneMaid`、`hallThreeMaids`、`towerOneMaidWithScaffolding`、`towerThreeMaidsWithScaffolding`、`tallPillar`、`tallTowerOneMaid`、`tallTowerThreeMaids`、`lateJoinerSharesTeammateMaterials`、`doubleChestLateJoiner`、`doubleChestBindsOnce`、`materialReportCountsAllSources`

1.20.1 版另加 3 個（共 26 個，全部通過）：`newerBlockNamesResolve`（降版別名與新方塊）、`jobDataRoundTrip`（SavedData 存讀）、`payloadsRoundTrip`（每個封包編解碼）。

測試內容不得為了通過而放寬斷言；若 1.20.1 行為真的不同（例如原版方塊屬性差異），在測試旁註解原因並記錄在本檔 §7。

## 6. 效能基準（tick，越少越好；log 搜尋 `BENCH`）

| GameTest | 1.21.1 v1.0.0 | 1.20.1 |
|---|---|---|
| `hallOneMaid`（12×12 大廳 276 塊） | 1892 | 1839 |
| `hallThreeMaids` | 583 | 591 |
| `towerOneMaidWithScaffolding`（5×5×10） | 1457 | 1454 |
| `towerThreeMaidsWithScaffolding` | 574 | 578 |
| `tallPillar`（18 格單柱） | 404 | 405 |
| `tallTowerOneMaid`（5×5×18） | 3088 | 3082 |
| `tallTowerThreeMaids` | 1112 | 1116 |

## 7. 移植紀錄（做的時候填）

- 1.20.1 與 1.21.1 行為不同之處、刻意的差異、查到的 API 結論都寫在這裡，格式比照 `docs/plan-1.21.1.md` §7。

### Phase 0（2026-10-06）
- `git init` + 基準 commit；`neoforge/` → `forge/`；MDG legacyforge 2.0.148 + Forge 47.4.26 + Parchment 2023.09.03；Gradle wrapper 維持 9.2.1（legacyforge 2.0.148 可用，不需降回 8.14.5）。
- 原始碼先移到 `forge/src/pending/java/`（不編譯），只留 `MaidBuilder` 空殼；之後各 Phase 搬回 `src/main/java`。
- 建置環境：toolchain 需要 JDK 17。本機只有 JDK 21，foojay 自動下載極慢，改手動放一份 Microsoft OpenJDK 17 到 `~/.gradle/jdks/` 並在**使用者層級** `~/.gradle/gradle.properties` 設 `org.gradle.java.installations.paths`（不放進專案）。
- 驗收：`./gradlew build` 成功；`runClient` 進主選單，log 顯示載入 `maidbuilder 1.0.0` 與 `touhou_little_maid 1.5.3-forge+mc1.20.1`，無錯誤。
- MDG legacy 的 `build/libs/` 只有 reobf 後的 jar（未 reobf 的在 `build/devlibs/`）。

### Phase 1（2026-10-06）
- `NbtIo.writePayload` 的 switch 型別模式改成 `instanceof` 鏈；測試中的 `getFirst()/getLast()` 改成 `get(0)` / `get(size()-1)`。core 主程式沒有其他 Java 21 專屬 API（以 `javac --release 17` 確認）。
- 結果：29 個測試全過（NbtIo 3、BuildPlanner 4、MaterialList 2、LitematicReader 5 + 1 個本機藍圖 factory、PackedBitArray 4、StructureReader 4、Transform 7），與 1.21.1 相同。

### Phase 2（2026-10-06）
- **與計畫的差異**：原始碼彼此相依很深（指令 → 工作 / 魔杖 / 擷取），逐 Phase 搬回需要大量暫時 stub，因此 Phase 2 直接把全部原始碼移植到可編譯（`src/pending` → `src/main`），之後的 Phase 專注在各自領域的實機／截圖驗收。
- 移植方式（各 Phase 的程式也在此一併完成，後續 Phase 再驗收）：
  - Data Components → `init/ModItemData`：`ItemData<T>` 以同名 codec 存在 `stack.tag.maidbuilder.<build_job|wand_placement|capture_area>`，呼叫端寫法 `ModItemData.X.get/set/has/remove(stack)`。
  - 網路：`ModNetwork`（SimpleChannel `maidbuilder:main`，協定 "1"，`consumerMainThread`；客戶端處理走 `DistExecutor`）；`Payloads` 的 record 改成 `encode/decode(FriendlyByteBuf)`，物品用 `writeId(BuiltInRegistries.ITEM)`，清單長度上限照舊（64 / 4096），位元組陣列上限 30000 / 256 KiB。
  - `MaterialContainers.handler`：`BlockEntity#getCapability(ForgeCapabilities.ITEM_HANDLER)`；Forge 1.20.1 大箱子回傳兩半合併的 handler（`doubleChest*` 測試通過）。**差異**：NeoForge 的 BLOCK capability 也涵蓋沒有方塊實體的容器（如堆肥桶），Forge 只有方塊實體，這類方塊在 1.20.1 不能當材料箱。
  - `BlockPos.min/max` 在 1.20.1 不存在 → `Convert.min/max`；`BlockPlacer` 用 `ForgeEventFactory.onBlockPlace/onMultiBlockPlace`，取消時 `BlockSnapshot.restore(true, false)`（與 Forge 自己的 BlockItem 相同）。
  - `StateResolver` 新增降版別名表（`minecraft:short_grass` → `minecraft:grass`），新增 GameTest `newerBlockNamesResolve`（另驗證 1.20.3+ 的 `crafter` 變空氣並列入 `unknownBlocks`）。GameTest 總數 24。
  - GameTest 模板 `floor16*.nbt` 只有石頭，直接把 DataVersion 改成 3465。模板第 0 層對應 helper 相對 y=1，與 1.21.1 相同（所有測試座標不需改）。
  - 設定畫面自寫（`client/config/`），先完成程式，Phase 6 驗收。
- **1.20.1 原版差異**：`BellBlock`、`DecoratedPotBlock` 在 1.20.1 沒有實作 mirror/rotate（之後的版本才有），加入 `coreTransformMatchesVanilla` 的 `KNOWN_VANILLA_QUIRKS` 並註解原因。模組放置時一律用遊戲自己的變換，所以建造結果與原版結構方塊一致。
- **建築師女僕修正（1.20.1 移動差異）**：1.20.1 女僕走到目標格時，一進入該格就停下（常停在格子邊緣），`findStand` 以格子中心判斷「搆得到」，但實際眼睛位置搆不到 → 她已經「到了」卻永遠不在範圍內，無限重找（`hallOneMaid` 卡在 136/273，每次都重現）。修正：`ReachPlanner.findStand` 不再回傳她目前所在、而且實際搆不到的那一格。
- **測試衛生**：`maidBuildsFarFromWorkPoint` 結束後女僕的工作點還原到測試區外 40 格，1.20.1 的 GameTest 排列下那裡剛好是 `hallThreeMaids` 的工地，TLM 會把她傳送過去，閒晃時站在地板缺口擋住放置（偶發失敗約 1/4）。測試結束時 `maid.discard()`（同 `materialReportCountsAllSources`），斷言不變。之後連跑 13 次全過。
- GameTest server 不限 tick 速度，24 個測試約 80 秒跑完；效能數字與 1.21.1 幾乎相同（§6）。

### Phase 3（2026-10-06）
- 渲染（實測確認）：1.20.1 的 `RenderLevelStageEvent#getPoseStack()` 在每個階段（含 `AFTER_LEVEL`，由 `GameRenderer` 發出）都已含相機旋轉，全域 model-view 是單位矩陣 → 幽靈方塊 VBO 用 `poseStack.last().pose()` 當 model-view + `CHUNK_OFFSET`；框線（紅框、淺藍框、範圍框、羽毛筆選區）都改用事件的 pose stack 再平移 `-camera`（1.21.1 是 `new PoseStack()` + 全域 model-view）。
- `BufferBuilder`：共用一個 render thread 專用的 `BufferBuilder`，`begin(QUADS, BLOCK)` → `endOrDiscardIfEmpty()` → `VertexBuffer.upload`；`AlphaVertexConsumer` 改寫成 1.20.1 介面（`vertex/color/uv/overlayCoords/uv2/normal/endVertex/defaultColor`）。
- GUI：`Screen.render` 先 `renderBackground(graphics)`；`ObjectSelectionList` 用 `(mc, width, height, top, bottom, itemHeight)`，關閉泥土背景（`setRenderBackground/TopAndBottom(false)`）讓世界維持可見，與 1.21.1 外觀一致；HUD 用 `RegisterGuiOverlaysEvent.registerAboveAll`，F3 判斷用 `options.renderDebug`。
- 配方：魔杖改成 TLM 1.20.1 的 `touhou_little_maid:altar_crafting`（`output.nbt.Item`、`power` 0.2、6 個材料不變）；羽毛筆 result 用 `item`。
- selftest 世界：`runServer`（`level-name=selftest`、`level-type=flat`，dev 用 `forge/run/eula.txt`）產生後複製到 `forge/run/saves/selftest`。
- 自測加強：自測中改為手持一支帶同樣放置的魔杖（HUD 會顯示），並在預覽內放一個錯誤方塊（鵝卵石放在玻璃位置）→ 一張截圖同時驗證半透明方塊、紅框、淺藍框（箱子、床）、範圍框、HUD。截圖已逐張檢查，材料清單、設定畫面、羽毛筆截圖也都正確。

### Phase 4（2026-10-06）
- 新增 GameTest：`jobDataRoundTrip`（`BuildJobData.save` → `load` 後比對放置、進度、材料箱、鷹架、取消狀態）、`payloadsRoundTrip`（每個 payload encode → decode 相等且不剩位元組，含 30000 B 上傳段與 256 KiB 下載段）。GameTest 總數 26，全過。
- 新增開發用網路冒煙測試 `runClientNetTest`（`-Dmaidbuilder.nettest=true`，遊戲目錄 `forge/runs/nettest`，與伺服器的 `forge/run` 分開，伺服器一定沒有那個檔案）：先 `runServer`（`level-name=networld`、離線模式、`ops.json` 把 `Dev` 設為 OP），客戶端自動：拿魔杖 → `SelectSchematic`（96 KB 隨機藍圖，4 段上傳）→ `AdjustPlacement` → 潛行右鍵確認 → 伺服器 `RequestUpload` → 分段上傳、SHA-1 驗證 → 魔杖連結新工作 → 收到 `JobStatus` → `MaterialReport` 開啟材料清單 → `/maidbuilder save` 擷取並下載到客戶端資料夾 → `/stop`。**全部 PASS**。
- 重啟驗證：重開專用伺服器後 `-PnettestReload=true` 跑第二輪 `/maidbuilder job list`，兩個工作都還在。專用伺服器啟動不崩潰（客戶端類別沒被載入）。
- 環境問題（非模組）：本機（有 VMware 虛擬網卡）偶爾在 Forge 登入握手時，客戶端收不到伺服器的 `fml:handshake` 封包，30 秒後斷線（`[::1]` 必現，`127.0.0.1` 偶發）；這發生在模組任何封包之前，重連即可。執行設定改用 `127.0.0.1`。

### Phase 5（2026-10-06）
- 6 個驗收 GameTest（`maidBuildsHut`、`maidFetchesFromMaterialChest`、`maidUsesScaffoldingForHighBlocks`、`maidBuildsFarFromWorkPoint`、`cancelStopsMaidAndClearsScaffolding`、`doubleChestBindsOnce`）在 Phase 2 修正後全過（連跑 13 次）。
- TLM 1.20.1 核對：`TaskDataRegister.register(key, Codec)` 存在（`BuilderMaidData` 不用改）；`SchedulePos.tick` 每 40 tick 重設範圍與傳送的邏輯與 1.21 相同；`MaidTickEvent`（在 `SchedulePos.tick` 之前）與 `InteractMaidEvent` 都在 `MinecraftForge.EVENT_BUS`；任務條件列語言 key 格式同為 `task.<ns>.<path>.condition.<name>`。

### Phase 6（2026-10-06）
- 10 個驗收 GameTest 全過，效能與 1.21.1 差距 ≤3%（§6）。
- 設定畫面（`client/config/`，Forge 1.20.1 沒有 NeoForge 的 `ConfigurationScreen`）：`ConfigScreenHandler.ConfigScreenFactory` → 總覽（客戶端／伺服器）→ 伺服器再分「建造」「藍圖」兩頁（同 1.21.1 分組）；開關用 `CycleButton`，數字用輸入框，範圍與驗證直接取 `ForgeConfigSpec.ValueSpec`（不另抄一份範圍），超出範圍變紅不採用；每項 tooltip = 1.21.1 的 `.tooltip` 文字 + 「允許範圍」。修改立即 `set`，離開頁面時 `spec.save()`。伺服器設定只在單人（整合伺服器且未開區網）可編輯，否則唯讀並顯示原因；沒開世界時伺服器按鈕停用。新增語言 key：`maidbuilder.configuration.edit/range/readonly.no_world/readonly.remote`（三語）。
- 截圖確認：材料清單（排序、顏色、tooltip、已足夠列變暗）、設定 config0..3。自測新增 `maidbuilder_selftest_opacity.png`（不透明度改 0.9 立即生效）。
- **差異**：1.20.1 的畫面背景只是變暗、不會模糊，魔杖／羽毛筆 HUD 會透出來壓到畫面標題 → 開著任何畫面時不畫 HUD。

### Phase 7（2026-10-06）
- `captureRoundTrip`、`quillSelectsBuilding` 通過；`StructureDetector` 地形標籤改用 `Tags.Blocks.GRAVEL`（forge:gravel）；羽毛筆截圖（自動框選、錨點、可推拉的面、HUD）正確。
- 光影：`ShaderCompat` 偵測 `oculus`（或 `iris`）模組並以反射呼叫 Oculus 內附的 `net.irisshaders.iris.api.v0.IrisApi`（Oculus 1.20.1-1.8.0 確認可用）。
- **Forge 1.20.1 的坑**：`AFTER_LEVEL` 事件拿到的 pose stack 是 `GameRenderer` 的**投影**矩陣（含視角晃動），不是相機旋轉（其他階段才是）→ 幽靈方塊消失、框線錯位。修正：`GhostPreview` 在 `AFTER_LEVEL` 依 `GameRenderer` 的方式用 `Camera` 重建旋轉（X 俯仰、Y 偏航 +180°）。
- `runClientShaderTest`：1.20.1 開發環境不能直接把正式版 mod jar 放進 `mods/`（SRG 名稱），改成 `-PshaderTest=true` 時以 `modLocalRuntime` 加入 Oculus `1.20.1-1.8.0` + Embeddium `0.3.31+mc1.20.1`（Modrinth maven，自動 remap，不會進入發佈依賴）；光影包 BSL v10.1.8、Complementary Reimagined r5.9.3 從 Modrinth 下載到 `forge/runs/shaders/shaderpacks/`（git 忽略），`config/oculus.properties` 選包。兩個光影包下預覽都是半透明、不被光影照亮、框線對齊、會被前方真實方塊遮擋；關掉 `shaderOverlay` 的對照圖（`_inlevel`）也正確。

### Phase 8（2026-10-06）
- 發佈檔：`reobfJar` 命名為 `maidbuilder-release_1.0.0_forge1.20.1.jar`，`clean build` 後 `forge/build/libs/` 只有這一個（SRG 名稱，內含 core；未 reobf 的開發 jar 在 `build/devlibs/`）。
- 乾淨環境：用官方安裝器裝 Forge 1.20.1-47.4.26 **伺服器**到暫存資料夾，`mods/` 只放 TLM 1.5.3（Modrinth 原檔）與發佈 jar → 正常啟動、產生設定檔、`/maidbuilder list`、`/maidbuilder job list` 正常。Forge 正式環境停用 GameTest，女僕行為留給 §9 實機驗收。
- 最終：core 29 個測試、GameTest 26 個全過，效能見 §6。
- 文件：`README.md`（版本表、與 1.21.1 差異、`:forge:` 指令與新的測試 run）、`curseforge/`（中英說明與 changelog 改為 1.20.1 Forge 版；這些檔案在 .gitignore 中，只更新本機）、`.github/workflows/build.yml`（Java 17、`forge/`）。

---

## 8. 守則（同 CLAUDE.md）

- `refs/` 只讀；不複製 Litematica／Forgematica／Structurize／TLM 程式碼。移植自己的 1.21.1 程式碼沒有問題。
- `core/` 不可 import `net.minecraft.*` / `net.minecraftforge.*` / `net.neoforged.*`。
- 不確定 API 時先搜 `refs/TouhouLittleMaid-1.20.1` 與 Forge／MC 1.20.1 sources jar，不憑記憶猜。
- 功能行為以 `docs/plan-1.21.1.md`、`docs/README-1.21.1.md` 為準；語言 key、指令、按鍵預設、設定項名稱、NBT 欄位名不改。
- 使用者溝通用繁體中文，程式碼與註解用英文。

## 9. 留給使用者的實機驗收（Claude 做完 Phase 8 後列出來請使用者測）

- [ ] 魔杖：選檔、移動、旋轉鏡像、確認、材料清單、取消（單人）
- [ ] 1000+ 方塊藍圖預覽 FPS
- [ ] 女僕蓋有屋頂的房屋（會搭鷹架、完工回收）；3 隻女僕同一工作不衝突；非家模式需主人在附近
- [ ] 羽毛筆自動框選、空中角落、推拉選區、存檔後用魔杖選得到；（可選）Litematica 1.20.1 開啟存出的檔案
- [ ] 設定畫面：客戶端選項即時生效、伺服器選項單人可改
- [ ] 光影（Oculus + BSL／Complementary）下預覽清楚
- [ ] 專用伺服器：上傳藍圖、重啟後工作仍在
