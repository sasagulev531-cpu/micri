# Дизайн-заметка: FCP-Jobs — «Одна шахта, много работ» + конфиг заданий v2

**Статус:** Ожидает `OK` владельца перед стартом реализации кода (§9 п. 2 ТЗ v2.0).  
**Ветка:** `arena/c8c01266-micri`  
**Модуль:** `fcp-jobs` (Paper 1.21.x, Java 21, без новых плагинов и без NMS)

---

## 1. Аудит текущих точек зачёта прогресса и заработка (§9 п. 1)

Ниже перечислены **все** места в текущем коде `fcp-jobs`, где начисляются прогресс заданий или деньги смены:

| № | Класс и метод | Строки | Триггер Bukkit | Что делает сейчас | Как переводится в слой R2 (`ActionReporter`) |
|---|---|---|---|---|---|
| 1 | `MiningNodeManager.handleBlockBreak` (ветка `BreakResult.ADVANCED`) | `500–505` | `BlockBreakEvent` (`MiningListener`, `HIGH`) | При промежуточной ломке стадии узла (если игрок в зоне и `WorkManager.isWorking(p)`) вызывает `quests.applyBlockBreak(p, current.name(), 1)` | Заменяется на `actionReporter.report(p, JobAction.of(BREAK, current.name(), 1, blockLoc, currentJob))` |
| 2 | `MiningNodeManager.handleBlockBreak` (ветка `BreakResult.COMPLETED`) | `508–524` | `BlockBreakEvent` (`MiningListener`, `HIGH`) | Выдаёт лут `giveLoot(...)`; если игрок в зоне и `isWorking(p)` — вызывает `WorkManager.add(p, reward)` и `quests.applyNodeCompletion(p, current.name(), drops, 1, reward)` | Генерирует пакет событий через `ActionReporter`: `BREAK` (последняя стадия), `MINE` + `OBTAIN` (по каждому выпавшему материалу `drops`), `COMPLETE_NODES` (`1`), `EARN_COINS` (`reward`), `JOB_ACTION` (для `MINER`) |
| 3 | `BlockBreakListener.onBlockBreak` (старая рудная механика) | `72–76` | `BlockBreakEvent` (`NORMAL`) | На не-узловых блоках `*_ORE` / `ANCIENT_DEBRIS` в зоне шахты при `isWorking(p)`: вызывает `WorkManager.add(p, money)` и `quests.applyBlockBreakAndCoins(p, ore.name(), money)`, ставит `COBBLESTONE` на 45 с | Идёт через `ActionReporter`: `BREAK(ore, 1, loc)` + `EARN_COINS(money, loc)` + `JOB_ACTION` (`MINER`, если активна роль шахтёра) |
| 4 | `QuestsMenuListener.onFurnaceExtract` | `94–99` | `FurnaceExtractEvent` (`MONITOR`, `ignoreCancelled=true`) | Вызывает `quests.applyItemSmelted(p, e.getItemType().name(), e.getItemAmount())` | Переносится в общий слушатель действий и репортит `SMELT(mat, amount, blockLoc)` (+ автоматически закрывает `OBTAIN`, и при активной смене `SMELTER` в зоне — `JOB_ACTION(SMELTER)`) |
| 5 | `QuestsMenuListener.onCraftItem` | `103–124` | `CraftItemEvent` (`MONITOR`, `ignoreCancelled=true`) + `runTask` через 1 тик | Считает реальную дельту предметов в инвентаре + оффхенде + на курсоре (защита от дюпа при полном инвентаре) и вызывает `quests.applyItemCrafted(p, targetMat.name(), crafted)` | Сохраняет 1-тик антидюп-дельту и репортит `CRAFT(mat, crafted, playerLoc)` (+ `OBTAIN`, и при активной смене `BLACKSMITH` в зоне — `JOB_ACTION(BLACKSMITH)`) |
| 6 | `WorkManager.add` / `WorkManager.stop` | `30–61` | Конец смены (`/work`, выход из зоны в `WorkCheckListener`, `QuitListener`, `onDisable`) | Хранит `money` только в RAM (`HashMap<UUID, Integer>`), при `stop(p)` вызывает `EconomyService.addCoins(id, earned)` без сохранения при сбое и без переживания краша сервера | Переводится на `WorkSessionManager` с дебаунс-персистентностью в `work-sessions.yml` (R7) и фоновыми повторами выплаты при недоступной экономике (30 с, до 30 мин) |

