# 女僕建造模組（Maid Builder）開發計畫

> 讓東方女僕（Touhou Little Maid）依照玩家放置的建築投影，自動搬材料、自動建造。
> 本文件是開發藍圖，所有決策、待查項目與各階段進度都寫在這裡。

---

## 0. 已確定的決策

| 項目 | 決定 | 理由 |
|---|---|---|
| MC 版本 | **1.21.1** | TLM 官方主線（最新 1.5.3，2026/5）；26.x 目前 TLM 無官方版 |
| 載入器 | **NeoForge 21.1.x** | TLM 1.21.1 只有 NeoForge 版 |
| Java | **21** | 1.21.1 需求 |
| 建置工具 | **ModDevGradle (MDG)** | NeoForge 官方推薦，未來移植 26.x 同一套 |
| 投影依賴 | **不依賴 Litematica / Forgematica** | 減少依賴、降低維護成本；只參考其架構與檔案格式 |
| 硬依賴 | 只有 **Touhou Little Maid** | |
| 藍圖格式 | 讀取 `.litematic` 與 `.nbt`（原版結構方塊，Phase 7 第四批） | 玩家現有藍圖多為 .litematic |
| 授權 | 自己重寫，不複製 LGPL 程式碼 | Forgematica/Litematica 為 LGPLv3；TLM 為 CC-BY-NC-SA-4.0 → 本模組不可商業化 |

---

## 1. 參考原始碼清單（請下載後放到 `refs/`）

> `refs/` 只讀、不進 git（加入 `.gitignore`）。分支名稱請下載時以實際 repo 為準。

| 優先 | 專案 | Repo | 分支 / 版本 | 要看什麼 |
|---|---|---|---|---|
| ★★★ | Touhou Little Maid | `TartaricAcid/TouhouLittleMaid` | 1.21.1 NeoForge 分支，對應 1.5.3 | ① 擴充入口（`ILittleMaid` / `@LittleMaidExtension` 類）② 任務介面（`IMaidTask` 類）與 `createBrainTasks` ③ 既有任務實作：農夫、放置類任務的 Behavior ④ 女僕背包 `getAvailableInv` / ItemHandler ⑤ 尋路（`MaidPathNavigation`）與工作範圍 ⑥ 網路封包註冊方式 |
| ★★★ | NeoForge MDK | `NeoForgeMDKs/MDK-1.21.1-ModDevGradle` | main | 專案骨架、`build.gradle`、`neoforge.mods.toml` |
| ★★☆ | Litematica（原版） | `maruohon/litematica` | 1.21.1 分支 | `LitematicaSchematic`（檔案讀寫）、`LitematicaBlockStateContainer`（位元壓縮）、放置/旋轉/鏡像的座標轉換、Schematic 渲染架構 **（只參考，不複製）** |
| ★★☆ | Forgematica | `ThinkingStudios/Litematica-Forge` | `1.21.1-neoforge/dev` | 同上邏輯在 NeoForge 上的渲染掛點（RenderLevelStageEvent 用法） |
| ★★☆ | Structurize（MineColonies 的結構庫） | `ldtteam/Structurize` | 1.21.1 分支 | 幽靈方塊預覽渲染、建造順序（先實心再依附）、BlockEntity 處理 |
| ★★☆ | Create | `Creators-of-Create/Create` | 1.21.1 分支 | Schematicannon 的放置順序與「需要依附的方塊延後」邏輯、藍圖道具（Schematic & Quill）的放置操作 UX |
| ★☆☆ | Patchouli | `VazkiiMods/Patchouli` | 1.21.1 分支 | 多方塊預覽（multiblock visualization）的輕量幽靈方塊渲染 |
| ★☆☆ | TLM 附屬範例 | 例如 Touhou Little Maid: Spell / RPG Class Task | 1.21.1 NeoForge | 第三方如何註冊新女僕任務 |
| ★☆☆ | Baritone | `cabaletta/baritone` | 1.21 分支 | `/build` 的放置順序、搆不到時的處理 |

另外參考：TLM 官方中文開發者 wiki（Java / KubeJS 擴充說明）。

---

## 2. 專案結構

兩個 Gradle 子專案，把「不碰 Minecraft 的邏輯」隔離出來，方便單元測試與日後移植。

```
maid-builder/
├─ refs/                         # 參考原始碼（不進 git）
├─ core/                         # 純 Java 21，零 MC 依賴，JUnit 5 測試
│  └─ src/main/java/.../core/
│     ├─ nbt/                    # 最小 NBT 讀取器（或用可選依賴，見 §7）
│     ├─ schematic/
│     │  ├─ Schematic.java       # 區域、尺寸、調色盤、方塊陣列
│     │  ├─ LitematicReader.java
│     │  └─ PackedBitArray.java  # 跨 long 邊界的位元解包
│     ├─ transform/              # 旋轉 / 鏡像 / 平移（座標 + 屬性字串）
│     └─ plan/
│        ├─ BuildPlanner.java    # 產生放置順序
│        └─ MaterialList.java    # 材料清單
├─ neoforge/                     # NeoForge 模組本體
│  └─ src/main/java/.../
│     ├─ MaidBuilder.java        # @Mod 入口
│     ├─ common/                 # 用到 MC 類別但與載入器無關
│     │  ├─ StateResolver.java   # 字串 BlockState → MC BlockState
│     │  ├─ job/BuildJob.java    # 一個建造工作（藍圖+放置+進度）
│     │  ├─ job/BuildJobData.java# SavedData 持久化
│     │  └─ maid/                # 女僕任務與 Behavior
│     ├─ network/                # 封包
│     ├─ item/BlueprintWand.java # 藍圖魔杖
│     └─ client/                 # 幽靈方塊渲染、HUD、按鍵（越薄越好）
└─ plan.md
```

