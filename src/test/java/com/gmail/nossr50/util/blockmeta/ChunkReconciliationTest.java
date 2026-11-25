package com.gmail.nossr50.util.blockmeta;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import com.gmail.nossr50.mcMMO;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.UUID;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ChunkReconciliationTest {
    @TempDir
    private Path worldFolder;
    private final UUID worldId = UUID.randomUUID();
    private World world;
    private Path region;
    private HashChunkManager manager;
    private mcMMO previousPlugin;

    @BeforeEach
    void setup() throws IOException {
        region = Files.createDirectories(worldFolder.resolve("mcmmo_regions")).resolve("mcmmo_-1_-1_.mcm");
        world = mock(World.class);
        when(world.getWorldFolder()).thenReturn(worldFolder.toFile());
        when(world.getUID()).thenReturn(worldId);
        when(world.getMinHeight()).thenReturn(-64);
        when(world.getMaxHeight()).thenReturn(320);
        manager = new HashChunkManager();
        previousPlugin = mcMMO.p;
        final mcMMO plugin = mock(mcMMO.class);
        when(plugin.getLogger()).thenReturn(Logger.getAnonymousLogger());
        mcMMO.p = plugin;
    }

    @AfterEach
    void restorePlugin() {
        mcMMO.p = previousPlugin;
    }

    @Test
    void clearsOnlyAirAndReversesTheLegacyNegativeCoordinateMapping() throws IOException {
        seed();
        assertTrue(manager.completeReconciliation(prepare()));
        final Block air = block(-1, -17, Material.AIR);
        final Block stone = block(-2, -18, Material.STONE);
        final Chunk chunk = mock(Chunk.class);
        when(chunk.getWorld()).thenReturn(world);
        when(chunk.getX()).thenReturn(-1);
        when(chunk.getZ()).thenReturn(-2);
        when(chunk.getBlock(15, 80, 15)).thenReturn(air);
        when(chunk.getBlock(14, 80, 14)).thenReturn(stone);

        final int cursor = manager.reconcileAir(chunk, 0, 1);
        assertTrue(cursor > 0);
        manager.reconcileAir(chunk, cursor, 1);

        assertFalse(manager.isIneligible(air));
        assertTrue(manager.isIneligible(stone));
        manager.chunkUnloaded(-1, -2, world);
        assertTrue(manager.completeReconciliation(prepare()));
        assertFalse(manager.isIneligible(air));
        assertTrue(manager.isIneligible(stone));
    }

    @Test
    void stagedReadCannotOverwriteLiveMarks() throws IOException {
        seed();
        final var prepared = prepare();
        final Block original = block(-1, -17, Material.AIR);
        final Block later = block(-3, -19, Material.STONE);
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getWorld(worldId)).thenReturn(world);
            manager.setEligible(original);
            manager.setIneligible(later);
        }
        assertTrue(manager.completeReconciliation(prepared));
        assertFalse(manager.isIneligible(original));
        assertTrue(manager.isIneligible(later));
        manager.chunkUnloaded(-1, -2, world);
    }

    @Test
    void writeInSameRegionInvalidatesStagedRead() throws IOException {
        seed();
        final var prepared = prepare();
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getWorld(worldId)).thenReturn(world);
            manager.setIneligible(block(-17, -17, Material.STONE));
        }
        manager.chunkUnloaded(-2, -2, world);
        assertFalse(manager.completeReconciliation(prepared));
        assertTrue(manager.completeReconciliation(prepare()));
        manager.chunkUnloaded(-1, -2, world);
    }

    @Test
    void preReadingEmptyChunkDoesNotCreateRegionFile() {
        assertTrue(manager.completeReconciliation(prepare()));
        assertFalse(Files.exists(region));
    }

    @Test
    void failedReadStaysUnpreparedAndCanReadTheRestoredFile() throws IOException {
        final byte[] savedRegion = truncateSeed();
        assertFalse(manager.completeReconciliation(prepare()));
        Files.write(region, savedRegion);
        assertTrue(manager.completeReconciliation(prepare()));
        assertTrue(manager.isIneligible(block(-1, -17, Material.STONE)));
        manager.chunkUnloaded(-1, -2, world);
    }

    @Test
    void foregroundFailureKeepsOldMarksAndReplaysNewSetsAndClears() throws IOException {
        final byte[] savedRegion = truncateSeed();
        final Block original = block(-1, -17, Material.STONE);
        final Block cleared = block(-2, -18, Material.STONE);
        final Block added = block(-3, -19, Material.STONE);
        assertFalse(manager.isIneligible(original));
        manager.setEligible(cleared);
        manager.setIneligible(added);

        Files.write(region, savedRegion);
        assertTrue(manager.completeReconciliation(prepare()));
        assertTrue(manager.isIneligible(original));
        assertFalse(manager.isIneligible(cleared));
        assertTrue(manager.isIneligible(added));
        manager.chunkUnloaded(-1, -2, world);

        assertTrue(manager.completeReconciliation(prepare()));
        assertTrue(manager.isIneligible(original));
        assertFalse(manager.isIneligible(cleared));
        assertTrue(manager.isIneligible(added));
        manager.chunkUnloaded(-1, -2, world);
    }

    @Test
    void unresolvedDeltaSurvivesChunkUnloadAndSavesAtShutdownWithoutAnotherScan()
            throws IOException {
        final byte[] savedRegion = truncateSeed();
        final Block added = block(-3, -19, Material.STONE);
        manager.setIneligible(added);
        manager.chunkUnloaded(-1, -2, world);

        Files.write(region, savedRegion);
        manager.closeAll();
        manager = new HashChunkManager();
        assertTrue(manager.completeReconciliation(prepare()));
        assertTrue(manager.isIneligible(block(-1, -17, Material.STONE)));
        assertTrue(manager.isIneligible(added));
        manager.chunkUnloaded(-1, -2, world);
    }

    @Test
    void unreadableRegionRemainsUnchangedWhenStopCannotRecover() throws IOException {
        truncateSeed();
        manager.setIneligible(block(-3, -19, Material.STONE));
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getWorld(worldId)).thenReturn(world);
            manager.closeAll();
        }
        assertArrayEquals(new byte[] {0}, Files.readAllBytes(region));
    }

    @Test
    void failedPlacementRestoresUnknownValueBeforeAndAfterAsyncRecovery() throws IOException {
        final byte[] savedRegion = truncateSeed();
        final Block original = block(-1, -17, Material.STONE);
        final Block natural = block(-3, -19, Material.STONE);
        assertFalse(manager.isIneligible(original));
        final Runnable restoreOriginal = manager.capturePlacementMark(original);
        final Runnable restoreNatural = manager.capturePlacementMark(natural);
        manager.setIneligible(original);
        manager.setIneligible(natural);
        restoreOriginal.run();
        assertTrue(manager.isIneligible(natural));

        Files.write(region, savedRegion);
        assertTrue(manager.completeReconciliation(prepare()));
        assertTrue(manager.isIneligible(natural));
        restoreNatural.run();
        assertTrue(manager.isIneligible(original));
        assertFalse(manager.isIneligible(natural));
        assertFalse(manager.needsReadRecovery(worldId, -1, -2));
        manager.chunkUnloaded(-1, -2, world);
    }

    @Test
    void untrackedForegroundRecoveryUsesOneQueuedReadAndMergesChanges() throws IOException {
        final byte[] savedRegion = truncateSeed();
        final Block original = block(-1, -17, Material.STONE);
        final Block natural = block(-3, -19, Material.STONE);
        final var pending = new ArrayDeque<Runnable>();
        manager.configureReadRecovery(pending::add, (id, x, z) -> false);

        assertFalse(manager.isIneligible(original));
        manager.setIneligible(natural);
        assertEquals(1, pending.size());
        Files.write(region, savedRegion);
        pending.remove().run();

        assertTrue(manager.isIneligible(original));
        assertTrue(manager.isIneligible(natural));
        assertTrue(pending.isEmpty());
        manager.chunkUnloaded(-1, -2, world);
    }

    @Test
    void queuedForegroundReadAfterWorldUnloadCannotConsumeShutdownChanges() throws IOException {
        final byte[] savedRegion = truncateSeed();
        final Block added = block(-3, -19, Material.STONE);
        final var pending = new ArrayDeque<Runnable>();
        manager.configureReadRecovery(pending::add, (id, x, z) -> false);
        manager.setIneligible(added);
        assertEquals(1, pending.size());
        manager.unloadWorld(world);

        Files.write(region, savedRegion);
        pending.remove().run();
        assertTrue(manager.needsReadRecovery(worldId, -1, -2));
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getWorld(worldId)).thenReturn(null);
            manager.closeAll();
        }

        manager = new HashChunkManager();
        assertTrue(manager.completeReconciliation(prepare()));
        assertTrue(manager.isIneligible(added));
        assertTrue(manager.isIneligible(block(-1, -17, Material.STONE)));
        manager.chunkUnloaded(-1, -2, world);
    }

    private byte[] truncateSeed() throws IOException {
        seed();
        final byte[] savedRegion = Files.readAllBytes(region);
        Files.write(region, new byte[] {0});
        return savedRegion;
    }

    private HashChunkManager.ReconciliationLoad prepare() {
        return manager.prepareReconciliation(worldId, worldFolder.toFile(), -64, 320, -1, -2);
    }

    private void seed() throws IOException {
        final BitSetChunkStore store = new BitSetChunkStore(world, -1, -2);
        store.setTrue(1, 80, 1);
        store.setTrue(2, 80, 2);
        try (McMMOSimpleRegionFile file = new McMMOSimpleRegionFile(region.toFile(), -1, -1);
                DataOutputStream output = file.getOutputStream(-1, -2)) {
            BitSetChunkStore.Serialization.writeChunkStore(output, store);
        }
    }

    private Block block(int x, int z, Material material) {
        final Block block = mock(Block.class);
        when(block.getWorld()).thenReturn(world);
        when(block.getX()).thenReturn(x);
        when(block.getY()).thenReturn(80);
        when(block.getZ()).thenReturn(z);
        when(block.getType()).thenReturn(material);
        return block;
    }
}
