# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

`bcgame-executor` — Spring Boot микросервис, который получает сигналы на ставку от `abb-scanner`, управляет браузером BC.Game через AdsPower CDP и ставит ставку через Playwright. Результат отправляется в Telegram.

```
abb-scanner  →  POST /api/bet-signal :8083
                    │
                    │  CDP (connectOverCDP)
                    ▼
             AdsPower profile (BC.game logged in)
                    │
                    ▼
             BC.game event page → click odds → fill stake → Place Bet
                    │
                    ▼
             Telegram notification
```

## Commands

```bash
# Build
./mvnw clean package -DskipTests

# Run
./mvnw spring-boot:run

# Build fat JAR and run
./mvnw clean package -DskipTests && java -jar target/bcgame-executor-0.0.1-SNAPSHOT.jar

# Run single test
./mvnw test -Dtest=ClassName#methodName
```

Port: **8083**

## Architecture

### Flow

1. `BetSignalController` — принимает `POST /api/bet-signal`, кидает задачу в `SingleThreadExecutor` (fire-and-forget, отдаёт `202 Accepted`)
2. `BetExecutor.execute()` — основная логика: навигация, поиск маркета, проверка коэффициента, клик, ввод суммы, Place Bet, детект успеха
3. `BrowserProvider` — singleton; при старте приложения подключается к AdsPower через CDP (`connectOverCDP`). `newPage()` создаёт новую вкладку в уже открытом контексте браузера
4. `AdsPowerClient` — REST-клиент к локальному AdsPower API (`http://local.adspower.net:50325`)
5. `TelegramNotifier` — отправляет результат ставки в Telegram чат

### Ключевые детали реализации

**Фиксированная сумма ставки** — `AMOUNT = 1.0 USDT` константа в `BetExecutor`, поле `amount` из сигнала игнорируется.

**Фильтрация BTI событий** — `BetExecutor` пропускает сигналы с `eventUrl` содержащим `/en/bti`.

**Поиск маркета** — фильтрация `[data-editor-id="tableMarketWrapper"]` по тексту `signal.getMarketName()`, затем перебор `[data-editor-id="tableOutcomePlate"]` внутри секции.

**Проверка коэффициента** — допуск `ODDS_TOLERANCE = 0.15`. Если коэф ушёл дальше — кидается `RuntimeException` и ставка не ставится.

**Нормализация исхода** — `outcomeMatches()` переводит текст в lowercase, убирает всё кроме `[a-z0-9.-]`, сравнивает через `contains` в обе стороны.

**Детект коэффициента** — JS evaluate: берёт первый `<span>` внутри плашки, текст которого соответствует `/^\d+(\.\d+)?$/`.

**Детект успеха** — ждёт `[data-editor-id="betslipNotification"]` (10s timeout), проверяет наличие `[data-editor-id="successIcon"]`.

### DOM-селекторы BC.Game

> Классы `btXXXX` — динамические, меняются между сессиями. Использовать только `data-editor-id` и `sc-*` классы.

| Элемент | Selector |
|---------|----------|
| Секция маркета | `[data-editor-id="tableMarketWrapper"]` |
| Плашка исхода (клик) | `[data-editor-id="tableOutcomePlate"]` |
| Название исхода | `[data-editor-id="tableOutcomePlateName"]` |
| Поле суммы | `label[data-editor-id="betslipStakeInput"] input` |
| Кнопка Place Bet | `button[data-editor-id="betslipPlaceBetButton"]` |
| Нотификация успеха | `[data-editor-id="betslipNotification"]` + `[data-editor-id="successIcon"]` |

Подробная DOM-документация с примерами HTML: `BCGAME_DOM.md`.

### Конфигурация

```yaml
adspower:
  enabled: true
  url: http://local.adspower.net:50325
  profile-ids:
    - "k1ap28oe"
    - "ВТОРОЙ_ПРОФИЛЬ_ID"

tg:
  bot-token: "..."
  chat-id: "..."
```

Один инстанс держит пул CDP-соединений (по одному на профиль). Ставки распределяются round-robin, параллельно — `newFixedThreadPool(profileCount)`. Несколько инстансов координирует `abb-scanner`.

## Входящий сигнал

```json
{
  "event":      "Japan (E) vs Kazakhstan (E)",
  "marketName": "Total corners",
  "market":     "over 7.5",
  "odds":       1.85,
  "amount":     52.38,
  "eventUrl":   "https://bc.game/sports/soccer/..."
}
```

`marketName` — категория (заголовок секции на BC.game), `market` — конкретный исход внутри неё.

## Открытые вопросы

- Точный формат поля `market` от ABB (нужно ли маппить `"ТБ(2.5)"` → `"over 2.5"` или ABB уже шлёт на английском?)
- Маппинг форматов ABB → BC.game описан в `BCGAME_DOM.md` раздел "Соответствие маркетов"
- Есть ли капча / 2FA на BC.game при ставке?
