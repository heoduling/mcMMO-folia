package com.gmail.nossr50.listeners;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.gmail.nossr50.MMOTestEnvironment;
import com.gmail.nossr50.mcMMO;
import com.gmail.nossr50.util.blockmeta.HashChunkManager;
import com.tcoded.folialib.wrapper.task.WrappedTask;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.logging.Logger;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockMultiPlaceEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class BlockListenerTrackingTest extends MMOTestEnvironment {
    private final Set<Block> placed = new HashSet<>();
    private final Queue<Consumer<WrappedTask>> callbacks = new ArrayDeque<>();
    private BlockListener listener;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        mockBaseEnvironment(Logger.getAnonymousLogger());
        listener = new BlockListener(mcMMO.p);
        final UUID worldId = UUID.randomUUID();
        when(world.getUID()).thenReturn(worldId);
        when(server.getWorld(worldId)).thenReturn(world);
        when(world.getMinHeight()).thenReturn(-64);
        when(world.getMaxHeight()).thenReturn(320);
        when(world.isChunkLoaded(0, 0)).thenReturn(true);
        when(foliaLib.getScheduler().isOwnedByCurrentRegion(any(Block.class))).thenReturn(true);
        when(foliaLib.getScheduler().runAtLocationLater(any(Location.class), any(Consumer.class), anyLong()))
                .thenAnswer(call -> {
                    callbacks.add(call.getArgument(1));
                    return null;
                });
        when(chunkManager.isIneligible(any(Block.class)))
                .thenAnswer(call -> placed.contains(call.getArgument(0)));
        doAnswer(call -> {
            placed.add(call.getArgument(0));
            return null;
        }).when(chunkManager).setIneligible(any(Block.class));
        doAnswer(call -> {
            placed.remove(call.getArgument(0));
            return null;
        }).when(chunkManager).setEligible(any(Block.class));
    }

    @AfterEach
    void tearDown() {
        cleanUpStaticMocks();
    }

    @Test
    void deniedSinglePlaceDoesNotMarkTheBlock() {
        final Block block = blockAt(1, 64, Material.STONE);
        final BlockPlaceEvent place = new BlockPlaceEvent(block, state(block), block, null, player, false);

        listener.onBlockPlace(place);

        assertEquals(Set.of(), placed);
    }

    @Test
    void lateCancellationRestoresTheMarkCapturedBeforeTheTemporaryWrite() {
        final Block block = blockAt(1, 64, Material.STONE);
        final BlockPlaceEvent place = new BlockPlaceEvent(block, state(block), block, null, player, true);
        final HashChunkManager storage = mock(HashChunkManager.class);
        final Runnable restoreMark = mock(Runnable.class);
        when(mcMMO.getUserBlockTracker()).thenReturn(storage);
        when(storage.capturePlacementMark(block)).thenReturn(restoreMark);

        listener.onBlockPlace(place);
        place.setCancelled(true);
        runChecks();

        final var order = inOrder(storage, restoreMark);
        order.verify(storage).capturePlacementMark(block);
        order.verify(storage).setIneligible(block);
        order.verify(restoreMark).run();
        order.verifyNoMoreInteractions();
    }

    @Test
    void multiPlaceSkipsOnlyPositionsOutsideWorldHeight() {
        final Block lower = blockAt(1, 319, Material.OAK_DOOR);
        final Block upper = blockAt(1, 320, Material.OAK_DOOR);
        final BlockMultiPlaceEvent place = new BlockMultiPlaceEvent(List.of(state(lower), state(upper)),
                lower, null, player, true);

        listener.onBlockMultiPlace(place);

        assertEquals(Set.of(lower), placed);
    }

    @Test
    void pistonBackfillUsesIncomingBlocksOriginalMarkers() {
        when(experienceConfigInstance.isPistonCheatingPrevented()).thenReturn(true);
        final Block piston = blockAt(0, 64, Material.PISTON);
        final Block naturalSource = blockAt(1, 64, Material.STONE);
        final Block placedSource = blockAt(2, 64, Material.DIRT);
        final Block emptyDestination = blockAt(3, 64, Material.AIR);
        when(naturalSource.getRelative(BlockFace.EAST)).thenReturn(placedSource);
        when(placedSource.getRelative(BlockFace.EAST)).thenReturn(emptyDestination);
        placed.add(placedSource);

        listener.onBlockPistonExtend(new BlockPistonExtendEvent(piston,
                List.of(naturalSource, placedSource), BlockFace.EAST));
        when(naturalSource.getType()).thenReturn(Material.PISTON_HEAD);
        when(placedSource.getType()).thenReturn(Material.STONE);
        when(emptyDestination.getType()).thenReturn(Material.DIRT);
        runChecks();

        assertEquals(Set.of(emptyDestination), placed);
    }

    @Test
    void explosionClearsOnlyAirStillOnFinalList() {
        final Block origin = blockAt(0, 64, Material.AIR);
        final Block destroyed = blockAt(1, 64, Material.GLASS);
        final Block removedFromList = blockAt(2, 64, Material.AIR);
        final Block replaced = blockAt(3, 64, Material.DIRT);
        placed.addAll(List.of(destroyed, removedFromList, replaced));
        final BlockExplodeEvent event = new BlockExplodeEvent(origin, state(origin),
                new ArrayList<>(List.of(destroyed, removedFromList, replaced)), 1);

        listener.onBlockExplode(event);
        event.blockList().remove(removedFromList);
        when(destroyed.getType()).thenReturn(Material.AIR);
        runChecks();

        assertEquals(Set.of(removedFromList, replaced), placed);
    }

    @Test
    void explosionHonorsCancellationAfterMonitorHandler() {
        final Block origin = blockAt(0, 64, Material.AIR);
        final Block protectedBlock = blockAt(1, 64, Material.GLASS);
        placed.add(protectedBlock);
        final BlockExplodeEvent event = new BlockExplodeEvent(origin, state(origin),
                List.of(protectedBlock), 1);

        listener.onBlockExplode(event);
        event.setCancelled(true);
        runChecks();

        assertEquals(Set.of(protectedBlock), placed);
    }

    @Test
    @SuppressWarnings("unchecked")
    void explosionDispatchesOneCheckForTwoBlocksInTheSameRemoteChunk() {
        final Block origin = blockAt(0, 64, Material.AIR);
        final Block first = blockAt(16, 64, Material.AIR);
        final Block second = blockAt(17, 64, Material.AIR);
        placed.addAll(List.of(first, second));
        when(world.isChunkLoaded(1, 0)).thenReturn(true);
        when(foliaLib.getScheduler().isOwnedByCurrentRegion(first)).thenReturn(false);
        when(foliaLib.getScheduler().isOwnedByCurrentRegion(second)).thenReturn(false);
        final Queue<Consumer<WrappedTask>> remoteCallbacks = new ArrayDeque<>();
        when(foliaLib.getScheduler().runAtLocation(any(Location.class), any(Consumer.class)))
                .thenAnswer(call -> {
                    remoteCallbacks.add(call.getArgument(1));
                    return null;
                });
        final BlockExplodeEvent event = new BlockExplodeEvent(origin, state(origin), List.of(first, second), 1);

        listener.onBlockExplode(event);
        runChecks();

        assertEquals(Set.of(first, second), placed);
        assertEquals(1, remoteCallbacks.size());
        remoteCallbacks.remove().accept(mock(WrappedTask.class));
        assertEquals(Set.of(), placed);
    }

    private Block blockAt(int x, int y, Material material) {
        final Block block = mock(Block.class);
        when(block.getWorld()).thenReturn(world);
        when(block.getX()).thenReturn(x);
        when(block.getY()).thenReturn(y);
        when(block.getLocation()).thenReturn(new Location(world, x, y, 0));
        when(block.getType()).thenReturn(material);
        return block;
    }

    private BlockState state(Block block) {
        final BlockState state = mock(BlockState.class);
        when(state.getBlock()).thenReturn(block);
        return state;
    }

    private void runChecks() {
        while (!callbacks.isEmpty()) {
            callbacks.remove().accept(mock(WrappedTask.class));
        }
    }
}
