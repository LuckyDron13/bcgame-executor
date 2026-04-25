# Task: BC.Game Bet Executor — Microservice Spec

## Overview

Standalone Spring Boot microservice (`bcgame-executor`) that:
- Receives bet signals from `abb-scanner` via HTTP
- Connects to a BC.game browser profile via AdsPower CDP
- Navigates to the event page
- Finds the market and clicks the odds
- Enters the stake amount and places the bet
- Sends Telegram notification with result

---

## System Map

```
abb-scanner  →  POST /api/bet-signal  →  bcgame-executor :8083
                                              │
                                              │ CDP (connectOverCDP)
                                              ▼
                                     AdsPower profile
                                     (BC.game logged in)
                                              │
                                              ▼
                                     BC.game event page
                                     click odds → enter amount → Place Bet
                                              │
                                              ▼
                                     Telegram notification
```

---

## Tech Stack

| Component  | Choice                                           |
|------------|--------------------------------------------------|
| Language   | Java 21                                          |
| Framework  | Spring Boot 3.4.5 (web — нужен REST endpoint)   |
| Browser    | Playwright (Java) via AdsPower CDP               |
| Telegram   | telegrambots-springboot-longpolling-starter      |
| Build      | Maven                                            |
| Package    | `com.dron.bcgame`                                |
| Port       | 8083                                             |

---

## Project Structure

```
bcgame-executor/
├── src/main/java/com/dron/bcgame/
│   ├── BcGameExecutorApplication.java
│   ├── config/
│   │   └── AppConfig.java                  ← RestTemplate bean
│   ├── adspower/
│   │   ├── AdsPowerProperties.java
│   │   ├── AdsPowerClient.java
│   │   ├── AdsPowerResponse.java
│   │   └── AdsPowerException.java
│   ├── browser/
│   │   └── BrowserProvider.java            ← CDP connect, newPage()
│   ├── bet/
│   │   ├── BetSignalRequest.java           ← { event, market, odds, amount, eventUrl }
│   │   ├── BetExecutor.java                ← основная логика (TODO: клики)
│   │   └── BetSignalController.java        ← POST /api/bet-signal
│   └── telegram/
│       ├── TelegramProperties.java
│       └── TelegramNotifier.java
├── BCGAME_DOM.md                           ← селекторы DOM
├── BCGAME_EXECUTOR_TASK.md                 ← этот файл
├── betslip-success.html                    ← HTML success нотификации
└── pom.xml
```

---

## Конфигурация (`application.yaml`)

```yaml
spring:
  application:
    name: bcgame-executor

server:
  port: 8083

adspower:
  enabled: true
  url: http://local.adspower.net:50325
  profile-id: "REPLACE_ME"   # профиль с залогиненным BC.game

tg:
  bot-token: "REPLACE_ME"
  chat-id: "REPLACE_ME"      # чат куда слать результат ставок
```

Каждый экземпляр на отдельной машине имеет свой `config.yaml` с уникальным `profile-id`.

---

## Входящий сигнал

`POST /api/bet-signal`

```json
{
  "event":      "Japan (E) vs Kazakhstan (E)",
  "marketName": "Total corners",
  "market":     "over 7.5",
  "odds":       1.85,
  "amount":     52.38,
  "eventUrl":   "https://bc.game/sports/soccer/esoccer/.../12345"
}
```

| Поле | Описание |
|------|----------|
| `event` | Название события |
| `marketName` | Категория маркета на BC.game (используется для поиска секции) |
| `market` | Исход внутри маркета (используется для поиска кнопки) |
| `odds` | Ожидаемый коэффициент — сверяется с реальным на странице |
| `amount` | Сумма ставки в USDT |
| `eventUrl` | **Полная прямая ссылка на BC.game** (уже разрезолвлена в сканере) |

Ответ: `202 Accepted` (fire-and-forget, ставка выполняется в фоне).

**Только corners:** `BetExecutor` обрабатывает только сигналы где `marketName` или `market` содержит `corner` / `угл`. Остальные игнорируются (`skip`).

---

## Алгоритм `BetExecutor.execute()`

