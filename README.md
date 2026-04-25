# bcgame-executor

Spring Boot микросервис для автоматического размещения ставок на BC.Game через браузер.
Получает сигналы от `abb-scanner`, управляет браузером через AdsPower CDP + Playwright,
ставит ставку и отправляет результат в Telegram.

---

## Архитектура

```
abb-scanner  ──►  POST /api/bet-signal  ──►  bcgame-executor :8083
                                                      │
                                          CDP connectOverCDP
                                                      │
                                                      ▼
                                         AdsPower profile (Chrome)
                                         BC.Game — уже залогинен
                                                      │
                                          navigate → find market
                                          verify odds → click
                                          fill stake → Place Bet
                                          detect success
                                                      │
                                                      ▼
                                         Telegram notification
```

Один инстанс может держать **несколько AdsPower профилей** одновременно.
Ставки распределяются round-robin, каждая ставка исполняется в отдельном потоке.

---

## Tech Stack

| | |
|---|---|
| Language | Java 21 |
| Framework | Spring Boot 3.4.5 |
| Browser automation | Playwright (Java) 1.57.0 via AdsPower CDP |
| Telegram | telegrambots-springboot-longpolling-starter 9.3.0 |
| Build | Maven |
| Port | 8083 |

---

## Структура проекта

```
src/main/java/com/dron/bcgame/
├── BcGameExecutorApplication.java
├── config/
│   └── AppConfig.java                  — RestTemplate bean
├── adspower/
│   ├── AdsPowerProperties.java         — конфиг prefix="adspower"
│   ├── AdsPowerClient.java             — HTTP к AdsPower API
│   ├── AdsPowerResponse.java           — DTO ответа AdsPower
│   └── AdsPowerException.java
├── browser/
│   └── BrowserProvider.java            — пул CDP-соединений, round-robin newPage()
├── bet/
│   ├── BetSignalRequest.java           — DTO входящего сигнала
│   ├── BetSignalController.java        — POST /api/bet-signal
│   └── BetExecutor.java                — вся логика ставки
└── telegram/
    ├── TelegramProperties.java
    └── TelegramNotifier.java
```

---

## Конфигурация

`src/main/resources/application.yaml`:

```yaml
server:
  port: 8083

adspower:
  url: http://local.adspower.net:50325
  profile-ids:
    - "PROFILE_ID_1"        # первый аккаунт BC.Game
    - "PROFILE_ID_2"        # второй аккаунт (опционально)

tg:
  bot-token: "BOT_TOKEN"
  chat-id: "CHAT_ID"
```

Каждый профиль — отдельный браузер Chrome в AdsPower с уже залогиненным BC.Game.
При добавлении профиля в список `profile-ids` он автоматически подключается при старте
и участвует в round-robin распределении ставок.

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
  "eventUrl":   "https://bc.game/sports/soccer/..."
}
```

| Поле | Описание |
|------|----------|
| `event` | Название события (только для логов/Telegram) |
| `marketName` | Заголовок секции маркета на BC.game — используется для поиска секции |
| `market` | Исход внутри маркета — используется для поиска кнопки |
| `odds` | Ожидаемый коэффициент, проверяется перед ставкой |
| `amount` | Игнорируется — ставка всегда `1.0 USDT` (константа `AMOUNT`) |
| `eventUrl` | Прямая ссылка на страницу события на BC.game |

Ответ: `202 Accepted` немедленно. Ставка выполняется асинхронно.

---

## Алгоритм ставки (`BetExecutor`)

```
1. Проверить eventUrl: если содержит /en/bti — пропустить (BTI события)
2. Взять Page из BrowserProvider (round-robin по профилям)
3. page.navigate(eventUrl)
4. Дождаться рендера [data-editor-id="tableMarketWrapper"] (25s timeout)
5. Найти секцию маркета по тексту marketName
6. Перебрать [data-editor-id="tableOutcomePlate"] внутри секции:
   - normalize(plateText) vs normalize(signal.market) — contains в обе стороны
   - если совпало: extractOdds(plate) через JS evaluate
   - если |currentOdds - signal.odds| > 0.15 → throw (коэф ушёл)
