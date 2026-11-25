package com.gmail.nossr50.listeners;

import static java.util.logging.Logger.getLogger;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.gmail.nossr50.MMOTestEnvironment;
import com.gmail.nossr50.config.PersistentDataConfig;
import com.gmail.nossr50.mcMMO;
import com.tcoded.folialib.FoliaLib;
import com.tcoded.folialib.impl.PlatformScheduler;
import com.tcoded.folialib.wrapper.task.WrappedTask;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.event.world.StructureGrowEvent;
import org.bukkit.event.world.WorldUnloadEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

/**
 * Covers the world lifecycle wiring: structure growth resetting placed-block eligibility, and
 * chunk/world unloads flushing their placed-block data.
 */
class WorldListenerTest extends MMOTestEnvironment {
    private static final Logger logger = getLogger(WorldListenerTest.class.getName());

    private MockedStatic<PersistentDataConfig> persistentDataConfigMock;
    private PlatformScheduler scheduler;
    private WorldListener worldListener;

    @BeforeEach
    void setUp() {
        mockBaseEnvironment(logger);
        persistentDataConfigMock = mockStatic(PersistentDataConfig.class);
        persistentDataConfigMock.when(PersistentDataConfig::getInstance)
                .thenReturn(mock(PersistentDataConfig.class));
        final FoliaLib foliaLib = mock(FoliaLib.class);
        scheduler = mock(PlatformScheduler.class);
        when(mcMMO.p.getFoliaLib()).thenReturn(foliaLib);
        when(foliaLib.getScheduler()).thenReturn(scheduler);
        mockedMcMMO.when(mcMMO::getChunkManager).thenReturn(chunkManager);
        worldListener = new WorldListener(mcMMO.p);
    }

    @AfterEach
    void tearDown() {
        persistentDataConfigMock.close();
        cleanUpStaticMocks();
    }

    /**
     * Structure growth (trees, huge fungi) is an outcome event that must clear placed-block
     * tracking for every block the structure claims, so grown blocks reward XP again. The
     * reset runs a tick later because the grown blocks do not exist yet while the event fires.
     */
    @Test
    @SuppressWarnings("unchecked")
    void structureGrowShouldMakeEveryGrownBlockEligibleOneTickLater() {
        final UUID worldId = UUID.randomUUID();
        final Location origin = new Location(world, 1, 64, 1);
        final Block grownLog = mock(Block.class);
        final Block unchanged = mock(Block.class);
        final BlockState expectedLog = mock(BlockState.class);
        final BlockState unappliedLog = mock(BlockState.class);
        when(world.getUID()).thenReturn(worldId);
        when(world.getMaxHeight()).thenReturn(320);
        when(world.isChunkLoaded(0, 0)).thenReturn(true);
        when(Bukkit.getWorld(worldId)).thenReturn(world);
        when(grownLog.getWorld()).thenReturn(world);
        when(grownLog.getType()).thenReturn(Material.OAK_LOG);
        when(unchanged.getWorld()).thenReturn(world);
        when(unchanged.getX()).thenReturn(2);
        when(unchanged.getType()).thenReturn(Material.STONE);
        when(expectedLog.getBlock()).thenReturn(grownLog);
        when(expectedLog.getType()).thenReturn(Material.OAK_LOG);
        when(expectedLog.getLocation()).thenReturn(origin);
        when(unappliedLog.getBlock()).thenReturn(unchanged);
        when(unappliedLog.getX()).thenReturn(2);
        when(unappliedLog.getType()).thenReturn(Material.OAK_LOG);
        final Location unchangedLocation = new Location(world, 2, 64, 1);
        when(unappliedLog.getLocation()).thenReturn(unchangedLocation);
        when(scheduler.isOwnedByCurrentRegion(origin)).thenReturn(true);
        when(scheduler.isOwnedByCurrentRegion(unchangedLocation)).thenReturn(true);
        final StructureGrowEvent event = mock(StructureGrowEvent.class);
        when(event.getWorld()).thenReturn(world);
        when(event.getLocation()).thenReturn(origin);
        when(event.getBlocks()).thenReturn(List.of(expectedLog, unappliedLog));

        worldListener.onStructureGrow(event);
        verify(chunkManager, never()).setEligible(any(Block.class));
        final ArgumentCaptor<Consumer<WrappedTask>> scheduledTask = ArgumentCaptor.forClass(Consumer.class);
        verify(scheduler).runAtLocationLater(eq(origin), scheduledTask.capture(), eq(1L));
        scheduledTask.getValue().accept(mock(WrappedTask.class));
        verify(chunkManager).setEligible(grownLog);
        verify(chunkManager, never()).setEligible(unchanged);
    }

    @Test
    void chunkUnloadShouldFlushTheChunksPlacedBlockData() {
        // Given - an unloading chunk
        final Chunk chunk = mock(Chunk.class);
        when(chunk.getX()).thenReturn(3);
        when(chunk.getZ()).thenReturn(-7);
        final ChunkUnloadEvent event = mock(ChunkUnloadEvent.class);
        when(event.getWorld()).thenReturn(world);
        when(event.getChunk()).thenReturn(chunk);

        // When - the unload is handled
        worldListener.onChunkUnload(event);

        // Then - the chunk's placed-block data is flushed
        verify(chunkManager).chunkUnloaded(3, -7, world);
    }

    @Test
    void worldUnloadShouldFlushTheWorldsPlacedBlockData() {
        // Given - an unloading world (block tracker persistence disabled, so no backup runs)
        final WorldUnloadEvent event = mock(WorldUnloadEvent.class);
        when(event.getWorld()).thenReturn(world);

        // When - the unload is handled
        worldListener.onWorldUnload(event);

        // Then - the world's placed-block data is flushed
        verify(chunkManager).unloadWorld(world);
    }
}