```
0. isCornersSignal? → если нет, skip (return)

1. page = browserProvider.newPage()
2. page.navigate(signal.getEventUrl())   ← полная прямая BC.game ссылка
3. page.waitForLoadState(NETWORKIDLE, timeout 15s)

4. Найти секцию маркета:
   [data-editor-id="tableMarketWrapper"]
   .filter(hasText = signal.getMarketName())
   .first()

5. Внутри секции найти исход:
   [data-editor-id="tableOutcomePlate"] × N
   → для каждого взять текст [data-editor-id="tableOutcomePlateName"]
   → normalize() + contains-сравнение с signal.getMarket()
   → проверить коэффициент: extractOdds(plate)
      если |currentOdds - signal.getOdds()| > 0.15 → throw (коэф ушёл)

6. target.click()

7. Дождаться betslip:
   label[data-editor-id="betslipStakeInput"] input

8. stakeInput.fill("1")   ← $1 USDT (константа AMOUNT = 1.0)

9. button[data-editor-id="betslipPlaceBetButton"].click()

10. Дождаться [data-editor-id="betslipNotification"] (timeout 10s)
    → [data-editor-id="successIcon"] visible → OK ✓
    → нет → throw

11. page.close()
12. telegramNotifier.sendBetPlaced(signal, AMOUNT) или sendBetFailed(signal, reason)
```

**Сумма ставки:** фиксированная константа `AMOUNT = 1.0 USDT` (не из сигнала).

---

## Telegram уведомления

**Ставка принята:**
```
BC.Game — ставка поставлена ✓
Japan (E) vs Kazakhstan (E)
Kazakhstan (E) total — under 1.5 @ 3.20
Сумма: $52.38 USDT
```

**Ошибка:**
```
BC.Game — ОШИБКА ❌
Japan (E) vs Kazakhstan (E)
under 1.5 @ 3.20
Причина: коэффициент изменился (было 3.20, стало 2.95)
```

---

## DOM-селекторы (сводка)

Полная документация: `BCGAME_DOM.md`

| Действие | Selector |
|----------|----------|
| Кнопка исхода | `[data-editor-id="tableOutcomePlate"]` |
| Название исхода | `[data-editor-id="tableOutcomePlateName"] .sc-7elhv3-3` |
| Гандикап | `[data-editor-id="tableOutcomePlateName"] .sc-7elhv3-4` |
| Поле суммы | `label[data-editor-id="betslipStakeInput"] input` |
| Кнопка Place Bet | `button[data-editor-id="betslipPlaceBetButton"]` |
| Успех | `[data-editor-id="betslipNotification"]` + `[data-editor-id="successIcon"]` |

> ⚠️ Классы `btXXXX` динамические — не использовать для автоматизации.

---

## Распределение по профилям

Один сканер (`abb-scanner`) отправляет сигналы на N из M исполнителей случайно.
Каждый `bcgame-executor` — отдельный JAR на отдельной машине со своим `profile-id`.

Конфиг сканера (`abb-scanner/application.yaml`):
```yaml
bcgame-executor:
  enabled: true
  send-count: 2
  instances:
    - name: "bcgame-1"
      url: http://192.168.1.20:8083
    - name: "bcgame-2"
      url: http://192.168.1.21:8083
    - name: "bcgame-3"
      url: http://192.168.1.22:8083
```

---

## Порядок реализации

1. ✅ `pom.xml`
2. ✅ `AdsPowerProperties`, `AdsPowerResponse`, `AdsPowerClient`, `AdsPowerException`
3. ✅ `BrowserProvider` — CDP connect
4. ✅ `BetSignalRequest`, `BetSignalController` — HTTP endpoint
5. ✅ `TelegramProperties`, `TelegramNotifier`
6. ✅ `BetExecutor` — заглушка (TODO)
7. ⬜ Реализовать поиск исхода по маркету (нормализация строк)
8. ⬜ Реализовать ввод суммы и клик Place Bet
9. ⬜ Реализовать детект успеха / ошибки
10. ⬜ Обработка `OddsChangedException`
11. ⬜ Обновить `abb-scanner`: `BetExecutorProperties` со списком инстансов, N-из-M в `BetExecutorClient`

---

## Открытые вопросы

1. Точный формат поля `market` в сигналах от ABB — нужно знать как нормализовать
   (например: `"ТБ(2.5)"` → `"over 2.5"`, или ABB уже шлёт в english?)
2. Есть ли капча / 2FA на BC.game при ставке?
