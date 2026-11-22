package Frontiks.Craft.api;

import org.bukkit.entity.Player;

/** Сборка-заглушка настоящего FCP-Core. См. README.md в корне модуля. */
public final class FcpPermissions {
    private FcpPermissions() {}

    public static boolean adminOr(Player player, String node) {
        return player.isOp() || player.hasPermission(node);
    }
}
