# Maid Builder（女僕建造）

[Touhou Little Maid](https://github.com/TartaricAcid/TouhouLittleMaid) 的附屬模組：讓女僕依照 `.litematic` 藍圖，用自己背包裡的方塊自動蓋房子。

An add-on for Touhou Little Maid: maids build structures from Litematica `.litematic` blueprints using blocks from their own inventory.

| 項目 | 版本 |
|---|---|
| Minecraft | 1.21.1 |
| NeoForge | 21.1.x（開發用 21.1.219） |
| Touhou Little Maid | 1.5.3 以上（唯一硬依賴） |
| Java | 21 |

> 不依賴 Litematica / Forgematica；只讀取它們的檔案格式。

> ⚠️ **Alpha 版**：功能已可使用，但仍可能有錯誤或在之後的版本變更存檔格式；使用前請先備份世界。

## 目前進度

完整開發藍圖見 [plan.md](plan.md)。

- [x] Phase 0：多專案骨架（`core` + `neoforge`）、TLM 依賴
- [x] Phase 1：`.litematic` 解析、旋轉／鏡像、建造順序、材料清單（`core`，JUnit）
- [x] Phase 2：`/maidbuilder paste` 指令貼上
- [x] Phase 3：選檔畫面、藍圖魔杖放置操作、幽靈預覽（錯誤格標紅）、HUD
- [x] Phase 4（大部分）：分段上傳、SavedData 持久化、進度同步（待專用伺服器實測）
- [x] Phase 5（MVP）：「建築師」女僕任務
- [x] Phase 6：材料箱補給、缺料清單 HUD
- [ ] Phase 7：進階功能（分批進行）
  - [x] 第一批：女僕搭鷹架蓋高處並回收、魔杖貼圖、魔杖祭壇配方、藍圖羽毛筆（遊戲內建立 `.litematic`）
  - [x] 第二批：多女僕協作（材料平均分配、同層分散、鷹架不互卡；3 隻女僕約為 1 隻的 2.4～3.2 倍速）
  - [ ] 之後：拆除錯誤方塊、BlockEntity 資料、`.nbt` 格式、設定 GUI

## 使用方式

### 取得道具

| 道具 | 取得方式 |
|---|---|
| 藍圖魔杖 | TLM **祭壇**：鐵鎬、鐵鏟、鐵斧、鐵鋤、鐵頭盔、蛋糕（0.2 P 點） |
| 藍圖羽毛筆 | 工作台（無序）：紙、羽毛、青金石 |

### 建造

1. 把 `.litematic` 放到遊戲目錄的 `schematics/` 資料夾（與 Litematica 相同位置）。
2. 手持**藍圖魔杖**：
   - 對空氣右鍵：開啟選檔畫面，選一個藍圖 → 出現半透明預覽
   - 對方塊右鍵：把原點移到該面上
   - `R` 旋轉、`M` 鏡像、方向鍵前後左右、`PgUp`/`PgDn` 上下（可在控制設定中修改）
   - 潛行＋右鍵：確認，建立建造工作（專用伺服器上會自動上傳藍圖）
3. 已連結工作的魔杖：
   - 右鍵自己的女僕：她綁定這個工作並切換成「建築師」任務
   - 右鍵箱子等容器：加入／移除**材料箱**，女僕缺料時會自己去拿
   - 潛行＋右鍵**兩次**：取消這個建造工作並解除連結（女僕會停工、拆掉已搭的鷹架）；已完成的工作按一次即可解除連結
4. 建議開啟女僕的「家模式」，材料箱放在她的工作範圍內（TLM 預設半徑 12）；未開家模式時她只蓋主人附近 16 格內的方塊。
5. **大型建築**：開家模式時，女僕在建造期間可以離開 TLM 的工作範圍去蓋整棟建築（不會被傳送回工作點），完工後自動恢復原本的工作範圍，不需要調整 TLM 設定。
6. **高處的方塊**：她會先在建築周圍（建築範圍外加 8 格的圓內）找能走上去的路；找不到就用背包裡的**鷹架**自己搭上去蓋，完工後把鷹架全部收回。身上和材料箱都沒有鷹架時，她會通知你，並先去蓋其他方塊。

### 建立藍圖（藍圖羽毛筆）

1. 手持藍圖羽毛筆，對兩個方塊右鍵標記兩個角落（會顯示框線與尺寸）。
2. 對空氣右鍵，輸入檔名 → 儲存到 `schematics/<檔名>.litematic`，可以直接用藍圖魔杖選取。
3. 潛行＋右鍵清除選取。只保存方塊（不含箱子內容、告示牌文字與實體）。

手持魔杖時左上角 HUD 會顯示預覽狀態（正確／缺少／錯誤），連結工作後改顯示進度與缺料清單。預覽中：半透明方塊＝待放，紅框＝放錯的方塊，淺藍框＝箱子、床等無法預覽模型的方塊。

指令：

| 指令 | 說明 |
|---|---|
| `/maidbuilder list` | 列出 `schematics/` 中的藍圖 |
| `/maidbuilder info <檔名>` | 尺寸、方塊數、材料清單 |
| `/maidbuilder paste <檔名> <x y z> [旋轉] [鏡像]` | 直接貼上（需 OP） |
| `/maidbuilder job create <檔名> <x y z> [旋轉] [鏡像]` | 用指令建立工作並取得魔杖 |
| `/maidbuilder job list` / `info <id>` | 工作列表／進度、材料箱與剩餘材料 |
| `/maidbuilder job retry <id>` | 把「需玩家處理」的方塊重新排入佇列 |
| `/maidbuilder job bind <id> <女僕>` | 用指令綁定女僕 |
| `/maidbuilder job wand <id>` / `remove <id>` | 重新取得魔杖／取消並刪除工作（女僕會先拆鷹架） |
| `/maidbuilder save <檔名> <from> <to>` | 把區域存成藍圖到自己的 `schematics/`（需 OP） |

伺服器設定檔：`<world>/serverconfig/maidbuilder-server.toml`（放置距離、流體、重試次數、材料箱距離、找路範圍 `workAreaMargin`、鷹架開關 `useScaffolding` 與最高 `maxScaffoldHeight`、上傳與擷取大小上限等）。

## 開發

```bash
./gradlew build                          # 編譯 + core 單元測試
./gradlew :core:test                     # 只跑 core 單元測試
./gradlew :neoforge:runGameTestServer    # 遊戲內自動測試（轉換對照原版、貼上、女僕建造、材料箱、魔杖流程、鷹架、擷取、配方）
./gradlew :neoforge:runClient            # 開發用客戶端（遊戲目錄：neoforge/run）
./gradlew :neoforge:runClientSelfTest    # 預覽渲染冒煙測試：需 neoforge/run/saves/selftest 世界，輸出 screenshots/maidbuilder_selftest.png
```

- 把真實的 `.litematic` 放到 `local-schematics/`（不進 git），`core` 測試會逐一驗證方塊數與 metadata。
- 參考原始碼放在 `refs/`（不進 git，只讀）。

### 專案結構

```
core/       純 Java 21，不依賴 Minecraft：NBT、.litematic 解析、座標與屬性轉換、BuildPlanner、材料清單
neoforge/   NeoForge 模組本體：StateResolver、BuildJob、女僕任務、指令、藍圖魔杖、GameTest
```

`core/` 不可 import `net.minecraft.*` / `net.neoforged.*`。

## 授權

目前暫定 All Rights Reserved。注意：Touhou Little Maid 的美術資源為 CC BY-NC-SA 4.0，Litematica / Forgematica 為 LGPLv3；本專案不複製它們的程式碼。
