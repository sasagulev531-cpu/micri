package Frontiks.Craft.mining;

import Frontiks.Craft.quests.QuestManager;
import Frontiks.Craft.work.HrabotaConfig;
import Frontiks.Craft.work.WorkManager;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Управление добываемыми узлами шахты: создание/удаление, ломка по стадиям,
 * истощение и восстановление, контур-маркеры, валидация.
 *
 * Все операции с миром — только на main thread. Чанки не загружаются принудительно:
 * проверки и восстановление выполняются только в уже загруженных чанках.
 * Состояние узла меняется синхронно ДО выдачи лута и начисления прогресса.
 */
public final class MiningNodeManager {

    /** Исключение с понятным русским сообщением для пользователя. */
    public static final class MiningNodeException extends Exception {
        public MiningNodeException(String message) { super(message); }
    }

    private static final org.bukkit.Color YELLOW = org.bukkit.Color.fromRGB(0xFFD700);
    private static final Particle.DustOptions DUST = new Particle.DustOptions(YELLOW, 0.6f);

    private final JavaPlugin plugin;
    private final MiningSettings settings;
    private final MiningNodeStore store;
    private final QuestManager quests;
    private final Random random = new Random();

    private final Map<String, MiningNode> nodes = new LinkedHashMap<>();     // id -> node
    private final Map<String, String> nodeIdByBlock = new HashMap<>();       // blockKey -> id
    private final Map<String, List<double[][]>> outlineCache = new HashMap<>();
    private final Set<String> outlineDirty = new HashSet<>();
    private final Map<UUID, Long> lastDepletedMessage = new HashMap<>();

    private BukkitTask markerTask;
    private BukkitTask restoreTask;

    public MiningNodeManager(JavaPlugin plugin, MiningSettings settings, MiningNodeStore store, QuestManager quests) {
        this.plugin = plugin;
        this.settings = settings;
        this.store = store;
        this.quests = quests;
    }

    // ----------------------------------------------------------------- lifecycle

    public void init() {
        store.init();
        reloadNodes();
        startTasks();
        plugin.getLogger().info("Узлы шахты загружены: " + nodes.size());
    }

    public void reload() {
        HrabotaConfig.reload();
        settings.reload();
        reloadNodes();
        restartTasks();
    }

    public void shutdown() {
        if (markerTask != null) markerTask.cancel();
        if (restoreTask != null) restoreTask.cancel();
        for (MiningNode node : snapshot()) {
            store.save(node);
        }
    }

    private void reloadNodes() {
        nodes.clear();
        nodeIdByBlock.clear();
        outlineCache.clear();
        outlineDirty.clear();
        List<String> errors = new ArrayList<>();
        for (MiningNode node : store.loadAll(errors)) {
            nodes.put(node.id, node);
            nodeIdByBlock.put(node.blockKey(), node.id);
            outlineDirty.add(node.id);
        }
        for (String error : errors) {
            plugin.getLogger().severe(error);
        }
        // Конфигурационные ошибки (например, битый mining.stages) — тоже в лог.
        for (String error : settings.configErrors()) {
            plugin.getLogger().severe("Конфигурация: " + error);
        }
    }