> 版本升級時，預期改動集中在 `neoforge/`，其中 `client/` 渲染最脆弱（26.2 起有 Vulkan 後端），所以渲染只做「半透明 BlockState」這一件事。

---

## 3. `.litematic` 格式（實作前請對照 Litematica 原始碼驗證）

檔案 = gzip 壓縮的 NBT。

```
root (Compound)
├─ Version (Int)                 # 格式版本，目前常見 5–7
├─ MinecraftDataVersion (Int)    # 用於 DataFixer 升級舊藍圖
├─ Metadata (Compound)           # Name, Author, EnclosingSize{x,y,z}, TotalBlocks, RegionCount...
└─ Regions (Compound)            # 可有多個區域
   └─ <regionName> (Compound)
      ├─ Position {x,y,z}        # 相對藍圖原點
      ├─ Size {x,y,z}            # ★ 可以是負數，要換算實際最小角
      ├─ BlockStatePalette (List<Compound>)  # {Name:"minecraft:oak_stairs", Properties:{facing:"east",...}}
      ├─ BlockStates (LongArray) # 位元壓縮索引
      ├─ TileEntities (List)     # 箱子內容、告示牌文字等
      ├─ Entities (List)
      └─ PendingBlockTicks / PendingFluidTicks
```

關鍵細節：
- 每格位元數 = `max(2, ceil(log2(paletteSize)))`。
- **值會跨越 long 邊界**（與原版區塊格式不同），`PackedBitArray` 必須處理跨界。
- 索引 = `y * sizeX * sizeZ + z * sizeX + x`（用 |size| 計算）。
- 調色盤索引 0 通常是 `minecraft:air`。
- 舊版本藍圖要用 `MinecraftDataVersion` 經 DataFixer 升級 BlockState（放在 `neoforge/common`，core 只保留原始字串）。

驗收：用 3–5 個真實 .litematic（含負尺寸、多區域、樓梯/門/床）寫單元測試，比對方塊數與 `Metadata.TotalBlocks`。

---

## 4. 系統流程

```
[客戶端] 選藍圖 → 藍圖魔杖放置（原點/旋轉/鏡像）→ 幽靈預覽
      │ 確認
      ▼
[封包] 上傳藍圖（分段）+ 放置參數
      ▼
[伺服器] 解析 → 建立 BuildJob（SavedData 持久化）→ 產生建造順序 + 材料清單
      ▼
[玩家] 對女僕指派「建築師」任務 + 綁定 BuildJob
      ▼
[女僕 AI] 找下一個待放方塊 → 背包有料？→ 走過去 → 放置 → 進度同步回客戶端
                          └ 沒料 → (MVP) 回報缺料 /（後期）去材料箱拿
```

### 4.1 藍圖從哪來
- **單人 / 區網**：伺服器直接讀 `.minecraft/schematics/`（與 Litematica 同資料夾，玩家不用搬檔）。
- **專用伺服器**：客戶端上傳。serverbound custom payload 上限很小（約 32KB），**必須分段傳送**並在伺服器端重組、驗證大小上限（設定檔可調）。
- 伺服器存放：`<world>/maidbuilder/schematics/<sha1>.litematic`，以雜湊去重。

### 4.2 放置操作（取代 Litematica 的放置功能）
藍圖魔杖（BlueprintWand）：
- 右鍵方塊：設定原點
- 按鍵：旋轉 90°、左右鏡像、上下微調
- 潛行+右鍵：確認送出
- 參考 Create 的 Schematic & Quill 操作手感

### 4.3 幽靈方塊渲染
- 掛在 `RenderLevelStageEvent`（AFTER_TRANSLUCENT_BLOCKS 之類的階段，實作時查 1.21.1 可用階段）。
- 只渲染半透明 BlockState 模型；已正確放置的格子不畫，放錯的格子畫紅框。
- 大藍圖要做快取（預先烘焙成 mesh，放置參數改變才重建）、距離裁切。
- 參考順序：Patchouli（最簡單）→ Structurize → Litematica（最完整）。

---

## 5. 建造規則

### 5.1 放置方式
直接用藍圖中的完整 BlockState，不模擬玩家轉向：
1. 從女僕背包扣除對應物品（`BlockState → Item`，用 `block.asItem()`，特例表處理：紅石線→紅石粉、作物→種子等）
2. `level.setBlock(pos, state, Block.UPDATE_ALL)`
3. 觸發 `BlockEvent.EntityPlaceEvent`（讓領地保護模組能攔截），被取消就跳過並記錄
4. 播放放置音效與女僕揮手動畫

