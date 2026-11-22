package Frontiks.Craft.mining;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Чистая структурная и материальная валидация конфигурации узлов/цепочек.
 * Проверка «материал существует и это блок» внедряется как Predicate,
 * чтобы класс не зависел от Bukkit.
 */
public final class NodeValidator {

    public record ValidationResult(List<String> errors) {
        public boolean ok() { return errors.isEmpty(); }
        public String joined() { return String.join("; ", errors); }
    }

    private NodeValidator() {}

    /** Валидация цепочки стадий и истощённого материала. */
    public static ValidationResult validateChain(List<String> chain, String depletedMaterial,
                                                 Predicate<String> validBlockMaterial) {
        List<String> errors = new ArrayList<>();
        if (chain == null || chain.isEmpty()) {
            errors.add("цепочка стадий пуста");
        } else {
            if (chain.size() > 16) errors.add("цепочка слишком длинная (>16 стадий)");
            for (int i = 0; i < chain.size(); i++) {
                String s = chain.get(i);
                if (s == null || s.isBlank()) {
                    errors.add("стадия #" + (i + 1) + ": пустой материал");
                    continue;
                }
                if (validBlockMaterial != null && !validBlockMaterial.test(s)) {
                    errors.add("стадия #" + (i + 1) + ": '" + s + "' не является допустимым блоком");
                }
            }
        }
        if (depletedMaterial == null || depletedMaterial.isBlank()) {
            errors.add("истощённый материал не задан");
        } else if (validBlockMaterial != null && !validBlockMaterial.test(depletedMaterial)) {
            errors.add("истощённый материал '" + depletedMaterial + "' не является допустимым блоком");
        }
        return new ValidationResult(errors);
    }

    /** Валидация лута. Пустой лут — ошибка (по ТЗ: «пустой лут» недопустим). */
    public static ValidationResult validateLoot(List<LootEntry> loot,
                                                Predicate<String> validItemMaterial) {
        List<String> errors = new ArrayList<>();
        if (loot == null || loot.isEmpty()) {
            errors.add("лут пустой");
            return new ValidationResult(errors);
        }
        for (int i = 0; i < loot.size(); i++) {
            LootEntry e = loot.get(i);
            String where = "лут #" + (i + 1);
            if (e == null) { errors.add(where + ": пустая запись"); continue; }
            if (e.material == null || e.material.isBlank()) {
                errors.add(where + ": материал не задан");
            } else if (validItemMaterial != null && !validItemMaterial.test(e.material)) {
                errors.add(where + ": '" + e.material + "' не является допустимым предметом/блоком");
            }
            if (e.min < 1) errors.add(where + ": min должен быть >= 1 (сейчас " + e.min + ")");
            if (e.max < e.min) errors.add(where + ": max (" + e.max + ") < min (" + e.min + ")");
            if (!(e.chance > 0.0 && e.chance <= 1.0)) {
                errors.add(where + ": chance должен быть в (0; 1] (сейчас " + e.chance + ")");
            }
        }
        return new ValidationResult(errors);
    }

    /** Валидация целого узла. */
    public static ValidationResult validateNode(MiningNode n,
                                                Predicate<String> validBlockMaterial,
                                                Predicate<String> validItemMaterial) {
        List<String> errors = new ArrayList<>();
        if (n == null) return new ValidationResult(List.of("узел == null"));
        if (n.id == null || n.id.isBlank()) errors.add("id узла пуст");
        if (n.worldUuid == null || n.worldUuid.isBlank()) errors.add("world-uuid не задан");
        if (n.worldName == null || n.worldName.isBlank()) errors.add("имя мира не задано");
        if (Math.abs((long) n.x) > 30_000_000 || Math.abs((long) n.z) > 30_000_000) {
            errors.add("координаты вне допустимого диапазона");
        }
        if (n.y < -2048 || n.y > 4096) errors.add("Y вне допустимого диапазона");
        if (n.cooldownMillis < 0) errors.add("cooldown не может быть отрицательным");
        if (n.stageIndex < 0 || (!n.chain.isEmpty() && n.stageIndex > n.chain.size() - 1)) {
            errors.add("stage-index вне диапазона цепочки");
        }
        errors.addAll(validateChain(n.chain, n.depletedMaterial, validBlockMaterial).errors());
        errors.addAll(validateLoot(n.loot, validItemMaterial).errors());
        return new ValidationResult(errors);
    }
}
