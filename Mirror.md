# Browser Mirror — New Project

## What is this

A Java + Playwright service that mirrors manual actions from a **leader browser**
to N **follower browsers** with a configurable delay (1–2 seconds).

Use case: operator manually places a bet on BC.Game in the leader browser —
all follower browsers (separate AdsPower profiles) replicate the same action automatically.

---

## Tech Stack

- **Java 21**
- **Spring Boot 3.x** (`web-application-type: none` — no HTTP server needed)
- **Playwright (Java) 1.49.0**
- **AdsPower Local API** — `http://local.adspower.net:50325`
- **Lombok**
- **Maven**

Base package: `com.dron.mirror`

---

## How it works

```
Leader Browser (manual — operator)
         │
         │  CDP connection (Playwright, read-only)
         ▼
 MirrorCoordinator (Java)
   ├── intercepts: navigation, clicks, input
         │
         │  replay with delay 1–2s
         ▼
 Follower 1 … Follower N (Playwright, automated)
   each = separate AdsPower profile
```

---

## What is mirrored

| Event type       | How captured                        | How replayed                          |
|------------------|-------------------------------------|---------------------------------------|
| Navigation       | `page.onFrameNavigated`             | `follower.navigate(url)`              |
| Click            | JS listener → `exposeFunction`      | `follower.locator(selector).click()`  |
| Input (amount)   | JS `input` event → `exposeFunction` | `follower.locator(selector).fill(val)`|

All replay is async with configurable `mirror.delay-ms` (default 1500).

---

## Project Structure

```
src/main/java/com/dron/mirror/
├── adspower/
│   ├── AdsPowerProperties.java       # @ConfigurationProperties(prefix = "adspower")
│   ├── AdsPowerClient.java           # HTTP client — start/stop/status browser
│   ├── AdsPowerResponse.java         # DTO for AdsPower JSON response
│   └── AdsPowerService.java          # getCdpEndpoint(), stopBrowser()
│
├── browser/
│   ├── PlaywrightConfig.java         # @Bean Playwright singleton
│   └── BrowserSessionManager.java   # getOrCreateSession(profileId): Page
│
├── mirror/
│   ├── MirrorProperties.java         # @ConfigurationProperties(prefix = "mirror")
│   ├── MirrorCoordinator.java        # core: attach to leader, replay on followers
│   └── EventReplayService.java       # handles URL / click / input replay logic
│
├── config/
│   └── AppConfig.java                # ThreadPoolTaskExecutor bean for async replay
│
└── MirrorApplication.java            # @SpringBootApplication entry point
```

---

## Configuration — `application.yaml`

```yaml
spring:
  application:
    name: browser-mirror

adspower:
  url: http://local.adspower.net:50325
  leader-profile-id: "REPLACE_ME"        # manual operator browser
  follower-profile-ids:
    - "REPLACE_ME_2"
    - "REPLACE_ME_3"

mirror:
  delay-ms: 1500          # replay delay per follower (ms)
  replay-navigation: true
  replay-clicks: true
  replay-input: true
  stagger-ms: 500         # additional delay between each follower (ms)
                          # follower1 = delay-ms, follower2 = delay-ms + stagger-ms, etc.
```

---

## Module Specs

### `AdsPowerProperties.java`

```java
@ConfigurationProperties(prefix = "adspower")
@Data
public class AdsPowerProperties {
    private String url;
    private String leaderProfileId;
    private List<String> followerProfileIds;
}
```

---

### `AdsPowerClient.java`

`RestTemplate`-based. Three methods:
- `startBrowser(profileId)` → GET `/api/v1/browser/start?user_id={profileId}`
- `stopBrowser(profileId)`  → GET `/api/v1/browser/stop?user_id={profileId}`
- `checkStatus(profileId)`  → GET `/api/v1/browser/active?user_id={profileId}`

Returns `AdsPowerResponse`. Throws `AdsPowerException` if `code != 0`.

AdsPower response shape:
```json
{
  "code": 0,
  "msg": "success",
  "data": {
    "ws": { "puppeteer": "ws://127.0.0.1:XXXX/..." },
    "debug_port": "XXXX"
  }
}
```

---

### `AdsPowerService.java`

- `getCdpEndpoint(profileId): String` — starts browser if not already running, returns `data.ws.puppeteer`
- `stopBrowser(profileId): void`

---

### `PlaywrightConfig.java`

