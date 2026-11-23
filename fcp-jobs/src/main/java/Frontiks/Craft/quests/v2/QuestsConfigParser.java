package Frontiks.Craft.quests.v2;

import Frontiks.Craft.actions.ActionKind;
import Frontiks.Craft.actions.ActionKindRegistry;
import Frontiks.Craft.jobs.JobKind;
import Frontiks.Craft.jobs.JobRegistry;
import Frontiks.Craft.quests.v2.QuestV2Models.*;
import org.yaml.snakeyaml.Yaml;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Парсер и валидатор quests.yml v2 с автоматической обратной совместимостью для v1 (§5, R3).
 * Чистый Java + SnakeYAML — работает и тестируется без запущенного сервера Bukkit.
 */
public final class QuestsConfigParser {

    private final ActionKindRegistry actionRegistry;
    private final JobRegistry jobRegistry;

    public QuestsConfigParser(ActionKindRegistry actionRegistry, JobRegistry jobRegistry) {
        this.actionRegistry = actionRegistry != null ? actionRegistry : ActionKindRegistry.withBuiltins();
        this.jobRegistry = jobRegistry;
    }

    public ParseResult parseYamlString(String yamlText, String fileName) {
        String source = (fileName == null || fileName.isBlank()) ? "quests.yml" : fileName;
        if (yamlText == null || yamlText.isBlank()) {
            return new ParseResult(QuestsConfig.empty(), List.of());
        }
        Object loaded;
        try {
            loaded = new Yaml().load(yamlText);
        } catch (Exception ex) {
            return new ParseResult(QuestsConfig.empty(),
                    List.of(source + ": синтаксическая ошибка YAML: " + ex.getMessage()));
        }
        if (loaded == null) {
            return new ParseResult(QuestsConfig.empty(), List.of());
        }
        if (!(loaded instanceof Map<?, ?> root)) {
            return new ParseResult(QuestsConfig.empty(),
                    List.of(source + ": корень документа должен быть YAML-объектом"));
        }
        return parseRootMap(root, source);
    }

    public ParseResult parseRootMap(Map<?, ?> root, String source) {
        if (root == null || root.isEmpty()) {
            return new ParseResult(QuestsConfig.empty(), List.of());
        }
        boolean isV2 = num(root.get("version"), 0) >= 2 || root.containsKey("quests") || root.containsKey("periods");
        if (!isV2 && (root.containsKey("daily") || root.containsKey("weekly"))) {
            return parseLegacyV1(root, source);
        }
        return parseV2(root, source);
    }

    // ------------------------------------------------------------------ v2 parser

