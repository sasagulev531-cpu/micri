package Frontiks.Craft.mining;

/**
 * Чистая state-машина добываемого узла. Никаких вызовов Bukkit —
 * можно тестировать без сервера.
 */
public final class NodeLogic {

    public enum BreakResult {
        /** Узел истощён (идёт кулдаун) — добычи нет. */
        DEPLETED,
        /** Стадия повысилась, награды пока нет. */
        ADVANCED,
        /** Ломаная последняя стадия: узел истощён, награда должна быть выдана. */
        COMPLETED
    }

    private NodeLogic() {}

    public static String expectedMaterial(MiningNode n) {
        if (n.depleted) return n.depletedMaterial;
        int i = clampIndex(n);
        return n.chain.get(i);
    }

    public static int clampIndex(MiningNode n) {
        if (n.chain.isEmpty()) return 0;
        return Math.max(0, Math.min(n.stageIndex, n.chain.size() - 1));
    }

    /**
     * Применяет «ломку» узла. Мутирует узел. Вызывается на main thread под synchronized(node).
     * Порядок: сначала меняется состояние узла и блок, потом выдаётся награда (см. MiningNodeManager).
     */
    public static BreakResult applyBreak(MiningNode n, long nowMs) {
        if (n.broken) return BreakResult.DEPLETED;
        if (n.depleted) return BreakResult.DEPLETED;
        int i = clampIndex(n);
        if (i < n.chain.size() - 1) {
            n.stageIndex = i + 1;
            return BreakResult.ADVANCED;
        }
        // Последняя ломаемая стадия: узел становится истощённым, выдаётся лут.
        n.depleted = true;
        n.depletedUntilMs = nowMs + Math.max(0L, n.cooldownMillis);
        return BreakResult.COMPLETED;
    }

    public static boolean isReadyToRestore(MiningNode n, long nowMs) {
        return n.depleted && !n.broken && nowMs >= n.depletedUntilMs;
    }

    public static long depletedRemainingMs(MiningNode n, long nowMs) {
        if (!n.depleted) return 0L;
        return Math.max(0L, n.depletedUntilMs - nowMs);
    }

    /** Восстанавливает узел к исходной стадии, если кулдаун истёк. Мутирует узел. */
    public static boolean applyRestore(MiningNode n, long nowMs) {
        if (!isReadyToRestore(n, nowMs)) return false;
        n.depleted = false;
        n.stageIndex = 0;
        n.depletedUntilMs = 0L;
        return true;
    }
}
