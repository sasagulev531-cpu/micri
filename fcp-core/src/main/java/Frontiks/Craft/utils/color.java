package Frontiks.Craft.utils;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Сборка-заглушка настоящего FCP-Core. См. README.md в корне модуля. */
public final class color {
    private static final Pattern HEX = Pattern.compile("&#([0-9a-fA-F]{6})");
    private color() {}

    /** Переводит '&' и '&#RRGGBB' в Minecraft legacy-коды (§). */
    public static String color(String input) {
        if (input == null) return "";
        Matcher m = HEX.matcher(input);
        StringBuilder sb = new StringBuilder(input.length() + 32);
        while (m.find()) {
            StringBuilder hex = new StringBuilder("§x");
            for (char c : m.group(1).toCharArray()) {
                hex.append('§').append(Character.toLowerCase(c));
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(hex.toString()));
        }
        m.appendTail(sb);
        return sb.toString().replace('&', '§');
    }
}
