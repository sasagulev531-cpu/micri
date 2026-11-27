package Frontiks.Craft.api;

import org.bukkit.Bukkit;

/** Сборка-заглушка настоящего FCP-Core. См. README.md в корне модуля. */
public final class FcpServices {
    private FcpServices() {}

    public static <T> T require(Class<T> service) {
        T loaded = Bukkit.getServicesManager().load(service);
        if (loaded == null) {
            throw new IllegalStateException("Required FCP service is not registered: " + service.getName());
        }
        return loaded;
    }
}