    private ParseResult parseV2(Map<?, ?> root, String source) {
        List<String> errors = new ArrayList<>();
        boolean enabled = bool(root.get("enabled"), true);

        Map<?, ?> defaultsMap = asMap(root.get("defaults"));
        String tzRaw = str(defaultsMap.get("timezone"));
        if (tzRaw.isEmpty()) tzRaw = str(root.get("timezone"));
        if (tzRaw.isEmpty()) tzRaw = "Europe/Moscow";
        ZoneId timezone = parseZone(tzRaw, source + " → defaults.timezone", errors);

        String defaultAcceptRaw = str(defaultsMap.get("accept")).toLowerCase(Locale.ROOT);
        boolean defaultManualAccept;
        if (defaultAcceptRaw.isEmpty() || "auto".equals(defaultAcceptRaw)) {
            defaultManualAccept = false;
        } else if ("manual".equals(defaultAcceptRaw)) {
            defaultManualAccept = true;
        } else {
            errors.add(source + " → defaults.accept: неизвестное значение '" + defaultAcceptRaw + "' (ожидалось auto или manual)");
            defaultManualAccept = false;
        }

        boolean defaultRequireWorking = bool(defaultsMap.get("require-working"), true);

        // security
        Map<?, ?> securityMap = asMap(root.get("security"));
        List<String> whitelist = stringList(securityMap.get("reward-commands-whitelist"));

        // periods
        Map<String, PeriodTemplate> periods = new LinkedHashMap<>();
        periods.put("daily", new PeriodTemplate("daily", PeriodKind.DAILY, 1, 3));
        periods.put("weekly", new PeriodTemplate("weekly", PeriodKind.WEEKLY, 7, 3));
        periods.put("once", new PeriodTemplate("once", PeriodKind.ONCE, 0, Integer.MAX_VALUE));

        Object rawPeriods = root.get("periods");
        if (rawPeriods instanceof Map<?, ?> pMap) {
            for (Map.Entry<?, ?> entry : pMap.entrySet()) {
                String pName = str(entry.getKey());
                if (pName.isEmpty()) continue;
                String pPath = source + " → periods." + pName;
                if (!(entry.getValue() instanceof Map<?, ?> def)) {
                    errors.add(pPath + ": ожидался объект шаблона периода");
                    continue;
                }
                String tpl = str(def.get("template")).toLowerCase(Locale.ROOT);
                if (tpl.isEmpty()) tpl = pName.toLowerCase(Locale.ROOT);
                PeriodKind kind = switch (tpl) {
                    case "daily" -> PeriodKind.DAILY;
                    case "weekly" -> PeriodKind.WEEKLY;
                    case "interval" -> PeriodKind.INTERVAL;
                    case "once" -> PeriodKind.ONCE;
                    default -> null;
                };
                if (kind == null) {
                    errors.add(pPath + ": неизвестный template='" + tpl + "' (допустимо: daily, weekly, interval, once)");
                    continue;
                }
                int days = kind == PeriodKind.INTERVAL ? num(def.get("days"), -1) : (kind == PeriodKind.WEEKLY ? 7 : 1);
                if (kind == PeriodKind.INTERVAL && (days < 1 || days > 3650)) {
                    errors.add(pPath + ": для interval параметр days должен быть от 1 до 3650");
                    continue;
                }
                int count = kind == PeriodKind.ONCE ? Integer.MAX_VALUE : Math.max(1, num(def.get("count"), 3));
                periods.put(pName, new PeriodTemplate(pName, kind, days, count));
            }
        }

        // quests
        List<QuestSpec> quests = new ArrayList<>();
        Map<String, QuestSpec> questsById = new LinkedHashMap<>();
        Object rawQuests = root.get("quests");
        if (rawQuests != null && !(rawQuests instanceof List<?>)) {
            errors.add(source + " → quests: ожидался список заданий");
        } else if (rawQuests instanceof List<?> qList) {
            for (int i = 0; i < qList.size(); i++) {
                String basePath = source + " → quests[" + i + "]";
                if (!(qList.get(i) instanceof Map<?, ?> qMap)) {
                    errors.add(basePath + ": ожидался YAML-объект задания");
                    continue;
                }
                QuestSpec spec = parseQuestV2(qMap, basePath, periods, defaultManualAccept, defaultRequireWorking, errors);
                if (spec == null) continue;
                if (questsById.containsKey(spec.id())) {
                    errors.add(basePath + "(id='" + spec.id() + "'): дублирующийся id задания");
                    continue;
                }
                quests.add(spec);
                questsById.put(spec.id(), spec);
            }
        }

        // Перекрёстная проверка ссылок requires.quest и on-complete.unlock
        for (QuestSpec q : quests) {
            String qPath = source + " → quest(id='" + q.id() + "')";
            String reqQuest = q.requires().questId();
            if (reqQuest != null && !reqQuest.isBlank() && !questsById.containsKey(reqQuest)) {
                errors.add(qPath + ".requires.quest: ссылка на несуществующее задание '" + reqQuest + "'");
            }
            for (String unlockId : q.unlockQuestIds()) {
                if (!questsById.containsKey(unlockId)) {
                    errors.add(qPath + ".on-complete.unlock: ссылка на несуществующее задание '" + unlockId + "'");
                }
            }
        }

        QuestsConfig cfg = new QuestsConfig(
                enabled, timezone, defaultManualAccept, defaultRequireWorking,
                whitelist, periods, quests, questsById
        );
        return new ParseResult(cfg, errors);
    }

