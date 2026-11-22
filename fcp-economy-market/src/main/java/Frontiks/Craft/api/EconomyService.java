package Frontiks.Craft.api;

import java.util.UUID;

/** Сборка-заглушка настоящего FCP-EconomyMarket. См. README.md в корне модуля. */
public interface EconomyService {
    int getCoins(UUID playerId);
    void addCoins(UUID playerId, int amount);
    void removeCoins(UUID playerId, int amount);

    int getDCoins(UUID playerId);
    void removeDCoins(UUID playerId, int amount);
    default void addDCoins(UUID playerId, int amount) { throw new UnsupportedOperationException("addDCoins not implemented in stub"); }
}
