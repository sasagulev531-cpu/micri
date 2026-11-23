package Frontiks.Craft.quests.v2;

import Frontiks.Craft.actions.ActionKind;
import Frontiks.Craft.actions.ActionKindRegistry;
import Frontiks.Craft.actions.JobAction;
import Frontiks.Craft.jobs.JobKind;
import Frontiks.Craft.jobs.JobRegistry;
import Frontiks.Craft.mining.MiningSettings;
import Frontiks.Craft.quests.v2.QuestEngineV2.LifecycleState;
import Frontiks.Craft.quests.v2.QuestEngineV2.PlayerContext;
import Frontiks.Craft.quests.v2.QuestV2Models.*;
import Frontiks.Craft.work.HrabotaConfig;
import Frontiks.Craft.work.WorkSessionTracker;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Полный набор unit-тестов без сервера Bukkit по матрице R9 (п. 1..12 ТЗ v2.0).
 */
class QuestsV2AndSessionsTest {

    // R9.1: Старый quests.yml v1 парсится автоматически в эквивалентный v2
    @Test
    void r9_1_legacyV1QuestsYmlConvertsToEquivalentV2() {
        String legacyV1 = """
                enabled: true
                timezone: "Europe/Moscow"
                daily:
                  count: 2
                  pool:
                    - id: daily_coal_24
                      title: "&fУголь"
                      require-accept: false
                      reward: 300
                      type: MINE
                      material: COAL
                      amount: 24
                weekly:
                  count: 1
                  pool:
                    - id: weekly_iron_chain
                      title: "&6Железо"
                      require-accept: true
                      reward: 3000
                      reward-items:
                        - "DIAMOND 2"
                      steps:
                        - type: MINE
                          material: RAW_IRON
                          amount: 64
                        - type: SMELT
                          material: IRON_INGOT
                          amount: 64
                """;

        QuestsConfigParser parser = new QuestsConfigParser(ActionKindRegistry.withBuiltins(), JobRegistry.withDefaultMiner());
        ParseResult res = parser.parseYamlString(legacyV1, "quests.yml");
        assertTrue(res.errors().isEmpty(), () -> "Не должно быть ошибок: " + res.errors());
        assertEquals(2, res.config().quests().size());

        QuestSpec daily = res.config().questsById().get("daily_coal_24");
        assertNotNull(daily);
        assertEquals("daily", daily.category());
        assertFalse(daily.manualAccept());
        assertEquals(300, daily.rewards().coins());
        assertEquals(1, daily.steps().size());
        assertEquals("MINE", daily.steps().get(0).conditions().get(0).kind().id);

        QuestSpec weekly = res.config().questsById().get("weekly_iron_chain");
        assertNotNull(weekly);
        assertEquals("weekly", weekly.category());
        assertTrue(weekly.manualAccept());
        assertEquals(2, weekly.steps().size());
        assertEquals(1, weekly.rewards().items().size());
        assertEquals("DIAMOND", weekly.rewards().items().get(0).material());
        assertEquals(2, weekly.rewards().items().get(0).amount());
    }

    // R9.2: Опечатка в event -> ошибка с точным путём, а валидное задание остаётся рабочим
    @Test
    void r9_2_typoInEventReportsExactPathAndKeepsValidQuests() {
        String yamlWithTypo = """
                version: 2
                quests:
                  - id: good_quest
                    category: daily
                    steps:
                      - all:
                          - { event: MINE, material: COAL, amount: 10 }
                    rewards: { coins: 100 }
                  - id: bad_quest
                    category: weekly
                    steps:
                      - all:
                          - { event: BREAK, material: STONE, amount: 10 }
                      - any-amount: 20
                        any:
                          - { event: MIN, material: RAW_IRON }
                """;

        QuestsConfigParser parser = new QuestsConfigParser(ActionKindRegistry.withBuiltins(), JobRegistry.withDefaultMiner());
        ParseResult res = parser.parseYamlString(yamlWithTypo, "quests.yml");

        assertEquals(1, res.errors().size());
        String err = res.errors().get(0);
        assertTrue(err.contains("quests.yml") && err.contains("quests[1]") && err.contains("steps[1].any[0]") && err.contains("MIN"),
                "Ошибка должна содержать точный путь к опечатке, получено: " + err);
        // Валидное задание сохранено и работает!
        assertTrue(res.config().questsById().containsKey("good_quest"));
        assertFalse(res.config().questsById().containsKey("bad_quest"));
    }

