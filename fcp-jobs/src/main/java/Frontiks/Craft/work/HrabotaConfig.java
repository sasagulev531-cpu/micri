package Frontiks.Craft.work;

import Frontiks.Craft.api.LegacyFiles;
import Frontiks.Craft.api.FcpYamlStorage;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;

public class HrabotaConfig {

    private static JavaPlugin plugin;
    private static File file;
    private static FileConfiguration config;

    public static void init(JavaPlugin owner) {
        plugin = owner;
        file = LegacyFiles.migrate(plugin, "hrabota.yml");

        if (!file.exists()) {
            plugin.saveResource("hrabota.yml", false);
        }

        config = FcpYamlStorage.load(plugin, file);
    }

    public static boolean contains(Location loc) {
        if (loc == null || loc.getWorld() == null || config == null
                || !config.contains("pos1.world") || !config.contains("pos2.world")) {
            return false;
        }
        String w1 = config.getString("pos1.world");
        String w2 = config.getString("pos2.world");
        if (w1 == null || w2 == null || !w1.equalsIgnoreCase(w2)) {
            return false;
        }
        if (!matchesWorld(loc.getWorld(), w1)) {
            return false;
        }

        int bx1 = (int) Math.floor(config.getDouble("pos1.x"));
        int by1 = (int) Math.floor(config.getDouble("pos1.y"));
        int bz1 = (int) Math.floor(config.getDouble("pos1.z"));

        int bx2 = (int) Math.floor(config.getDouble("pos2.x"));
        int by2 = (int) Math.floor(config.getDouble("pos2.y"));
        int bz2 = (int) Math.floor(config.getDouble("pos2.z"));

        int minX = Math.min(bx1, bx2);
        int maxX = Math.max(bx1, bx2);
        int minY = Math.min(by1, by2) - 1; // включая пол под ногами, на котором стоял админ при /setgranic
        int maxY = Math.max(by1, by2) + 2; // включая блоки на уровне головы/потолка
        int minZ = Math.min(bz1, bz2);
        int maxZ = Math.max(bz1, bz2);

        int x = loc.getBlockX();
        int y = loc.getBlockY();
        int z = loc.getBlockZ();

        return x >= minX && x <= maxX
                && y >= minY && y <= maxY
                && z >= minZ && z <= maxZ;
    }

    private static boolean matchesWorld(World world, String configured) {
        String target = stripNamespace(configured.trim());
        String actualName = stripNamespace(world.getName());
        if (actualName.equalsIgnoreCase(target)) {
            return true;
        }
        try {
            String keyVal = world.getKey().getKey();
            if (keyVal.equalsIgnoreCase(target)) {
                return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static String stripNamespace(String s) {
        int colon = s.indexOf(':');
        return colon >= 0 ? s.substring(colon + 1) : s;
    }

    public static String describeBounds() {
        if (config == null || !config.contains("pos1.world") || !config.contains("pos2.world")) {
            return "зона не задана в hrabota.yml";
        }
        int bx1 = (int) Math.floor(config.getDouble("pos1.x"));
        int by1 = (int) Math.floor(config.getDouble("pos1.y"));
        int bz1 = (int) Math.floor(config.getDouble("pos1.z"));
        int bx2 = (int) Math.floor(config.getDouble("pos2.x"));
        int by2 = (int) Math.floor(config.getDouble("pos2.y"));
        int bz2 = (int) Math.floor(config.getDouble("pos2.z"));
        return "зона: " + config.getString("pos1.world")
                + " X[" + Math.min(bx1, bx2) + ".." + Math.max(bx1, bx2) + "]"
                + " Y[" + (Math.min(by1, by2) - 1) + ".." + (Math.max(by1, by2) + 2) + "]"
                + " Z[" + Math.min(bz1, bz2) + ".." + Math.max(bz1, bz2) + "]";
    }

    public static FileConfiguration get() {
        if (config == null) {
            config = new YamlConfiguration();
        }
        return config;
    }

    public static void save() {
        try {
            if (config != null && file != null) {
                FcpYamlStorage.save(plugin, file, config);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static void reload() {
        if (file != null && file.exists()) {
            config = FcpYamlStorage.load(plugin, file);
        }
    }
}
