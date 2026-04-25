# BC.Game DOM — селекторы для автоматизации ставок

Источник: `football-live.html` — страница лайв события (Real Madrid vs Deportivo Alaves, LaLiga).

---

## Структура маркетов

### Обёртка одного маркета
```
[data-editor-id="tableMarketWrapper"]
  └── .bt643
        └── .bt644 > .bt645 > span          ← название маркета ("Total", "Handicap", ...)
  └── [data-editor-id="tableOutcomePlate"]  ← кнопка исхода (кликать)
  └── [data-editor-id="tableOutcomePlate"]
  └── ...
```

### Кнопка исхода (одна ставка)
```html
<div data-editor-id="tableOutcomePlate" class="sc-7elhv3-0 laXRqx sc-9oeyuj-0 jIJSdy">
  <div class="bt292"></div>
  <div data-editor-id="tableOutcomePlateName" class="sc-7elhv3-2 dwaytB">
    <span class="sc-7elhv3-4 gzOcFJ">(-3.5)</span>    ← гандикап (если есть)
    <span class="sc-7elhv3-3 dJEQNf">Real Madrid </span> ← название исхода
  </div>
  <div class="bt296 sc-7elhv3-1 czyPR">
    <span class="bt298">4.9</span>                    ← коэффициент (текущий)
    <span class="bt298 bt299 bt300">4.9</span>        ← старый коэф (при изменении)
    <span class="bt298 bt301 bt302">4.7</span>        ← новый коэф (при изменении)
  </div>
</div>
```

**Ключевые селекторы:**

| Что | Selector |
|-----|----------|
| Кнопка исхода (клик) | `[data-editor-id="tableOutcomePlate"]` |
| Название маркета | `[data-editor-id="tableMarketWrapper"] .bt644 .bt645 span` |
| Название исхода | `[data-editor-id="tableOutcomePlateName"] .sc-7elhv3-3` |
| Гандикап | `[data-editor-id="tableOutcomePlateName"] .sc-7elhv3-4` |
| Коэффициент | `.bt298` (первый span внутри `.bt296`) |
| ID исхода | атрибут `id` на `.sc-7elhv3-2`: `outcome-{eventId}-{n}-{market}={value}-{n}` |

**Примечание:** коэффициент меняется в реальном времени. Если оба `bt299 bt300` и `bt301 bt302` присутствуют — коэф обновился. Брать первый `.bt298` или `.bt298:first-child`.

---

## Маркеты на странице (из HTML)

| Маркет | Примеры исходов |
|--------|-----------------|
| `Total` | `over 2.5`, `under 2.5`, `over 3`, ... |
| `Both teams to score` | `yes`, `no` |
| `Handicap` | `(-3.5) Real Madrid`, `(3.5) Deportivo Alaves`, ... |
| `Real Madrid total` | `over 2.5`, `under 2.5`, ... |
| `Deportivo Alaves total` | `over 0.5`, `under 0.5`, ... |
| `Third goal` | `Real Madrid`, `none`, `Deportivo Alaves` |
| `Correct score` | `2:0` и др. (через scoreline picker) |
| `Will be a penalty` | `yes`, `no` |

Главный маркет "1X2" (Win/Draw/Win) — на странице должен быть, но находится выше в DOM (в секции `Main`, строки до 240).

---

## Betslip (купон ставки)

```html
<div data-cy="betslip" class="spt-bet-slip">
```

### Состояние выбранного исхода на кнопке

При клике кнопка исхода меняет класс `sc-7elhv3-0`:
- **не выбрана**: `sc-7elhv3-0 laXRqx ...`
- **выбрана**: `sc-7elhv3-0 gQneJl ...`

Также меняется класс имени: `dwaytB` (обычный) → `iRsuCh` (выбранный).

> ⚠️ `btXXXX` классы (bt298, bt1722, etc.) — динамические, меняются между сессиями.
> Использовать только `data-editor-id` и `sc-*` классы.

### Betslip с добавленным исходом

Источник: `live-resolver.hrml.html` — ставка на `under 1.5 @ 3.2`, сумма 100 USDT.