    // R9.3: Семантика групп all и any + отсутствие переноса остатка между шагами (§5.4)
    @Test
    void r9_3_allAndAnyGroupsAndNoOverflowBetweenSteps() {
        String yaml = """
                version: 2
                periods:
                  daily: { template: daily, count: 5 }
                quests:
                  - id: q_chain_groups
                    category: daily
                    accept: auto
                    steps:
                      - name: "Шаг 1 (ANY 10)"
                        any-amount: 10
                        any:
                          - { event: MINE, material: COAL }
                          - { event: MINE, material: RAW_COPPER }
                      - name: "Шаг 2 (ALL)"
                        all:
                          - { event: MINE, material: COAL, amount: 5 }
                          - { event: COMPLETE_NODES, amount: 2 }
                    rewards: { coins: 500 }
                """;

        QuestsConfig cfg = new QuestsConfigParser(ActionKindRegistry.withBuiltins(), JobRegistry.withDefaultMiner())
                .parseYamlString(yaml, "quests.yml").config();
        PlayerQuestProfile profile = new PlayerQuestProfile();
        AtomicInteger paid = new AtomicInteger();

        // Шаг 1 (ANY 10): 6 COAL + 8 RAW_COPPER = 14 (>= 10 -> переход на Шаг 2, перелив 4 COAL НЕ переносится!)
        QuestEngineV2.applyAction(profile, cfg, PlayerContext.DEFAULT_MINER,
                JobAction.of("MINE", "COAL", 6, 40, true, "MINER"), 1000L, new Random(1), q -> true);
        var rAdvance = QuestEngineV2.applyAction(profile, cfg, PlayerContext.DEFAULT_MINER,
                JobAction.of("MINE", "RAW_COPPER", 8, 40, true, "MINER"), 1000L, new Random(1),
                q -> { paid.addAndGet(q.rewards().coins()); return true; });

        assertEquals(1, rAdvance.stepAdvances().size());
        assertEquals(0, paid.get());

        // На Шаге 2 добываем сначала ещё 20 COAL, но узлы ещё 0/2 -> шаг ALL не закрыт
        QuestEngineV2.applyAction(profile, cfg, PlayerContext.DEFAULT_MINER,
                JobAction.of("MINE", "COAL", 5, 40, true, "MINER"), 1000L, new Random(1),
                q -> { paid.addAndGet(q.rewards().coins()); return true; });
        assertEquals(0, paid.get());

        // Добиваем 2 узла -> оба условия ALL выполнены -> выплата 500!
        var rFinish = QuestEngineV2.applyAction(profile, cfg, PlayerContext.DEFAULT_MINER,
                JobAction.of("COMPLETE_NODES", null, 2, 40, true, "MINER"), 1000L, new Random(1),
                q -> { paid.addAndGet(q.rewards().coins()); return true; });
        assertEquals(List.of("q_chain_groups"), rFinish.completedNow());
        assertEquals(500, paid.get());
    }