    private QuestSpec parseQuestV2(Map<?, ?> qMap,
                                   String basePath,
                                   Map<String, PeriodTemplate> periods,
                                   boolean defaultManualAccept,
                                   boolean defaultRequireWorking,
                                   List<String> errors) {
        String id = str(qMap.get("id"));
        if (id.isEmpty()) {
            errors.add(basePath + ": отсутствует обязательное поле id");
            return null;
        }
        String qPath = basePath + "(id='" + id + "')";

        String title = str(qMap.get("title"));
        if (title.isEmpty()) title = id;
        String icon = emptyToNull(str(qMap.get("icon")).toUpperCase(Locale.ROOT));
        List<String> desc = stringOrList(qMap.get("description"));

        String category = str(qMap.get("category"));
        if (category.isEmpty()) category = "daily";
        if (!periods.containsKey(category)) {
            errors.add(qPath + ".category: неизвестный период '" + category + "'");
            return null;
        }

        boolean manualAccept;
        if (qMap.containsKey("accept")) {
            String acc = str(qMap.get("accept")).toLowerCase(Locale.ROOT);
            if ("manual".equals(acc)) {
                manualAccept = true;
            } else if ("auto".equals(acc)) {
                manualAccept = false;
            } else {
                errors.add(qPath + ".accept: неизвестное значение '" + acc + "' (ожидалось auto или manual)");
                return null;
            }
        } else if (qMap.containsKey("require-accept")) {
            manualAccept = bool(qMap.get("require-accept"), defaultManualAccept);
        } else {
            manualAccept = defaultManualAccept;
        }

        // requires
        RequiresSpec requires = parseRequires(asMap(qMap.get("requires")), qPath + ".requires", errors);
        if (requires == null) return null;

        // steps
        List<StepSpec> steps = new ArrayList<>();
        Object rawSteps = qMap.get("steps");
        if (rawSteps instanceof List<?> sList && !sList.isEmpty()) {
            for (int si = 0; si < sList.size(); si++) {
                String sPath = qPath + ".steps[" + si + "]";
                if (!(sList.get(si) instanceof Map<?, ?> sMap)) {
                    errors.add(sPath + ": ожидался объект шага");
                    return null;
                }
                StepSpec step = parseStepV2(sMap, sPath, defaultRequireWorking, errors);
                if (step == null) return null;
                steps.add(step);
            }
        } else if (qMap.containsKey("event") || qMap.containsKey("type")) {
            // Одношаговое сокращение на верхнем уровне
            ConditionSpec single = parseCondition(qMap, qPath, true, defaultRequireWorking, errors);
            if (single == null) return null;
            steps.add(new StepSpec(single.describe(), GroupMode.ALL, List.of(single), 0, null));
        } else {
            errors.add(qPath + ".steps: задание должно содержать непустой список steps");
            return null;
        }

        // rewards
        RewardsSpec rewards = parseRewards(qMap, qPath, errors);
        if (rewards == null) return null;

        // on-complete.unlock
        Map<?, ?> onComplete = asMap(qMap.get("on-complete"));
        List<String> unlockIds = stringList(onComplete.get("unlock"));

        return new QuestSpec(id, title, icon, desc, category, manualAccept, requires, steps, rewards, unlockIds);
    }

    private RequiresSpec parseRequires(Map<?, ?> reqMap, String path, List<String> errors) {
        if (reqMap.isEmpty()) return RequiresSpec.NONE;
        String questId = emptyToNull(str(reqMap.get("quest")));
        String jobId = emptyToNull(str(reqMap.get("job")).toUpperCase(Locale.ROOT));
        String perm = emptyToNull(str(reqMap.get("permission")));
        if (jobId != null && jobRegistry != null && !jobRegistry.contains(jobId)) {
            errors.add(path + ".job: неизвестный вид работы '" + jobId + "'");
            return null;
        }
        return new RequiresSpec(questId, jobId, perm);
    }

