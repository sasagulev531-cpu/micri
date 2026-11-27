package Frontiks.Craft.quests.v2;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * Защита исполнения наградных команд по белому списку префиксов (R8.1, §3.6).
 * Чистый Java-класс без Bukkit — покрывается unit-тестами с mock-диспатчером.
 */
public final class CommandWhitelistGuard {

    private static final Pattern SAFE_PLAYER_NAME = Pattern.compile("^[A-Za-z0-9_\\.]{1,32}$");

    private CommandWhitelistGuard() {}

    public record ExecutionReport(List<String> executed, List<String> blocked) {}

    /**
     * Проверяет каждую команду по белому списку префиксов и исполняет только разрешённые.
     * Пустой белый список запрещает ВСЕ команды.
     */
    public static ExecutionReport executeAllowed(List<String> rawCommands,
                                                 List<String> whitelistPrefixes,
                                                 String playerName,
                                                 UUID playerUuid,
                                                 Consumer<String> commandDispatcher,
                                                 Consumer<String> warningLogger) {
        List<String> executed = new ArrayList<>();
        List<String> blocked = new ArrayList<>();
        if (rawCommands == null || rawCommands.isEmpty()) {
            return new ExecutionReport(executed, blocked);
        }

        List<String> normalizedPrefixes = normalizePrefixes(whitelistPrefixes);
        String safeName = (playerName != null && SAFE_PLAYER_NAME.matcher(playerName).matches()) ? playerName : null;
        String safeUuid = playerUuid != null ? playerUuid.toString() : null;

        for (String raw : rawCommands) {
            if (raw == null || raw.isBlank()) continue;
            String cmd = stripLeadingSlash(raw.trim());
            if (cmd.contains("\n") || cmd.contains("\r") || cmd.contains(";")) {
                blocked.add(cmd);
                if (warningLogger != null) {
                    warningLogger.accept("Заблокирована небезопасная reward-команда: '" + cmd + "'");
                }
                continue;
            }

            if (!matchesAnyPrefix(cmd, normalizedPrefixes)) {
                blocked.add(cmd);
                if (warningLogger != null) {
                    warningLogger.accept("Reward-команда отклонена (не входит в security.reward-commands-whitelist): '" + cmd + "'");
                }
                continue;
            }

            if (cmd.contains("%player%")) {
                if (safeName == null) {
                    blocked.add(cmd);
                    if (warningLogger != null) {
                        warningLogger.accept("Reward-команда отклонена из-за некорректного ника игрока: '" + playerName + "'");
                    }
                    continue;
                }
                cmd = cmd.replace("%player%", safeName);
            }
            if (cmd.contains("%uuid%")) {
                if (safeUuid == null) {
                    blocked.add(cmd);
                    continue;
                }
                cmd = cmd.replace("%uuid%", safeUuid);
            }

            if (commandDispatcher != null) {
                commandDispatcher.accept(cmd);
            }
            executed.add(cmd);
        }
        return new ExecutionReport(executed, blocked);
    }

    public static boolean matchesAnyPrefix(String normalizedCmd, List<String> whitelistPrefixes) {
        if (normalizedCmd == null || normalizedCmd.isBlank()) return false;
        List<String> prefixes = normalizePrefixes(whitelistPrefixes);
        if (prefixes.isEmpty()) return false;
        String lowerCmd = normalizedCmd.toLowerCase(Locale.ROOT);
        for (String p : prefixes) {
            if (lowerCmd.equals(p) || lowerCmd.startsWith(p + " ")) {
                return true;
            }
        }
        return false;
    }

    private static List<String> normalizePrefixes(List<String> raw) {
        if (raw == null || raw.isEmpty()) return List.of();
        List<String> out = new ArrayList<>();
        for (String s : raw) {
            if (s == null) continue;
            String clean = stripLeadingSlash(s.trim()).toLowerCase(Locale.ROOT);
            if (!clean.isEmpty()) out.add(clean);
        }
        return out;
    }

    private static String stripLeadingSlash(String s) {
        while (s.startsWith("/")) {
            s = s.substring(1).trim();
        }
        return s;
    }
}
