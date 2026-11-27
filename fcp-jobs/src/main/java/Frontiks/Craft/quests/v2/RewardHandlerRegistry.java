package Frontiks.Craft.quests.v2;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Расширяемый реестр обработчиков наград (§5.6).
 * Позволяет в будущих релизах подключать новые виды наград (например BP-очки)
 * без переписывания ядра выплат.
 */
public final class RewardHandlerRegistry {

    @FunctionalInterface
    public interface ExtraRewardHandler {
        boolean grant(UUID playerId, String questId, Object configValue);
    }

    private final Map<String, ExtraRewardHandler> handlers = new LinkedHashMap<>();

    public synchronized void register(String rewardKey, ExtraRewardHandler handler) {
        if (rewardKey == null || rewardKey.isBlank() || handler == null) return;
        handlers.put(rewardKey.trim().toLowerCase(), handler);
    }

    public synchronized ExtraRewardHandler get(String rewardKey) {
        if (rewardKey == null) return null;
        return handlers.get(rewardKey.trim().toLowerCase());
    }

    public synchronized Map<String, ExtraRewardHandler> snapshot() {
        return new LinkedHashMap<>(handlers);
    }
}