    // R9.4: Условие времени time: { as-job: MINER, minutes: 30 }
    @Test
    void r9_4_timeConditionAccumulatesMinutesOnlyWhileWorkingAsJob() {
        String yaml = """
                version: 2
                quests:
                  - id: q_time_shift
                    category: daily
                    accept: auto
                    steps:
                      - name: "Смена 30 мин и 10 угля"
                        all:
                          - { event: MINE, material: COAL, amount: 10 }
                        time:
                          as-job: MINER
                          minutes: 30
                    rewards: { coins: 400 }
                """;
        QuestsConfig cfg = new QuestsConfigParser(ActionKindRegistry.withBuiltins(), JobRegistry.withDefaultMiner())
                .parseYamlString(yaml, "quests.yml").config();
        PlayerQuestProfile profile = new PlayerQuestProfile();
        AtomicInteger paid = new AtomicInteger();

        // Добыли уголь, но время 0/30 -> ещё не выполнено
        QuestEngineV2.applyAction(profile, cfg, PlayerContext.DEFAULT_MINER,
                JobAction.of("MINE", "COAL", 10, 50, true, "MINER"), 1000L, new Random(1),
                q -> { paid.addAndGet(q.rewards().coins()); return true; });
        assertEquals(0, paid.get());

        // Вне смены (workingInMine=false) время не считается
        QuestEngineV2.applyAction(profile, cfg, PlayerContext.DEFAULT_MINER,
                JobAction.of("TIME_AS_JOB", null, 30, 50, false, "MINER"), 1000L, new Random(1),
                q -> { paid.addAndGet(q.rewards().coins()); return true; });
        assertEquals(0, paid.get());

        // 15 + 15 минут на смене MINER -> задание закрывается!
        QuestEngineV2.applyAction(profile, cfg, PlayerContext.DEFAULT_MINER,
                JobAction.of("TIME_AS_JOB", null, 15, 50, true, "MINER"), 1000L, new Random(1),
                q -> { paid.addAndGet(q.rewards().coins()); return true; });
        assertEquals(0, paid.get());

        var rDone = QuestEngineV2.applyAction(profile, cfg, PlayerContext.DEFAULT_MINER,
                JobAction.of("TIME_AS_JOB", null, 15, 50, true, "MINER"), 1000L, new Random(1),
                q -> { paid.addAndGet(q.rewards().coins()); return true; });
        assertEquals(List.of("q_time_shift"), rDone.completedNow());
        assertEquals(400, paid.get());
    }

    // R9.5: Периоды daily, weekly (ISO + timezone), interval (N дней), once
    @Test
    void r9_5_periodsDailyWeeklyIntervalAndOnceRespectTimezoneAndSchedule() {
        ZoneId msk = ZoneId.of("Europe/Moscow");
        PeriodTemplate daily = new PeriodTemplate("daily", PeriodKind.DAILY, 1, 3);
        PeriodTemplate weekly = new PeriodTemplate("weekly", PeriodKind.WEEKLY, 7, 3);
        PeriodTemplate monthly = new PeriodTemplate("monthly", PeriodKind.INTERVAL, 30, 1);
        PeriodTemplate once = new PeriodTemplate("once", PeriodKind.ONCE, 0, 10);

        long sun2359 = ZonedDateTime.of(2026, 11, 22, 23, 59, 0, 0, msk).toInstant().toEpochMilli();
        long mon0001 = ZonedDateTime.of(2026, 11, 23, 0, 1, 0, 0, msk).toInstant().toEpochMilli();

        assertNotEquals(
                QuestEngineV2.computePeriodKey(daily, msk, sun2359, 0L),
                QuestEngineV2.computePeriodKey(daily, msk, mon0001, 0L)
        );
        assertNotEquals(
                QuestEngineV2.computePeriodKey(weekly, msk, sun2359, 0L),
                QuestEngineV2.computePeriodKey(weekly, msk, mon0001, 0L)
        );

        long anchor = sun2359;
        long day29 = anchor + 29L * 86_400_000L;
        long day30 = anchor + 30L * 86_400_000L;
        assertEquals(
                QuestEngineV2.computePeriodKey(monthly, msk, anchor, anchor),
                QuestEngineV2.computePeriodKey(monthly, msk, day29, anchor)
        );
        assertNotEquals(
                QuestEngineV2.computePeriodKey(monthly, msk, anchor, anchor),
                QuestEngineV2.computePeriodKey(monthly, msk, day30, anchor)
        );
        assertEquals("ONCE", QuestEngineV2.computePeriodKey(once, msk, anchor, anchor));
        assertEquals("ONCE", QuestEngineV2.computePeriodKey(once, msk, day30, anchor));
    }