> Существующие публичные методы `QuestManager.applyBlockBreak(...)`, `applyBlockBreakAndCoins(...)`, `applyNodeCompletion(...)`, `applyItemSmelted(...)`, `applyItemCrafted(...)` сохраняются как тонкие обёртки над `ActionReporter` для обратной совместимости (§4/R2).

---

## 2. Виды работы (Job Kinds — R1, 5 ролей внутри шахты)

Реестр `JobRegistry` хранит объекты интерфейса/класса `JobKind` (`id`, `displayName`, `icon`, `description`, `trackedEvents`, `defaultJob`). Новый вид работы добавляется регистрацией одного объекта `JobKind` + слушателя его механики без переписывания ядра.

### Состав видов работы в первом релизе (5 штук):

| ID (`JobKind`) | Имя в GUI | Иконка | По умолчанию | События роли | Мини-механика в шахте (работает без NMS и не ломает закрытую шахту) |
|---|---|---|---|---|---|
| `MINER` | `&eШахтёр` | `IRON_PICKAXE` | **Да** | `MINE`, `BREAK`, `COMPLETE_NODES`, `EARN_COINS`, `JOB_ACTION(MINER)` | Текущая добыча многостадийных узлов (`MiningNodeManager`) и классических руд (`BlockBreakListener`) — **нулевая регрессия**. |
| `SMELTER` | `&6Печник` | `BLAST_FURNACE` | Нет | `SMELT`, `OBTAIN`, `JOB_ACTION(SMELTER)` | 1) Извлечение выплавленных слитков/ресурсов из печей и плавилен (`FurnaceExtractEvent`).<br>2) **Поддержание жара печей в шахте:** ПКМ углём / древесным углём / угольным блоком по `FURNACE` или `BLAST_FURNACE` внутри зоны шахты (с проверкой кулдауна 10 с на блок печи и расходом 1 ед. угля) — засчитывает цикл работы печника `JOB_ACTION(SMELTER)` и начисляет небольшую сдельную плату в копилку смены. |
| `BLACKSMITH` | `&7Кузнец` | `ANVIL` | Нет | `CRAFT`, `OBTAIN`, `JOB_ACTION(BLACKSMITH)` | 1) Крафт слитков, блоков, рельсов, вагонеток и инструментов (`CraftItemEvent` с сохранением 1-тик антидюп-проверки).<br>2) **Закалка на наковальне/точиле в шахте:** ПКМ железным/медным/золотым слитком по `ANVIL` / `SMITHING_TABLE` / `GRINDSTONE` внутри зоны шахты на смене (кулдаун 8 с на станок, без потери слитка либо перековка заготовки, со звуком наковальни) — даёт `JOB_ACTION(BLACKSMITH)` и сдельную оплату смены. |
| `CART_DRIVER` | `&bГонщик вагонеток` | `CHEST_MINECART` | Нет | `CART_DELIVERY`, `JOB_ACTION(CART_DRIVER)` | **Перевозка породы по путям в шахте** (2 совместимых способа без сложной настройки):<br>а) Игрок едет в `MINECART` по рельсам внутри зоны шахты: каждые пройденные **20 блоков пути** по разным координатам рельсов (защита от качания взад-вперёд по 2 блокам через окно последних координат) засчитывают рейс `CART_DELIVERY(ANY, 1)` + `JOB_ACTION(CART_DRIVER)` + монеты смены.<br>б) Разгрузка породы: ПКМ рудой/булыжником в руке по `CHEST_MINECART` или `BARREL`/`HOPPER` у рельсов внутри зоны шахты — сдаёт стак/партию породы в отгрузку `CART_DELIVERY(material, amount)` (с лимитом и кулдауном от спама). |
| `PUMP_OPERATOR` | `&9Насосчик` | `WATER_BUCKET` | Нет | `PUMP_CYCLE`, `JOB_ACTION(PUMP_OPERATOR)` | **Осушение и дренаж шахты:**<br>а) Забор воды пустым ведром (`PlayerBucketFillEvent`, `WATER`) в затопленных карманах внутри зоны шахты: блок воды временно исчезает и сам восстанавливается через 30 с (как руда), ведро остаётся пустым (нет засорения инвентаря и нет разлива воды в шахте!), засчитывается `PUMP_CYCLE(WATER, 1)` + `JOB_ACTION(PUMP_OPERATOR)` + монеты смены.<br>б) Переключение дренажных рычагов/медных решёток/котлов (`LEVER` рядом с водой/трубой или `WATER_CAULDRON`) в зоне шахты с кулдауном 15 с на точку — также даёт `PUMP_CYCLE` + `JOB_ACTION(PUMP_OPERATOR)`. |