### 5.2 放置順序（BuildPlanner）
分三輪，每輪內由下往上、同層由近到遠：
1. **實心 / 可獨立存在的方塊**
2. **需要依附或支撐的方塊**：火把、按鈕、拉桿、地毯、壓力板、紅石線/中繼器、門、床、植物、梯子、藤蔓
3. **重力方塊與流體**：沙、礫石、混凝土粉末、水/岩漿源（流體預設跳過，設定可開）

判定「需要依附」：先用硬編碼列表 + `BlockState.canSurvive()` 在放置前檢查，不能存活就延後重試，超過重試次數則標記失敗。

### 5.3 特殊方塊
- **兩格方塊**（門、床、高草、大花）：只處理下半/腳部，放置時一併放另一半並扣一個物品。
- **BlockEntity**：MVP 忽略內容（箱子放空的）；之後選擇性套用 TileEntities（告示牌文字等），**不複製容器內容**（避免刷物品）。
- **已存在但錯誤的方塊**：MVP 不拆除，只標紅；之後可設定是否讓女僕拆除並收回。

### 5.4 搆不到的位置
- 放置距離設定檔可調（預設 4.5 格，上限 6）。
- 先在「工作範圍」內找能走到、且搆得到的站立點（`ReachPlanner.findStand`）。工作範圍＝以建築為圓心、半徑為建築水平半對角線＋`workAreaMargin`（預設 8）的圓；路徑一旦進入範圍就不得再離開，避免女僕為了找路跑太遠。
- 找不到就搭鷹架（Phase 7 已實作，`BuilderScaffoldTask`）：在目標旁 3 格內選一個柱位（優先沿用現有鷹架、眼睛與目標同高），走到柱腳由下往上疊原版鷹架，爬上去把搆得到的方塊都蓋完，再潛行滑下來。鷹架位置記在 BuildJob（存檔），工作完成後由 `BuilderTeardownTask` 整柱回收回女僕背包。
- 身上沒有鷹架：材料箱有就去拿；都沒有就通知主人（每分鐘最多一次），該方塊延後 30 秒再試，**不計入失敗次數**。
- 關閉 `useScaffolding` 時維持舊行為：重試數次後標記「需玩家處理」。

---

## 6. 女僕任務

- 新增任務：**建築師（Builder）**，透過 TLM 擴充入口註冊（實際介面名稱以 `refs/` 中 TLM 1.5.3 原始碼為準）。
- 綁定方式：玩家手持藍圖魔杖右鍵女僕 → 把目前 BuildJob 綁定給她。
- 可多名女僕共用同一 BuildJob（以「已認領格子」集合避免重複）。
- Behavior 拆分：
  - `FindNextTargetBehavior`：從 BuildJob 取下一個「背包有料且可到達」的格子
  - `MoveToTargetBehavior`：用 TLM 女僕尋路，走到放置距離內
  - `PlaceBlockBehavior`：§5.1
  - （後期）`FetchMaterialBehavior`：去綁定的材料箱拿缺的東西
- 進度：每放 N 塊同步一次給附近玩家（HUD 顯示 已完成/總數、缺料清單）。
- 效能：每 tick 限制掃描數量；BuildJob 預先排好順序，不在 tick 中重算整份藍圖。

---

## 7. 待查 / 待決定

- [x] TLM 1.5.3 的任務註冊介面實際名稱、`createBrainTasks` 簽名、女僕背包存取方式
  → `@LittleMaidExtension` + `ILittleMaid.addMaidTask(TaskManager)`；`IMaidTask.createBrainTasks(EntityMaid)` 回傳 `List<Pair<Integer, BehaviorControl<? super EntityMaid>>>`；背包 `maid.getAvailableInv(false)`（`CombinedInvWrapper`）；女僕自訂資料用 `registerTaskData` + `maid.getData/setAndSyncData`（codec 必須編碼成 CompoundTag）；右鍵女僕攔截用 `InteractMaidEvent`
  → 家模式要用 `maid.getSchedulePos().setHomeModeEnable(maid, pos)` + `maid.setHomeModeEnable(true)`，只呼叫後者時女僕會被傳送到 (0,0,0)
- [x] TLM 是否已有「綁定箱子/工作區」機制可重用（材料箱階段）
  → 沒有可直接重用的（`ChestManager` 只給隙間飾品用）。材料箱改由本模組自己做：`BuildJob.materialSources` + `BuilderFetchMaterialTask`
  → 注意：家模式下 TLM 的尋路（`MaidNodeEvaluator`）不會走出工作範圍（預設半徑 12，`MaidWorkRange`），材料箱要放在範圍內
