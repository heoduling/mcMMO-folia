package com.gmail.nossr50.listeners;

import com.gmail.nossr50.config.WorldBlacklist;
import com.gmail.nossr50.mcMMO;
import com.gmail.nossr50.util.blockmeta.HashChunkManager;
import java.io.File;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.event.world.WorldUnloadEvent;

public class ChunkListener implements Listener {
    private static final int CHUNKS_PER_PASS = 8;
    private static final int MARKERS_PER_CHUNK = 128;
    private static final long MAX_RETRY_SECONDS = 60;
    private final Map<ChunkKey, Scan> loaded = new HashMap<>();
    private final Set<Scan> pending = new LinkedHashSet<>();
    private final Map<ChunkKey, Scan> active = new HashMap<>();
    private HashChunkManager manager;

    public void startReconciliation(mcMMO plugin) {
        if (!(mcMMO.getChunkManager() instanceof HashChunkManager tracker)) {
            return;
        }
        synchronized (this) {
            if (manager != null) {
                return;
            }
            manager = tracker;
        }
        for (World world : plugin.getServer().getWorlds()) {
            if (!WorldBlacklist.isWorldBlacklisted(world)) {
                for (Chunk chunk : world.getLoadedChunks()) {
                    track(chunk);
                }
            }
        }
        tracker.configureReadRecovery(recovery ->
                plugin.getFoliaLib().getScheduler().runAsync(task -> recovery.run()), this::hasScan);
        plugin.getFoliaLib().getScheduler().runTimer(task -> dispatch(plugin, tracker), 20L, 20L);
    }

    private synchronized boolean hasScan(UUID world, int x, int z) {
        return manager != null && loaded.containsKey(new ChunkKey(world, x, z));
    }

    public synchronized void stopReconciliation() {
        manager = null;
        loaded.clear();
        pending.clear();
        active.clear();
    }

    private synchronized void track(Chunk chunk) {
        if (manager == null) {
            return;
        }
        final ChunkKey key = new ChunkKey(chunk.getWorld().getUID(), chunk.getX(), chunk.getZ());
        loaded.computeIfAbsent(key, ignored -> {
            final Scan scan = new Scan(chunk, key);
            pending.add(scan);
            return scan;
        });
    }

    private synchronized Scan poll(HashChunkManager tracker) {
        if (manager != tracker || active.size() >= CHUNKS_PER_PASS || pending.isEmpty()) {
            return null;
        }
        final long now = System.nanoTime();
        final var iterator = pending.iterator();
        while (iterator.hasNext()) {
            final Scan scan = iterator.next();
            if (scan.retryDelaySeconds > 0 && scan.retryAtNanos - now > 0) {
                continue;
            }
            if (active.putIfAbsent(scan.key, scan) == null) {
                iterator.remove();
                return scan;
            }
        }
        return null;
    }

    private synchronized boolean current(Scan scan, HashChunkManager tracker) {
        return manager == tracker && loaded.get(scan.key) == scan;
    }

    private synchronized void finish(Scan scan, HashChunkManager tracker, boolean requeue) {
        active.remove(scan.key, scan);
        if (current(scan, tracker)) {
            if (requeue) {
                pending.add(scan);
            } else {
                loaded.remove(scan.key);
            }
        }
    }

    private void dispatch(mcMMO plugin, HashChunkManager tracker) {
        for (int i = 0; i < CHUNKS_PER_PASS; i++) {
            final Scan scan = poll(tracker);
            if (scan == null) {
                return;
            }
            if (!scan.chunk.isLoaded()) {
                finish(scan, tracker, false);
                continue;
            }
            try {
                plugin.getFoliaLib().getScheduler().runAtLocation(scan.location,
                        task -> visit(plugin, tracker, scan));
            } catch (RuntimeException ex) {
                finish(scan, tracker, true);
                throw ex;
            }
        }
    }

