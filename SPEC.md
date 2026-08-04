# TRADING EXEC LAB — 實作規格書 (SPEC.md)

> 交付對象：Claude Code
> 專案性質：作品集導向的交易系統後端（Java / Spring Boot）
> 版本：v1.0
> 目標時程：2026/08 – 2026/10（Phase 0–5）；2027/01–02（Phase 6，選配）

---

## §0. 專案目的與成功定義

### 0.1 這個專案是什麼

一個**獨立於任何實盤系統之外**的交易執行服務（Execution Service），用來展示生產級後端能力：訂單狀態機、冪等性、事件驅動、複式記帳帳本、對帳、可觀測性。

### 0.2 這個專案不是什麼

- **不是策略研究專案**。本 repo 不包含任何 alpha 邏輯、訊號、回測。策略是外部輸入。
- **不是實盤交易系統**。全程只連交易所測試網。
- **不追求獲利**。績效數字不是本專案的產出物。

### 0.3 成功定義（作品集標準）

完成時必須同時滿足：

1. 陌生工程師 clone 後，執行 `docker compose up` + `make demo`，5 分鐘內能看到一筆訂單走完完整生命週期
2. README（英文）含架構圖，說明每個設計決策的**取捨**而非只有功能列表
3. 每個 Phase 至少一份 ADR（Architecture Decision Record）
4. 整合測試涵蓋所有失敗路徑（重送、亂序、斷線、部分成交、對帳差異）
5. 作者本人能對任一核心流程做 10 分鐘白板講解，並回答「為什麼不用另一種做法」

---

## §1. 隔離規則（HARD CONSTRAINTS — 最高優先級）

> **背景**：作者另有一套實錢運行中的網格交易 bot。本專案的任何行為都不得影響它。
> 以下規則的優先級**高於**本文件其他所有內容。任何實作若與本章衝突，一律以本章為準。

### 1.1 Repo 隔離

- **必須**是全新的獨立 repo（建議名稱 `trading-exec-lab`）
- **禁止** import、複製、貼上任何既有實盤專案（`pionex-signal-bot` 等）的程式碼
- **禁止**以 git submodule、symlink 或任何形式引用既有專案目錄
- 若需要參考既有專案的概念，用文字重新描述，不搬程式碼

### 1.2 執行環境隔離

- **禁止**部署到現有的 GCP VM（該主機為 e2-micro，1GB RAM，已承載實錢 bot 與多個容器；本專案的 JVM + PostgreSQL + Kafka 記憶體需求約 3GB，會觸發 OOM Killer 並可能終止實盤程序）
- 開發環境：**本機開發，本機執行**
- 若日後需要遠端展示，另開獨立主機，不共用現有 VM
- Docker network 使用專案專屬 network，不得使用 `host` 模式或既有 network

### 1.3 憑證隔離

- **禁止**讀取、引用、複製任何既有專案的 config、`.env`、API key
- **禁止**在任何檔案中出現實盤交易所（Pionex）的 API key，即使是註解或測試假資料
- 本專案僅使用 **Binance Spot Testnet** 的測試金鑰
- 所有金鑰經環境變數注入，`.env.example` 只放佔位字串
- `.gitignore` 必須涵蓋 `.env`、`*.key`、`*.pem`、`config.local.*`

### 1.4 交易行為隔離（最關鍵）

- 交易所 adapter 的 base URL **必須**由設定檔注入，且預設值為測試網位址
- 啟動時執行 **fail-fast 檢查**：若偵測到 base URL 不屬於白名單（測試網網域），程序立即終止並輸出明確錯誤，不得降級為警告後繼續執行
- 白名單以外的網域一律拒絕，**不提供任何覆寫開關**（不接受環境變數 `ALLOW_MAINNET=true` 這類後門）
- Pionex API 完全不在本專案的依賴範圍內

### 1.5 資料隔離

- 專用 PostgreSQL 實例（獨立 container、獨立 port、獨立 volume）
- 資料庫名稱與既有專案不重複
- 不掛載任何既有專案的 bind mount 路徑

---

## §2. 技術選型

| 項目 | 選擇 | 理由（寫進 ADR-0001） |
|---|---|---|
| 語言 | Java 21 | 展示主力技能；virtual threads 可用於 I/O 密集場景 |
| 框架 | Spring Boot 3.x | 與作者既有經驗一致 |
| 資料庫 | PostgreSQL 16 | 需要交易性保證與 `SELECT FOR UPDATE` |
| 訊息 | Redpanda（Kafka 相容） | 單一 binary、本機資源佔用遠低於 Kafka + ZK |
| Migration | Flyway | 版本化 schema，展示正規流程 |
| 測試 | JUnit 5 + Testcontainers | 整合測試跑真實 DB／broker，非 mock |
| 監控 | Micrometer + Prometheus + Grafana | 可觀測性是評分項 |
| 建置 | Gradle (Kotlin DSL) | — |
| CI | GitHub Actions | 綠燈 badge 是作品集的第一印象 |

**限制**：不引入 Spring Cloud 全家桶、不做微服務拆分。單一 modular monolith，模組間以 package 邊界隔離。過度工程在作品集中是扣分項。

