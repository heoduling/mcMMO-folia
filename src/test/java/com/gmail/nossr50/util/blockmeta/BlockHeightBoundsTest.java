package com.gmail.nossr50.util.blockmeta;

import static com.gmail.nossr50.util.MetadataConstants.METADATA_KEY_REPLANT;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.gmail.nossr50.MMOTestEnvironment;
import com.gmail.nossr50.listeners.BlockListener;
import com.gmail.nossr50.mcMMO;
import com.gmail.nossr50.util.BlockUtils;
import com.gmail.nossr50.util.player.UserManager;
import java.io.File;
import java.util.UUID;
import java.util.logging.Logger;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class BlockHeightBoundsTest extends MMOTestEnvironment {
    @BeforeEach
    void setUp() {
        mockBaseEnvironment(Logger.getAnonymousLogger());
        when(world.getMinHeight()).thenReturn(-64);
        when(world.getMaxHeight()).thenReturn(640);
        when(world.getUID()).thenReturn(UUID.randomUUID());
        when(world.getWorldFolder()).thenReturn(new File(testDataFolder, "world"));
        when(player.getGameMode()).thenReturn(GameMode.SURVIVAL);
        when(playerInventory.getItemInMainHand()).thenReturn(new ItemStack(Material.AIR));
    }

    @AfterEach
    void tearDown() {
        cleanUpStaticMocks();
    }

    private Block block(int y) {
        Block block = mock(Block.class);
        when(block.getWorld()).thenReturn(world);
        when(block.getX()).thenReturn(6);
        when(block.getY()).thenReturn(y);
        when(block.getZ()).thenReturn(1);
        when(block.getLocation()).thenReturn(new Location(world, 6, y, 1));
        when(block.getType()).thenReturn(Material.STONE);
        when(block.hasMetadata(METADATA_KEY_REPLANT)).thenReturn(true);
        return block;
    }

    private BlockState state(int y) {
        BlockState state = mock(BlockState.class);
        when(state.getWorld()).thenReturn(world);
        when(state.getX()).thenReturn(6);
        when(state.getY()).thenReturn(y);
        when(state.getZ()).thenReturn(1);
        return state;
    }

    @ParameterizedTest
    @ValueSource(ints = {-66, -65, -64, -63, 639, 640, 641})
    void cleanupRemovesMetadataAndOnlyTouchesValidStoreCoordinates(int y) {
        Block block = block(y);
        BlockUtils.cleanupBlockMetadata(block);
        verify(block).removeMetadata(METADATA_KEY_REPLANT, mcMMO.p);
        verify(chunkManager, times(y >= -64 && y < 640 ? 1 : 0)).setEligible(block);
    }

    @ParameterizedTest
    @ValueSource(ints = {-66, -65, 640, 641})
    void invalidPublicTrackerAccessIsSafeAndDoesNotLoadStorage(int y) {
        HashChunkManager manager = new HashChunkManager();
        Block block = block(y);
        BlockState state = state(y);
        clearInvocations(world);
        assertDoesNotThrow(() -> {
            manager.setIneligible(block);
            manager.setEligible(block);
            manager.setIneligible(state);
            manager.setEligible(state);
        });
        assertTrue(manager.isIneligible(block));
        assertTrue(manager.isIneligible(state));
        assertFalse(manager.isEligible(block));
        assertFalse(manager.isEligible(state));
        verify(world, never()).getWorldFolder();
        verify(world, never()).getUID();
    }

    @ParameterizedTest
    @ValueSource(ints = {-64, -63, 639})
    void validPublicTrackerAccessRetainsBlockAndStateRoundTrips(int y) {
        HashChunkManager manager = new HashChunkManager();
        Block block = block(y);
        BlockState state = state(y);
        assertTrue(manager.isEligible(block));
        manager.setIneligible(block);
        assertTrue(manager.isIneligible(state));
        manager.setEligible(state);
        assertTrue(manager.isEligible(block));
        manager.setIneligible(state);
        assertTrue(manager.isIneligible(block));
        manager.setEligible(block);
        assertTrue(manager.isEligible(state));
    }

    @ParameterizedTest
    @ValueSource(ints = {-66, -65, 640, 641})
    void rawStoreRejectsBothExclusiveBoundariesBeforeMutation(int y) {
        BitSetChunkStore store = new BitSetChunkStore(world, 0, 0);
        assertThrows(IndexOutOfBoundsException.class, () -> store.setTrue(6, y, 1));
        assertThrows(IndexOutOfBoundsException.class, () -> store.setFalse(6, y, 1));
        assertThrows(IndexOutOfBoundsException.class, () -> store.isTrue(6, y, 1));
        assertTrue(store.isEmpty());
        assertFalse(store.isDirty());
    }

    @ParameterizedTest
    @ValueSource(ints = {-64, -63, 639})
    void rawStoreRetainsLegalBoundaryMarkers(int y) {
        BitSetChunkStore store = new BitSetChunkStore(world, 0, 0);
        store.setTrue(6, y, 1);
        assertTrue(store.isTrue(6, y, 1));
        store.setFalse(6, y, 1);
        assertFalse(store.isTrue(6, y, 1));
    }

    @ParameterizedTest
    @ValueSource(ints = {-66, -65, 640, 641})
    void invalidBreakStopsBeforeGameplayAndCleansOnlyTransientMetadata(int y) {
        Block block = block(y);
        BlockBreakEvent event = new BlockBreakEvent(block, player);
        BlockListener listener = new BlockListener(mcMMO.p);
        listener.onBlockBreakHigher(event);
        listener.onBlockBreak(event);
        verify(block, never()).getType();
        verify(block).removeMetadata(METADATA_KEY_REPLANT, mcMMO.p);
        verifyNoInteractions(chunkManager);
        assertFalse(event.isCancelled());
    }

    @ParameterizedTest
    @ValueSource(ints = {-64, -63, 639})
    void validBreakStillClearsMarkerAtBoundary(int y) {
        Block block = block(y);
        when(UserManager.hasPlayerDataKey(player)).thenReturn(false);
        BlockListener listener = new BlockListener(mcMMO.p);
        BlockBreakEvent event = new BlockBreakEvent(block, player);
        listener.onBlockBreakHigher(event);
        listener.onBlockBreak(event);
        verify(chunkManager).setEligible(block);
        verify(block).removeMetadata(METADATA_KEY_REPLANT, mcMMO.p);
        assertFalse(event.isCancelled());
    }
}
