package Frontiks.Craft.api;

import org.bukkit.Location;
import org.bukkit.entity.Player;

/** Сборка-заглушка настоящего FCP-Core. См. README.md в корне модуля. */
public interface WorkService {
    boolean isWorking(Player player);
    boolean isInWorkRegion(Location location);
}
