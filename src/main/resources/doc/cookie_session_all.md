# Cookie 與 Session 生命週期完全解剖（基於本項目實戰）

> 本文檔以當前項目 `test-login-back` 為標本，從**理論 → 源碼定位 → 斷點調試 → 日誌驗證 → 瀏覽器觀察**五個維度，把 Cookie 和 Session 的整個"出生 → 活著 → 死亡"完整講透。

> 技術棧：Spring Boot 3.2.5 + Spring Security 6.x + 內嵌 Tomcat 10.x + Servlet 6（Jakarta EE 9+）

---

## 目錄

1. [全景圖：先看劇本](#一全景圖先看劇本)
2. [Session 的誕生時機 —— 真相比你想的晚](#二session-的誕生時機--真相比你想的晚)
3. [JSESSIONID Cookie 是怎麼被寫進 Response 的](#三jsessionid-cookie-是怎麼被寫進-response-的)
4. [SecurityContext 是怎麼進 Session 的](#四securitycontext-是怎麼進-session-的)
5. [remember-me Cookie 的生命週期](#五remember-me-cookie-的生命週期)
6. [死亡：登出與超時](#六死亡登出與超時)
7. [調試斷點清單（按出現順序）](#七調試斷點清單按出現順序)
8. [日誌配置：不打斷點也能看見](#八日誌配置不打斷點也能看見)
9. [瀏覽器側觀察方法](#九瀏覽器側觀察方法)
10. [一張可以貼牆上的時序圖](#十一張可以貼牆上的時序圖)

---

## 一、全景圖：先看劇本

整個生命週期分 4 幕：

```
第一幕：用戶第一次訪問 /login
  └─ 一般情況下 Session 還"沒"創建（Spring Security 會延遲到必要時）
  └─ 但 CSRF Filter 為了存 token 會創建 Session（這是常見誤解點）

第二幕：用戶提交 POST /login
  ├─ 認證成功
  ├─ Session ID 被換新（防 Session Fixation）
  ├─ SecurityContext 寫入 Session
  ├─ JSESSIONID Cookie 通過 Set-Cookie 響應給瀏覽器
  └─ 若勾選"記住我"，再寫一個 remember-me Cookie

第三幕：後續請求 /dashboard /api/users
  ├─ 瀏覽器自動帶上 JSESSIONID Cookie
  ├─ Tomcat 用 Cookie 找到服務端 Session
  ├─ Spring Security 從 Session 拿出 SecurityContext
  └─ 業務代碼可以拿到當前用戶

第四幕：登出 / 超時
  ├─ 主動登出 → LogoutFilter 銷毀 Session + 刪除 remember-me Cookie
  └─ 超時失效 → Tomcat 後台清理線程銷毀 Session
```

**關鍵心智**：
- Session 是**懶創建**的，誰先動手調用 `request.getSession()` 誰就創建它
- JSESSIONID Cookie **不是你的代碼寫的**，是 Tomcat 在 commit response 時偷偷塞進去的
- SecurityContext 進 Session 是**認證 Filter 的職責**，不是你寫的

---

## 二、Session 的誕生時機 —— 真相比你想的晚

### 2.1 Session 不是訪問就創建

很多人以為"訪問首頁就有 Session 了"，**這是錯的**。Spring Security 6 默認的 `SessionCreationPolicy` 是 **`IF_REQUIRED`**，意思是"**有人需要才創建**"。

### 2.2 那麼"誰"會觸發創建？

按本項目實際運行順序，以下調用任一發生就會創建 Session：

| 觸發者 | 在何時 | 源碼位置 |
|--------|--------|----------|
| **CsrfFilter** | 任何請求進來時為了存/校驗 CSRF Token | `org.springframework.security.web.csrf.CsrfFilter#doFilterInternal` → `LazyCsrfTokenRepository` |
| **HttpSessionSecurityContextRepository** | 認證成功後寫 SecurityContext | `saveContext(...)` → `request.getSession(true)` |
| **UsernamePasswordAuthenticationFilter** | 登錄成功 → 觸發 SessionAuthenticationStrategy | 內部會 `request.changeSessionId()` |
| **PageController** 你的代碼 | `@GetMapping("/dashboard")` 方法簽名注入 `HttpSession` | `PageController.java:28` |
| **任意 `request.getSession()` 調用** | 任何業務代碼 | 你寫的代碼 |

### 2.3 Session 創建的"那一行"

**最終真正創建 Session 的地方在 Tomcat**：

```
org.apache.catalina.connector.Request#doGetSession(boolean create)
   ↓
Manager#createSession(String requestedSessionId)
   ↓
StandardManager#createSession  (繼承自 ManagerBase)
   ↓
new StandardSession(this)   ← 對象在這裡誕生
   ↓
generateSessionId()          ← Session ID 在這裡分配（默認 SHA1PRNG 隨機）
```

**關鍵類**：
- `org.apache.catalina.connector.Request` - HTTP 請求包裝，提供 `getSession`
- `org.apache.catalina.session.ManagerBase` - Session 工廠
- `org.apache.catalina.session.StandardSession` - Session 對象本體
- `org.apache.catalina.session.StandardSessionFacade` - 業務代碼拿到的就是這個門面

### 2.4 在本項目中觸發 Session 創建的"第一次"

訪問 `GET /login` 時：

```
Request 進入 Filter Chain
  → CsrfFilter 為了寫 _csrf token 調用 request.getSession(true)
  → 此刻 Session 第一次被創建，得到一個 sessionId
  → CsrfToken 被存入 session attribute
  → 渲染 login.html 時 Thymeleaf 讀 token 寫到表單裡
```

**你可以在 `PageController#loginPage` 第 22 行打斷點驗證**：進來前 `request.getSession(false)` 看是不是 null（其實不是 null，CsrfFilter 已經創建了）。

---

## 三、JSESSIONID Cookie 是怎麼被寫進 Response 的

### 3.1 Cookie 不是 Spring 寫的，是 Tomcat 寫的

很多人以為是 Spring Security 寫了 Cookie，**錯**。Spring Security 只負責"寫 SecurityContext 進 Session"，**Cookie 的事是 Tomcat 接管的**。

### 3.2 真正寫 Cookie 的代碼路徑

當 `request.getSession(true)` 創建 Session 後：

```
org.apache.catalina.connector.Request#doGetSession
   ↓
（創建 Session 後）
   ↓
configureSessionCookie(Cookie cookie)        ← 配置 Cookie 屬性
   ↓
response.addSessionCookieInternal(cookie)    ← 加到 Response（內部隊列）
   ↓
（Response 提交時）
   ↓
org.apache.catalina.connector.Response#generateCookieString
   ↓
HTTP Response Header: Set-Cookie: JSESSIONID=xxx; Path=/; HttpOnly
```

### 3.3 Cookie 屬性是怎麼確定的？

| 屬性 | 默認值 | 由誰決定 |
|------|--------|---------|
| `Name` | `JSESSIONID` | Servlet 規範（`SessionTrackingMode.COOKIE`） |
| `Path` | `/` 或 contextPath | Tomcat 根據 contextPath 設置 |
| `HttpOnly` | `true` | Servlet 3.0+ 默認，可在 `application.yml` 改 |
| `Secure` | 跟 request 走（HTTPS 才有） | Tomcat 自動 |
| `Max-Age` | 不設（Session Cookie，瀏覽器關閉就消失） | Servlet 規範 |
| `SameSite` | 不設（瀏覽器默認 Lax） | Tomcat 9+ 可配，Spring Boot 通過 `server.servlet.session.cookie.same-site` 配 |

### 3.4 在 application.yml 裡可以怎麼配

當前項目沒配，使用全部默認。如果想顯式配置：

```yaml
server:
  servlet:
    session:
      timeout: 30m                 # Session 超時時間
      cookie:
        name: JSESSIONID           # Cookie 名稱
        http-only: true            # 防 XSS
        secure: false              # 生產上 HTTPS 改 true
        same-site: lax             # 防 CSRF
        max-age: -1                # -1 = 瀏覽器關閉就失效
        path: /
```

---

## 四、SecurityContext 是怎麼進 Session 的

### 4.1 兩個關鍵 Filter

```
Spring Security 6.x 中：
  SecurityContextHolderFilter    ← 從 Session 讀 SecurityContext，放進 ThreadLocal
  ↓
  ... 其他 Filter ...
  ↓
  UsernamePasswordAuthenticationFilter ← 認證成功時調用 securityContextRepository.saveContext()
```

> Spring Security 5.x 用的是 `SecurityContextPersistenceFilter`，6.x 拆成了"讀 (HolderFilter)" 和 "寫 (各認證 Filter 自己負責)" 兩部分。

### 4.2 寫入 Session 的精確時刻

當 `POST /login` 認證成功時：

```
UsernamePasswordAuthenticationFilter#successfulAuthentication
   ↓
SecurityContextHolder.getContextHolderStrategy().setContext(context)  ← 先放 ThreadLocal
   ↓
this.securityContextRepository.saveContext(context, request, response)
   ↓
HttpSessionSecurityContextRepository#saveContext
   ↓
session.setAttribute("SPRING_SECURITY_CONTEXT", context)  ← Session 裡多了這個 key
```

**這個 key 你可以在 `dashboard` 頁面驗證**：在 `PageController#dashboard` 裡加一行
```java
System.out.println(session.getAttribute("SPRING_SECURITY_CONTEXT"));
```
能打印出 `SecurityContextImpl[Authentication=...]`。

### 4.3 後續請求是怎麼"認出"用戶的

每次請求進來：

```
SecurityContextHolderFilter#doFilter
   ↓
DeferredSecurityContext#get()  ← 懶加載
   ↓
HttpSessionSecurityContextRepository#readSecurityContextFromSession
   ↓
session.getAttribute("SPRING_SECURITY_CONTEXT")  ← 取出來
   ↓
SecurityContextHolder.setContext(context)  ← 放 ThreadLocal
   ↓
業務代碼 SecurityContextHolder.getContext().getAuthentication() 就能拿到
```

---

## 五、remember-me Cookie 的生命週期

本項目在 `SecurityConfig.java:33-37` 啟用了 remember-me：

```java
.rememberMe(remember -> remember
    .key("test-login-remember-me-key")
    .tokenValiditySeconds(7 * 24 * 60 * 60)
    .rememberMeParameter("remember-me")
);
```

### 5.1 創建時機

只有用戶**勾選了"記住我"**並登錄成功時才創建。

```
登錄表單 POST /login + 帶上 remember-me=on
   ↓
UsernamePasswordAuthenticationFilter#successfulAuthentication
   ↓
RememberMeServices#loginSuccess(request, response, authentication)
   ↓
TokenBasedRememberMeServices#onLoginSuccess
   ├─ 計算 token = base64(username + ":" + expiry + ":" + algName + ":"
   │              + md5/sha256(username + ":" + expiry + ":" + password + ":" + key))
   ├─ 構造 Cookie: name=remember-me, value=token, maxAge=7天
   └─ response.addCookie(cookie)
```

### 5.2 使用時機（重點理解）

當 Session 失效（瀏覽器關閉、Session 超時）後，下次訪問：

```
RememberMeAuthenticationFilter#doFilter
   ├─ 檢查 SecurityContext 有沒有 Authentication（沒有才繼續）
   ├─ RememberMeServices#autoLogin(request, response)
   │   └─ 從 Cookie 解析 token，驗簽
   │       └─ 重建 Authentication 對象
   ├─ AuthenticationManager#authenticate(rememberMeAuth)
   │   └─ RememberMeAuthenticationProvider 通過 key 校驗
   └─ 寫入 SecurityContext + 創建新 Session
```

**關鍵點**：remember-me 只在 **Session 認證失敗** 時才介入；有 Session 就不走它。

### 5.3 死亡時機

- 主動登出 → `LogoutFilter` 調用 `RememberMeServices#logout`，下發 `Set-Cookie: remember-me=; Max-Age=0`
- Token 過期（7 天） → 下次驗簽失敗自動失效
- 用戶改密碼 → token 裡的 password hash 變了，下次驗簽失敗

---

## 六、死亡：登出與超時

### 6.1 主動登出（POST /logout）

```
LogoutFilter#doFilter
   ↓
（識別到 /logout 路徑）
   ↓
LogoutHandler 鏈逐個執行：
  ├─ CsrfLogoutHandler              ← 清 CSRF token
  ├─ SecurityContextLogoutHandler   ← 清 SecurityContext + session.invalidate()
  ├─ TokenBasedRememberMeServices   ← 下發 remember-me=; Max-Age=0
  └─ ...
   ↓
LogoutSuccessHandler → 重定向到 /login?logout
```

**`session.invalidate()` 真正做的事**：
```
StandardSession#invalidate
   ↓
expire(true)
   ├─ notifyAttributeListenersOnUnbind  ← 觸發 HttpSessionAttributeListener
   ├─ manager.remove(this)              ← 從 Manager 的 sessions Map 中移除
   └─ recycle()                         ← 對象回收
```

> Cookie 本身**不會立即從瀏覽器消失**，需要服務器顯式發 `Set-Cookie: JSESSIONID=; Max-Age=0` 才能真正刪掉客戶端的 Cookie。Spring Security 默認會做這件事。

### 6.2 超時失效（默認 30 分鐘）

Tomcat 有個後台線程 `ContainerBackgroundProcessor`：

```
StandardManager#processExpires (每隔 backgroundProcessorDelay 秒執行一次)
   ↓
遍歷所有 Session
   ↓
session.isValid()  ← 內部檢查 lastAccessedTime + maxInactiveInterval < now
   ↓
失效的 session.expire()
```

**註意**：
- "30 分鐘"指的是**最後一次訪問後 30 分鐘**，不是登錄後 30 分鐘
- 任何請求都會刷新 `lastAccessedTime`
- Tomcat 不會主動通知客戶端 Session 失效，下次請求過來時客戶端 Cookie 還在，但服務端找不到對應 Session，於是會創建一個新的（除非走了 remember-me）

---

## 七、調試斷點清單（按出現順序）

### 7.1 IDEA 調試前的準備

1. 打開 IDEA → `File` → `Project Structure` → 確認 SDK 是 JDK 17+
2. 啟動類 `TestLoginApplication` → 右鍵 `Debug`
3. 在斷點上**右鍵勾選 "Suspend - Thread"**（不要 All），否則 Tomcat 會卡死
4. 條件斷點：對於高頻 Filter，用 `Condition` 過濾，比如 `request.getRequestURI().equals("/login")`

### 7.2 觀察"Session 第一次誕生"

| 順序 | 類 | 方法 | 行號（參考） | 觀察什麼 |
|------|----|------|------|---------|
| 1 | `org.springframework.security.web.csrf.CsrfFilter` | `doFilterInternal` | 入口 | CSRF 觸發 Session 創建 |
| 2 | `org.apache.catalina.connector.Request` | `doGetSession(boolean)` | `create=true` 分支 | **Session 對象誕生瞬間** |
| 3 | `org.apache.catalina.session.ManagerBase` | `createSession(String)` | 整個方法 | sessionId 分配 |
| 4 | `org.apache.catalina.session.StandardSession` | `<init>` 構造方法 | 整個 | StandardSession 對象創建 |

**Tip**：在斷點視窗用 `Evaluate Expression` 跑 `request.getSession(false)` 觀察前後變化。

### 7.3 觀察 "JSESSIONID Cookie 寫入"

| 順序 | 類 | 方法 | 觀察什麼 |
|------|----|------|---------|
| 1 | `org.apache.catalina.connector.Request` | `doGetSession` 末尾 | `configureSessionCookie` 調用 |
| 2 | `org.apache.catalina.connector.Request` | `configureSessionCookie` | Cookie 屬性配置（HttpOnly、Path） |
| 3 | `org.apache.catalina.connector.Response` | `addSessionCookieInternal` | Cookie 進 Response 隊列 |
| 4 | `org.apache.catalina.connector.Response` | `generateCookieString` | **真正生成 Set-Cookie Header 字符串** |

### 7.4 觀察 "認證成功 → SecurityContext 進 Session"

| 順序 | 類 | 方法 | 觀察什麼 |
|------|----|------|---------|
| 1 | `org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter` | `attemptAuthentication` | 認證入口，username/password |
| 2 | 你的 `UserDetailsServiceImpl` | `loadUserByUsername` | DB 查詢（已有的代碼） |
| 3 | `org.springframework.security.authentication.dao.DaoAuthenticationProvider` | `additionalAuthenticationChecks` | **密碼比對（BCrypt.matches）** |
| 4 | `org.springframework.security.web.authentication.AbstractAuthenticationProcessingFilter` | `successfulAuthentication` | 認證成功後續流程 |
| 5 | `org.springframework.security.web.authentication.session.ChangeSessionIdAuthenticationStrategy` | `applySessionFixation` | **Session ID 換新（防 fixation）** |
| 6 | `org.springframework.security.web.context.HttpSessionSecurityContextRepository` | `saveContext` / `setAttribute` | **SecurityContext 寫入 Session** |
| 7 | `org.springframework.security.web.authentication.rememberme.AbstractRememberMeServices` | `onLoginSuccess` | **remember-me Cookie 創建** |

### 7.5 觀察 "後續請求識別用戶"

| 順序 | 類 | 方法 | 觀察什麼 |
|------|----|------|---------|
| 1 | `org.springframework.security.web.context.SecurityContextHolderFilter` | `doFilter` | 請求入口讀 SecurityContext |
| 2 | `org.springframework.security.web.context.HttpSessionSecurityContextRepository` | `readSecurityContextFromSession` | **從 Session 讀出來** |
| 3 | 你的 `PageController` | `dashboard` 第 28 行 | 業務代碼拿到 `userDetails` 和 `session` |

### 7.6 觀察 "登出 / Session 銷毀"

| 順序 | 類 | 方法 | 觀察什麼 |
|------|----|------|---------|
| 1 | `org.springframework.security.web.authentication.logout.LogoutFilter` | `doFilter` | 識別 /logout |
| 2 | `org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler` | `logout` | **session.invalidate() 調用** |
| 3 | `org.apache.catalina.session.StandardSession` | `invalidate` / `expire` | **Session 對象銷毀** |
| 4 | `org.springframework.security.web.authentication.rememberme.AbstractRememberMeServices` | `cancelCookie` | **remember-me Cookie 刪除** |

---

## 八、日誌配置：不打斷點也能看見

把以下配置加到 `src/main/resources/application.yml`：

```yaml
logging:
  level:
    # ===== Spring Security 核心 =====
    org.springframework.security: DEBUG
    # FilterChainProxy: 看每個 Filter 觸發
    org.springframework.security.web.FilterChainProxy: TRACE
    # SecurityContext 讀寫操作
    org.springframework.security.web.context: TRACE
    # 認證 Filter 細節
    org.springframework.security.web.authentication: DEBUG
    # remember-me 細節（看 token 編解碼）
    org.springframework.security.web.authentication.rememberme: TRACE
    # 授權決策
    org.springframework.security.access: DEBUG

    # ===== Tomcat Session 管理 =====
    # Session 創建、銷毀、失效都會打
    org.apache.catalina.session: DEBUG
    # Cookie 寫入細節
    org.apache.catalina.connector: DEBUG

    # ===== Spring Web =====
    # 看 DispatcherServlet 派發
    org.springframework.web: DEBUG
```

啟動後請求一次完整登錄流程，控制台會輸出類似：

```
TRACE FilterChainProxy : Securing GET /login
DEBUG CsrfFilter : Loaded token (csrf token=XXX)
DEBUG StandardManager : Creating new session, id=ABC123    ← Session 誕生
DEBUG ... Saving SecurityContext ... to HttpSession
DEBUG TokenBasedRememberMeServices : Adding remember-me cookie
```

---

## 九、瀏覽器側觀察方法

### 9.1 F12 三個關鍵面板

#### Network 面板
- 點某個請求 → **Headers** Tab
  - **Request Headers** → `Cookie:` → 看當前帶了哪些 Cookie
  - **Response Headers** → `Set-Cookie:` → 看服務端要瀏覽器存什麼 Cookie

#### Application 面板（Chrome / Edge）
- 左側 `Storage` → `Cookies` → `http://localhost:8080`
- 能看到所有 Cookie 的全部屬性（Name / Value / Domain / Path / Expires / HttpOnly / Secure / SameSite）
- 可以**手動刪除**某個 Cookie 來模擬"丟失"場景

#### Console 面板
- `document.cookie` —— 注意 **HttpOnly 的 Cookie 在這裡看不到**（這正是 HttpOnly 的意義）
- 你會發現 `JSESSIONID` 和 `remember-me` 在 `document.cookie` 裡都**不可見**，因為都是 HttpOnly

### 9.2 推薦的端到端觀察流程

```
步驟 1：清空所有 Cookie（Application → Cookies → 右鍵 Clear）
步驟 2：訪問 http://localhost:8080/login
        → Network 看 Set-Cookie，應該已經有 JSESSIONID 了（CsrfFilter 觸發）
步驟 3：勾選"記住我"，輸入 admin / 123456 提交
        → Network 看 POST /login 的 Response
        → Set-Cookie 會出現 2 個：新的 JSESSIONID（換 ID 防 fixation）+ remember-me
步驟 4：訪問 /dashboard
        → Cookie Header 會帶上 JSESSIONID
        → 觀察 dashboard 頁面顯示的 Cookie 表格（你項目本來就渲染了）
步驟 5：手動刪除 JSESSIONID（保留 remember-me）
        → 刷新 /dashboard
        → 因為 remember-me 還在，能直接登錄成功
        → Network 看到新的 JSESSIONID 被下發
步驟 6：手動刪除所有 Cookie 後刷新
        → 直接被踢回 /login
```

走完這 6 步，Cookie/Session 的整個生命週期你就**親眼見過一遍**了。

---

## 十、一張可以貼牆上的時序圖

```
┌─────────────┐    ┌──────────────┐    ┌─────────────────────┐    ┌──────────────┐
│  Browser    │    │  Tomcat      │    │  Spring Security    │    │  Your Code   │
│  (Chrome)   │    │  Connector   │    │  Filter Chain       │    │              │
└──────┬──────┘    └──────┬───────┘    └─────────┬───────────┘    └──────┬───────┘
       │                  │                      │                       │
       │ GET /login       │                      │                       │
       ├─────────────────→│                      │                       │
       │                  │  HttpServletRequest  │                       │
       │                  ├─────────────────────→│ CsrfFilter            │
       │                  │                      │ 需要存 csrf token     │
       │                  │                      │ → request.getSession()│
       │                  │←─────────────────────┤                       │
       │                  │ ★ Session 第一次創建 │                       │
       │                  │ ★ JSESSIONID 分配    │                       │
       │                  │ ★ Set-Cookie 隊列    │                       │
       │                  │                      ├──────────────────────→│
       │                  │                      │  PageController       │
       │                  │                      │  return "login"       │
       │                  │←─────────────────────┤←──────────────────────┤
       │   200 OK         │                      │                       │
       │   Set-Cookie:    │                      │                       │
       │   JSESSIONID=ABC │                      │                       │
       │←─────────────────┤                      │                       │
       │                  │                      │                       │
       │ POST /login      │                      │                       │
       │ Cookie: JSES...  │                      │                       │
       │ user=admin&...   │                      │                       │
       │ remember-me=on   │                      │                       │
       ├─────────────────→├─────────────────────→│ UsernamePasswordAuth  │
       │                  │                      │ Filter                │
       │                  │                      │ → 查 DB → BCrypt 比對 │
       │                  │                      │ ★ 認證成功            │
       │                  │                      │ ★ Session ID 換新     │
       │                  │                      │ ★ SecurityContext     │
       │                  │                      │   寫入 Session        │
       │                  │                      │ ★ remember-me Cookie  │
       │                  │                      │   生成寫入 Response   │
       │   302 → /dash    │                      │                       │
       │   Set-Cookie:    │                      │                       │
       │   JSESSIONID=NEW │                      │                       │
       │   remember-me=…  │                      │                       │
       │←─────────────────┤                      │                       │
       │                  │                      │                       │
       │ GET /dashboard   │                      │                       │
       │ Cookie:          │                      │                       │
       │  JSESSIONID=NEW  │                      │                       │
       │  remember-me=…   │                      │                       │
       ├─────────────────→├─────────────────────→│ SecurityContextHolder │
       │                  │                      │ Filter                │
       │                  │                      │ → 從 Session 讀       │
       │                  │                      │   SecurityContext     │
       │                  │                      │ → ThreadLocal 注入    │
       │                  │                      ├──────────────────────→│
       │                  │                      │                       │ PageController
       │                  │                      │                       │ #dashboard
       │                  │                      │                       │ HttpSession 注入
       │                  │                      │                       │ session.setAttribute
       │                  │                      │                       │   ("loginTime", ...)
       │   200 dashboard.html                    │                       │
       │←──────────────────────────────────────────────────────────────┤
       │                  │                      │                       │
       │ POST /logout     │                      │                       │
       │ Cookie: ...      │                      │                       │
       ├─────────────────→├─────────────────────→│ LogoutFilter          │
       │                  │                      │ ★ session.invalidate()│
       │                  │                      │ ★ Set-Cookie:         │
       │                  │                      │   JSESSIONID=; Max-Age│
       │                  │                      │   =0                  │
       │                  │                      │ ★ Set-Cookie:         │
       │                  │                      │   remember-me=;       │
       │                  │                      │   Max-Age=0           │
       │   302 → /login?logout                   │                       │
       │←─────────────────┤                      │                       │
       │                  │                      │                       │
```

---

## 附錄 A：本項目代碼中與 Session/Cookie 相關的位置速查

| 文件 | 行 | 內容 |
|------|----|----|
| `SecurityConfig.java` | 33-37 | `rememberMe` 配置（key、有效期、表單字段） |
| `SecurityConfig.java` | 16-39 | `SecurityFilterChain` 註冊（決定 Filter 鏈長相） |
| `PageController.java` | 28 | `HttpSession session` 方法參數（觸發 Session 取出） |
| `PageController.java` | 33-41 | `session.setAttribute(...)` 業務寫入 |
| `PageController.java` | 51-65 | 讀取 `request.getCookies()` 渲染到頁面 |
| `dashboard.html` | 76-105 | Cookie 列表表格（**最直觀的可視化**） |
| `application.yml` | - | 沒配 session/cookie 相關，全用默認 |

## 附錄 B：常見問題自測

讀完本文應該能回答：

1. ❓ 訪問 `/login` 時為什麼瀏覽器就有 JSESSIONID 了？我還沒登錄啊。
   → CsrfFilter 為了存 CSRF token 提前創建了 Session。

2. ❓ 登錄成功後 JSESSIONID 為什麼"變了"？
   → Spring Security 防 Session Fixation，用 `ChangeSessionIdAuthenticationStrategy` 換了新 ID。

3. ❓ 我手動刪了 remember-me Cookie，為什麼還能登錄？
   → 因為 JSESSIONID 還在，Session 還有效，根本沒走到 remember-me 邏輯。

4. ❓ 關了瀏覽器再打開，為什麼有時要重登有時不用？
   → JSESSIONID 是 Session Cookie（瀏覽器關就沒），但 remember-me 是持久 Cookie（7 天）。沒勾"記住我"就要重登。

5. ❓ 我在 Console 用 `document.cookie` 為什麼看不到 JSESSIONID？
   → 因為它是 `HttpOnly`，JS 訪問不到（這是防 XSS 的關鍵）。

---

## 附錄 C：進階閱讀的源碼地圖

按"由淺入深"順序讀，每個都建議用 IDEA 打開源碼配合斷點：

```
入口（Spring Security 6.x）
└── DefaultSecurityFilterChain                  Filter 鏈本體
    ├── SecurityContextHolderFilter             SecurityContext 讀取
    │   └── HttpSessionSecurityContextRepository  ★ Session 讀寫核心
    │
    ├── CsrfFilter                              CSRF（也是 Session 創建源頭）
    │   └── HttpSessionCsrfTokenRepository
    │
    ├── LogoutFilter                            登出
    │   └── SecurityContextLogoutHandler        ★ session.invalidate()
    │
    ├── UsernamePasswordAuthenticationFilter   表單登錄
    │   └── ChangeSessionIdAuthenticationStrategy  ★ Session ID 換新
    │
    ├── RememberMeAuthenticationFilter          remember-me 認證
    │   └── TokenBasedRememberMeServices        ★ remember-me Cookie 讀寫
    │
    └── AuthorizationFilter                     授權檢查（最後）

底層（Tomcat 10.x）
└── Request                                    org.apache.catalina.connector.Request
    ├── doGetSession(boolean)                  ★★★ Session 真正創建的地方
    └── configureSessionCookie                 ★★★ JSESSIONID Cookie 屬性配置

    Response                                   org.apache.catalina.connector.Response
    └── addSessionCookieInternal               ★★★ Cookie 加入 Response

    StandardSession                            org.apache.catalina.session.StandardSession
    ├── 構造方法                               ★ Session 對象誕生
    ├── invalidate / expire                    ★ Session 對象銷毀
    └── setAttribute / getAttribute            業務數據存取

    ManagerBase                                org.apache.catalina.session.ManagerBase
    ├── createSession                          ★ Session 工廠
    ├── generateSessionId                      ★ ID 生成（隨機）
    └── processExpires                         ★ 後台失效掃描
```

---

> 把本文當作"標尺"，下次面對任何 Spring Boot + Spring Security 項目，按這個結構去定位 Cookie 和 Session 的行為，幾乎不會再有黑盒。