    private StepSpec parseStepV2(Map<?, ?> sMap,
                                 String sPath,
                                 boolean defaultRequireWorking,
                                 List<String> errors) {
        String name = str(sMap.get("name"));
        if (name.isEmpty()) name = str(sMap.get("title"));

        boolean hasAll = sMap.containsKey("all") && sMap.get("all") != null;
        boolean hasAny = sMap.containsKey("any") && sMap.get("any") != null;
        boolean hasTime = sMap.containsKey("time") && sMap.get("time") != null;

        if (hasAll && hasAny) {
            errors.add(sPath + ": нельзя указывать одновременно группы 'all' и 'any'");
            return null;
        }

        TimeSpec timeSpec = null;
        if (hasTime) {
            if (!(sMap.get("time") instanceof Map<?, ?> tMap)) {
                errors.add(sPath + ".time: ожидался объект { as-job, minutes }");
                return null;
            }
            String asJob = emptyToNull(str(tMap.get("as-job")).toUpperCase(Locale.ROOT));
            if (asJob != null && jobRegistry != null && !jobRegistry.contains(asJob)) {
                errors.add(sPath + ".time.as-job: неизвестный вид работы '" + asJob + "'");
                return null;
            }
            int minutes = num(tMap.get("minutes"), -1);
            if (minutes < 1 || minutes > MAX_AMOUNT) {
                errors.add(sPath + ".time.minutes: должно быть от 1 до " + MAX_AMOUNT);
                return null;
            }
            timeSpec = new TimeSpec(asJob, minutes);
        }

        if (hasAll) {
            if (!(sMap.get("all") instanceof List<?> allList) || allList.isEmpty()) {
                errors.add(sPath + ".all: ожидался непустой список условий");
                return null;
            }
            List<ConditionSpec> conds = new ArrayList<>();
            for (int ci = 0; ci < allList.size(); ci++) {
                String cPath = sPath + ".all[" + ci + "]";
                if (!(allList.get(ci) instanceof Map<?, ?> cMap)) {
                    errors.add(cPath + ": ожидался объект условия");
                    return null;
                }
                ConditionSpec cond = parseCondition(cMap, cPath, true, defaultRequireWorking, errors);
                if (cond == null) return null;
                conds.add(cond);
            }
            if (name.isEmpty()) name = conds.get(0).describe();
            return new StepSpec(name, GroupMode.ALL, conds, 0, timeSpec);
        }

        if (hasAny) {
            if (!(sMap.get("any") instanceof List<?> anyList) || anyList.isEmpty()) {
                errors.add(sPath + ".any: ожидался непустой список условий");
                return null;
            }
            int anyAmount = num(sMap.get("any-amount"), num(sMap.get("amount"), -1));
            if (anyAmount < 1 || anyAmount > MAX_AMOUNT) {
                errors.add(sPath + ".any-amount: должно быть указано целое число от 1 до " + MAX_AMOUNT);
                return null;
            }
            List<ConditionSpec> conds = new ArrayList<>();
            for (int ci = 0; ci < anyList.size(); ci++) {
                String cPath = sPath + ".any[" + ci + "]";
                if (!(anyList.get(ci) instanceof Map<?, ?> cMap)) {
                    errors.add(cPath + ": ожидался объект условия");
                    return null;
                }
                ConditionSpec cond = parseCondition(cMap, cPath, false, defaultRequireWorking, errors);
                if (cond == null) return null;
                conds.add(cond);
            }
            if (name.isEmpty()) name = "Любое из условий (" + anyAmount + ")";
            return new StepSpec(name, GroupMode.ANY, conds, anyAmount, timeSpec);
        }

        // Поддержка v1-шага внутри steps: { type/event: MINE, material: ..., amount: ... }
        if (sMap.containsKey("event") || sMap.containsKey("type")) {
            ConditionSpec cond = parseCondition(sMap, sPath, true, defaultRequireWorking, errors);
            if (cond == null) return null;
            if (name.isEmpty()) name = cond.describe();
            return new StepSpec(name, GroupMode.ALL, List.of(cond), 0, timeSpec);
        }

        if (timeSpec != null) {
            if (name.isEmpty()) name = timeSpec.describe();
            return new StepSpec(name, GroupMode.NONE, List.of(), 0, timeSpec);
        }

        errors.add(sPath + ": шаг должен содержать 'all', 'any' или 'time'");
        return null;
    }