- [x] 是否依賴 maid_storage_manager（MSM）做材料補給 → **不依賴**
  → MSM 約 540 個檔案、5.6 萬行，對外 API 只有女僕之間的 `communicate`（`ICommunicatable` / `RequestItemWish`），需要另一隻倉管女僕配合，不適合當硬依賴
  → 自寫「綁定材料箱」只需數百行；只在女僕缺料時、每 40 tick 最多掃一次、只讀綁定的少數容器，效能影響可忽略
  → 之後可做**選用整合**（見 Phase 7）：安裝 MSM 時讓建築師女僕用 `RequestItemWish` 向倉管女僕要料
- [x] NBT 讀取：已在 core **自寫**（`core/nbt/NbtIo`，含大小/深度上限）
- [x] 1.21.1 `RenderLevelStageEvent` 適合的 Stage 與半透明 RenderType
  → `Stage.AFTER_TRANSLUCENT_BLOCKS` + `RenderType.translucent()`。此 stage 的 PoseStack 為 null，全域 model-view 已含相機旋轉；VertexBuffer 用 event 的 modelView + shader 的 `ChunkOffset` 放到相機相對位置；線框用自建 PoseStack 平移 -camera（見 `GhostPreview`）
- [x] serverbound payload 實際上限 → `ServerboundCustomPayloadPacket` 為 32767 bytes，上傳分段每段 30000 bytes（`Payloads.UPLOAD_CHUNK_BYTES`）
- [x] DataFixer 升級舊藍圖 BlockState 的 API 用法
  → `DataFixers.getDataFixer().update(References.BLOCK_STATE, new Dynamic<>(NbtOps.INSTANCE, tag), fromVersion, currentVersion)`（見 `StateResolver`）
- [x] 模組 ID / 名稱：`maidbuilder` / Maid Builder
- [x] TLM 依賴來源：Modrinth maven `maven.modrinth:touhou-little-maid:1.5.3-neoforge+mc1.21.1`
- [x] 1.21.1 GameTest 座標：template 的第 0 層在 helper 相對座標 y=1

---

## 8. 開發階段與驗收標準

### Phase 0 — 環境
- 從 MDK 建立多專案結構（`core` + `neoforge`），加入 TLM 1.5.3 為依賴（Modrinth/CurseForge maven）。
- ✅ `runClient` 能進遊戲、TLM 正常載入；`core` 的 JUnit 能跑。

### Phase 1 — 藍圖解析（純 core）
- `LitematicReader`、`PackedBitArray`、座標轉換、`MaterialList`。
- ✅ 單元測試通過（§3 驗收）；旋轉/鏡像後屬性（facing、axis、shape、half、hinge）正確。

### Phase 2 — 指令貼上（不用女僕）
- `/maidbuilder paste <file> <x y z> [rot] [mirror]` 直接放置到世界。
- ✅ 放出來的建築與 Litematica 貼上結果一致（樓梯、門、床、紅石元件正確）。
- 這一步同時驗證 StateResolver、BuildPlanner 順序、兩格方塊處理。

### Phase 3 — 幽靈預覽 + 藍圖魔杖 【已完成，待玩家實機驗收 FPS】
- 客戶端選檔 UI、魔杖操作、半透明渲染、錯誤格標紅。
- ✅ 1000+ 方塊的藍圖預覽 FPS 無明顯下降。
- 實作：每 16³ 區段一個 VertexBuffer；每 tick 最多比對 8192 格、只在區段結果改變時重建、每幀最多重建 2 個區段、160 格外與視錐外不畫。
- 箱子、床等「方塊實體渲染」的方塊無法烘焙成幽靈模型，改畫淺藍框。
- 開發用渲染冒煙測試：`gradlew :neoforge:runClientSelfTest`（需 `neoforge/run/saves/selftest` 世界），會輸出 `screenshots/maidbuilder_selftest*.png`（預覽、材料清單、設定畫面）。

### Phase 4 — 網路與 BuildJob 【大致完成，待專用伺服器實測】
- 分段上傳、伺服器存檔、SavedData 持久化、進度同步。
- 已完成：分段上傳（伺服器主動要求、大小上限、SHA-1 驗證）、SavedData、每 2 秒同步進度與缺料給手持魔杖的玩家。
- ✅ 專用伺服器上測試；重啟伺服器後工作仍在。

### Phase 5 — 女僕建築師（MVP 完成點）
- 任務註冊、四個 Behavior、手動把材料塞進女僕背包。
- ✅ 女僕能獨立蓋完一間 7×7 含門窗、樓梯屋頂、火把的小屋。

### Phase 6 — 材料補給 【已完成】
- 綁定材料箱、缺料時自動去拿、缺料清單 HUD。
- 已連結的魔杖右鍵容器即可加入／移除材料箱（任何提供 ItemHandler capability 的容器都可以）。
- 女僕只在「背包裡沒有可蓋的方塊」時才去拿料，一次拿接下來 128 個方塊所需的量（受背包空間限制）。

### Phase 7 — 進階（分批進行）