    // R9.6: Однократность выплаты, retry при сбое экономики и 30-минутный лимит авто-повторов
    @Test
    void r9_6_singlePayoutRetryAnd30MinuteAutoRetryCap() {
        String yaml = """
                version: 2
                quests:
                  - id: q_pay_test
                    category: daily
                    accept: auto
                    steps:
                      - all:
                          - { event: COMPLETE_NODES, amount: 1 }
                    rewards: { coins: 777 }
                """;
        QuestsConfig cfg = new QuestsConfigParser(ActionKindRegistry.withBuiltins(), JobRegistry.withDefaultMiner())
                .parseYamlString(yaml, "quests.yml").config();
        PlayerQuestProfile profile = new PlayerQuestProfile();
        AtomicBoolean economyUp = new AtomicBoolean(false);
        AtomicInteger payoutCount = new AtomicInteger();
        List<String> adminAlerts = new ArrayList<>();

        long t0 = 1_000_000L;
        var r1 = QuestEngineV2.applyAction(profile, cfg, PlayerContext.DEFAULT_MINER,
                JobAction.of("COMPLETE_NODES", null, 1, 50, true, "MINER"), t0, new Random(1),
                q -> {
                    if (!economyUp.get()) return false;
                    payoutCount.incrementAndGet();
                    return true;
                });
        assertEquals(List.of("q_pay_test"), r1.completedNow());
        assertEquals(List.of("q_pay_test"), r1.payFailed());
        assertEquals(0, payoutCount.get());

        // Авто-повтор через 10 минут (экономика всё ещё лежит) -> пробует, лимит не исчерпан
        QuestEngineV2.retryPendingPayouts(profile, cfg, t0 + 10L * 60_000L, false,
                q -> false, adminAlerts::add);
        assertTrue(adminAlerts.isEmpty());

        // Авто-повтор через 31 минуту (> 30 мин) -> прекращает авто-повторы и пишет алерт админу!
        economyUp.set(true);
        QuestEngineV2.retryPendingPayouts(profile, cfg, t0 + 31L * 60_000L, false,
                q -> { payoutCount.incrementAndGet(); return true; }, adminAlerts::add);
        assertEquals(1, adminAlerts.size());
        assertEquals(0, payoutCount.get(), "После 30 минут авто-повтор не должен вызываться");

        // Но ручная кнопка «Проверить награды» (manualClick=true) успешно довыдаёт ровно 1 раз!
        var rManual = QuestEngineV2.retryPendingPayouts(profile, cfg, t0 + 32L * 60_000L, true,
                q -> { payoutCount.incrementAndGet(); return true; }, adminAlerts::add);
        assertEquals(List.of("q_pay_test"), rManual.paidNow());
        assertEquals(1, payoutCount.get());

        // Повторные события и повторные клики больше НИКОГДА не начисляют дубль!
        QuestEngineV2.applyAction(profile, cfg, PlayerContext.DEFAULT_MINER,
                JobAction.of("COMPLETE_NODES", null, 5, 50, true, "MINER"), t0 + 33L * 60_000L, new Random(1),
                q -> { payoutCount.incrementAndGet(); return true; });
        QuestEngineV2.retryPendingPayouts(profile, cfg, t0 + 34L * 60_000L, true,
                q -> { payoutCount.incrementAndGet(); return true; }, adminAlerts::add);
        assertEquals(1, payoutCount.get());
    }

