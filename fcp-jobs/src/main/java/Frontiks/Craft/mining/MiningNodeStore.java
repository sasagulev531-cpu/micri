package Frontiks.Craft.mining;

import Frontiks.Craft.api.FcpYamlStorage;
import Frontiks.Craft.api.LegacyFiles;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * YAML-хранилище узлов (mining-nodes.yml) через существующий FCP storage.
 * Хранит ТОЛЬКО состояние узлов. Все операции синхронные, под замком.
 */
public final class MiningNodeStore {

    private final JavaPlugin plugin;
    private final ReentrantLock lock = new ReentrantLock();
    private File file;
    private FileConfiguration config;

    public MiningNodeStore(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void init() {
        lock.lock();
        try {
            file = LegacyFiles.migrate(plugin, "mining-nodes.yml");
            if (!file.exists()) {
                config = new YamlConfiguration();
                config.set("version", 1);
                FcpYamlStorage.save(plugin, file, config);
            } else {
                config = FcpYamlStorage.load(plugin, file);
                if (!config.contains("version")) config.set("version", 1);
            }
        } catch (Exception e) {
            plugin.getLogger().severe("Не удалось инициализировать mining-nodes.yml: " + e);
            if (config == null) config = new YamlConfiguration();
        } finally {
            lock.unlock();
        }
    }

    /** Загружает все узлы. Невалидные записи логируются и пропускаются (не роняют сервер). */
    public List<MiningNode> loadAll(List<String> errorsOut) {
        lock.lock();
        try {
            List<MiningNode> out = new ArrayList<>();
            if (config == null || !config.isConfigurationSection("nodes")) return out;
            for (String id : config.getConfigurationSection("nodes").getKeys(false)) {
                try {
                    Map<String, Object> raw = readNodeMap("nodes." + id);
                    MiningNode node = MiningNode.fromMap(id, raw);
                    out.add(node);
                } catch (Exception e) {
                    String msg = "mining-nodes.yml: узел '" + id + "' пропущен: " + e;
                    plugin.getLogger().severe(msg);
                    if (errorsOut != null) errorsOut.add(msg);
                }
            }
            return out;
        } finally {
            lock.unlock();
        }
    }

    /** Читает секцию узла как вложенную map (включая loot/chain). */
    @SuppressWarnings("unchecked")
    private Map<String, Object> readNodeMap(String path) {
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        var sec = config.getConfigurationSection(path);
        if (sec == null) throw new IllegalStateException("секция не найдена: " + path);
        for (String key : sec.getKeys(false)) {
            Object value = sec.get(key);
            if (value instanceof org.bukkit.configuration.ConfigurationSection sub) {
                out.put(key, sub.getValues(true));
            } else {
                out.put(key, value);
            }
        }
        return out;
    }

    /** Сохраняет узел (создаёт/обновляет). При ошибке логирует и возвращает false. */
    public boolean save(MiningNode node) {
        lock.lock();
        try {
            if (config == null || file == null) {
                plugin.getLogger().severe("MiningNodeStore не инициализирован — узел " + node.id + " не сохранён");
                return false;
            }
            Map<String, Object> m = node.toMap();
            for (Map.Entry<String, Object> e : m.entrySet()) {
                config.set("nodes." + node.id + "." + e.getKey(), e.getValue());
            }
            FcpYamlStorage.save(plugin, file, config);
            return true;
        } catch (Exception e) {
            plugin.getLogger().severe("Не удалось сохранить узел " + node.id + ": " + e);
            return false;
        } finally {
            lock.unlock();
        }
    }

    public boolean delete(String nodeId) {
        lock.lock();
        try {
            if (config == null || file == null) return false;
            config.set("nodes." + nodeId, null);
            FcpYamlStorage.save(plugin, file, config);
            return true;
        } catch (Exception e) {
            plugin.getLogger().severe("Не удалось удалить узел " + nodeId + ": " + e);
            return false;
        } finally {
            lock.unlock();
        }
    }

    /** Полностью перезаписывает файл по текущему состоянию памяти (для /miningnode reload отладки). */
    public void reloadFromDisk() {
        lock.lock();
        try {
            if (file == null) return;
            if (file.exists()) config = FcpYamlStorage.load(plugin, file);
        } catch (Exception e) {
            plugin.getLogger().severe("Не удалось перечитать mining-nodes.yml: " + e);
        } finally {
            lock.unlock();
        }
    }
}
