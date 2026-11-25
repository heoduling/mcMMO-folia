package com.gmail.nossr50.listeners;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.gmail.nossr50.MMOTestEnvironment;
import com.gmail.nossr50.mcMMO;
import com.gmail.nossr50.util.BlockUtils;
import com.tcoded.folialib.wrapper.task.WrappedTask;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.logging.Logger;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.event.Cancellable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockFadeEvent;
import org.bukkit.event.block.LeavesDecayEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

class BlockListenerDestructionTest extends MMOTestEnvironment {
    private Block block;
    private BlockListener listener;
    private MockedStatic<BlockUtils> blockUtils;

    @BeforeEach
    void setUp() {
        mockBaseEnvironment(Logger.getAnonymousLogger());
        listener = new BlockListener(mcMMO.p);
        block = mock(Block.class);
        when(block.getWorld()).thenReturn(world);
        when(block.getX()).thenReturn(-17);
        when(block.getZ()).thenReturn(33);
        when(block.getLocation()).thenReturn(new Location(world, -17, 64, 33));
        final UUID worldId = UUID.randomUUID();
        when(world.getUID()).thenReturn(worldId);
        when(server.getWorld(worldId)).thenReturn(world);
        when(world.isChunkLoaded(-2, 2)).thenReturn(true);
        when(block.getType()).thenReturn(Material.AIR);
        when(chunkManager.isIneligible(block)).thenReturn(true);
        blockUtils = mockStatic(BlockUtils.class);
        blockUtils.when(() -> BlockUtils.isWithinWorldBounds(block)).thenReturn(true);
    }

    @AfterEach
    void tearDown() {
        blockUtils.close();
        cleanUpStaticMocks();
    }

    private Cancellable event(String method) {
        return switch (method) {
            case "onBlockBurn" -> new BlockBurnEvent(block, null);
            case "onBlockFade" -> new BlockFadeEvent(block, mock(BlockState.class));
            case "onLeavesDecay" -> new LeavesDecayEvent(block);
            default -> throw new IllegalArgumentException(method);
        };
    }

    private void dispatch(String name, Cancellable event) throws Exception {
        var method = BlockListener.class.getMethod(name, event.getClass());
        EventHandler handler = method.getAnnotation(EventHandler.class);
        assertNotNull(handler);
        assertEquals(EventPriority.MONITOR, handler.priority());
        assertTrue(handler.ignoreCancelled());
        method.invoke(listener, event);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void runDeferredCheck() {
        ArgumentCaptor<Consumer<WrappedTask>> callback = ArgumentCaptor.forClass((Class) Consumer.class);
        verify(foliaLib.getScheduler()).runAtLocationLater(eq(block.getLocation()), callback.capture(), eq(1L));
        callback.getValue().accept(mock(WrappedTask.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"onBlockBurn", "onBlockFade", "onLeavesDecay"})
    void destroyedBlockClearsOnlyAfterDeferredCheck(String method) throws Exception {
        dispatch(method, event(method));
        blockUtils.verify(() -> BlockUtils.cleanupBlockMetadata(block), never());
        runDeferredCheck();
        blockUtils.verify(() -> BlockUtils.cleanupBlockMetadata(block));
    }

    @ParameterizedTest
    @ValueSource(strings = {"onBlockBurn", "onBlockFade", "onLeavesDecay"})
    void cancelledDestructionKeepsMarker(String method) throws Exception {
        Cancellable event = event(method);
        event.setCancelled(true);
        dispatch(method, event);
        verify(foliaLib.getScheduler(), never()).runAtLocationLater(any(), any(Consumer.class), anyLong());
        blockUtils.verify(() -> BlockUtils.cleanupBlockMetadata(block), never());
    }

    @ParameterizedTest
    @ValueSource(strings = {"onBlockBurn", "onBlockFade", "onLeavesDecay"})
    void nonAirResultKeepsMarker(String method) throws Exception {
        dispatch(method, event(method));
        when(block.getType()).thenReturn(Material.DIRT);
        runDeferredCheck();
        blockUtils.verify(() -> BlockUtils.cleanupBlockMetadata(block), never());
    }

    @ParameterizedTest
    @ValueSource(strings = {"onBlockBurn", "onBlockFade", "onLeavesDecay"})
    void lateCancellationKeepsMarker(String method) throws Exception {
        Cancellable event = event(method);
        dispatch(method, event);
        event.setCancelled(true);
        runDeferredCheck();
        blockUtils.verify(() -> BlockUtils.cleanupBlockMetadata(block), never());
    }

    @ParameterizedTest
    @ValueSource(strings = {"onBlockBurn", "onBlockFade", "onLeavesDecay"})
    void unloadedChunkIsNotAccessed(String method) throws Exception {
        dispatch(method, event(method));
        when(world.isChunkLoaded(-2, 2)).thenReturn(false);
        runDeferredCheck();
        verify(block, never()).getType();
        blockUtils.verify(() -> BlockUtils.cleanupBlockMetadata(block), never());
    }

    @ParameterizedTest
    @ValueSource(strings = {"onBlockBurn", "onBlockFade", "onLeavesDecay"})
    void unloadedWorldIsNotAccessed(String method) throws Exception {
        dispatch(method, event(method));
        when(server.getWorld(world.getUID())).thenReturn(null);
        runDeferredCheck();
        verify(block, never()).getType();
        blockUtils.verify(() -> BlockUtils.cleanupBlockMetadata(block), never());
    }

    @ParameterizedTest
    @ValueSource(strings = {"onBlockBurn", "onBlockFade", "onLeavesDecay"})
    void naturalBlockSchedulesNoCleanup(String method) throws Exception {
        when(chunkManager.isIneligible(block)).thenReturn(false);
        dispatch(method, event(method));
        verify(foliaLib.getScheduler(), never()).runAtLocationLater(any(), any(Consumer.class), anyLong());
    }

    @ParameterizedTest
    @ValueSource(strings = {"onBlockBurn", "onBlockFade", "onLeavesDecay"})
    void outsideWorldBoundsDoesNotReadTracker(String method) throws Exception {
        blockUtils.when(() -> BlockUtils.isWithinWorldBounds(block)).thenReturn(false);
        dispatch(method, event(method));
        verify(chunkManager, never()).isIneligible(block);
        verify(foliaLib.getScheduler(), never()).runAtLocationLater(any(), any(Consumer.class), anyLong());
    }
}