| Элемент | Selector |
|---------|----------|
| Betslip контейнер | `[data-cy="betslip"]` |
| Счётчик ставок в шапке | `[data-editor-id="betslipBetsTotalPin"]` |
| Удалить исход | `[data-editor-id="betslipSelectionRemoveButton"]` |
| Название исхода в купоне | `[data-editor-id="betslipSelection"] a span:last-of-type` |
| Коэффициент в купоне | `[data-editor-id="betslipSelectionOdd"] .bt1901` → нет стабильного, берём первый span |
| **Поле суммы ставки** | `label[data-editor-id="betslipStakeInput"] input` |
| Валюта | `label[data-editor-id="betslipStakeInput"] i` → "USDT" |
| Кнопки быстрой суммы | `[data-editor-id="betslipStakeButton"]` → 10, 20, 50, 300 |
| Кнопка Max | `[data-editor-id="maxBetButton"]` |
| Total Bet | `[data-editor-id="betslipTotalLine"]` |
| Potential Win | `[data-editor-id="betslipPotentialWin"]` |
| **Кнопка Place Bet** | `button[data-editor-id="betslipPlaceBetButton"]` |
| Кнопка Book | `[data-editor-id="betslipShareButton"]` |
| Очистить купон | `[data-cy="ic-delete"]` (кнопка корзины) |

```html
<!-- поле суммы -->
<label data-editor-id="betslipStakeInput">
  <input class="bt1959 bt1960" placeholder="0" inputmode="decimal"
         pattern="\d*" maxlength="10" type="text" value="100">
  <i>USDT</i>
</label>

<!-- кнопка ставки -->
<button data-editor-id="betslipPlaceBetButton">
  <span>Place Bet</span>
</button>
```

---

## Алгоритм поиска нужного коэффициента

Сигнал приходит с полем `market` (например `"over 2.5"` или `"Ф1(0)"`) и `odds` (например `2.10`).

```
1. Ищем маркет по тексту:
   querySelectorAll('[data-editor-id="tableMarketWrapper"]')
   → фильтруем по .bt644 .bt645 span (text содержит ключевое слово маркета)

2. Внутри маркета ищем исход:
   querySelectorAll('[data-editor-id="tableOutcomePlate"]')
   → для каждого: берём текст из .sc-7elhv3-3 (+ .sc-7elhv3-4 если есть)
   → сравниваем с market из сигнала

3. Проверяем коэффициент:
   .bt298 (первый) → parseFloat
   → если |текущий - ожидаемый| > 0.05 → логируем предупреждение, но ставим
   → если > 0.15 → отменяем (коэф ушёл)

4. Кликаем [data-editor-id="tableOutcomePlate"]
```

---

## Соответствие маркетов ABB → BC.game

ABB присылает маркет в своём формате. Нужно маппить.

| ABB format | BC.game outcome text |
|------------|---------------------|
| `Ф1(0)` / `AH1 0` | `(0) Team1` в Handicap |
| `Ф2(0)` / `AH2 0` | `(0) Team2` в Handicap |
| `ТБ(2.5)` / `O 2.5` | `over 2.5` в Total |
| `ТМ(2.5)` / `U 2.5` | `under 2.5` в Total |
| `1` (Win) | `Team1` в 1X2 |
| `X` (Draw) | `Draw` в 1X2 |
| `2` (Win) | `Team2` в 1X2 |

> ⚠️ Точный маппинг нужно уточнить по реальным арбам из ABB.

---

## Подтверждение успешной ставки

После клика "Place Bet" в betslip появляется нотификация:

```html
<div data-editor-id="betslipNotification">
  <svg data-editor-id="successIcon" stroke="#24ee89">...</svg>
  <div>Your bets have been successfully placed!</div>
  <div>
    <button data-editor-id="successNotificationButton">Go to My Bets</button>
    <button data-editor-id="successNotificationButton">Reuse selections</button>
    <button data-editor-id="successNotificationButton">Share</button>
  </div>
</div>
```

| Что | Selector |
|-----|----------|
| Нотификация (появление = успех) | `[data-editor-id="betslipNotification"]` |
| Иконка успеха (зелёная галочка) | `[data-editor-id="successIcon"]` |
| Текст подтверждения | `[data-editor-id="betslipNotification"] div` → "Your bets have been successfully placed!" |

**Алгоритм определения результата:**
```
1. Кликнуть Place Bet
2. Ждать появления [data-editor-id="betslipNotification"] (timeout 10s)
3. Если появился и содержит [data-editor-id="successIcon"] → ставка принята ✓
4. Если timeout → ставка не прошла ✗ (логировать screenshot)
```

---

## Открытые вопросы

1. ~~HTML betslip после добавления исхода~~ — **закрыт**
2. ~~Подтверждение успешной ставки~~ — **закрыт** (`[data-editor-id="betslipNotification"]`)
3. Точный формат маркетов как они приходят от ABB (поле `market` в сигнале)
4. Есть ли капча / 2FA при ставке?