### Переключение вида работы:
1. **В меню `/work`** (без изменения существующих слотов `4` — Информация, `12` — Задачи, `14` — Начать/Закончить работу!):
   - Добавляется **слот `10` («Вид работы»)**: показывает текущую роль, описание её механики и список всех ролей; клик ЛКМ/ПКМ переключает роль по кругу (или открывает компактное подменю выбора из 5 иконок ролей).
   - Переключение разрешено **только на активной смене** (`WorkManager.isWorking(p) == true`). При старте новой смены всегда выставляется дефолт — `MINER`.
2. **Командой:** `/work job <miner|smelter|blacksmith|cart_driver|pump_operator>` (с Tab-комплитом; базовый `/work` без аргументов по-прежнему открывает `WorkMenu`).
3. **Отображение:** текущий вид работы виден в слоте `4` и слоте `10` меню `/work`, а также в шапке (слот `4`) меню `/quests`.

---

## 3. Событийный слой и реестр `ActionKind` (R2)

### Пакет `Frontiks.Craft.jobs` / `Frontiks.Craft.actions`:
- `ActionKind` — дескриптор типа события (`String id`, `Set<String> aliases`, `boolean requiresMaterial`, `boolean defaultRequireWorkingInMine`, обработчик сопоставления `ConditionMatcher`).
- `ActionKindRegistry` — потокобезопасный реестр `ActionKind`:
  - Предзаполняется встроенными видами:
    1. `MINE` (алиасы: `LOOT`, `COLLECT_LOOT`, `LOOT_AMOUNT`)
    2. `BREAK` (алиас: `BREAK_BLOCK`)
    3. `COMPLETE_NODES` (алиасы: `NODES`, `NODES_COMPLETED`)
    4. `SMELT` (алиасы: `FURNACE`, `SMELT_ITEM`)
    5. `CRAFT` (алиас: `CRAFT_ITEM`)
    6. `OBTAIN` (алиасы: `GET`, `OBTAIN_ITEM`) — срабатывает на `MINE`, `SMELT` и `CRAFT`
    7. `EARN_COINS` (алиасы: `COINS`, `EARN_MONEY`)
    8. `JOB_ACTION` — универсальное действие конкретного вида работы (`job: <ID>`)
    9. `CART_DELIVERY` — доставка/отгрузка породы вагонетчиком
    10. `PUMP_CYCLE` — цикл откачки воды / дренажа насосчиком
    11. `TIME_AS_JOB` — накопление минут на активной смене в заданной роли (обслуживает блок шага `time: { as-job, minutes }`)
  - Метод `register(ActionKind customKind)` позволяет в рантайме или тесте зарегистрировать новый `ActionKind` **без единой правки `QuestEngine` / `QuestBoard`** (проверяется отдельным unit-тестом R9.12).
- `JobAction` (immutable record):
  ```java
  public record JobAction(
      String actionKindId,     // канонический ID из ActionKindRegistry
      String material,         // UPPERCASE Material name или null
      int amount,              // >= 1
      Integer blockY,          // координата Y действия (для min-depth / max-depth), может быть null
      boolean workingInMine,   // WorkManager.isWorking(p) && HrabotaConfig.contains(...)
      String playerJobId       // текущий вид работы игрока на смене (MINER и т.д.) или null
  )
  ```
- `ActionReporter` — единая точка входа `report(UUID playerId, Player onlinePlayerOrNull, JobAction action)`, передающая событие в `QuestEngine` и статистику смены.

---

## 4. Схема конфига `quests.yml` v2 и сравнение с §5 ТЗ

Грамматика §5 реализуется **строго 1:1 без ломающих отклонений**. Ниже перечислены только совместимые уточнения (алиасы и дефолты), чтобы и все примеры из §5, и старый конфиг v1 работали без сюрпризов:

| Пункт §5 | Как реализовано | Обоснование |
|---|---|---|
| §5.1 `version: 2`, `defaults`, `security`, `periods`, `quests` | Полное совпадение 1:1. Если `version` не указан и в корне есть `daily.pool` / `weekly.pool` — включается прозрачный конвертер `v1 → v2` в памяти (файл на диске не портится). | Требование R3 / §3.2 обратной совместимости. |
| §5.2 `NODES_COMPLETED` vs §5.3 `COMPLETE_NODES` | В §5.2 в примере написано `event: NODES_COMPLETED`, а в §5.3 — `COMPLETE_NODES(NODES)`. В `ActionKindRegistry` канонический тип `COMPLETE_NODES` принимает оба алиаса (`NODES_COMPLETED` и `NODES`). | Чтобы буквально скопированный пример из §5.2 проходил валидацию без ошибок. |
| §5.2 `min-depth` / `max-depth` | Целые координаты `Y` блока/игрока: если задан `min-depth: Ymin`, то при `Y < Ymin` действие **не** считается («Y и ниже — точнее ниже порога `min-depth` — не считается»: валидно условие `Y >= minDepth`); если задан `max-depth: Ymax`, валидно `Y <= maxDepth` (например, `max-depth: 40` = копать на глубине `Y <= 40`, `min-depth: 0` = не уходить ниже `Y = 0`). Если `min-depth > max-depth` — валидатор сообщает ошибку с путём. | Буквальное следование тексту §5.2 (`min-depth`: ниже не считается, `max-depth`: выше не считается). |
| §5.4 `all` / `any` / `time` | В шаге обязателен либо `all: [...]`, либо `any: [...]` + `any-amount: N` (но не оба сразу!), либо только `time: { as-job, minutes }`, либо комбинация `all` + `time` / `any` + `time`. Перелив прогресса между шагами **не** переносится. | Строго по §5.4 и R4. |
| §5.5 Периоды `daily`, `weekly`, `interval`, `once` | Все 4 шаблона поддерживаются в секции `periods:` (а `category: once` работает даже если не объявлен в `periods:`). Выдача и сброс — строго ленивые при обращении к данным игрока. | Строго по §5.5. |
| §5.6 `rewards` (`coins`, `items` с `chance`, `commands`) и `on-complete.unlock` | Полное совпадение 1:1 + внутренний `RewardHandlerRegistry` (расширяемый под будущие типы наград без реализации BP-очков сейчас). Команды фильтруются через `security.reward-commands-whitelist` (R8.1). Разблокировка `once`-заданий срабатывает после `completed && paid` (R9.7). | Строго по §5.6, §7 и R8.1. |
| Валидатор (R3) | Каждая ошибка содержит точный путь, например: `quests.yml → quests[3](id='code_4').steps[1].any[0]: неизвестный event='MIN'`. Невалидное задание пропускается и пишется в лог/отчёт `/questsadmin validate`, остальные валидные задания продолжают работать. | Строго по R3 и R9.2. |

---

## 5. Хранение данных, дебаунс, выплаты и безопасность (R4, R7, R8)

1. **Дебаунс записи файлов (§3 п. 5):**
   - Общий помощник `DebouncedYamlSaver`: при изменении состояния помечает файл `dirty` и планирует сохранение не чаще **1 раза в 5 секунд (100 тиков)**; в `onDisable()` выполняет немедленный синхронный `flushNow()`.
   - Применяется к `mining-quests.yml` и `work-sessions.yml` (никаких синхронных записей YAML на каждый сломанный блок).
2. **Персистентность смены и заработка (`work-sessions.yml`, R7):**
   - Сохраняет для каждого `UUID`: `active` (`true/false`), `jobKind` (`MINER` и т.д.), `startedAtMs`, `earnedCoins` (текущий накопленный заработок смены), `pendingPayoutCoins` (сумма к выплате, если смена завершилась при недоступной экономике), `firstFailedAtMs`.
   - При старте плагина (`onEnable`) загружает незавершённые сессии: если при рестарте игрок офлайн — переводит его накопленный `earnedCoins` в `pendingPayoutCoins` и выплачивает через `EconomyService` (а если экономика ещё не поднялась — ставит в очередь повторов). Потеря заработка при краше/рестарте исключена.
3. **Накопление времени роли (`TIME_AS_JOB`) и повторы выплат (§3 п. 5, R4):**
   - Время в роли считается в уже существующем 20-тиковом цикле проверки работающих игроков (раз в 60 секунд добавляет `+1` минуту активным игрокам на смене в зоне шахты через `ActionReporter` — `TIME_AS_JOB`).
   - Отдельный лёгкий таймер повтора невыплаченных наград и зарплат смены запускается с периодом **600 тиков (30 секунд)**: пытается выплатить записи с `paid == false` / `pendingPayoutCoins > 0`. Если с первой ошибки прошло **> 30 минут**, прекращает авто-повторы для этой записи и пишет чёткое `SEVERE`-сообщение администратору в лог (при этом ручная кнопка «Проверить награды» в GUI остаётся рабочей).