    private void visit(mcMMO plugin, HashChunkManager tracker, Scan scan) {
        if (!current(scan, tracker) || !scan.chunk.isLoaded()) {
            finish(scan, tracker, false);
            return;
        }
        if (scan.prepared) {
            if (!tracker.needsReadRecovery(scan.key.world, scan.key.x, scan.key.z)) {
                scanLoadedChunk(tracker, scan, null);
                return;
            }
            scan.prepared = false;
            scan.index = 0;
        }

        final World world = scan.chunk.getWorld();
        final File folder = world.getWorldFolder();
        final int minHeight = world.getMinHeight();
        final int maxHeight = world.getMaxHeight();
        try {
            plugin.getFoliaLib().getScheduler().runAsync(task -> {
                if (!current(scan, tracker)) {
                    finish(scan, tracker, false);
                    return;
                }
                final HashChunkManager.ReconciliationLoad load = tracker.prepareReconciliation(
                        scan.key.world, folder, minHeight, maxHeight, scan.key.x, scan.key.z);
                if (!current(scan, tracker) || !scan.chunk.isLoaded()) {
                    tracker.discardReconciliation(load);
                    finish(scan, tracker, false);
                    return;
                }
                try {
                    plugin.getFoliaLib().getScheduler().runAtLocation(scan.location,
                            callback -> scanLoadedChunk(tracker, scan, load));
                } catch (RuntimeException ex) {
                    tracker.discardReconciliation(load);
                    finish(scan, tracker, true);
                    throw ex;
                }
            });
        } catch (RuntimeException ex) {
            finish(scan, tracker, true);
            throw ex;
        }
    }

    private void scanLoadedChunk(HashChunkManager tracker, Scan scan, HashChunkManager.ReconciliationLoad load) {
        final boolean loadedNow = current(scan, tracker) && scan.chunk.isLoaded();
        try {
            if (!loadedNow) {
                return;
            }
            if (scan.prepared || tracker.completeReconciliation(load)) {
                scan.prepared = true;
                scan.retryDelaySeconds = 0;
                scan.index = tracker.reconcileAir(scan.chunk, scan.index, MARKERS_PER_CHUNK);
            } else if (load != null && load.readFailure() != null) {
                if (scan.retryDelaySeconds == 0) {
                    mcMMO.p.getLogger().warning(load.readFailure() + " (will retry with backoff)");
                }
                scan.retryDelaySeconds = Math.min(MAX_RETRY_SECONDS, Math.max(1, scan.retryDelaySeconds * 2));
                scan.retryAtNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(scan.retryDelaySeconds);
            }
        } finally {
            tracker.discardReconciliation(load);
            finish(scan, tracker, loadedNow);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkLoad(ChunkLoadEvent event) {
        if (!WorldBlacklist.isWorldBlacklisted(event.getWorld())) {
            track(event.getChunk());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public synchronized void onWorldUnload(WorldUnloadEvent event) {
        final UUID world = event.getWorld().getUID();
        loaded.values().removeIf(scan -> scan.key.world.equals(world));
        pending.removeIf(scan -> scan.key.world.equals(world));
        active.keySet().removeIf(key -> key.world.equals(world));
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onChunkUnload(ChunkUnloadEvent event) {
        final Chunk unloadingChunk = event.getChunk();
        if (unloadingChunk == null) {
            return;
        }
        synchronized (this) {
            final Scan old = loaded.remove(new ChunkKey(event.getWorld().getUID(),
                    unloadingChunk.getX(), unloadingChunk.getZ()));
            pending.remove(old);
        }

        // Avoid processing if chunk is null or unloaded
        if (!unloadingChunk.isLoaded()) {
            return;
        }

        // Copies the entity list, so only fetch it once
        final Entity[] entities = unloadingChunk.getEntities();
        if (entities == null) {
            return;
        }

        try {
            for (Entity entity : entities) {
                if (entity instanceof LivingEntity livingEntity) {
                    mcMMO.getTransientEntityTracker().removeTrackedEntity(livingEntity);
                }
            }
        } catch (Exception ex) {
            mcMMO.p.getLogger().warning(
                    "Caught exception during chunk unload event processing: " + ex);
        }
    }

    private record ChunkKey(UUID world, int x, int z) {
    }

    private static final class Scan {
        private final Chunk chunk;
        private final ChunkKey key;
        private final Location location;
        private boolean prepared;
        private int index;
        private long retryDelaySeconds;
        private long retryAtNanos;

        private Scan(Chunk chunk, ChunkKey key) {
            this.chunk = chunk;
            this.key = key;
            location = new Location(chunk.getWorld(), (double) key.x * 16, 0, (double) key.z * 16);
        }
    }
}
