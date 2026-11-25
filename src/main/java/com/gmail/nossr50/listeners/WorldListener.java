package com.gmail.nossr50.listeners;

import com.gmail.nossr50.config.PersistentDataConfig;
import com.gmail.nossr50.config.WorldBlacklist;
import com.gmail.nossr50.mcMMO;
import com.gmail.nossr50.util.BlockUtils;
import com.gmail.nossr50.util.blockmeta.McMMORegionBackupStore;
import com.gmail.nossr50.util.blockmeta.NullChunkManager;
import com.gmail.nossr50.util.blockmeta.PendingBlockChanges;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockFertilizeEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.event.world.StructureGrowEvent;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.event.world.WorldUnloadEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class WorldListener implements Listener {
    private final mcMMO plugin;

    public WorldListener(final mcMMO plugin) {
        this.plugin = plugin;
    }

    /**
     * Record the growth dispatch before other plugins can change the pending blocks.
     *
     * @param event The event to watch
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onStructureGrow(StructureGrowEvent event) {
        /* WORLD BLACKLIST CHECK */
        if (WorldBlacklist.isWorldBlacklisted(event.getWorld())) {
            return;
        }

        final World world = event.getWorld();
        if (mcMMO.getUserBlockTracker() instanceof NullChunkManager) {
            return;
        }
        final PendingBlockChanges changes = plugin.getPendingBlockChanges();
        final var scheduler = plugin.getFoliaLib().getScheduler();
        final Location origin = event.getLocation();
        final PendingBlockChanges.Growth growth = changes.beginGrowth(event,
                () -> scheduler.isOwnedByCurrentRegion(origin));
        for (BlockState state : event.getBlocks()) {
            changes.watchGrowth(growth, state);
        }
        try {
            scheduler.runAtLocationLater(origin, task -> {
                if (Bukkit.getWorld(world.getUID()) != world
                        || !world.isChunkLoaded(origin.getBlockX() >> 4, origin.getBlockZ() >> 4)) {
                    changes.cancelGrowth(growth);
                    return;
                }
                final Map<ChunkPosition, List<GeneratedBlock>> byChunk = new HashMap<>();
                changes.resolveGrowth(growth).forEach((state, ticket) -> {
                    final Block block = state.getBlock();
                    byChunk.computeIfAbsent(new ChunkPosition(state.getX() >> 4, state.getZ() >> 4),
                            ignored -> new ArrayList<>()).add(new GeneratedBlock(state, block, ticket));
                });
                byChunk.forEach((chunk, states) -> {
                    final Location first = states.get(0).state.getLocation();
                    if (scheduler.isOwnedByCurrentRegion(first)) {
                        clearGeneratedBlocks(world, chunk, states, changes);
                    } else if (world.isChunkLoaded(chunk.x(), chunk.z())) {
                        scheduler.runAtLocation(first,
                                callback -> clearGeneratedBlocks(world, chunk, states, changes));
                    } else {
                        finishGeneration(changes, states);
                    }
                });
            }, 1L);
        } catch (RuntimeException ex) {
            changes.cancelGrowth(growth);
            throw ex;
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onBlockFertilize(BlockFertilizeEvent event) {
        plugin.getPendingBlockChanges().beginFertilize(event);
    }

    private void clearGeneratedBlocks(World world, ChunkPosition chunk, List<GeneratedBlock> states,
            PendingBlockChanges changes) {
        if (Bukkit.getWorld(world.getUID()) != world || !world.isChunkLoaded(chunk.x(), chunk.z())) {
            finishGeneration(changes, states);
            return;
        }
        final var tracker = mcMMO.getUserBlockTracker();
        for (GeneratedBlock generated : states) {
            try {
                changes.settlePlacement(generated.ticket, generated.block, tracker);
                if (changes.mayApply(generated.ticket) && BlockUtils.isWithinWorldBounds(generated.block)
                        && generated.block.getType() == generated.state.getType()) {
                    tracker.setEligible(generated.block);
                }
            } finally {
                changes.finish(generated.ticket, true);
            }
        }
    }

    private void finishGeneration(PendingBlockChanges changes, List<GeneratedBlock> states) {
        states.forEach(generated -> changes.finish(generated.ticket, false));
    }

    private record GeneratedBlock(BlockState state, Block block, PendingBlockChanges.Ticket ticket) {
    }

    private record ChunkPosition(int x, int z) {
    }

    /**
     * Restores mcMMO block-tracker data from the backup store for any world that loads after
     * plugin enable (Multiverse worlds, lazy-loaded dimensions). Only runs when Paper 26.1+ has
     * reshaped the world and the in-world {@code mcmmo_regions/} folder is empty; a no-op in
     * all other cases.
     *
     * @param event The event to watch
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onWorldLoad(WorldLoadEvent event) {
        if (!PersistentDataConfig.getInstance().useBlockTracker()
                || !plugin.getGeneralConfig().getRegionDataMigrationBackupsEnabled()) {
            return;
        }
        if (WorldBlacklist.isWorldBlacklisted(event.getWorld())) {
            return;
        }
        McMMORegionBackupStore.restoreWorld(event.getWorld(), plugin.getLogger(),
                plugin.getDataFolder().toPath());
    }

    /**
     * Flushes chunk-store data for the unloading world and, on Spigot / pre-26.1 Paper layouts
     * (the "legacy shape"), writes a backup snapshot into the mcMMO plugin data directory so
     * the block-tracker data survives if a future Paper upgrade deletes the world's old folder
     * layout. Skipped for worlds already on the Paper 26.1+ layout and for blacklisted worlds.
     *
     * @param event The event to watch
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onWorldUnload(WorldUnloadEvent event) {
        plugin.getPendingBlockChanges().forgetWorld(event.getWorld(), mcMMO.getUserBlockTracker());
        /* WORLD BLACKLIST CHECK */
        if (WorldBlacklist.isWorldBlacklisted(event.getWorld())) {
            return;
        }

        mcMMO.getChunkManager().unloadWorld(event.getWorld());

        if (PersistentDataConfig.getInstance().useBlockTracker()
            && plugin.getGeneralConfig().getRegionDataMigrationBackupsEnabled()) {
            McMMORegionBackupStore.backupWorld(event.getWorld(), plugin.getLogger(),
                    plugin.getDataFolder().toPath());
        }
    }

    /**
     * Monitor ChunkUnload events.
     *
     * @param event The event to watch
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChunkUnload(ChunkUnloadEvent event) {
        final Chunk chunk = event.getChunk();
        plugin.getPendingBlockChanges().forgetChunk(event.getWorld(), chunk.getX(), chunk.getZ(),
                mcMMO.getUserBlockTracker());
        /* WORLD BLACKLIST CHECK */
        if (WorldBlacklist.isWorldBlacklisted(event.getWorld())) {
            return;
        }

        mcMMO.getChunkManager().chunkUnloaded(chunk.getX(), chunk.getZ(), event.getWorld());
    }
}
