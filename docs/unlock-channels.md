# 開鎖通道比較

查證日期：2026-11-03。標「未驗證」的是我沒有實測或沒找到來源的部分。

目標：刷卡後，讓接在 PSF-B01-GL 開關上的電鎖開一下。App 的動作層是抽象介面（`Actuator`），下面兩個通道各寫一個實作即可。

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

## 目前 App 的狀態

v0.2.0 的動作是**模擬開鎖**（`DryRunActuator`），不會控制任何真實裝置。刷卡、白名單、冷卻時間、操作紀錄都已實作並可實測。換成真實通道時，只要新增一個 `Actuator` 實作。