**第一批【已完成，待玩家實機驗收】**
- [x] **搆不到高處**：先在工作範圍內找路；找不到就自己搭鷹架、爬上去蓋，完工後回收所有鷹架（見 §5.4）。
  - 任務選擇頁提示：說明多一行「給予鷹架，她會自己搭鷹架到高處……」，條件列顯示「身上有鷹架」（綠／紅）。
  - TLM 的女僕只有在沿著含垂直段的路徑移動時 `onClimbable()` 才為真，所以爬升／下降的速度由 `BuilderScaffoldTask` 直接控制（同 TLM 的 `MaidClimbTask` 做法），下降時同時潛行穿過鷹架並重置摔落距離。
  - GameTest `maidUsesScaffoldingForHighBlocks`：10 格高柱子，沒鷹架時只蓋到搆得到的高度且不放棄，給鷹架後完工並回收全部鷹架。
  - ✅ 實機（2026-09-29）：有屋頂的房屋會搭鷹架蓋屋頂；多隻女僕同一工作不衝突；非家模式可建造但主人需待在附近（`ownerRangeWithoutHome`）。
- [x] **取消工作**（實機發現：解除魔杖連結後女僕仍繼續蓋）：連結中的魔杖潛行＋右鍵兩次（3 秒內）＝取消工作。`BuildJob.cancelled`（存檔）→ 女僕停工、拆除鷹架，最後一柱拆完即刪除工作；綁定已刪除工作的女僕自動解除綁定。`/maidbuilder job remove` 走同一流程。已完成的工作只解除連結、不需確認。
- [x] **大型／高聳建築被傳送回工作範圍**（實機發現）：不改 TLM 設定，由 `BuilderRangeHandler`（TLM `MaidTickEvent`）處理。
  - TLM 的 `SchedulePos.tick` 每 40 tick 把活動範圍重設為「工作點＋`MaidWorkRange`」，超出就傳送回工作點；尋路（`MaidNodeEvaluator`）也不會走出這個範圍。
  - 女僕有需要她的工作（未完成，或還有鷹架要拆）、家模式、日程為工作、職業為建築師時：每 tick 把 TLM 工作點設為她所在位置（檢查永遠通過），並把活動範圍設成以建築為中心、涵蓋建築＋`workAreaMargin`＋材料箱的球。
  - 原本的工作點存在 `BuilderMaidData.savedWorkPos`，條件不成立時立即還原；期間玩家若自行改了工作點，以玩家的為準。
  - 注意：若在工作途中移除本模組，女僕的工作點會停在最後位置。
  - GameTest `maidBuildsFarFromWorkPoint`（工作點在 40 格外；沒有這個處理時 0/13）、`cancelStopsMaidAndClearsScaffolding`。
- [x] **藍圖魔杖貼圖**：`Asset/wand.jpg` 去背縮成 32×32（`textures/item/blueprint_wand.png`）。
- [x] **藍圖魔杖合成配方**：TLM 祭壇（`touhou_little_maid:altar_recipe_serializers`），鐵鎬、鐵鏟、鐵斧、鐵鋤、鐵頭盔、蛋糕，消耗 0.2 P 點（與多數 TLM 道具相同）。
- [x] **遊戲內建立藍圖**：獨立物品「藍圖羽毛筆」（`blueprint_quill`，合成：紙＋羽毛＋青金石，無序）。
  - 右鍵方塊標兩角（第三下重新開始），對空氣右鍵輸入檔名存檔，潛行＋右鍵清除；手持時顯示選區框線與 HUD。
  - 伺服器擷取方塊 → `LitematicWriter` → 每段 256 KiB 下載給客戶端，寫入玩家自己的 `schematics/`（專用伺服器與單人一致）。
  - `/maidbuilder save <檔名> <from> <to>`（需 OP）走同一流程。
  - 只存方塊狀態，**不存 BlockEntity 內容與實體**：避免在伺服器上藉此讀取別人箱子的內容；建造時本來就不套用容器內容。
  - 檔名消毒（不可含路徑與 Windows 保留字元、最長 64 字）；客戶端只會寫 `schematics/<檔名>.litematic`，未經玩家確認不覆蓋既有檔案（改用 `_2`、`_3`…）。
  - 上限 `maxCaptureVolume`（預設 256³），每位玩家 2 秒冷卻，範圍內區塊必須已載入。
  - GameTest `captureRoundTrip`：擷取 → 讀回 → 貼到旁邊逐格比對。
  - ✅ 待實機：存出的檔案用 Litematica 開啟。
- 羽毛筆貼圖目前是暫代的 16×16 像素圖，可替換 `textures/item/blueprint_quill.png`。

**第二批：多女僕協作優化【已完成，待實機驗收】**

以 GameTest 基準量測（tick，越少越好；`hallOneMaid/hallThreeMaids`、`towerOneMaid/towerThreeMaidsWithScaffolding`）：

| 情境 | 1 隻（前 → 後） | 3 隻（前 → 後） |
|---|---|---|
| 12×12 大廳 276 塊，材料全在一個材料箱 | 8420 → 1892 | 3460 → 583 |
| 5×5×10 空心塔（需鷹架） | — → 1457 | 6828 → 609 |