    private void startTasks() {
        long refresh = settings.markerRefreshTicks();
        markerTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tickMarkers, refresh, refresh);
        restoreTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tickRestores, 20L, 20L);
    }

    private void restartTasks() {
        if (markerTask != null) { markerTask.cancel(); markerTask = null; }
        if (restoreTask != null) { restoreTask.cancel(); restoreTask = null; }
        startTasks();
    }

    private List<MiningNode> snapshot() {
        return new ArrayList<>(nodes.values());
    }

    // ----------------------------------------------------------------- accessors

    public MiningSettings settings() { return settings; }

    /** Очищает лут узла без валидации (явное действие админа, награда просто не будет выдаваться). */
    public void clearLoot(MiningNode node) {
        synchronized (node) {
            node.loot = new ArrayList<>();
            persist(node);
        }
    }

    public List<MiningNode> getAllNodes() {
        return Collections.unmodifiableList(new ArrayList<>(nodes.values()));
    }

    public MiningNode getNodeAt(Location loc) {
        if (loc == null || loc.getWorld() == null) return null;
        String id = nodeIdByBlock.get(blockKey(loc.getWorld(), loc.getBlockX(), loc.getBlockY(), loc.getBlockZ()));
        return id == null ? null : nodes.get(id);
    }

    public boolean isMiningNode(Location loc) {
        return getNodeAt(loc) != null;
    }

    public MiningNode getNode(String id) {
        return id == null ? null : nodes.get(id);
    }

    /** Публичный алиас для обновления контуров соседей после изменения блока. */
    public void markNeighborsChanged(Location loc) {
        markOutlineAround(loc);
    }

    private static String blockKey(World world, int x, int y, int z) {
        return world.getUID() + ":" + x + ":" + y + ":" + z;
    }

    private static String blockKey(MiningNode node) {
        return node.worldUuid + ":" + node.x + ":" + node.y + ":" + node.z;
    }

    /** Мир узла без принудительной загрузки; null, если мир выгружен/недоступен. */
    private World worldOf(MiningNode node) {
        if (node.worldUuid != null && !node.worldUuid.isBlank()) {
            try {
                World w = Bukkit.getWorld(UUID.fromString(node.worldUuid));
                if (w != null) return w;
            } catch (IllegalArgumentException ignored) {
                plugin.getLogger().warning("Узел " + node.id + ": некорректный world-uuid");
            }
        }
        return node.worldName == null || node.worldName.isBlank() ? null : Bukkit.getWorld(node.worldName);
    }

    // ----------------------------------------------------------------- create / delete / edit

    /**
     * Создаёт узел на блоке. Блок приводится к первой стадии цепочки из конфига.
     * Бросает MiningNodeException с русским сообщением, если блок вне зоны/занят/конфиг невалиден.
     */
    public MiningNode createNodeAt(Block block, Player admin) throws MiningNodeException {
        Location loc = block.getLocation();
        if (!HrabotaConfig.contains(loc)) {
            throw new MiningNodeException("Этот блок вне шахтной зоны. Узлы можно создавать только внутри настроенной зоны (см. /setgranic).");
        }
        if (getNodeAt(loc) != null) {
            throw new MiningNodeException("На этом блоке уже настроен узел.");
        }

        List<String> chain = new ArrayList<>(settings.defaultStages());
        String depleted = settings.defaultDepleted();
        NodeValidator.ValidationResult validation = NodeValidator.validateChain(chain, depleted, blockMaterialValidator());
        if (!validation.ok()) {
            throw new MiningNodeException("Невалидная цепочка стадий в конфиге: " + validation.joined());
        }

        World world = block.getWorld();
        MiningNode node = MiningNode.builder()
                .world(world.getUID().toString(), world.getName())
                .pos(block.getX(), block.getY(), block.getZ())
                .chain(chain)
                .depletedMaterial(depleted)
                .loot(settings.defaultLoot())
                .cooldownMillis(settings.cooldownSeconds() * 1000L)
                .build();

        synchronized (node) {
            if (!store.save(node)) {
                throw new MiningNodeException("Не удалось сохранить узел (подробности в консоли).");
            }
            Material first = materialOrNull(node.chain.get(0));
            if (first != null && block.getType() != first) {
                block.setType(first, false);
            }
            nodes.put(node.id, node);
            nodeIdByBlock.put(blockKey(node), node.id);
            outlineDirty.add(node.id);
        }
        plugin.getLogger().info("Узел шахты создан " + (admin == null ? "" : "админом " + admin.getName())
                + " в " + world.getName() + " " + node.x + " " + node.y + " " + node.z + " (id=" + node.id + ")");
        return node;
    }

    /** Удаляет узел на блоке. Возвращает удалённый узел или null. */
    public MiningNode deleteNodeAt(Location loc) {
        MiningNode node = getNodeAt(loc);
        if (node == null) return null;
        removeNode(node);
        return node;
    }

    public void removeNode(MiningNode node) {
        synchronized (node) {
            nodes.remove(node.id);
            nodeIdByBlock.remove(blockKey(node));
            outlineCache.remove(node.id);
            outlineDirty.remove(node.id);
            store.delete(node.id);
        }
    }

    /** Принудительное восстановление истощённого узла администратором. */
    public void forceRestore(MiningNode node) throws MiningNodeException {
        if (node.broken) throw new MiningNodeException("Узел в состоянии десинхронизации — сначала почините блок или удалите узел.");
        synchronized (node) {
            node.depleted = false;
            node.depletedUntilMs = 0L;
            node.stageIndex = 0;
            applyBlockState(node, NodeLogic.expectedMaterial(node));
            persist(node);
            outlineDirty.add(node.id);
        }
    }

    public void setCooldownSeconds(MiningNode node, int seconds) {
        synchronized (node) {
            node.cooldownMillis = Math.max(0, seconds) * 1000L;
            persist(node);
        }
    }

    public void setStageChain(MiningNode node, List<String> newChain) throws MiningNodeException {
        List<String> chain = new ArrayList<>();
        for (String s : newChain) if (s != null && !s.isBlank()) chain.add(s.trim().toUpperCase());
        NodeValidator.ValidationResult v = NodeValidator.validateChain(chain, node.depletedMaterial, blockMaterialValidator());
        if (!v.ok()) throw new MiningNodeException("Цепочка невалидна: " + v.joined());
        synchronized (node) {
            node.chain = chain;
            node.stageIndex = NodeLogic.clampIndex(node);
            // Блок приводим к текущей ожидаемой стадии — иначе узел мгновенно десинхронизируется.
            applyBlockState(node, NodeLogic.expectedMaterial(node));
            persist(node);
            outlineDirty.add(node.id);
        }
    }

    public void addStage(MiningNode node, String material) throws MiningNodeException {
        List<String> chain = new ArrayList<>(node.chain);
        if (chain.size() >= 16) throw new MiningNodeException("Максимум 16 стадий.");
        chain.add(material.trim().toUpperCase());
        setStageChain(node, chain);
    }

    public void removeStage(MiningNode node, int index) throws MiningNodeException {
        List<String> chain = new ArrayList<>(node.chain);
        if (chain.size() <= 1) throw new MiningNodeException("В цепочке должна остаться хотя бы одна стадия.");
        if (index < 0 || index >= chain.size()) throw new MiningNodeException("Некорректный индекс стадии.");
        chain.remove(index);
        setStageChain(node, chain);
    }

    public void setStageMaterial(MiningNode node, int index, String material) throws MiningNodeException {
        List<String> chain = new ArrayList<>(node.chain);
        if (index < 0 || index >= chain.size()) throw new MiningNodeException("Некорректный индекс стадии.");
        chain.set(index, material.trim().toUpperCase());
        setStageChain(node, chain);
    }

    public void setDepletedMaterial(MiningNode node, String material) throws MiningNodeException {
        String m = material.trim().toUpperCase();
        NodeValidator.ValidationResult v = NodeValidator.validateChain(node.chain, m, blockMaterialValidator());
        if (!v.ok()) throw new MiningNodeException("Истощённый материал невалиден: " + v.joined());
        synchronized (node) {
            node.depletedMaterial = m;
            if (node.depleted) applyBlockState(node, m);
            persist(node);
            outlineDirty.add(node.id);
        }
    }

    public void setLoot(MiningNode node, List<LootEntry> loot) throws MiningNodeException {
        NodeValidator.ValidationResult v = NodeValidator.validateLoot(loot, itemMaterialValidator());
        if (!v.ok()) throw new MiningNodeException("Лут невалиден: " + v.joined());
        synchronized (node) {
            node.loot = new ArrayList<>(loot);
            persist(node);
        }
    }

    // ----------------------------------------------------------------- block events

    /** Основная обработка ломки блока. Вызывается на main thread ДО ванильных обработчиков. */
    public void handleBlockBreak(BlockBreakEvent e) {
        Player p = e.getPlayer();

        // Админский инструмент не ломает блоки вообще (даже вне узлов).
        if (MiningWand.isWand(plugin, p.getInventory().getItemInMainHand())) {
            e.setCancelled(true);
            if (!Frontiks.Craft.api.FcpPermissions.adminOr(p, "fcp.admin.jobs.node")) {
                throttledMessage(p, "&cНет прав (fcp.admin.jobs.node).", 5000);
                return;
            }
            handleWandClick(p, e.getBlock());
            return;
        }

        MiningNode node = getNodeAt(e.getBlock().getLocation());
        if (node == null) {
            return; // обычный блок — не наша механика
        }

        if (p.getGameMode() == org.bukkit.GameMode.CREATIVE || p.getGameMode() == org.bukkit.GameMode.SPECTATOR) {
            // В творческом узлы не ломаются вообще — защита от случайных действий админа.
            e.setCancelled(true);
            return;
        }

        e.setCancelled(true); // никакого ванильного дропа/XP по узлам

        if (node.broken) {
            throttledMessage(p, "&cЭтот узел отключён из-за изменения блока. Нужен администратор (/miningnode validate).", 5000);
            return;
        }

        synchronized (node) {
            long now = System.currentTimeMillis();
            String expected = NodeLogic.expectedMaterial(node);
            Material current = e.getBlock().getType();
            if (!current.name().equalsIgnoreCase(expected)) {
                markBroken(node, "ожидался " + expected + ", найден " + current.name());
                p.sendMessage(Frontiks.Craft.utils.color.color(
                        Frontiks.Craft.utils.Msg.PREFIX + "&cУзел десинхронизирован (блок изменён). Нужен администратор."));
                return;
            }

            NodeLogic.BreakResult result = NodeLogic.applyBreak(node, now);

            if (result == NodeLogic.BreakResult.DEPLETED) {
                long remainingSec = NodeLogic.depletedRemainingMs(node, now) / 1000L + 1;
                throttledMessage(p, "&eУзел ещё восстанавливается (~" + remainingSec + " c). Награды нет.", 3000);
                return;
            }

            // Переход состояния — синхронно и ДО выдачи лута/прогресса.
            String nextMaterial = NodeLogic.expectedMaterial(node);
            applyBlockState(node, nextMaterial);
            persist(node);
            outlineDirty.add(node.id);

            if (result == NodeLogic.BreakResult.ADVANCED) {
                return;
            }

            // COMPLETED: лут, деньги смены и прогресс заданий.
            Map<String, Integer> drops = LootTable.roll(node.loot, random);
            giveLoot(p, e.getBlock().getLocation(), drops);

            boolean inZone = HrabotaConfig.contains(e.getBlock().getLocation());
            if (inZone && WorkManager.isWorking(p)) {
                int reward = settings.nodeRewardCoins();
                if (reward > 0) {
                    try {
                        WorkManager.add(p, reward);
                    } catch (Throwable t) {
                        plugin.getLogger().severe("Не удалось начислить деньги смены за узел " + node.id + ": " + t);
                    }
                }
                quests.applyNodeCompletion(p, drops, 1);
            }
        }

        // Соседи могли измениться (блок сменился типом) — пересчитать контур.
        markOutlineAround(e.getBlock().getLocation());
    }

    /** После установки/ломки любого блока рядом с узлом — помечаем контур на пересчёт. */
    public void handleBlockPlace(BlockPlaceEvent e) {
        markOutlineAround(e.getBlock().getLocation());
    }

    public void handleChunkLoad(ChunkLoadEvent e) {
        // Чанк загрузился — у узлов рядом с ним могли быть «закрытые на время выгрузки» грани.
        for (MiningNode node : snapshot()) {
            if (Math.abs((node.x >> 4) - e.getChunk().getX()) <= 1
                    && Math.abs((node.z >> 4) - e.getChunk().getZ()) <= 1) {
                outlineDirty.add(node.id);
            }
        }
        // И сразу пытаемся восстановить истощённые узлы, чей кулдаун уже истёк.
        tickRestores();
    }

    private void markOutlineAround(Location center) {
        World w = center.getWorld();
        if (w == null) return;
        int bx = center.getBlockX(), by = center.getBlockY(), bz = center.getBlockZ();
        for (MiningNode node : snapshot()) {
            if (node.worldUuid == null || !node.worldUuid.equals(w.getUID().toString())) continue;
            if (Math.abs(node.x - bx) <= 1 && Math.abs(node.y - by) <= 1 && Math.abs(node.z - bz) <= 1) {
                outlineDirty.add(node.id);
            }
        }
    }

    /** Обработка клика админским инструментом по блоку. */
    public void handleWandClick(Player admin, Block block) {
        handleWandClick(admin, block, true);
    }

    public void handleWandClick(Player admin, Block block, boolean openMenu) {
        if (!HrabotaConfig.contains(block.getLocation())) {
            admin.sendMessage(Frontiks.Craft.utils.color.color(
                    Frontiks.Craft.utils.Msg.PREFIX + "&cЭтот блок вне шахтной зоны."));
            return;
        }
        MiningNode existing = getNodeAt(block.getLocation());
        if (existing != null) {
            if (openMenu) {
                new Frontiks.Craft.mining.MiningNodeMenu(plugin, this, admin, existing).open();
            }
            return;
        }
        try {
            MiningNode created = createNodeAt(block, admin);
            admin.sendMessage(Frontiks.Craft.utils.color.color(
                    Frontiks.Craft.utils.Msg.PREFIX + "&aУзел создан на " + created.x + " " + created.y + " " + created.z
                            + " в мире " + created.worldName));
            admin.sendMessage(Frontiks.Craft.utils.color.color(
                    Frontiks.Craft.utils.Msg.PREFIX + "&7Цепочка: &f" + String.join(" &8→ &f", created.chain)
                            + " &8→ &7(истощён: &f" + created.depletedMaterial + "&7)"));
            if (openMenu) {
                new Frontiks.Craft.mining.MiningNodeMenu(plugin, this, admin, created).open();
            }
        } catch (MiningNodeException ex) {
            admin.sendMessage(Frontiks.Craft.utils.color.color(Frontiks.Craft.utils.Msg.PREFIX + "&c" + ex.getMessage()));
        }
    }

    // ----------------------------------------------------------------- tasks

    private void tickMarkers() {
        if (!settings.markersEnabled()) return;
        long radiusSq = (long) settings.markerRadius() * settings.markerRadius();
        int ppe = settings.pointsPerEdge();

        for (MiningNode node : snapshot()) {
            try {
                if (node.broken) continue;
                if (node.depleted && !settings.showDepletedMarkers()) continue;
                World world = worldOf(node);
                if (world == null || !world.isChunkLoaded(node.x >> 4, node.z >> 4)) continue;

                // Есть ли игрок рядом — иначе не рисуем вообще.
                double cx = node.x + 0.5, cy = node.y + 0.5, cz = node.z + 0.5;
                boolean playerNear = false;
                for (Player pl : world.getPlayers()) {
                    double dx = pl.getLocation().getX() - cx;
                    double dy = pl.getLocation().getY() - cy;
                    double dz = pl.getLocation().getZ() - cz;
                    if (dx * dx + dy * dy + dz * dz <= radiusSq) { playerNear = true; break; }
                }
                if (!playerNear) continue;

                List<double[][]> edges = outline(node, world, ppe);
                if (edges == null) continue;

                for (double[][] edge : edges) {
                    for (double[] pt : edge) {
                        world.spawnParticle(Particle.DUST,
                                node.x + pt[0], node.y + pt[1], node.z + pt[2],
                                1, 0, 0, 0, 0, DUST);
                    }
                }
            } catch (Throwable t) {
                plugin.getLogger().severe("Ошибка отрисовки маркера узла " + node.id + ": " + t);
            }
        }
    }

    private void tickRestores() {
        long now = System.currentTimeMillis();
        for (MiningNode node : snapshot()) {
            try {
                if (node.broken || !node.depleted) continue;
                if (!NodeLogic.isReadyToRestore(node, now)) continue;
                World world = worldOf(node);
                if (world == null) continue;
                if (!world.isChunkLoaded(node.x >> 4, node.z >> 4)) continue; // не грузим чанк
                synchronized (node) {
                    // Повторная проверка под замком
                    if (!NodeLogic.isReadyToRestore(node, System.currentTimeMillis())) continue;
                    Block block = world.getBlockAt(node.x, node.y, node.z);
                    if (!block.getType().name().equalsIgnoreCase(node.depletedMaterial)) {
                        markBroken(node, "ожидался " + node.depletedMaterial + ", найден " + block.getType().name());
                        continue;
                    }
                    NodeLogic.applyRestore(node, System.currentTimeMillis());
                    applyBlockState(node, NodeLogic.expectedMaterial(node));
                    persist(node);
                    outlineDirty.add(node.id);
                }
            } catch (Throwable t) {
                plugin.getLogger().severe("Ошибка восстановления узла " + node.id + ": " + t);
            }
        }
    }

    private List<double[][]> outline(MiningNode node, World world, int pointsPerEdge) {
        if (!outlineDirty.contains(node.id) && outlineCache.containsKey(node.id)) {
            return outlineCache.get(node.id);
        }
        boolean[] open = new boolean[6];
        boolean anyUnknown = false;
        int minY = world.getMinHeight();
        int maxY = world.getMaxHeight();
        for (int i = 0; i < 6; i++) {
            int nx = node.x + OutlineComputer.FACE_OFFSETS[i][0];
            int ny = node.y + OutlineComputer.FACE_OFFSETS[i][1];
            int nz = node.z + OutlineComputer.FACE_OFFSETS[i][2];
            if (ny < minY || ny >= maxY) { open[i] = true; continue; } // открытая сторона света/void
            if (!world.isChunkLoaded(nx >> 4, nz >> 4)) {
                open[i] = false;   // не знаем — считаем закрытым (контур не «просвечивает»)
                anyUnknown = true; // и оставляем dirty — пересчитаем, когда чанк загрузится
                continue;
            }
            Material t = world.getBlockAt(nx, ny, nz).getType();
            open[i] = t.isAir() || !t.isOccluding();
        }
        List<double[][]> points = OutlineComputer.computeOutlinePoints(open, pointsPerEdge);
        outlineCache.put(node.id, points);
        if (anyUnknown) {
            outlineDirty.add(node.id);
        } else {
            outlineDirty.remove(node.id);
        }
        return points;
    }

    // ----------------------------------------------------------------- internals

    private void persist(MiningNode node) {
        if (!store.save(node)) {
            // Ошибка уже залогирована хранилищем. Не молчим и не врём игроку —
            // логируем здесь с контекстом операции.
            plugin.getLogger().severe("Узел " + node.id + " изменён в памяти, но не сохранён на диск. Возможна десинхронизация после перезапуска.");
        }
    }

    private void applyBlockState(MiningNode node, String materialName) {
        World world = worldOf(node);
        if (world == null) {
            markBroken(node, "мир недоступен");
            return;
        }
        if (!world.isChunkLoaded(node.x >> 4, node.z >> 4)) {
            // Чанк выгружен — блок трогать нельзя (и не нужно грузить). Узел продеградирует
            // до desync-проверки при ближайшем взаимодействии/загрузке чанка.
            plugin.getLogger().warning("Узел " + node.id + ": чанк не загружен, блок не изменён (" + materialName + ")");
            return;
        }
        Material material = Material.matchMaterial(materialName);
        if (material == null || !material.isBlock()) {
            plugin.getLogger().severe("Узел " + node.id + ": невалидный материал '" + materialName + "'");
            return;
        }
        world.getBlockAt(node.x, node.y, node.z).setType(material, false);
    }

    private void markBroken(MiningNode node, String reason) {
        node.broken = true;
        plugin.getLogger().severe("Узел " + node.id + " (" + node.x + " " + node.y + " " + node.z + ") десинхронизирован: "
                + reason + ". Блок не трогаем; используйте /miningnode validate и пересоздайте узел.");
        outlineDirty.remove(node.id);
        persist(node);
    }

    private void giveLoot(Player p, Location dropAt, Map<String, Integer> drops) {
        if (drops.isEmpty()) return;
        for (Map.Entry<String, Integer> entry : drops.entrySet()) {
            Material material = Material.matchMaterial(entry.getKey());
            if (material == null) {
                plugin.getLogger().severe("Лут узла: неизвестный материал '" + entry.getKey() + "' — пропущен");
                continue;
            }
            int remaining = Math.max(0, entry.getValue());
            int maxStack = Math.max(1, material.getMaxStackSize());
            while (remaining > 0) {
                int take = Math.min(maxStack, remaining);
                remaining -= take;
                ItemStack stack = new ItemStack(material, take);
                Map<Integer, ItemStack> leftover = p.getInventory().addItem(stack);
                for (ItemStack rest : leftover.values()) {
                    p.getWorld().dropItemNaturally(dropAt.clone().add(0.5, 0.5, 0.5), rest);
                }
            }
        }
    }

    private void throttledMessage(Player p, String message, long cooldownMs) {
        long now = System.currentTimeMillis();
        Long last = lastDepletedMessage.get(p.getUniqueId());
        if (last != null && now - last < cooldownMs) return;
        lastDepletedMessage.put(p.getUniqueId(), now);
        p.sendMessage(Frontiks.Craft.utils.color.color(Frontiks.Craft.utils.Msg.PREFIX + message));
    }

    /** Отчёт о состоянии всех узлов + ошибки конфига (для /miningnode validate). */
    public List<String> validateAll() {
        List<String> report = new ArrayList<>();
        report.addAll(settings.configErrors());

        List<MiningNode> all = snapshot();
        int broken = 0, outsideZone = 0, chunkChecked = 0, chunkDesync = 0;

        for (MiningNode node : all) {
            NodeValidator.ValidationResult v = NodeValidator.validateNode(node, blockMaterialValidator(), itemMaterialValidator());
            for (String err : v.errors()) {
                report.add("узел " + shortId(node.id) + " @ " + node.x + " " + node.y + " " + node.z + ": " + err);
            }
            if (node.broken) broken++;
            Location loc = nodeLocation(node);
            if (loc != null && !HrabotaConfig.contains(loc)) {
                outsideZone++;
                report.add("узел " + shortId(node.id) + " @ " + node.x + " " + node.y + " " + node.z + ": вне текущей шахтной зоны");
            }
            World world = worldOf(node);
            if (world != null && world.isChunkLoaded(node.x >> 4, node.z >> 4)) {
                chunkChecked++;
                Material actual = world.getBlockAt(node.x, node.y, node.z).getType();
                String expected = NodeLogic.expectedMaterial(node);
                if (!actual.name().equalsIgnoreCase(expected)) chunkDesync++;
            }
        }
        if (broken > 0) report.add("десинхронизированных узлов: " + broken + " (пересоздайте их через wand)");
        if (outsideZone > 0) report.add("узлов вне зоны: " + outsideZone);
        if (chunkDesync > 0) report.add("узлов с несовпадающим блоком (по загруженным чанкам): " + chunkDesync
                + " из проверенных " + chunkChecked);
        if (report.isEmpty()) report.add("Ошибок не найдено. Узлов: " + all.size());
        else report.add(0, "Всего узлов: " + all.size() + ", десинхронизировано: " + broken);
        return report;
    }

    public Location nodeLocation(MiningNode node) {
        World world = worldOf(node);
        if (world == null) return null;
        return new Location(world, node.x + 0.5, node.y + 0.5, node.z + 0.5);
    }

    public String shortId(String id) {
        return id == null ? "?" : id.substring(0, Math.min(8, id.length()));
    }

    private static Material materialOrNull(String name) {
        return name == null ? null : Material.matchMaterial(name);
    }

    public Predicate<String> blockMaterialValidator() {
        return s -> {
            Material m = Material.matchMaterial(s == null ? "" : s);
            return m != null && m.isBlock();
        };
    }

    public Predicate<String> itemMaterialValidator() {
        return s -> Material.matchMaterial(s == null ? "" : s) != null;
    }
}