    private ConditionSpec parseCondition(Map<?, ?> cMap,
                                         String cPath,
                                         boolean requireAmount,
                                         boolean defaultRequireWorking,
                                         List<String> errors) {
        String eventRaw = str(cMap.get("event"));
        if (eventRaw.isEmpty()) eventRaw = str(cMap.get("type"));
        if (eventRaw.isEmpty()) {
            errors.add(cPath + ": отсутствует поле event");
            return null;
        }
        ActionKind kind = actionRegistry.resolve(eventRaw);
        if (kind == null || ActionKindRegistry.TIME_AS_JOB.equals(kind.id)) {
            errors.add(cPath + ": неизвестный event='" + eventRaw + "'");
            return null;
        }

        String matRaw = str(cMap.get("material")).toUpperCase(Locale.ROOT);
        if (kind.requiresMaterial && matRaw.isEmpty()) {
            errors.add(cPath + ": для event='" + kind.id + "' обязательно поле material");
            return null;
        }
        String material = kind.requiresMaterial ? matRaw : emptyToNull(matRaw);

        int amount = num(cMap.get("amount"), requireAmount ? -1 : 1);
        if (amount < 1 || amount > MAX_AMOUNT) {
            errors.add(cPath + ": amount должен быть от 1 до " + MAX_AMOUNT);
            return null;
        }

        Integer minDepth = optInt(cMap.get("min-depth"));
        Integer maxDepth = optInt(cMap.get("max-depth"));
        if (minDepth != null && maxDepth != null && minDepth > maxDepth) {
            errors.add(cPath + ": min-depth (" + minDepth + ") не может быть больше max-depth (" + maxDepth + ")");
            return null;
        }

        String jobId = emptyToNull(str(cMap.get("job")).toUpperCase(Locale.ROOT));
        if (jobId != null && jobRegistry != null && !jobRegistry.contains(jobId)) {
            errors.add(cPath + ".job: неизвестный вид работы '" + jobId + "'");
            return null;
        }

        boolean reqWork = cMap.containsKey("require-working")
                ? bool(cMap.get("require-working"), kind.defaultRequireWorking && defaultRequireWorking)
                : (kind.defaultRequireWorking && defaultRequireWorking);

        String label = emptyToNull(str(cMap.get("title")));
        if (label == null) label = emptyToNull(str(cMap.get("label")));

        return new ConditionSpec(kind, material, amount, minDepth, maxDepth, jobId, reqWork, label);
    }

    private RewardsSpec parseRewards(Map<?, ?> qMap, String qPath, List<String> errors) {
        Object rawRewards = qMap.get("rewards");
        if (rawRewards instanceof Map<?, ?> rMap) {
            int coins = num(rMap.get("coins"), 0);
            if (coins < 0) {
                errors.add(qPath + ".rewards.coins: не может быть отрицательным");
                return null;
            }
            List<RewardItemSpec> items = parseRewardItems(rMap.get("items"), qPath + ".rewards.items", errors);
            if (items == null) return null;
            List<String> commands = stringList(rMap.get("commands"));
            Map<String, Object> extra = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : rMap.entrySet()) {
                String k = str(e.getKey());
                if (!Set.of("coins", "items", "commands").contains(k)) {
                    extra.put(k, e.getValue());
                }
            }
            return new RewardsSpec(coins, items, commands, extra);
        }