4. **Режим закрытой шахты (§3 п. 7):**
   - Если в `hrabota.yml` отсутствуют `pos1`/`pos2` или мир шахты не загружен / узлов `0` — `HrabotaConfig.contains(...)` безопасно возвращает `false`, старт плагина, тики, `/work`, `/quests` и `/questsadmin` работают без единого исключения и без спама в лог.

---

## 6. Команды, права и GUI (R5, R6)

- **Новые команды и права в `plugin.yml`:**
  - `/questsadmin <validate|reload|jobs>` (алиас: `/qadmin`, плюс подкоманда `/mnode questvalidate`) — полный отчёт валидации `quests.yml` v2, пулов периодов, цепочек `requires`/`unlock`, белого списка команд и зарегистрированных видов работы. Право: `fcp.admin.jobs.quests` (`default: op`).
  - Право на переключение вида работы `/work job ...` и в меню: `fcp.jobs.switch` (`default: true`).
- **GUI `/work` (`WorkMenu`, 27 слотов):**
  - Слот `4` — Информация (статус смены, текущий вид работы, заработок смены) — сохранён.
  - Слот `10` — **Вид работы** (иконка текущей роли, описание её действий, ЛКМ/ПКМ — переключить роль на активной смене).
  - Слот `12` — Задачи шахты (`/quests`) — сохранён.
  - Слот `14` — Начать / Закончить работу — сохранён.
- **GUI `/quests` (`QuestsMenu`, 54 слота с фильтрацией/пагинацией при >21 заданиях):**
  - Отображает: название, иконку, категорию (`Ежедневное` / `Еженедельное` / `Интервальное` / `Разовое`), требуемый вид работы (`requires.job`), предусловия (`🔒 Требуется: ...`), прогресс всех условий внутри `all` или суммарный `any` + таймер `time` по шагам (`✔` / `▶` / `🔒`), список наград (монеты, предметы с шансом), статус (`Взять ЛКМ` / `Активно` / `Выполнено` / `Ожидает выплаты`), кнопку «Проверить награды» и кнопку закрытия.

---

## 7. План реализации по слоям (§9 п. 3)

После подтверждения (`OK`) реализация пойдёт строго слоями с зелёным `mvn -B clean package` на каждом шаге:

1. **Слой A — Событийный слой (R2):** `ActionKind`, `ActionKindRegistry`, `JobAction`, `ActionReporter` + тесты расширяемости (R9.12).
2. **Слой B — Виды работы (R1):** `JobKind`, `JobRegistry`, 5 ролей (`MINER`, `SMELTER`, `BLACKSMITH`, `CART_DRIVER`, `PUMP_OPERATOR`) и слушатели их шахтных мини-механик.
3. **Слой C — Парсер и валидатор `quests.yml` v2 + конвертер v1→v2 (R3):** модели условий (`all`/`any`/`time`, `min-depth`/`max-depth`, `requires`, `on-complete.unlock`), строгий валидатор с путями ошибок + unit-тесты (R9.1, R9.2).
4. **Слой D — Runtime машина состояний (R4):** переходы `locked → available → in_progress → completed`, периоды `daily/weekly/interval/once`, retry выплат с лимитом 30 мин, расширение `mining-quests.yml` с чтением старых записей + unit-тесты (R9.3–R9.7, R9.11).
5. **Слой E — Персистентность смены и заработка (R7):** `WorkSessionStore` (`work-sessions.yml`), `DebouncedYamlSaver`, восстановление и довыплата после рестарта + unit-тест (R9.9) и тест закрытой шахты (R9.10).
6. **Слой F — Безопасность (R8):** фильтр `security.reward-commands-whitelist`, `RewardHandlerRegistry` + unit-тест с mock-диспатчером (R9.8).
7. **Слой G — GUI и команды (R5, R6):** обновление `WorkMenu`, `QuestsMenu`, команда `/questsadmin` (`/mnode questvalidate`), `plugin.yml`.
8. **Слой H — Контент, документация и релизная сборка (R10, §10):** `quests.example.yml` и дефолтный `quests.yml` (≥16 заданий по матрице §5.7), полное обновление `README.md`, удаление `.sha256`, финальная сборка `fcp-jobs-1.0.0.jar` без деплоя на прод.
