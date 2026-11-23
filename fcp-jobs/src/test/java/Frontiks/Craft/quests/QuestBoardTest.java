package Frontiks.Craft.quests;

import org.junit.jupiter.api.Test;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class QuestBoardTest {

    private static Map<String, QuestDef> sampleDefs() {
        Map<String, QuestDef> map = new LinkedHashMap<>();
        map.put("q_cobble", new QuestDef("q_cobble", QuestDef.Type.LOOT_AMOUNT, "COBBLESTONE", 10,
                "Булыжник", "Соберите 10", 100));
        map.put("q_nodes", new QuestDef("q_nodes", QuestDef.Type.NODES_COMPLETED, null, 3,
                "Узлы", "Разработайте 3 узла", 250));
        return map;
    }

    @Test
    void assignPicksUniqueQuestsUpToPoolSize() {
        List<QuestDef> pool = new ArrayList<>(sampleDefs().values());
        List<QuestAssignment> assigned = QuestBoard.assign(pool, 5, new Random(1L));
        assertEquals(2, assigned.size());
        assertNotEquals(assigned.get(0).id, assigned.get(1).id);
    }

    @Test
    void progressCompletionAndSinglePayoutEvenOnRepeatedCalls() {
        Map<String, QuestDef> defs = sampleDefs();
        List<QuestAssignment> active = List.of(
                new QuestAssignment("q_cobble"),
                new QuestAssignment("q_nodes")
        );
        AtomicInteger totalCoinsPaid = new AtomicInteger();

        // Шаг 1: 4 булыжника + 1 узел
        var r1 = QuestBoard.applyProgress(active, defs, Map.of("COBBLESTONE", 4), 1,
                (id, coins) -> { totalCoinsPaid.addAndGet(coins); return true; });
        assertTrue(r1.completedNow().isEmpty());
        assertEquals(0, totalCoinsPaid.get());

        // Шаг 2: ещё 6 булыжника (итого 10/10 -> выполнение q_cobble) + 1 узел (2/3)
        var r2 = QuestBoard.applyProgress(active, defs, Map.of("COBBLESTONE", 6), 1,
                (id, coins) -> { totalCoinsPaid.addAndGet(coins); return true; });
        assertEquals(List.of("q_cobble"), r2.completedNow());
        assertEquals(List.of("q_cobble"), r2.paidNow());
        assertEquals(100, totalCoinsPaid.get());

        // Шаг 3: ещё 10 булыжника + 1 узел -> q_cobble НЕ оплачивается повторно, q_nodes завершается
        var r3 = QuestBoard.applyProgress(active, defs, Map.of("COBBLESTONE", 10), 1,
                (id, coins) -> { totalCoinsPaid.addAndGet(coins); return true; });
        assertEquals(List.of("q_nodes"), r3.completedNow());
        assertEquals(List.of("q_nodes"), r3.paidNow());
        assertEquals(350, totalCoinsPaid.get());
    }

    @Test
    void failedPayoutStaysUnpaidAndSucceedsOnRetry() {
        Map<String, QuestDef> defs = sampleDefs();
        List<QuestAssignment> active = List.of(new QuestAssignment("q_nodes"));
        AtomicBoolean economyAvailable = new AtomicBoolean(false);
        AtomicInteger paidCount = new AtomicInteger();

        var r1 = QuestBoard.applyProgress(active, defs, Map.of(), 3, (id, coins) -> {
            if (!economyAvailable.get()) return false;
            paidCount.incrementAndGet();
            return true;
        });
        assertEquals(List.of("q_nodes"), r1.completedNow());
        assertTrue(r1.paidNow().isEmpty());
        assertEquals(List.of("q_nodes"), r1.payFailed());
        assertTrue(active.get(0).completed);
        assertFalse(active.get(0).paid);

        // Экономика восстановилась -> /quests claim выплачивает ровно 1 раз
        economyAvailable.set(true);
        var r2 = QuestBoard.retryPendingPayouts(active, defs, (id, coins) -> {
            paidCount.incrementAndGet();
            return true;
        });
        assertEquals(List.of("q_nodes"), r2.paidNow());
        assertTrue(active.get(0).paid);
        assertEquals(1, paidCount.get());

        // Повторный claim ничего не начисляет
        var r3 = QuestBoard.retryPendingPayouts(active, defs, (id, coins) -> {
            paidCount.incrementAndGet();
            return true;
        });
        assertTrue(r3.paidNow().isEmpty());
        assertEquals(1, paidCount.get());
    }

    @Test
    void periodKeysRespectConfiguredTimezoneAndIsoWeek() {
        ZoneId msk = ZoneId.of("Europe/Moscow");
        long sunLate = ZonedDateTime.of(2026, 11, 22, 23, 59, 0, 0, msk).toInstant().toEpochMilli();
        long monEarly = ZonedDateTime.of(2026, 11, 23, 0, 1, 0, 0, msk).toInstant().toEpochMilli();

        assertNotEquals(
                QuestBoard.periodKey(QuestBoard.Period.DAILY, msk, sunLate),
                QuestBoard.periodKey(QuestBoard.Period.DAILY, msk, monEarly)
        );
        assertNotEquals(
                QuestBoard.periodKey(QuestBoard.Period.WEEKLY, msk, sunLate),
                QuestBoard.periodKey(QuestBoard.Period.WEEKLY, msk, monEarly)
        );
    }

    @Test
    void breakBlockObtainItemAndMaxAmount65536Work() {
        QuestDef qBreak = QuestDef.fromRaw(Map.of(
                "id", "q_break_stone",
                "type", "сломать",
                "material", "STONE",
                "amount", 65536,
                "reward", 5000
        ), "test");
        assertEquals(QuestDef.Type.BREAK_BLOCK, qBreak.type);
        assertEquals(65536, qBreak.amount);

        assertThrows(IllegalArgumentException.class, () -> QuestDef.fromRaw(Map.of(
                "id", "q_too_big",
                "type", "добыть",
                "material", "COAL",
                "amount", 65537,
                "reward", 100
        ), "test"));

        QuestDef qGet = QuestDef.fromRaw(Map.of(
                "id", "q_get_iron",
                "type", "получить",
                "material", "IRON_INGOT",
                "amount", 5,
                "reward", 200
        ), "test");
        assertEquals(QuestDef.Type.OBTAIN_ITEM, qGet.type);

        Map<String, QuestDef> defs = Map.of(qBreak.id, qBreak, qGet.id, qGet);
        List<QuestAssignment> active = List.of(new QuestAssignment(qBreak.id), new QuestAssignment(qGet.id));
        AtomicInteger paid = new AtomicInteger();

        var r1 = QuestBoard.applyProgress(
                active, defs,
                Map.of("STONE", 65536),
                Map.of(),
                Map.of("IRON_INGOT", 5),
                0,
                (id, coins) -> { paid.addAndGet(coins); return true; }
        );
        assertEquals(2, r1.completedNow().size());
        assertEquals(5200, paid.get());
    }
}