找到並修正的瓶頸：
- [x] **材料分配不均**：第一隻女僕一次拿走 128 格份量，第三隻只拿到 20 塊就閒置。改為每隻只拿「剩餘數量 ÷ 參與女僕數」（上限 128），隊伍人數＝近期在做這份工作的女僕與附近綁定同工作的建築師女僕取大者。結果 91/91/91，各跑一趟。
- [x] **同層就近分散**：`BuildJob.findBest` 在「同一建造階段、同一高度」的步驟中挑離自己最近的，且在隊友已認領方塊 3 格內加罰分，女僕自然分散；仍維持由下往上的順序。單隻女僕也因少走路而變快。
- [x] **不空等**：成功認領後立刻可以找下一塊（原本每次搜尋間隔 10～19 tick），放置間隔固定 5 tick 以保持自然。
- [x] **鷹架互相卡住**：隊友佔用的鷹架柱不再被選用，改在旁邊另搭一柱；因「柱子被佔用／身上沒鷹架」而暫緩的方塊只對該女僕暫緩（`BuilderSession.defer`），不再讓已在鷹架上的隊友也跳過它們。原本塔的案例每爬一次只放 1 塊（58 次爬升），修正後 10 倍快。
- [x] **騰空時搜尋誤判**：原版尋路在實體不著地時不產生路徑，女僕跳躍／剛生成時搜尋會把地面方塊誤記為「搆不到」。改為只在著地或水中時搜尋。
- [x] HUD 工作列顯示目前參與的女僕數。
- [x] 實機驗收（2026-09-29）：女僕會分散；高處各搭各的柱子，完工後全數回收。
- [x] **大箱子只算一個容器**：點任一半都是同一筆（`MaterialContainers.canonical`），已綁定時點另一半＝解除；讀取時以 `MaterialContainers.distinct` 去重，HUD 不會把存量算兩次。
- [x] **拿料退路**：接下來幾塊需要的材料箱子裡沒有時，改拿箱子裡「之後才會用到」的材料先蓋那些，不再原地等。
- [x] 修正：鷹架補給清單是不可變 Map，拿取時會拋例外。
- [x] **隊友分料**（邊界測試：A 先拿完材料、B 才加入）：箱子沒貨時，女僕會走到身上材料比她多的隊友旁，拿走差額的一半（至少 4 個才值得走）。GameTest `lateJoinerSharesTeammateMaterials`、`doubleChestLateJoiner`、`doubleChestBindsOnce`。

**第三批：材料清單 GUI【已完成，實機測試正常（2026-10-06），待專用伺服器實測】**

玩家回饋：材料清單用聊天室訊息看不清楚。**不新增道具**，把已連結魔杖「右鍵空處」的進度摘要（原 `WandActions.showSummary`）改成打開材料清單畫面。原本的文字摘要保留在 `/maidbuilder job info <id>`，方便除錯。

- [x] **資料在伺服器端計算**：客戶端讀不到遠處箱子的內容。右鍵後由伺服器統計，用新的 clientbound payload（`MaterialReport`）送出一份快照，GUI 只負責顯示。
  - 每個物品一筆：`item`、剩餘需求、總需求、玩家背包數量、材料箱數量、女僕背包數量。
  - 剩餘需求沿用 `BuildJob.remainingMaterials()`，總需求為新增的 `totalMaterials()`；方塊與物品的對應（門／床一個物品對兩格、紅石線換紅石粉、水／岩漿換桶、`extraItems`）沿用 `requirementsFor` / `MaterialRules`，確保 GUI 的數字和女僕實際要拿的量一致。
  - 材料箱：`MaterialContainers.distinct(materialSources())` + `count()`，大箱子不重複計算；未載入的箱子不計入，GUI 上註明有幾個箱子未載入。
  - 女僕背包：綁定此工作、且在已載入區塊內的女僕（`getAvailableInv(false)`）。女僕已經拿走的材料要算進去，否則會被誤判成缺料。
- [x] **畫面**：一般 `Screen`，不用 container menu（不需要物品格）。
  - 頂部：工作名稱、進度（已完成／總數）、待玩家處理數、參與女僕數、材料箱數。
  - 可捲動列表，每列顯示「物品圖示＋名稱＋擁有／剩餘需求」。
  - 三種狀態：✔ 足夠（綠色，整列變暗）／部分足夠，例如 `32 / 64`（黃色）／完全沒有（紅色）。
  - 預設排序：缺料在前，再依缺少數量排。可切換為依名稱排序；有搜尋框。
  - Tooltip：分別列出玩家、材料箱、女僕各有多少，以及總需求量（超過一組時顯示「幾組＋幾個」）。
  - 畫面開著時，每 20 tick 向伺服器要一次新快照（另有「重新整理」按鈕）；伺服器端每位玩家每 10 tick 最多回應一次（開啟畫面不受限制）。
- [x] **尚未確認建造時也能開啟**：魔杖已放置、還沒確認建造時沒有 BuildJob，也沒有材料箱，只能比對玩家背包。需求量改由 `GhostPreview` 在建立預覽時順便計算（沿用 `BuildJob.requirementsFor`，不用 core 的 `MaterialList`，這樣門、床、壁掛火把等的換算才會和女僕一致），「剩餘需求」直接使用預覽已在進行的世界比對結果，已經放好的方塊不計入。
  - 開啟方式：新按鍵「材料清單」（預設 `B`，`WandKeys.MATERIALS`），已連結和未連結的魔杖都能用；已連結時等同右鍵空處。畫面開著時再按一次 `B` 即可關閉。
  - 注意：預覽一律包含流體，所以伺服器設定 `placeFluids=false` 時，確認前的清單可能會多列出水桶或岩漿桶。
