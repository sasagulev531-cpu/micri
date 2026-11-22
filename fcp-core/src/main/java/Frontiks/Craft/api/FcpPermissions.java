package Frontiks.Craft.api;

import org.bukkit.command.CommandSender;

/** Сборка-заглушка настоящего FCP-Core (точная сигнатура метода adminOr). */
public final class FcpPermissions {
    private FcpPermissions() {}

    public static boolean adminOr(CommandSender sender, String node, String... extraNodes) {
        if (sender.isOp() || sender.hasPermission(node)) return true;
        if (extraNodes != null) {
            for (String extra : extraNodes) {
                if (extra != null && sender.hasPermission(extra)) return true;
            }
        }
        return false;
    }
}
