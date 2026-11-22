package Frontiks.Craft.mining;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class MiningPureLogicTest {

    private static MiningNode sampleNode() {
        MiningNode n = new MiningNode();
        n.id = "node-1";
        n.worldUuid = "00000000-0000-0000-0000-000000000001";
        n.worldName = "Spawn";
        n.x = 10;
        n.y = 65;
        n.z = 120;
        n.chain = List.of("TUFF", "STONE", "ANDESITE");
        n.depletedMaterial = "COBBLESTONE";
        n.cooldownMillis = 45_000L;
        n.loot = List.of(
                new LootEntry("COBBLESTONE", 2, 4, 1.0),
                new LootEntry("COAL", 1, 1, 0.25)
        );
        return n;
    }

    @Test
    void stageTransitionsAndCooldownRestore() {
        MiningNode n = sampleNode();
        assertEquals("TUFF", NodeLogic.expectedMaterial(n));

        // 1-й удар: TUFF -> STONE (ADVANCED, без награды)
        assertEquals(NodeLogic.BreakResult.ADVANCED, NodeLogic.applyBreak(n, 1_000L));
        assertEquals(1, n.stageIndex);
        assertFalse(n.depleted);
        assertEquals("STONE", NodeLogic.expectedMaterial(n));

        // 2-й удар: STONE -> ANDESITE (ADVANCED, без награды)
        assertEquals(NodeLogic.BreakResult.ADVANCED, NodeLogic.applyBreak(n, 2_000L));
        assertEquals(2, n.stageIndex);
        assertFalse(n.depleted);
        assertEquals("ANDESITE", NodeLogic.expectedMaterial(n));

        // 3-й удар: ANDESITE -> COMPLETED (истощён: COBBLESTONE, таймер 45с)
        assertEquals(NodeLogic.BreakResult.COMPLETED, NodeLogic.applyBreak(n, 10_000L));
        assertTrue(n.depleted);
        assertEquals(55_000L, n.depletedUntilMs);
        assertEquals("COBBLESTONE", NodeLogic.expectedMaterial(n));

        // Повторные удары во время кулдауна не продвигают стадию и не дают COMPLETED
        assertEquals(NodeLogic.BreakResult.DEPLETED, NodeLogic.applyBreak(n, 20_000L));
        assertEquals(35_000L, NodeLogic.depletedRemainingMs(n, 20_000L));
        assertFalse(NodeLogic.applyRestore(n, 54_999L));

        // В момент окончания кулдауна восстанавливается к 1-й стадии (TUFF)
        assertTrue(NodeLogic.applyRestore(n, 55_000L));
        assertFalse(n.depleted);
        assertEquals(0, n.stageIndex);
        assertEquals("TUFF", NodeLogic.expectedMaterial(n));
    }

    @Test
    void brokenDesyncNodeRefusesBreaksAndRestore() {
        MiningNode n = sampleNode();
        n.broken = true;
        assertEquals(NodeLogic.BreakResult.DEPLETED, NodeLogic.applyBreak(n, 1_000L));
        n.depleted = true;
        n.depletedUntilMs = 500L;
        assertFalse(NodeLogic.isReadyToRestore(n, 1_000L));
        assertFalse(NodeLogic.applyRestore(n, 1_000L));
    }

    @Test
    void validatorRejectsEmptyChainUnknownMaterialsAndBadLoot() {
        Set<String> validBlocks = Set.of("TUFF", "STONE", "ANDESITE", "COBBLESTONE");
        Set<String> validItems = Set.of("COBBLESTONE", "COAL");

        assertTrue(NodeValidator.validateNode(sampleNode(), validBlocks::contains, validItems::contains).ok());

        // Пустая цепочка
        var emptyChain = NodeValidator.validateChain(List.of(), "COBBLESTONE", validBlocks::contains);
        assertFalse(emptyChain.ok());

        // Несуществующий материал
        var badBlock = NodeValidator.validateChain(List.of("TUFF", "NOT_A_BLOCK"), "COBBLESTONE", validBlocks::contains);
        assertFalse(badBlock.ok());

        // Пустой лут
        assertFalse(NodeValidator.validateLoot(List.of(), validItems::contains).ok());

        // Отрицательный/нулевой шанс и min > max
        var badLoot = NodeValidator.validateLoot(
                List.of(new LootEntry("COBBLESTONE", 5, 2, 0.0)),
                validItems::contains
        );
        assertFalse(badLoot.ok());
        assertTrue(badLoot.errors().size() >= 2);
    }

    @Test
    void lootTableRollRespectsMinMaxAndChance() {
        List<LootEntry> table = List.of(
                new LootEntry("COBBLESTONE", 2, 4, 1.0),
                new LootEntry("DIAMOND", 1, 1, 0.0)
        );
        Random rng = new Random(42L);
        for (int i = 0; i < 50; i++) {
            Map<String, Integer> drops = LootTable.roll(table, rng);
            int cobble = drops.getOrDefault("COBBLESTONE", 0);
            assertTrue(cobble >= 2 && cobble <= 4);
            assertFalse(drops.containsKey("DIAMOND"));
        }
    }

    @Test
    void outlineComputerDeduplicatesSharedEdgesAndHidesFullyEnclosedNode() {
        // Все грани закрыты -> 0 рёбер
        boolean[] closed = {false, false, false, false, false, false};
        assertEquals(0, OutlineComputer.edgeCount(closed));

        // 1 открытая грань -> 4 ребра
        boolean[] oneFace = {true, false, false, false, false, false};
        assertEquals(4, OutlineComputer.edgeCount(oneFace));

        // 2 смежные открытые грани (+x и +y) -> 7 уникальных рёбер (общее ребро не дублируется)
        boolean[] twoAdjacent = {true, false, true, false, false, false};
        assertEquals(7, OutlineComputer.edgeCount(twoAdjacent));

        // Все 6 граней открыты -> 12 рёбер куба
        boolean[] allOpen = {true, true, true, true, true, true};
        List<double[][]> edges = OutlineComputer.computeOutlinePoints(allOpen, 4);
        assertEquals(12, edges.size());
        for (double[][] edge : edges) {
            assertEquals(4, edge.length);
        }
    }

    @Test
    void miningNodeRoundTripSerialization() {
        MiningNode orig = sampleNode();
        orig.stageIndex = 1;
        orig.depleted = true;
        orig.depletedUntilMs = 99_999L;

        Map<String, Object> map = orig.toMap();
        MiningNode copy = MiningNode.fromMap("node-1", map);
        assertEquals(orig.id, copy.id);
        assertEquals(orig.worldUuid, copy.worldUuid);
        assertEquals(orig.worldName, copy.worldName);
        assertEquals(orig.x, copy.x);
        assertEquals(orig.y, copy.y);
        assertEquals(orig.z, copy.z);
        assertEquals(orig.chain, copy.chain);
        assertEquals(orig.depletedMaterial, copy.depletedMaterial);
        assertEquals(orig.stageIndex, copy.stageIndex);
        assertEquals(orig.depleted, copy.depleted);
        assertEquals(orig.depletedUntilMs, copy.depletedUntilMs);
        assertEquals(orig.loot.size(), copy.loot.size());
    }
}
