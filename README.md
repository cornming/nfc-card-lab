# NFC 讀卡驗證（nfc-card-lab）

一個 Android App，用來回答一個問題：**我手上這張閒置 IC 卡，手機讀不讀得到？UID 穩不穩定？能不能拿來當「鑰匙」？**

這是「用閒置 IC 卡刷手機做事」sideproject 的早期版本：**每張已登錄的卡可以各自綁一個動作**，刷卡後依規則執行。目前有兩種動作：模擬、HTTP POST。

## 它做什麼

1. 刷卡後顯示 UID、卡片支援的 NFC 技術、ATQA／SAK、MIFARE Classic 型號、NDEF 容量等資訊。
2. 自動判斷這張卡適不適合用 UID 辨識（例如偵測「隨機 UID」）。
3. **穩定性測試**：連續刷同一張卡，確認 UID 每次都一樣。
4. **登錄白名單**：把卡登錄起來，之後刷卡會顯示「已登錄」或「未登錄」。
5. **複製結果**：一鍵複製完整報告（含手機型號），方便貼給別人或存進筆記。
6. **每張卡各自的動作與冷卻**：已登錄的卡刷過後，執行這張卡綁定的動作；同一張卡成功執行後 5 秒內不會重複執行；未登錄的卡一律拒絕。
7. **操作紀錄**：每次刷卡的判斷結果與動作結果都會記下來（最近 50 筆，只存在手機內，不記錄網址、標頭或內容）。也可以按「手動測試動作」單獨測試動作。

## 動作種類

登錄一張卡之後會跳出「設定這張卡的動作」，也可以隨時按「設定動作」修改。

| 動作 | 說明 |
|---|---|
| 模擬 | 不控制任何裝置，只在紀錄裡留下一筆。用來測試流程。 |
| HTTP POST | 對你指定的網址送出 POST。 |
| Google Home 自動化 | **尚未實作**（需要 Google Home APIs 的 SDK，見 [docs/unlock-channels.md](docs/unlock-channels.md)）。 |

### HTTP POST 怎麼設定

- **網址**：`https://` 可以連任何主機。`http://` **只允許區域網路位址**（`192.168.x.x`、`10.x.x.x`、`172.16–31.x.x`、`127.x.x.x`、`169.254.x.x`）、`localhost` 與 `*.local`，避免明碼資料走到網際網路。網址不能含帳號密碼，請改用標頭。
- **Content-Type**：預設 `application/json`。
- **標頭**：每行一個，格式 `名稱: 值`，例如 `Authorization: Bearer xxxx`。
- **內容**：預設 `{"uid":"{uid}","time":"{time}"}`。可以使用 `{uid}`（卡片 UID）、`{label}`（卡片名稱）、`{time}`（刷卡時間，ISO-8601）。Content-Type 含 `json` 時，代入的值會自動做 JSON 跳脫。
- **成功的判斷**：伺服器回應 HTTP 2xx 才算成功。**不會自動跟隨轉址**（3xx 視為失敗），逾時 8 秒。
- **網址與標頭可能含密鑰**，以明碼存在這支手機的 App 私有空間（已關閉備份），不會顯示在畫面摘要或操作紀錄裡。

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

## 疑難排解：刷第一次沒反應，要開啟 App 後再刷一次才成功

這是 Android NFC 的架構限制，不是程式邏輯錯誤：App 的讀卡（reader mode）**只在 App 已經在前景（已開啟）時才會生效**。手機解鎖後，如果 App 還沒開啟或留在背景，第一次刷卡只會把 App 叫起來，那次的卡片資料不會被處理；要等 App 進到前景，再刷第二次才會成功。

0.3.1 起，`AndroidManifest.xml` 加了 NFC 的 `TECH_DISCOVERED` 設定，讓系統在 App 不在前景時，也能把卡片資料一起送進來，由 App 立刻處理，理論上**第一次刷卡就能成功**，不用再刷第二次。

