package Frontiks.Craft.work;

import Frontiks.Craft.api.LegacyFiles;
import Frontiks.Craft.api.FcpYamlStorage;
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

    public static boolean contains(org.bukkit.Location loc) {
        if (loc==null || loc.getWorld()==null || config==null || !config.contains("pos1.world") || !config.contains("pos2.world")) return false;
        String w1=config.getString("pos1.world"), w2=config.getString("pos2.world");
        if (w1==null || w2==null || !loc.getWorld().getName().equals(w1) || !w1.equals(w2)) return false;
        double x1=config.getDouble("pos1.x"),y1=config.getDouble("pos1.y"),z1=config.getDouble("pos1.z"),x2=config.getDouble("pos2.x"),y2=config.getDouble("pos2.y"),z2=config.getDouble("pos2.z");
        return loc.getX()>=Math.min(x1,x2)&&loc.getX()<=Math.max(x1,x2)&&loc.getY()>=Math.min(y1,y2)&&loc.getY()<=Math.max(y1,y2)&&loc.getZ()>=Math.min(z1,z2)&&loc.getZ()<=Math.max(z1,z2);
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