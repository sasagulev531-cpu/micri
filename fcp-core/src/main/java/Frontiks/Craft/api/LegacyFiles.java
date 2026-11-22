package Frontiks.Craft.api;

import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;

/** Сборка-заглушка настоящего FCP-Core. См. README.md в корне модуля. */
public final class LegacyFiles {
    private LegacyFiles() {}

    /** Возвращает файл в каталоге данных плагина (упрощённо, без миграций). */
    public static File migrate(JavaPlugin plugin, String name) {
        File dir = plugin.getDataFolder();
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IllegalStateException("Cannot create data folder: " + dir);
        }
        return new File(dir, name);
    }
}