這項改動**尚未在實體手機上驗證**，有兩個已知的行為變化請留意：
- 手機螢幕有亮、App 不在前景時，靠近任何 NFC 標籤（不只是你登錄的卡，例如悠遊卡、信用卡）都可能讓 App 跳出來。未登錄的卡仍會被拒絕、不會執行任何動作，只是畫面會跳出來。
- 如果手機上還裝了其他會讀 NFC 標籤的 App（例如 NFC Tools），系統可能會跳出「選擇要用哪個 App 開啟」的選單，需要你手動選一次。

## 隱私

- **網路權限只用在「HTTP POST」動作**：你替某張卡設定了 POST 網址，刷卡時才會連線，而且只連你設定的那個網址。沒有設定 POST 動作就不會有任何網路連線。沒有遙測、沒有更新檢查。
- 白名單與動作設定存在 App 私有儲存空間，且關閉了備份。
- 只讀取卡片探測階段就有的資料，不對卡片發送寫入指令，也不嘗試驗證 MIFARE 金鑰。

## 後續規劃

| 階段 | 內容 |
|---|---|
| M0／M1 | 讀卡、驗證、白名單（已完成） |
| 0.2.0 | 「刷卡 → 規則 → 動作」的流程；規則有「未登錄拒絕」與「冷卻時間」；操作紀錄 |
| 0.3.0（本版） | 每張卡各自綁定動作；新增 HTTP POST 動作；冷卻改為每張卡各自計算 |
| M2 | 新增「Google Home 自動化」動作（執行你現成的自動化），前置條件與限制見 [docs/unlock-channels.md](docs/unlock-channels.md) |
| M4 的其餘部分 | 時段限制等規則 |
| 0.3.1 | 加入 NFC `TECH_DISCOVERED` 設定，修正「App 不在前景時，第一次刷卡沒反應、要再刷第二次才成功」 |

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

- 已通過 54 個單元測試：讀卡分析、刷卡規則、網址規則、樣板、設定的儲存編碼，以及**對本機真實 HTTP 伺服器送出 POST**（方法、標頭、UTF-8 內容、5xx、轉址不跟隨、逾時、連不上）。
- 已在 Google Pixel 10 Pro（Android 17）實機測試過讀卡：能讀到 MIFARE Classic 1K 卡的 UID，同一張卡連續刷 8 次 UID 一致。
- **0.2.0 起新增的畫面（設定動作對話框、操作紀錄）、POST 對真實網路服務的行為，以及 0.3.1 的「App 不在前景時刷卡」修正，都尚未在實體手機上驗證。**有問題請用「複製結果」把報告貼回來。

## 自行建置

需要 JDK 17 以上與 Android SDK（compileSdk 35）。

```
./gradlew testDebugUnitTest assembleDebug
```

APK 位置：`app/build/outputs/apk/debug/app-debug.apk`。

## 程式結構

- `CardAnalyzer.kt`：純邏輯（SAK 對照、隨機 UID 判斷、穩定性追蹤），有單元測試。
- `AccessPolicy.kt`：刷卡規則（未登錄拒絕、每張卡各自的冷卻）。
- `ActionConfig.kt`、`Actuator.kt`：動作的設定與執行（模擬、HTTP POST）。要新增動作種類，就新增一個 `ActionConfig` 子類別與對應的 `Actuator`。
- `UrlPolicy.kt`、`Template.kt`、`HttpPoster.kt`：POST 的網址規則、樣板與 HTTP 客戶端（不依賴 Android，可在 JVM 測試）。
- `BindingCodec.kt`、`CardStore.kt`：卡片與動作設定的編碼與儲存。
- `TagReader.kt`：把 Android 的 `Tag` 轉成純資料。
- `OperationLog.kt`：操作紀錄。
- `MainActivity.kt`：畫面、NFC reader mode（App 在前景時），以及 `res/xml/nfc_tech_filter.xml` 搭配的 `TECH_DISCOVERED` Intent 處理（App 不在前景時）。

只使用 Android 內建 API，沒有任何第三方執行期相依套件。