- [x] 語言檔（`zh_tw`、`zh_cn`、`en_us`），HUD 按鍵提示與魔杖 tooltip 也已更新。
- 實作：`MaterialReports`（伺服器端統計）、`Payloads.RequestMaterialReport` / `MaterialReport` / `MaterialRow`、`MaterialListScreen`。
- GameTest `materialReportCountsAllSources`：玩家、大箱子（兩半都綁定也只算一次）、女僕三種來源分開計算，已完成的方塊只計入總需求，未載入的材料箱會被計數。
- `runClientSelfTest` 另外輸出 `screenshots/maidbuilder_selftest_materials.png`（確認前的清單，三種狀態都有）。
- ✅ 驗收：
  - 500 種以上材料的大型藍圖可以順暢捲動，payload 不超過上限（上限 4096 列，每列約 10 bytes）。
  - 數字和女僕實際拿料的量一致（門、床、紅石、水桶）。
  - 大箱子不重複計算；未載入的箱子不會造成錯誤。
  - 專用伺服器上測試。
  - GameTest：伺服器端統計函式（玩家＋箱子＋女僕三種來源、雙箱子）。

**第四批：原版 `.nbt` 與設定畫面【已完成，實機測試正常（2026-10-06）】**
- [x] **原版結構 `.nbt`**：core 新增 `StructureReader`（size／palette 或 palettes 取第一組／稀疏 blocks 清單，含 BlockEntity）。`SchematicReader` 依**內容**判斷格式（不看副檔名），所有讀取處（`SchematicStore`、`ClientSchematics`、上傳驗證）都改用它，工作副本仍存為 `<sha1>.litematic`（內容可能是 .nbt），舊存檔不受影響。
  - 檔案沒列出的位置（結構空位）視為空氣：女僕不會放，也不會清除世界原有的方塊。
  - 拒絕 1.13 之前的檔案（DataVersion < 1631），與 .litematic 一致。
  - 選檔畫面與 `/maidbuilder list` 同時列出 `.litematic` 與 `.nbt`；指令中省略副檔名時依序找 `.litematic`、`.nbt`。
  - 測試：core `StructureReaderTest`（4 項）；GameTest `vanillaStructureRoundTrip` 用原版 `StructureTemplate` 存檔 → 本模組讀取 → 貼上逐格比對（含結構空位、門、箱子）。
- [x] **設定畫面**：使用 NeoForge 內建 `ConfigurationScreen`（`MaidBuilderClient`，`@Mod(dist = CLIENT)`），從模組列表的「設定」打開；所有選項都有三種語言的名稱與說明。
  - 新增**客戶端設定** `MaidBuilderClientConfig`：`showHud`、`ghostOpacity`（改變時預覽逐步重建）、`previewRenderDistance`、`showWrongBlocks`、`showBoundingBox`。
  - 伺服器設定只能在單人世界中修改（NeoForge 對連線中與區網開放的世界停用編輯）。
  - `runClientSelfTest` 另外輸出 `maidbuilder_selftest_config0..3.png`（總覽、客戶端、伺服器、建造頁）。

**第五批：光影相容與鷹架加高【已完成，待實機驗收】**
- [x] **光影相容**（實機回饋：開光影時預覽不顯眼）：光影包會把在世界繪製階段畫的半透明方塊當成水／玻璃重新打光、混色，BSL 與 Complementary Unbound 下幽靈方塊幾乎變成實心，和真實方塊分不出來。
  - 偵測到光影包使用中（`ShaderCompat`，以反射呼叫 Iris 公開 API `IrisApi.isShaderPackInUse()`，Iris 不是依賴）時，預覽改在 `RenderLevelStageEvent.Stage.AFTER_LEVEL` 繪製：此時 Iris 已完成最終合成，但深度緩衝尚未為手部清除，所以預覽保持原本的半透明外觀，仍會被前方的方塊與實體正確遮擋（截圖確認女僕會擋住預覽）。
  - 此階段全域 model-view 已不含相機旋轉，畫框線前會暫時推入（`GhostPreview.render`）。
  - 客戶端設定 `shaderOverlay`（預設開）：關閉則交給光影包處理，與舊行為相同。沒有光影時完全走原本的路徑。
  - 開發用：`gradlew :neoforge:runClientShaderTest`，遊戲目錄 `neoforge/runs/shaders`（已被 git 排除），`mods/` 放 Iris 1.8.12 + Sodium 0.6.13（NeoForge 1.21.1），`shaderpacks/` 放光影包，`config/iris.properties` 選擇使用哪個。自測會另外輸出 `maidbuilder_selftest_inlevel.png`（關閉疊加時的樣子）以便比較。已用 BSL v10.1.8 與 Complementary Unbound r5.9.3 驗證。