7. target.click()
8. Дождаться betslip input (8s), заполнить "1.0"
9. Нажать betslipPlaceBetButton
10. Дождаться [data-editor-id="betslipNotification"] (10s)
11. Проверить наличие [data-editor-id="successIcon"] → OK или throw
12. page.close()
13. Telegram: sendBetPlaced или sendBetFailed
```

---

## Мультипрофильный режим

Поддержка нескольких AdsPower профилей в одном инстансе.

**`BrowserProvider`** при старте приложения:
- Итерирует `adspower.profile-ids`
- Для каждого вызывает `AdsPowerClient.startBrowser()` → получает WS endpoint
- Подключается через `playwright.chromium().connectOverCDP(wsEndpoint)`
- Хранит список `Browser` объектов

**`newPage()`** — `AtomicInteger` counter → `idx % browsers.size()` → round-robin.

**`BetSignalController`** — `FixedThreadPool(profileCount)`, ставки идут параллельно:
- Сигнал 1 → профиль A (поток 1)
- Сигнал 2 → профиль B (поток 2)
- Сигнал 3 → профиль A (поток 1, когда освободится)

Логи помечены ID профиля: `[k1ap28oe] Bet placed OK: ...`

---

## DOM-селекторы BC.Game

Стабильные селекторы через `data-editor-id` (не использовать `btXXXX` классы — они динамические):

| Элемент | Selector |
|---------|----------|
| Секция маркета | `[data-editor-id="tableMarketWrapper"]` |
| Кнопка исхода | `[data-editor-id="tableOutcomePlate"]` |
| Название исхода | `[data-editor-id="tableOutcomePlateName"]` |
| Поле суммы | `label[data-editor-id="betslipStakeInput"] input` |
| Кнопка Place Bet | `button[data-editor-id="betslipPlaceBetButton"]` |
| Уведомление | `[data-editor-id="betslipNotification"]` |
| Иконка успеха | `[data-editor-id="successIcon"]` |

Подробная DOM-документация с HTML примерами: [`BCGAME_DOM.md`](BCGAME_DOM.md)

---

## Telegram уведомления

**Ставка принята:**
```
BC.Game — ставка поставлена
Japan (E) vs Kazakhstan (E)
over 7.5 @ 1.85
Сумма: $1.00
```

**Ошибка:**
```
BC.Game — ОШИБКА ставки
Japan (E) vs Kazakhstan (E)
over 7.5 @ 1.85
Odds moved: expected 1.85, got 2.10
```

---

## Сборка и запуск

```bash
# Сборка
./mvnw clean package -DskipTests

# Запуск
java -jar target/bcgame-executor-0.0.1-SNAPSHOT.jar

# Запуск с переопределением профиля (второй инстанс на другой порт)
java -jar target/bcgame-executor-0.0.1-SNAPSHOT.jar \
  --server.port=8084 \
  --adspower.profile-ids[0]=OTHER_PROFILE_ID
```

Логи пишутся в `logs/bcgame-executor.log` (ротация по 10MB, хранение 7 дней).

---

## Связанные проекты

| Проект | Роль |
|--------|------|
| `abb-scanner` | Находит арбитражные ситуации, шлёт сигналы на N из M инстансов executor |
| [`browser-mirror`](https://github.com/LuckyDron13/browser-mirror) | Зеркалирует ручные действия оператора на follower-профили через CDP |

### Конфиг abb-scanner для нескольких инстансов

```yaml
bcgame-executor:
  enabled: true
  send-count: 2          # сколько инстансов получат каждый сигнал
  instances:
    - name: "bcgame-1"
      url: http://192.168.1.20:8083
    - name: "bcgame-2"
      url: http://192.168.1.21:8083
```

---

## Документация в репозитории

| Файл | Содержимое |
|------|-----------|
| [`BCGAME_DOM.md`](BCGAME_DOM.md) | DOM-структура BC.Game: селекторы, HTML примеры, маппинг маркетов ABB→BC.game |
| [`BCGAME_EXECUTOR_TASK.md`](BCGAME_EXECUTOR_TASK.md) | Оригинальное техническое задание на микросервис |
| [`Mirror.md`](Mirror.md) | Спецификация browser-mirror (вынесен в отдельный проект) |
| [`CLAUDE.md`](CLAUDE.md) | Гайд для Claude Code: команды, архитектура, селекторы |
| `*.html` | HTML-снапшоты страниц BC.Game для анализа DOM |