    // R9.7: once + on-complete.unlock разблокирует следующее задание ТОЛЬКО после выполнения И выплаты
    @Test
    void r9_7_onceUnlockTriggersOnlyAfterCompletionAndPayout() {
        String yaml = """
                version: 2
                quests:
                  - id: stage_1
                    category: once
                    accept: auto
                    steps:
                      - all:
                          - { event: COMPLETE_NODES, amount: 2 }
                    rewards: { coins: 100 }
                    on-complete:
                      unlock: [stage_2]
                  - id: stage_2
                    category: once
                    accept: auto
                    requires:
                      quest: stage_1
                    steps:
                      - all:
                          - { event: COMPLETE_NODES, amount: 3 }
                    rewards: { coins: 300 }
                """;
        QuestsConfig cfg = new QuestsConfigParser(ActionKindRegistry.withBuiltins(), JobRegistry.withDefaultMiner())
                .parseYamlString(yaml, "quests.yml").config();
        PlayerQuestProfile profile = new PlayerQuestProfile();
        AtomicBoolean economyUp = new AtomicBoolean(false);

        // Изначально stage_2 заблокирован (LOCKED)
        var initialViews = QuestEngineV2.buildViews(profile, cfg, PlayerContext.DEFAULT_MINER, 1000L, new Random(1));
        var v2Before = initialViews.stream().filter(v -> v.spec().id().equals("stage_2")).findFirst().orElseThrow();
        assertEquals(LifecycleState.LOCKED, v2Before.lifecycle());

        // Выполняем stage_1 при упавшей экономике (completed=true, paid=false) -> stage_2 НЕ должен разблокироваться!
        QuestEngineV2.applyAction(profile, cfg, PlayerContext.DEFAULT_MINER,
                JobAction.of("COMPLETE_NODES", null, 2, 40, true, "MINER"), 2000L, new Random(1),
                q -> economyUp.get());
        var midViews = QuestEngineV2.buildViews(profile, cfg, PlayerContext.DEFAULT_MINER, 2000L, new Random(1));
        var v2Mid = midViews.stream().filter(v -> v.spec().id().equals("stage_2")).findFirst().orElseThrow();
        assertEquals(LifecycleState.LOCKED, v2Mid.lifecycle(), "До выплаты награды разблокировки быть не должно");

        // Экономика поднялась -> выплата stage_1 проходит -> stage_1 исчезает навсегда, а stage_2 становится IN_PROGRESS!
        economyUp.set(true);
        var retryRes = QuestEngineV2.retryPendingPayouts(profile, cfg, 3000L, true, q -> true, null);
        assertEquals(List.of("stage_2"), retryRes.unlockedNow());

        var afterViews = QuestEngineV2.buildViews(profile, cfg, PlayerContext.DEFAULT_MINER, 3000L, new Random(1));
        assertTrue(afterViews.stream().noneMatch(v -> v.spec().id().equals("stage_1")),
                "Оплаченное once-задание должно исчезнуть навсегда");
        var v2After = afterViews.stream().filter(v -> v.spec().id().equals("stage_2")).findFirst().orElseThrow();
        assertEquals(LifecycleState.IN_PROGRESS, v2After.lifecycle());
    }

    // R9.8: Белый список reward-команд (security.reward-commands-whitelist)
    @Test
    void r9_8_rewardCommandsWhitelistBlocksUnauthorizedCommands() {
        List<String> dispatched = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        UUID uuid = UUID.randomUUID();

        // 1) Пустой белый список блокирует всё
        CommandWhitelistGuard.executeAllowed(
                List.of("op %player%", "fcp give %player% crate 1"),
                List.of(),
                "Steve", uuid, dispatched::add, warnings::add
        );
        assertTrue(dispatched.isEmpty());
        assertEquals(2, warnings.size());

        // 2) С белым списком ["fcp give"] проходит только "fcp give ...", а "op ..." и команды с ';' блокируются
        warnings.clear();
        var rep = CommandWhitelistGuard.executeAllowed(
                List.of("op %player%", "fcp give %player% key 1", "fcp give %player% key 1; op Steve"),
                List.of("fcp give"),
                "Miner_01", uuid, dispatched::add, warnings::add
        );
        assertEquals(List.of("fcp give Miner_01 key 1"), dispatched);
        assertEquals(1, rep.executed().size());
        assertEquals(2, rep.blocked().size());
    }

