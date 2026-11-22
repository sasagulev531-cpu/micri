package Frontiks.Craft.api;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;

/** Сборка-заглушка настоящего FCP-Core. См. README.md в корне модуля. */
public final class FcpYamlStorage {
    private FcpYamlStorage() {}

    public static FileConfiguration load(JavaPlugin plugin, File file) {
        if (file == null) throw new IllegalArgumentException("file == null");
        if (file.getParentFile() != null && !file.getParentFile().exists() && !file.getParentFile().mkdirs()) {
            throw new UncheckedIOException(new IOException("Cannot create parent of " + file));
        }
        if (!file.exists()) {
            try {
                if (!file.createNewFile()) throw new IOException("createNewFile() == false");
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return YamlConfiguration.loadConfiguration(file);
    }

    public static void save(JavaPlugin plugin, File file, FileConfiguration config) {
        if (file == null || config == null) {
            throw new IllegalArgumentException("file or config == null");
        }
        if (file.getParentFile() != null && !file.getParentFile().exists() && !file.getParentFile().mkdirs()) {
            throw new UncheckedIOException(new IOException("Cannot create parent of " + file));
        }
        try {
            config.save(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
