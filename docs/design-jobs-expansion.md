# Дизайн-заметка: FCP-Jobs — Шахтёр (`MINER`) + гибкий конфиг заданий v2

**Статус:** Согласовано владельцем — **оставляем только роль `MINER` («Шахтёр»)** без лишних ролей (остальные работы владелец спроектирует сам позже; расширяемый реестр `JobRegistry` заложен в архитектуру «под капотом»).  
**Ветка:** `arena/c8c01266-micri`  
**Модуль:** `fcp-jobs` (Paper 1.21.x, Java 21, без новых плагинов и без NMS)

---

## 1. Аудит текущих точек зачёта прогресса и заработка (§9 п. 1)

| № | Класс и метод | Строки | Триггер Bukkit | Что делает сейчас | Перевод на слой R2 (`ActionReporter`) |
|---|---|---|---|---|---|
| 1 | `MiningNodeManager.handleBlockBreak` (`ADVANCED`) | `500–505` | `BlockBreakEvent` (`MiningListener`, `HIGH`) | При ломке промежуточной стадии узла в зоне на смене вызывает `quests.applyBlockBreak(p, current.name(), 1)` | Репортит через `ActionReporter`: `BREAK(current, 1, blockY)` + `JOB_ACTION(MINER, 1)` |
| 2 | `MiningNodeManager.handleBlockBreak` (`COMPLETED`) | `508–524` | `BlockBreakEvent` (`MiningListener`, `HIGH`) | Выдаёт лут; на смене в зоне вызывает `WorkManager.add(p, reward)` и `quests.applyNodeCompletion(...)` | Репортит через `ActionReporter`: `BREAK`, `MINE` по каждому предмету лута, `COMPLETE_NODES(1)`, `EARN_COINS(reward)`, `JOB_ACTION(MINER, 1)` |
| 3 | `BlockBreakListener.onBlockBreak` | `72–76` | `BlockBreakEvent` (`NORMAL`) | На не-узловых рудах в зоне на смене вызывает `WorkManager.add(p, money)` и `quests.applyBlockBreakAndCoins(p, ore.name(), money)` | Репортит через `ActionReporter`: `BREAK(ore, 1, blockY)`, `EARN_COINS(money)`, `JOB_ACTION(MINER, 1)` |
| 4 | `QuestsMenuListener.onFurnaceExtract` | `94–99` | `FurnaceExtractEvent` (`MONITOR`) | Вызывает `quests.applyItemSmelted(...)` | Репортит `SMELT(mat, amount, blockY)` (автоматически учитывается и в `OBTAIN`) |
| 5 | `QuestsMenuListener.onCraftItem` | `103–124` | `CraftItemEvent` (`MONITOR`) + 1-тик дельта | Защищённо считает реально скрафченное количество и вызывает `quests.applyItemCrafted(...)` | Сохраняет 1-тик антидюп-дельту и репортит `CRAFT(mat, crafted, playerY)` (также учитывается в `OBTAIN`) |
| 6 | `WorkManager.add` / `WorkManager.stop` | `30–61` | Смена `/work`, выход из зоны, `QuitListener`, `onDisable` | Хранит заработок только в RAM, при `stop(p)` начисляет монеты один раз без защиты от рестарта/сбоя экономики | Переводится на `WorkSessionStore` (`work-sessions.yml`) с дебаунс-сохранением и повторами выплат (30 с, лимит 30 мин) |

---

## 2. Решение владельца по видам работы (R1)

- **В игре активен ТОЛЬКО один вид работы: `MINER` («Шахтёр», `IRON_PICKAXE`, по умолчанию).**
- Никаких дополнительных придуманных ролей (печник, кузнец, вагонетки, насосы) в прод-код и меню `/work` не добавляется — меню `/work` остаётся чистым и привычным (слоты `4`, `12`, `14`).
- Архитектурно создаётся чистый расширяемый реестр `JobRegistry` + `JobKind`, в котором зарегистрирован `MINER`. Когда владелец придумает новые работы, их можно будет зарегистрировать одним классом/записью без переделки движка квестов и событий.

---

## 3. Событийный слой `ActionReporter` и реестр `ActionKind` (R2)

Потокобезопасный реестр `ActionKindRegistry` (новые виды действий регистрируются вызовом `registry.register(...)` без правки движка заданий — покрыто unit-тестом):
1. `MINE` (алиасы: `LOOT`, `COLLECT_LOOT`, `LOOT_AMOUNT`)
2. `BREAK` (алиас: `BREAK_BLOCK`)
3. `COMPLETE_NODES` (алиасы: `NODES`, `NODES_COMPLETED`)
4. `SMELT` (алиасы: `FURNACE`, `SMELT_ITEM`)
5. `CRAFT` (алиас: `CRAFT_ITEM`)
6. `OBTAIN` (алиасы: `GET`, `OBTAIN_ITEM` — матчит `MINE`, `SMELT` и `CRAFT`)
7. `EARN_COINS` (алиасы: `COINS`, `EARN_MONEY`)
8. `JOB_ACTION` (счётчик действий работы `job: MINER` или будущих работ)
9. `TIME_AS_JOB` (накопление минут на активной смене в шахте для блока `time: { as-job: MINER, minutes: N }`)

---

## 4. Грамматика `quests.yml` v2, совместимость и безопасность (R3–R8)

- Реализуется грамматика §5 (`version: 2`, `defaults`, `security.reward-commands-whitelist`, `periods` с шаблонами `daily` / `weekly` / `interval` + `once`, `quests` с `requires`, `steps` (`all` / `any` + `any-amount` / `time`), `min-depth` / `max-depth`, `rewards` (`coins`, `items` с `chance`, `commands`), `on-complete.unlock`).
- Прозрачный конвертер `v1 → v2` в памяти: старый `quests.yml` читается и работает без правок.
- Валидатор выдаёт точный путь ошибки (`quests.yml → quests[3](id='...').steps[1].any[0]: неизвестный event='MIN'`) и не роняет плагин.
- Дебаунс записи `mining-quests.yml` и `work-sessions.yml` (не чаще 1 раза в 5 с + синхронный flush в `onDisable`).
- Повторы невыплаченных наград и зарплат смены каждые 30 с (до 30 мин, затем лог админу) + ручная кнопка «Проверить награды» в `/quests`.
- Режим закрытой шахты (`pos1`/`pos2` не заданы или узлов нет): плагин стартует, тики и GUI `/work`, `/quests`, `/questsadmin` работают без ошибок.
