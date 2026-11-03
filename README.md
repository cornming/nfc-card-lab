# NFC 讀卡驗證（nfc-card-lab）

一個 Android App，用來回答一個問題：**我手上這張閒置 IC 卡，手機讀不讀得到？UID 穩不穩定？能不能拿來當「鑰匙」？**

這是「用閒置 IC 卡刷手機開電鎖」sideproject 的早期版本。目前的「動作」是**模擬開鎖**，**不會控制任何真實裝置**。

## 它做什麼

1. 刷卡後顯示 UID、卡片支援的 NFC 技術、ATQA／SAK、MIFARE Classic 型號、NDEF 容量等資訊。
2. 自動判斷這張卡適不適合用 UID 辨識（例如偵測「隨機 UID」）。
3. **穩定性測試**：連續刷同一張卡，確認 UID 每次都一樣。
4. **登錄白名單**：把卡登錄起來，之後刷卡會顯示「已登錄」或「未登錄」。
5. **複製結果**：一鍵複製完整報告（含手機型號），方便貼給別人或存進筆記。
6. **動作與冷卻**：已登錄的卡刷過後會執行動作（目前是模擬開鎖），成功後 5 秒內不會重複執行；未登錄的卡一律拒絕。
7. **操作紀錄**：每次刷卡的判斷結果與動作結果都會記下來（最近 50 筆，只存在手機內）。也可以按「手動測試動作」單獨測試動作。

## 安裝

1. 到這個 repo 的 **Actions** 分頁，打開最新一次成功的「Build APK」，下載 `nfc-card-lab-debug-apk`（解壓縮後是 `app-debug.apk`）。
2. 把 APK 傳到手機，點開安裝。Android 會要求你允許「安裝未知來源的應用程式」。
3. 打開 App，確認手機的 NFC 已開啟，把卡片貼近手機背面（天線位置依機型而異）。

這是 debug 版本，只適合自己測試。

**關於簽章**：Android 不允許用不同簽章的 APK 覆蓋安裝。0.1.0 是用隨機 debug 金鑰簽署，0.1.1 起改用固定金鑰，所以從 0.1.0 升級時要**先解除安裝一次**（已登錄的白名單會一起清掉）。之後用同一把金鑰簽署的版本都可以直接覆蓋安裝。固定金鑰的設定方式見下方「固定簽章」。

## 怎麼看結果

| 畫面 | 意思 |
|---|---|
| 完全沒反應 | 卡片可能是手機讀不到的 125 kHz 類型、位置沒對準，或卡片本身有問題。 |
| 讀到 UID，沒有警告 | 這張卡可以當 UID 辨識的候選。再做穩定性測試。 |
| 「隨機 UID」警告 | 4 bytes UID 且第一個 byte 為 08，依 ISO/IEC 14443-3 通常代表隨機 UID，不能拿來辨識。 |
| 穩定性測試顯示「與上一次不同」 | 如果刷的是同一張卡，代表 UID 不固定，不適合。 |
| SAK 像 MIFARE Classic 但手機沒有 MifareClassic 技術 | 手機的 NFC 晶片可能不支援 Classic，但 UID 仍然讀得到，不影響辨識。 |

注意：UID 可以被複製，**白名單只是辨識，不是安全機制**。

## 隱私

- 沒有網路權限，讀到的資料不會離開手機。
- 白名單存在 App 私有儲存空間，且關閉了備份。
- 只讀取卡片探測階段就有的資料，不對卡片發送寫入指令，也不嘗試驗證 MIFARE 金鑰。

## 後續規劃

| 階段 | 內容 |
|---|---|
| M0／M1 | 讀卡、驗證、白名單（已完成） |
| M3 的一部分（本版 0.2.0） | 「刷卡 → 規則 → 動作」的流程，動作是模擬；規則有「未登錄拒絕」與「冷卻時間」；操作紀錄 |
| M2 | 把模擬動作換成真實通道（Google Home APIs 或 eWeLink 開放 API），兩者的前置條件與限制見 [docs/unlock-channels.md](docs/unlock-channels.md) |
| M4 的其餘部分 | 時段限制等規則 |

## 固定簽章

金鑰**不放在 repo 裡**。簽章設定讀取環境變數（`NFC_KEYSTORE_FILE`、`NFC_KEYSTORE_PASSWORD`，別名預設 `nfc-card-lab`，可用 `NFC_KEY_ALIAS` 覆寫）；沒有提供時退回 Android 預設的 debug 金鑰。

在 GitHub Actions 使用固定金鑰：到 repo 的 Settings → Secrets and variables → Actions，新增兩個 repository secrets：

| Secret | 內容 |
|---|---|
| `NFC_KEYSTORE_BASE64` | 金鑰檔（PKCS12）的 Base64 編碼 |
| `NFC_KEYSTORE_PASSWORD` | 金鑰檔密碼（金鑰密碼與它相同） |

沒有設定這兩個 Secrets 時，CI 仍會編譯成功，但會顯示警告，APK 用隨機金鑰簽署。每次 CI 會印出 APK 的簽章憑證指紋，可用來確認金鑰有沒有固定。

**金鑰檔要自己備份**：遺失之後只能重新產生，已安裝的 App 必須先解除安裝才能換新金鑰。

## 狀態

- 已通過單元測試（讀卡分析與刷卡規則）並可編譯。0.2.0 新增的流程與操作紀錄**尚未在實體手機上驗證**。
- 已在 Google Pixel 10 Pro（Android 17）實機測試過讀卡：能讀到 MIFARE Classic 1K 卡的 UID，同一張卡連續刷 8 次 UID 一致。
- 尚未實測的是後續階段（控制裝置）。有問題請用「複製結果」把報告貼回來。

## 自行建置

需要 JDK 17 以上與 Android SDK（compileSdk 35）。

```
./gradlew testDebugUnitTest assembleDebug
```

APK 位置：`app/build/outputs/apk/debug/app-debug.apk`。

## 程式結構

- `CardAnalyzer.kt`：純邏輯（SAK 對照、隨機 UID 判斷、穩定性追蹤），有單元測試。
- `TagReader.kt`：把 Android 的 `Tag` 轉成純資料。
- `CardStore.kt`：白名單儲存。
- `MainActivity.kt`：畫面與 NFC reader mode。

只使用 Android 內建 API，沒有任何第三方執行期相依套件。