---

## §3. 系統架構

```
                    ┌──────────────────┐
   REST API ───────►│  Order Service   │
                    │  (狀態機 + 冪等)  │
                    └────────┬─────────┘
                             │ outbox
                             ▼
                    ┌──────────────────┐
                    │    Redpanda      │
                    └────────┬─────────┘
                             │
              ┌──────────────┼──────────────┐
              ▼              ▼              ▼
     ┌──────────────┐ ┌────────────┐ ┌──────────────┐
     │   Exchange   │ │   Ledger   │ │ Reconciler   │
     │   Adapter    │ │ (複式記帳)  │ │  (定時對帳)   │
     └──────┬───────┘ └────────────┘ └──────┬───────┘
            │                                │
            ▼                                ▼
   Binance Spot Testnet              差異告警 / Kill Switch
```

### 模組職責

| 模組 | 職責 | 明確不做 |
|---|---|---|
| `order` | 訂單生命週期、狀態機、冪等控制 | 不直接呼叫交易所 |
| `exchange` | 交易所 REST / WebSocket、重試、限流 | 不寫業務邏輯 |
| `ledger` | 複式記帳、餘額、不變量檢查 | 不知道「訂單」是什麼 |
| `recon` | 內外部狀態比對、差異分類 | 不自動修正差異（只告警） |
| `risk` | 部位／金額上限、kill switch | 不做策略判斷 |

---

## §4. Phase 分期實作

> 每個 Phase 完成後必須：整合測試綠燈 → 更新 README → 寫 ADR → commit。
> **未達驗收條件不得進入下一 Phase。**

### Phase 0：骨架與護欄（估 1 週）

**交付**
- Gradle 專案、Spring Boot 啟動、`/health` 端點
- `docker-compose.yml`：app + postgres + redpanda + prometheus + grafana
- Flyway 初始 migration
- GitHub Actions：build + test
- **§1.4 的 fail-fast 網域檢查（本 Phase 必須完成，不得延後）**
- ADR-0001（技術選型）、ADR-0002（為何 modular monolith）

**驗收**
- `docker compose up` 全綠
- 單元測試：將 base URL 設為非白名單值時，應用啟動失敗且錯誤訊息明確
- CI badge 顯示 passing

---

### Phase 1：複式記帳帳本（估 1.5 週）

**交付**

資料模型：
```
accounts(id, owner_id, asset, type, created_at)
  type ∈ {AVAILABLE, LOCKED, EXTERNAL, FEE}

postings(id, entry_id, account_id, amount, created_at)
  -- amount 可正可負，DECIMAL(36,18)

entries(id, idempotency_key UNIQUE, kind, ref_id, created_at)
```

核心規則：
- 一個 `entry` 內所有 `postings` 的 `amount` 總和**必須為 0**（DB constraint 或 trigger 強制）
- 餘額由 postings 加總得出，**不存快照欄位**（避免不一致；效能問題留給 Phase 5 用物化視圖處理，並在 ADR 說明取捨）
- 所有金額用 `BigDecimal`，**禁止任何 float/double**
- 寫入一律經 `idempotency_key`，重複寫入回傳原結果而非報錯

**驗收**
- 屬性測試（property-based）：隨機產生 1000 組交易序列後，總帳仍平衡
- 併發測試：20 執行緒同時對同一帳戶扣款，餘額不得為負、不得超扣
- 重送同一 `idempotency_key` 100 次，帳本只有一筆 entry

---

### Phase 2：訂單狀態機（估 2 週）

**狀態轉移**
```
        ┌──────────────────────────────────┐
        ▼                                  │
     PENDING ──► SUBMITTED ──► PARTIALLY_FILLED ──► FILLED
        │            │                │
        │            ▼                ▼
        └────────► REJECTED        CANCELED
```

規則：
- 狀態轉移表以程式碼明確定義，非法轉移擲出例外並記錄
- 每次轉移寫入 `order_events`（append-only，可重建當前狀態）
- 冪等：client 帶 `client_order_id`，重複下單回傳既有訂單

**Outbox pattern**
- 訂單狀態變更與 outbox 寫入在**同一交易**內
- 獨立 publisher 輪詢 outbox 發送至 Redpanda，發送成功後標記
- 允許 at-least-once，消費端負責去重

**REST API**
```
POST   /api/v1/orders          下單（需 Idempotency-Key header）
GET    /api/v1/orders/{id}     查詢
DELETE /api/v1/orders/{id}     撤單
GET    /api/v1/orders          列表（分頁）
```

**驗收**
- 所有非法狀態轉移皆被拒絕，測試覆蓋完整轉移矩陣
- 同一 `Idempotency-Key` 併發送出 50 次，只產生 1 筆訂單
- 殺掉 publisher 後重啟，outbox 訊息不遺失、不重複入帳

---

### Phase 3：交易所 Adapter（估 2 週）

**交付**
- Binance Spot Testnet REST（下單／撤單／查詢）
- User Data Stream（WebSocket）接收成交回報
- 斷線自動重連 + listenKey 續期
- **重連後的狀態補齊**：重連期間可能漏掉的成交，以 REST 查詢補齊，並與本地狀態合併
- 限流處理（respect `X-MBX-USED-WEIGHT`），指數退避
- 部分成交累加，成交均價計算