- [x] **鷹架原地加高**（實機回饋：需要更高的鷹架時，女僕會先滑到地面再接）：站在頂端把搆得到的都蓋完後，若「照原本順序下一塊要蓋的」（與 `BuilderFindTargetTask` 共用 `pickNext`）在上方、且把同一柱加高就搆得到（`ReachPlanner.raiseColumn`），就認領該方塊，從頂端直接往上接鷹架（原版鷹架點任一格都會接在頂端）再往上爬，省掉滑下、走回柱腳、從底部重爬。鷹架不夠、上方被擋、超過 `maxScaffoldHeight`、柱子被隊友佔用或下一塊在別處時，照舊滑下去。
  - 新增基準 GameTest（`floor16_tall`）：`tallPillar`（18 格單柱）、`tallTowerOneMaid` / `tallTowerThreeMaids`（5×5×18 空心塔）。
  - 結果（tick，兩次執行相同）：18 格單柱 605 → **404**（-33%）、18 格塔 3 隻 1293 → **1112**（-14%）、18 格塔 1 隻 3238 → **3088**（-5%）、10 格塔 3 隻 609 → **574**（-6%）；其他基準（大廳、10 格塔 1 隻、分料）不變，22 個 GameTest 全部通過。
  - 單隻女僕蓋高塔的提升有限：下一塊常在另一面牆（另一柱），仍需下去換柱。之後可考慮橫向延伸鷹架。

**第六批：藍圖羽毛筆框選改良【已完成，待實機驗收】**
玩家回饋：兩個角落都必須點在方塊上——非方形建築的第二角常常沒有方塊可點；上層比底層突出時第一角得點在地形上。
- [x] **點建築自動框選整棟**（`StructureDetector`）：從點到的方塊往外找相連（含斜角，26 鄰）的非地形方塊，框住它們。地形＝空氣、流體、可取代方塊（草、蔓藤、雪層…）、泥土／沙／天然石頭／礫石／礦石／樹葉／花／樹苗／冰雪／基岩／黏土／甘蔗／仙人掌／竹子／海草。上限 65536 個方塊與 `maxCaptureVolume`，超過或碰到未載入區塊就改為手動框選。點到地形或孤立方塊時直接當第一個角落。
  - 自動框選後**待確認**（金色框，記住點到的方塊為 `anchor`）：推拉選區或對空氣右鍵＝確認（對空氣右鍵同時開啟存檔畫面）；潛行＋右鍵＝取消自動，改為「第一個角落＝點到的方塊」，接著手動選第二個角落。其他狀態下潛行＋右鍵仍是清除。
- [x] **空中標記角落**：對空氣右鍵在視線前方 N 格（預設 5，1～64）標記角落（第一或第二個都可以），只有第一個角落時 PgUp／PgDn 調整 N，畫面上會顯示將標記的位置。
- [x] **推拉選區**：有框時 PgUp／PgDn 把「指著的那一面」往外推／往內拉 1 格（按住 Shift 一次 5 格，最小 1 格）。從框外指著框時取射線碰到的面，在框內或沒指到框時取視線方向的面；會移動的面以綠框標示，HUD 顯示面的名稱與尺寸。
- [x] 羽毛筆改用 `onItemUseFirst`：點門、箱子等可互動方塊時是標記，不會打開它們。
- [x] 按鍵由 `PreviewManager.handleKeys` 統一分派：手持魔杖時照舊，手持羽毛筆時 PgUp／PgDn 送 `Payloads.QuillAdjust`。
- 實作：`CaptureArea` 新增 `anchor`、`airDistance`（存檔欄位皆為選用，舊物品可直接讀取），完成的框一律存成最小角／最大角；伺服器邏輯在 `QuillActions`。
- GameTest `quillSelectsBuilding`：小屋＋斜角相連的突出部分（會框進去）、旁邊的泥土與草和遠處的孤立方塊（不會）、取消後保留起點、空中第一與第二角落、調整空中距離、推拉東面與從框內推頂面、體積上限。23 個 GameTest 全部通過，效能基準不變。
- `runClientSelfTest` 另外輸出 `maidbuilder_selftest_quill.png`（在玩家前方蓋小屋並自動框選，截圖後拆除）。

**正式版 1.0.0（2026-10-06）**：包含第一～六批。`mod_version=1.0.0`，jar 檔名 `maidbuilder-release_<版本>_neoforge<MC 版本>.jar`（`neoforge/build.gradle` 的 jar 任務）；需求 NeoForge 21.1.219 以上、TLM 1.5.3 以上。CurseForge 描述已更新（`curseforge/`）。

**之後的批次**
- 拆除錯誤方塊、BlockEntity 資料套用（告示牌文字等）。
- 鷹架橫向延伸／在相鄰柱之間移動，減少單隻女僕蓋高塔時的上下次數。
- 擷取時選擇性保存安全的 BlockEntity 資料（告示牌、旗幟），仍排除容器內容。
- （選用）maid_storage_manager 整合：偵測到 MSM 時，建築師女僕缺料可向倉管女僕發出 `RequestItemWish`。