    // R9.9: Персистентность смены и заработка переживает рестарт без потери монет
    @Test
    void r9_9_workSessionEarningsSurviveRestartWithoutCoinLoss() {
        UUID player = UUID.randomUUID();
        WorkSessionTracker beforeCrash = new WorkSessionTracker();
        beforeCrash.startShift(player, JobKind.MINER_ID, 10_000L);
        beforeCrash.addEarned(player, 150);
        beforeCrash.addEarned(player, 320);
        assertEquals(470, beforeCrash.getEarned(player));

        // Сохранение состояния на диск перед рестартом/крашем
        Map<String, Object> savedYamlMap = beforeCrash.toMap();

        // Новый экземпляр после рестарта; сначала экономика ещё не загрузилась (false)
        WorkSessionTracker afterRestart = new WorkSessionTracker();
        afterRestart.loadFromMap(savedYamlMap);
        AtomicInteger creditedCoins = new AtomicInteger();

        afterRestart.recoverAfterRestart(20_000L, (id, coins) -> false);
        assertFalse(afterRestart.isWorking(player));
        assertEquals(470, afterRestart.getPendingPayout(player), "Заработок обязан сохраниться в очереди выплаты");

        // Экономика поднялась -> фоновый retry выплачивает ровно 470 монет и очищает долг!
        int paidCount = afterRestart.retryPendingPayouts(50_000L, false, (id, coins) -> {
            assertEquals(player, id);
            creditedCoins.addAndGet(coins);
            return true;
        }, null);
        assertEquals(1, paidCount);
        assertEquals(470, creditedCoins.get());
        assertEquals(0, afterRestart.getPendingPayout(player));

        // Повторный вызов ничего не начисляет повторно
        afterRestart.retryPendingPayouts(80_000L, false, (id, coins) -> {
            creditedCoins.addAndGet(coins);
            return true;
        }, null);
        assertEquals(470, creditedCoins.get());
    }

    // R9.10: Режим закрытой шахты (пустой HrabotaConfig / null локации / отсутствие узлов)
    @Test
    void r9_10_closedMineModeDoesNotThrowOnNullOrUnsetRegion() {
        assertDoesNotThrow(() -> {
            assertFalse(HrabotaConfig.contains(null));
            assertNotNull(HrabotaConfig.describeBounds());
            assertNotNull(HrabotaConfig.get());

            MiningSettings settings = new MiningSettings();
            assertTrue(settings.configErrors().isEmpty(), () -> "Ошибки дефолтного конфига: " + settings.configErrors());
            assertTrue(settings.questsConfigV2().quests().size() >= 15,
                    "Встроенный quests.example.yml должен содержать >= 15 заданий шахтёра");
        });
    }

    // R9.11: Регрессия старых типов + min-depth / max-depth + чтение старых записей mining-quests.yml
    @Test
    void r9_11_regressionOldTypesDepthBoundsAndLegacyPlayerSave() throws Exception {
        // Проверяем, что quests.example.yml в ресурсах валиден и содержит >= 15 заданий только для шахтёра
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("quests.example.yml")) {
            assertNotNull(in, "quests.example.yml должен быть в resources");
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            ParseResult pr = new QuestsConfigParser(ActionKindRegistry.withBuiltins(), JobRegistry.withDefaultMiner())
                    .parseYamlString(text, "quests.example.yml");
            assertTrue(pr.errors().isEmpty(), () -> "quests.example.yml содержит ошибки: " + pr.errors());
            assertTrue(pr.config().quests().size() >= 15);
        }