**驗收（本 Phase 最重要）**
- 混沌測試：測試中隨機切斷 WebSocket，最終訂單狀態仍與交易所一致
- 亂序測試：故意將成交事件亂序、重複投遞，最終狀態正確
- 部分成交測試：一筆訂單分 5 次成交，累計數量與均價正確
- 交易所回傳 5xx 時重試，且**不產生重複訂單**（靠 `client_order_id`）

---

### Phase 4：對帳與風控（估 1.5 週）

**Reconciler**
- 定時（預設 60s）拉取交易所 open orders + balances
- 三方比對：本地訂單狀態 vs 交易所訂單狀態 vs 帳本餘額
- 差異分類：`MISSING_LOCAL` / `MISSING_REMOTE` / `STATE_MISMATCH` / `BALANCE_DRIFT`
- **只告警，不自動修正**（ADR 說明理由：自動修正在金融系統中可能放大錯誤）
- 差異寫入 `recon_breaks` 表，附完整快照供事後追查

**Risk**
- 單筆最大金額、單日最大筆數、最大持倉限制
- **Kill switch**：觸發後拒絕所有新訂單，既有訂單全撤，需人工重置
- 觸發條件：對帳差異超過閾值 / 錯誤率超標 / 手動觸發

**驗收**
- 人為在 DB 中製造不一致，reconciler 必須在 2 個週期內偵測並分類正確
- Kill switch 觸發後，新訂單一律被拒，且重啟服務後仍維持停止狀態（狀態持久化）

---

### Phase 5：可觀測性與收尾（估 1.5 週）

**交付**
- Micrometer 指標：訂單延遲分佈（p50/p95/p99）、各狀態訂單數、對帳差異數、交易所 API 錯誤率、outbox 積壓量
- Grafana dashboard JSON 進 repo，`docker compose up` 後直接可看
- 結構化日誌（JSON），全鏈路帶 `correlation_id`
- `make demo` 腳本：一鍵跑完整劇本（下單 → 部分成交 → 撤單 → 對帳 → 觸發 kill switch）
- **英文 README**：架構圖、設計取捨、如何執行、已知限制
- 錄製 90 秒 demo GIF 放 README 頂端

**驗收**
- 陌生人依 README 操作，5 分鐘內看到完整訂單生命週期
- Grafana 面板有真實數據
- README 的「Known limitations」章節誠實列出至少 5 項未處理的問題

---

### Phase 6：錢包服務（選配，2027/01–02）

> 僅在確定主攻幣圈職缺時實作。若主攻 fintech／衍生品，改做定價與風險引擎。
> **同樣受 §1 全部隔離規則約束**，鏈上部分僅使用 Ethereum Sepolia 測試網。

- 區塊掃描器 + **鏈重組處理**（回滾已入帳的充值）
- 入帳冪等鍵：`txHash + logIndex`
- HD 錢包地址派生（BIP32/44）
- 出金 **nonce 管理**（併發送出時的序號競爭）
- gas 估算、卡單 replace-by-fee
- 冷熱錢包分層與 sweep
- 鏈上餘額 vs 帳本對帳
- 私鑰經 KMS/Vault，**repo 內連測試網私鑰都不得出現**

---

## §5. 全域實作規範

1. **金額一律 `BigDecimal`**，DB 一律 `DECIMAL(36,18)`。程式碼中出現 `double`／`float` 處理金額即為 bug。
2. **時間一律 UTC**，型別用 `Instant`。
3. **所有外部呼叫都要有 timeout**，無 timeout 的 HTTP client 視為 bug。
4. **不吞例外**。catch 後必須處理或重拋，禁止空 catch 區塊。
5. **測試不用 mock 資料庫**，一律 Testcontainers 跑真實 PostgreSQL。
6. Commit message 遵循 Conventional Commits（`feat:`／`fix:`／`docs:`）。**禁止 "update" 這類無意義訊息**——commit history 是作品集的一部分。
7. 每個 Phase 一個 PR，即使是單人專案也走 PR 流程（展示協作習慣）。

---

## §6. 明確排除範圍

以下項目**不做**，且應在 README 的 Known limitations 誠實說明：

- 撮合引擎（不自建 order book）
- 策略／訊號／回測
- 前端介面（只有 REST API + Grafana）
- 多租戶、身分驗證授權
- 微服務拆分、Kubernetes 部署
- 任何實盤或主網連線

---

## §7. 交接檢查清單

Claude Code 開始前請確認：

- [ ] 已在**全新空目錄**建立 repo，與既有專案無任何檔案關聯
- [ ] 已理解 §1 的優先級高於其他所有章節
- [ ] 已確認不部署至既有 GCP VM
- [ ] `.env.example` 只含測試網佔位值
- [ ] 每個 Phase 完成前不進入下一 Phase

每個 Phase 結束時回報：完成項目、測試結果、遇到的取捨與選擇理由、下一 Phase 的風險預估。