```java
@Configuration
public class PlaywrightConfig {
    @Bean
    public Playwright playwright() {
        return Playwright.create(); // Spring manages lifecycle — NO try-with-resources
    }
}
```

---

### `BrowserSessionManager.java`

`Map<String, Page> sessions` in memory.

- `getOrCreateSession(profileId): Page` — calls `adsPowerService.getCdpEndpoint()`, connects via `playwright.chromium().connectOverCDP(cdpEndpoint)`, returns first page or new page
- `closeSession(profileId): void`
- `closeAllSessions(): void` — `@PreDestroy`

---

### `MirrorProperties.java`

```java
@ConfigurationProperties(prefix = "mirror")
@Data
public class MirrorProperties {
    private long delayMs = 1500;
    private long staggerMs = 500;
    private boolean replayNavigation = true;
    private boolean replayClicks = true;
    private boolean replayInput = true;
}
```

---

### `MirrorCoordinator.java`

Main service. `@PostConstruct` starts mirroring.

**Steps:**
1. Get leader `Page` via `BrowserSessionManager`
2. Get all follower `Page`s
3. Attach JS listener to leader for clicks + input
4. Attach `onFrameNavigated` listener on leader
5. Each event → `EventReplayService.replay(event, followers)`

**JS injection on leader:**
```javascript
document.addEventListener('click', (e) => {
    const el = e.target;
    const selector = resolveSelector(el); // see below
    if (selector) window._mirrorClick(selector);
}, true);

document.addEventListener('input', (e) => {
    const el = e.target;
    const selector = resolveSelector(el);
    if (selector) window._mirrorInput(selector, el.value);
}, true);

function resolveSelector(el) {
    if (el.getAttribute('data-testid'))
        return '[data-testid="' + el.getAttribute('data-testid') + '"]';
    if (el.id) return '#' + el.id;
    // fallback: tag + first stable class
    const cls = [...el.classList].find(c => !c.match(/[0-9]{4,}/));
    return cls ? el.tagName.toLowerCase() + '.' + cls : null;
}
```

---

### `EventReplayService.java`

```java
void replayNavigation(String url, List<Page> followers) { ... }
void replayClick(String selector, List<Page> followers) { ... }
void replayInput(String selector, String value, List<Page> followers) { ... }
```

Each method:
- Iterates followers with index `i`
- Delay = `delayMs + i * staggerMs`
- Uses `CompletableFuture.runAsync(() -> { Thread.sleep(delay); ... }, taskExecutor)`
- Wraps in try/catch, logs errors — never propagates

---

### `AppConfig.java`

```java
@Bean
public TaskExecutor mirrorExecutor() {
    ThreadPoolTaskExecutor exec = new ThreadPoolTaskExecutor();
    exec.setCorePoolSize(10);
    exec.setMaxPoolSize(20);
    exec.setThreadNamePrefix("mirror-");
    exec.initialize();
    return exec;
}
```

---

## Error Handling

**`AdsPowerException.java`** — `RuntimeException` with message + cause.

**`GlobalExceptionHandler`** not needed (no HTTP layer).
Errors are logged via `@Slf4j` + caught in `EventReplayService` — a follower failure never stops other followers or the leader.

---

## Rules

- All code and comments in **English**
- `@Slf4j` on every service
- Lombok everywhere (`@Value`, `@Data`, `@RequiredArgsConstructor`, `@Slf4j`)
- No logic in `MirrorApplication.java`
- Selector resolution must be resilient — if selector is null, log WARN and skip (do not throw)
- Leader browser is **never automated** — only listeners attached, no `.click()` / `.fill()` called on it

---

## pom.xml dependencies needed

```xml
<dependency>
    <groupId>com.microsoft.playwright</groupId>
    <artifactId>playwright</artifactId>
    <version>1.49.0</version>
</dependency>
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter</artifactId>
</dependency>
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-configuration-processor</artifactId>
    <optional>true</optional>
</dependency>
<dependency>
    <groupId>org.projectlombok</groupId>
    <artifactId>lombok</artifactId>
    <optional>true</optional>
</dependency>
<dependency>
    <groupId>com.fasterxml.jackson.core</groupId>
    <artifactId>jackson-databind</artifactId>
</dependency>
```

---

## What is NOT in scope (Phase 1)

- No HTTP API (no REST endpoints)
- No Telegram notifications
- No bet-executor integration
- No scroll mirroring
- No right-click / drag mirroring
