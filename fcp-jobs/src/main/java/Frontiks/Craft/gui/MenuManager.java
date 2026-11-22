package Frontiks.Craft.gui;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class MenuManager {

    private static final Map<UUID, String> openMenus = new HashMap<>();

    public static void setMenu(UUID playerId, String menuName) {
        openMenus.put(playerId, menuName);
    }

    public static String getMenu(UUID playerId) {
        return openMenus.getOrDefault(playerId, "");
    }

    public static void removeMenu(UUID playerId) {
        openMenus.remove(playerId);
    }

    public static boolean isInMenu(UUID playerId, String menuName) {
        return menuName.equals(openMenus.get(playerId));
    }
}