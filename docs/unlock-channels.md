# 開鎖通道比較

查證日期：2026-11-03。標「未驗證」的是我沒有實測或沒找到來源的部分。

目標：刷卡後，讓接在對講機開門按鈕上的 PSF-B01-GL 開關動作一下（模擬按一下開門鈕）。App 的動作層是抽象介面（`Actuator`），下面兩個通道各寫一個實作即可。

## 通道 A：eWeLink 開放 API

來源：[CoolKit-Technologies/eWeLink-API](https://github.com/CoolKit-Technologies/eWeLink-API)（官方文件庫）。

**申請**
- 登入 [eWeLink Developer Center](https://dev.ewelink.cc)，提交應用名稱、聯絡方式、**重新導向網址（redirect URL）**、專案簡介，等待審核。
- 審核通過後在 Platform → App Management 建立應用，**目前只能建立一個應用**。

**個人開發者（免費）的限制**
- 只支援 OAuth 2.0 授權登入；憑證有效期 1 年；沒有技術支援；介面清單有限；呼叫次數有限；不保證穩定。
- 免費 APPID 的配額：每個區域每月所有介面合計 50,000 次，超過會回 HTTP 403 或錯誤碼 412。
- 單一 IP 呼叫間隔要大於等於 500 毫秒，5 分鐘內不超過 300 次。
- **最大的風險**：個人與企業申請的 APPID「目前只開放部分授權品牌的設備與主流設備類型」。PSF-B01-GL 的品牌在不在清單內，**我沒有查到清單，必須實測**（未驗證）。

**OAuth 流程**
1. 把使用者導向 `https://c2ccdn.coolkit.cc/oauth/index.html`，帶上 `clientId`、時間戳 `seq`、簽章 `authorization`、`redirectUrl`、`grantType=authorization_code`、`state`、`nonce`。簽章是以 clientSecret 為金鑰、對 `{clientId}_{seq}` 做 HMAC-SHA256 再 Base64。
2. 使用者登入後，頁面導回 `redirectUrl`，帶上 `code`、`region`、`state`。`code` 只有 30 秒有效。
3. 呼叫所在區域的 `POST /v2/user/oauth/token` 取得 accessToken。亞洲區網域是 `https://as-apia.coolkit.cc`。

**對這個專案的影響**
- clientSecret 會用來簽授權網址。若把它放進 APK，而 APK 又是公開 repo 的 CI 產物，就等於公開了。需要私下建置，或改用自己的小型後端代簽（未設計）。
- 點動：UIID 協定文件有 `pulse`（on/off）與 `pulseWidth`（500 到 3,600,000 毫秒，必須是 500 的倍數）。你的裝置適用哪個 UIID 還沒確認，所以雲端是否支援點動**未驗證**。

## 通道 B：Google Home APIs

來源：[google-home/google-home-api-sample-app-android](https://github.com/google-home/google-home-api-sample-app-android)（官方範例，Apache-2.0）與其內附的知識庫文件。

**現況**
- 你的裝置已經在 Google Home 出現：名稱「一樓大門」，類型「插座」，連結途徑 eWeLink Smart Home。
- 官方範例支援「On/Off Plug-in Unit」的開與關。「插座」與它的對應是推論，未驗證。
- 文件要求：裝置必須先能在 Google Home App 裡控制，才能用 Home APIs 控制。

**前置條件**
- SDK（`play-services-home`、`play-services-home-types` 17.1.0）**不在 Google 公開的 Maven 上**（公開的只有 2022 年的 16.0.0，且沒有 `-types`）。文件要求登入 Google Home Developers 下載後放在本機。
- Google Cloud 專案、OAuth 同意畫面（把你的 Google 帳號加為測試使用者）、Android 類型的 OAuth 用戶端（填套件名稱與**簽章憑證的 SHA-1**）。
- 實體手機（依賴 Google Play services）。
- 開放測試版。文件寫 Developer Console 尚未開放註冊；未驗證 App 的測試使用者上限是 100 人。
- 只有開與關。Google Home 送出「開」之後，裝置會不會依點動設定自動關，**未驗證**。

## 比較

| | eWeLink 開放 API | Google Home APIs |
|---|---|---|
| 裝置現況 | 品牌是否被授權：未知 | 已經連上、看得到 |
| 前置作業 | 申請開發者帳號，等人工審核 | 下載 SDK、建 Google Cloud 專案與 OAuth |
| 點動控制 | 文件有參數，雲端是否可用未驗證 | 只有開關，點動靠裝置端設定 |
| 金鑰處理 | clientSecret 需保密 | 以簽章 SHA-1 綁定，不需要放密鑰 |
| 狀態 | 免費版有限制，憑證 1 年 | 開放測試版 |

## 補充（2026-11-04）：現有的 Google Home 自動化「芝麻開門」

**使用者提供的現況（我沒有自己驗證）**
- Google Home 裡有一個自動化「芝麻開門」：起動條件是語音（用 Gemini 說），動作是開啟裝置「一樓大門」。這樣說就能開門。
- 裝置是接在傳統對講機開門按鈕上的 Sonoff 繼電器類裝置（PSF-B01-GL），**點動已在裝置端設定好**。

**Home APIs 可以直接執行這個自動化**
- 範例 App 的知識庫文件：Automations 頁會列出住家裡所有的自動化，並可「手動執行（如果支援）」；程式用 `createdAutomation.execute()` 執行，沒有手動起動點時，從第一個起動條件之後開始。
- 這比自己送「開」的指令好：保留原本的自動化內容與點動行為。
- **未驗證**：在 Google Home App 建立、以語音起動的自動化，是否算「支援手動執行」。

**不經過 SDK 的方式（都是非官方或未驗證）**
- Tasker：沒有官方介面可以執行 Google Home 自動化。社群的做法是啟動 Gemini 後用 AutoInput 填字送出（[r/tasker](https://r.darklab.sh/r/tasker/comments/1k1kjdx/how_to_launch_gemini_via_android_intent_and_send)），依賴 Gemini 畫面元件，App 更新就可能失效。Google 沒有公開能帶入文字的 Gemini Intent。
- Google Home 的「我的最愛」小工具（Android 12 以上）可以放自動化，但點一下是否直接執行**未驗證**；2025 年有 Reddit 回報主畫面捷徑只能建到「自動化資料夾」。
- Assistant SDK 文字指令：Google 標示僅供實驗與非商業用途；依 Home Assistant 文件，測試模式的憑證每 7 天過期、回應只有音訊沒有文字，無法確認門有沒有真的開。

## 目前 App 的狀態

v0.3.0 有兩種動作：**模擬**與 **HTTP POST**，每張卡各自綁定。POST 已用本機真實的 HTTP 伺服器做過整合測試，尚未對外部服務實測。**Google Home 自動化尚未實作**，需要先完成上面「通道 B」的前置條件。要新增動作種類，就新增一個 `ActionConfig` 子類別與對應的 `Actuator`。