        // Проверяем min-depth / max-depth и старый формат сохранения игрока
        String yaml = """
                version: 2
                quests:
                  - id: q_depth
                    category: daily
                    accept: auto
                    steps:
                      - all:
                          - { event: MINE, material: RAW_IRON, amount: 10, min-depth: -30, max-depth: 20 }
                    rewards: { coins: 200 }
                """;
        QuestsConfig cfg = new QuestsConfigParser(ActionKindRegistry.withBuiltins(), JobRegistry.withDefaultMiner())
                .parseYamlString(yaml, "quests.yml").config();

        // Старый формат mining-quests.yml v1 (daily.quests с полем progress: 6)
        PlayerQuestProfile legacyLoaded = PlayerQuestProfile.fromMap(Map.of(
                "daily", Map.of(
                        "period", QuestEngineV2.computePeriodKey(cfg.periods().get("daily"), cfg.timezone(), 1000L, 0L),
                        "quests", List.of(Map.of("id", "q_depth", "accepted", true, "step", 0, "progress", 6,
                                "completed", false, "paid", false))
                )
        ));

        AtomicInteger paid = new AtomicInteger();
        // На высоте Y = 50 (> max-depth 20) не засчитывается!
        QuestEngineV2.applyAction(legacyLoaded, cfg, PlayerContext.DEFAULT_MINER,
                JobAction.of("MINE", "RAW_IRON", 5, 50, true, "MINER"), 1000L, new Random(1),
                q -> { paid.addAndGet(q.rewards().coins()); return true; });
        assertEquals(0, paid.get());

        // На глубине Y = -40 (< min-depth -30) не засчитывается!
        QuestEngineV2.applyAction(legacyLoaded, cfg, PlayerContext.DEFAULT_MINER,
                JobAction.of("MINE", "RAW_IRON", 5, -40, true, "MINER"), 1000L, new Random(1),
                q -> { paid.addAndGet(q.rewards().coins()); return true; });
        assertEquals(0, paid.get());

        // На глубине Y = 0 (внутри [-30..20]): было 6 + 4 = 10 -> задание выполнено!
        var rOk = QuestEngineV2.applyAction(legacyLoaded, cfg, PlayerContext.DEFAULT_MINER,
                JobAction.of("MINE", "RAW_IRON", 4, 0, true, "MINER"), 1000L, new Random(1),
                q -> { paid.addAndGet(q.rewards().coins()); return true; });
        assertEquals(List.of("q_depth"), rOk.completedNow());
        assertEquals(200, paid.get());
    }

    // R9.12: Новый ActionKind регистрируется в реестре и закрывает условие без правки движка заданий
    @Test
    void r9_12_customActionKindRegistersAndCompletesQuestWithoutTouchingEngine() {
        ActionKindRegistry registry = ActionKindRegistry.withBuiltins();
        registry.register(new ActionKind("GEODE_CRACK", Set.of("CRACK_GEODE"), true, true, "Расколоть жеоду"));

        String yaml = """
                version: 2
                quests:
                  - id: q_custom_geode
                    category: daily
                    accept: auto
                    steps:
                      - all:
                          - { event: GEODE_CRACK, material: AMETHYST_CLUSTER, amount: 3 }
                    rewards: { coins: 900 }
                """;

        QuestsConfig cfg = new QuestsConfigParser(registry, JobRegistry.withDefaultMiner())
                .parseYamlString(yaml, "quests.yml").config();
        PlayerQuestProfile profile = new PlayerQuestProfile();
        AtomicInteger paid = new AtomicInteger();

        var res = QuestEngineV2.applyAction(profile, cfg, PlayerContext.DEFAULT_MINER,
                JobAction.of("GEODE_CRACK", "AMETHYST_CLUSTER", 3, 12, true, "MINER"), 1000L, new Random(1),
                q -> { paid.addAndGet(q.rewards().coins()); return true; });
        assertEquals(List.of("q_custom_geode"), res.completedNow());
        assertEquals(900, paid.get());
    }
}