        // Совместимость с полями верхнего уровня (v1): reward, reward-items, reward-commands
        int coins = num(qMap.get("reward"), num(qMap.get("reward-coins"), 0));
        if (coins < 0) {
            errors.add(qPath + ".reward: не может быть отрицательным");
            return null;
        }
        List<RewardItemSpec> items = parseRewardItems(qMap.get("reward-items"), qPath + ".reward-items", errors);
        if (items == null) return null;
        List<String> commands = stringList(qMap.get("reward-commands"));
        return new RewardsSpec(coins, items, commands, Map.of());
    }

    private List<RewardItemSpec> parseRewardItems(Object rawItems, String iPath, List<String> errors) {
        if (rawItems == null) return List.of();
        if (!(rawItems instanceof List<?> list)) {
            errors.add(iPath + ": ожидался список предметов");
            return null;
        }
        List<RewardItemSpec> out = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            Object elem = list.get(i);
            String elemPath = iPath + "[" + i + "]";
            if (elem instanceof Map<?, ?> m) {
                String mat = str(m.get("material")).toUpperCase(Locale.ROOT);
                if (mat.isEmpty()) {
                    errors.add(elemPath + ": отсутствует material");
                    return null;
                }
                int amt = num(m.get("amount"), 1);
                if (amt < 1 || amt > MAX_AMOUNT) {
                    errors.add(elemPath + ": amount должен быть от 1 до " + MAX_AMOUNT);
                    return null;
                }
                double chance = dbl(m.get("chance"), 1.0);
                if (Double.isNaN(chance) || chance <= 0.0 || chance > 1.0) {
                    errors.add(elemPath + ": chance должен быть в диапазоне (0; 1]");
                    return null;
                }
                out.add(new RewardItemSpec(mat, amt, chance));
            } else if (elem != null) {
                String[] parts = String.valueOf(elem).trim().split("\\s+");
                if (parts.length == 0 || parts[0].isBlank()) continue;
                String mat = parts[0].toUpperCase(Locale.ROOT);
                int amt = parts.length >= 2 ? num(parts[1], 1) : 1;
                double chance = parts.length >= 3 ? dbl(parts[2], 1.0) : 1.0;
                if (amt < 1 || chance <= 0.0 || chance > 1.0) {
                    errors.add(elemPath + ": некорректная запись предмета '" + elem + "'");
                    return null;
                }
                out.add(new RewardItemSpec(mat, amt, chance));
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ конвертер v1 -> v2

    private ParseResult parseLegacyV1(Map<?, ?> root, String source) {
        List<String> errors = new ArrayList<>();
        boolean enabled = bool(root.get("enabled"), true);
        String tzRaw = str(root.get("timezone"));
        if (tzRaw.isEmpty()) tzRaw = "Europe/Moscow";
        ZoneId timezone = parseZone(tzRaw, source + " → timezone", errors);

        Map<?, ?> dailySec = asMap(root.get("daily"));
        Map<?, ?> weeklySec = asMap(root.get("weekly"));
        int dailyCount = Math.max(1, Math.min(7, num(dailySec.get("count"), 3)));
        int weeklyCount = Math.max(1, Math.min(7, num(weeklySec.get("count"), 3)));

        Map<String, PeriodTemplate> periods = new LinkedHashMap<>();
        periods.put("daily", new PeriodTemplate("daily", PeriodKind.DAILY, 1, dailyCount));
        periods.put("weekly", new PeriodTemplate("weekly", PeriodKind.WEEKLY, 7, weeklyCount));
        periods.put("once", new PeriodTemplate("once", PeriodKind.ONCE, 0, Integer.MAX_VALUE));

        List<QuestSpec> quests = new ArrayList<>();
        Map<String, QuestSpec> questsById = new LinkedHashMap<>();
        Set<String> seenIds = new HashSet<>();

        convertV1Pool(dailySec.get("pool"), "daily", false, source + " → daily.pool",
                periods, quests, questsById, seenIds, errors);
        convertV1Pool(weeklySec.get("pool"), "weekly", true, source + " → weekly.pool",
                periods, quests, questsById, seenIds, errors);

        QuestsConfig cfg = new QuestsConfig(
                enabled, timezone, false, true, List.of(), periods, quests, questsById
        );
        return new ParseResult(cfg, errors);
    }

    private void convertV1Pool(Object rawPool,
                               String category,
                               boolean weeklyDefaultManual,
                               String poolPath,
                               Map<String, PeriodTemplate> periods,
                               List<QuestSpec> quests,
                               Map<String, QuestSpec> questsById,
                               Set<String> seenIds,
                               List<String> errors) {
        if (!(rawPool instanceof List<?> list)) return;
        for (int i = 0; i < list.size(); i++) {
            String path = poolPath + "[" + i + "]";
            if (!(list.get(i) instanceof Map<?, ?> rawMap)) {
                errors.add(path + ": ожидался объект задания");
                continue;
            }
            Map<String, Object> enriched = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : rawMap.entrySet()) {
                enriched.put(String.valueOf(e.getKey()), e.getValue());
            }
            enriched.put("category", category);

            // В v1 цепочки и weekly по умолчанию требовали взятия, а одиночные шахтные daily — нет
            boolean defaultManual = weeklyDefaultManual || (rawMap.get("steps") instanceof List<?> s && s.size() > 1);
            if (!defaultManual && !rawMap.containsKey("require-accept") && !rawMap.containsKey("accept")) {
                String t = str(rawMap.get("type"));
                if (t.isEmpty()) t = str(rawMap.get("event"));
                ActionKind ak = actionRegistry.resolve(t);
                if (ak != null && !ak.defaultRequireWorking) {
                    defaultManual = true;
                }
            }

            QuestSpec spec = parseQuestV2(enriched, path, periods, defaultManual, true, errors);
            if (spec == null) continue;
            if (!seenIds.add(spec.id())) {
                errors.add(path + "(id='" + spec.id() + "'): дублирующийся id задания");
                continue;
            }
            quests.add(spec);
            questsById.put(spec.id(), spec);
        }
    }

    // ------------------------------------------------------------------ helpers

    private static ZoneId parseZone(String raw, String path, List<String> errors) {
        try {
            return ZoneId.of(raw.trim());
        } catch (Exception e) {
            errors.add(path + ": некорректный часовой пояс '" + raw + "', используется Europe/Moscow");
            return ZoneId.of("Europe/Moscow");
        }
    }

    private static Map<?, ?> asMap(Object o) {
        return o instanceof Map<?, ?> m ? m : Map.of();
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o).trim();
    }

    private static String emptyToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }

    private static boolean bool(Object o, boolean fallback) {
        if (o instanceof Boolean b) return b;
        if (o == null) return fallback;
        String s = String.valueOf(o).trim().toLowerCase(Locale.ROOT);
        if ("true".equals(s) || "yes".equals(s) || "1".equals(s)) return true;
        if ("false".equals(s) || "no".equals(s) || "0".equals(s)) return false;
        return fallback;
    }

    private static int num(Object o, int fallback) {
        if (o instanceof Number n) return n.intValue();
        if (o == null) return fallback;
        try {
            return Integer.parseInt(String.valueOf(o).trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    private static Integer optInt(Object o) {
        if (o == null) return null;
        if (o instanceof Number n) return n.intValue();
        String s = String.valueOf(o).trim();
        if (s.isEmpty() || "null".equalsIgnoreCase(s)) return null;
        try {
            return Integer.parseInt(s);
        } catch (Exception e) {
            return null;
        }
    }

    private static double dbl(Object o, double fallback) {
        if (o instanceof Number n) return n.doubleValue();
        if (o == null) return fallback;
        try {
            return Double.parseDouble(String.valueOf(o).trim());
        } catch (Exception e) {
            return Double.NaN;
        }
    }

    private static List<String> stringOrList(Object raw) {
        if (raw == null) return List.of();
        if (raw instanceof List<?> list) {
            List<String> out = new ArrayList<>();
            for (Object e : list) {
                if (e != null) out.add(String.valueOf(e));
            }
            return out;
        }
        String s = String.valueOf(raw);
        return s.isBlank() ? List.of() : List.of(s);
    }

    private static List<String> stringList(Object raw) {
        if (!(raw instanceof List<?> list)) return List.of();
        List<String> out = new ArrayList<>();
        for (Object e : list) {
            if (e != null) {
                String s = String.valueOf(e).trim();
                if (!s.isEmpty()) out.add(s);
            }
        }
        return out;
    }
}
