package com.gmail.nossr50.util.blockmeta;

import com.gmail.nossr50.mcMMO;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class HashChunkManager implements ChunkManager {
    private static final int MAX_FOREGROUND_RECOVERIES = 8;
    private final HashMap<CoordinateKey, McMMOSimpleRegionFile> regionMap = new HashMap<>(); // Tracks active regions
    private final HashMap<CoordinateKey, HashSet<CoordinateKey>> chunkUsageMap = new HashMap<>(); // Tracks active chunks by region
    private final HashMap<CoordinateKey, ChunkStore> chunkMap = new HashMap<>(); // Tracks active chunks
    private final HashMap<CoordinateKey, ReconciliationLoad> reconciliationLoads = new HashMap<>();
    private final Map<CoordinateKey, FailedRead> failedReads = new HashMap<>();
    private boolean reconciliationClosed;
    private Consumer<Runnable> recoveryExecutor;
    private ScanCoverage scanCoverage;
    private int foregroundRecoveries;

    @FunctionalInterface
    public interface ScanCoverage {
        boolean covers(UUID worldId, int x, int z);
    }

    /** Schedule foreground-triggered recovery when the normal scan does not own the chunk. */
    public synchronized void configureReadRecovery(Consumer<Runnable> executor, ScanCoverage coverage) {
        recoveryExecutor = executor;
        scanCoverage = coverage;
    }

    @Override
    public synchronized void closeAll() {
        reconciliationClosed = true;
        reconciliationLoads.clear();
        recoveryExecutor = null;
        scanCoverage = null;
        // Save all dirty chunkstores; one failing chunk must not abort the rest of shutdown
        for (ChunkStore chunkStore : chunkMap.values()) {
            if (!chunkStore.isDirty()) {
                continue;
            }
            World world = Bukkit.getWorld(chunkStore.getWorldId());
            if (world == null) {
                continue; // Oh well
            }
            try {
                writeChunkStore(world, chunkStore);
            } catch (Exception e) {
                logChunkSaveFailure(chunkStore.getChunkX(), chunkStore.getChunkZ(),
                        world.getName(), e);
            }
        }
        for (var entry : failedReads.entrySet()) {
            final FailedRead failure = entry.getValue();
            final int changeCount = failure.changes.size();
            if (changeCount == 0) {
                continue;
            }
            final CoordinateKey key = entry.getKey();
            try {
                // The async scanner is already stopped. Give each outstanding change one
                // final attempt against the actual baseline without waiting for a retry.
                final ChunkStore store = readChunkStore(failure.worldFolder, failure.bounds, key);
                writeChunkStore(failure.worldFolder, mergeChanges(key, failure, store));
            } catch (IOException | RuntimeException ex) {
                mcMMO.p.getLogger().warning("Unable to recover placed-block data for chunk ("
                        + key.x() + ", " + key.z() + ") in world '" + failure.worldName
                        + "' at shutdown. Unsaved marker changes: " + changeCount + ": " + ex);
            }
        }
        failedReads.clear();
        // Clear in memory chunks
        chunkMap.clear();
        chunkUsageMap.clear();
        // Close all region files
        for (McMMOSimpleRegionFile rf : regionMap.values()) {
            closeQuietly(rf);
        }
        regionMap.clear();
    }

    private static void logChunkSaveFailure(int cx, int cz, @NotNull String worldName,
            @NotNull Exception e) {
        mcMMO.p.getLogger().warning("Failed to save placed-block data for chunk (" + cx + ", "
                + cz + ") in world '" + worldName + "': " + e);
    }

    private static void closeQuietly(@Nullable McMMOSimpleRegionFile regionFile) {
        if (regionFile == null) {
            return;
        }
        try {
            regionFile.close();
        } catch (Exception e) {
            mcMMO.p.getLogger().warning("Failed to close placed-block region file: " + e);
        }
    }

    private @Nullable ChunkStore readChunkStore(File worldFolder, BitSetChunkStore.WorldBounds bounds,
            CoordinateKey chunkKey) throws IOException {
        final CoordinateKey regionKey = toRegionKey(chunkKey.worldID(), chunkKey.x(), chunkKey.z());
        final McMMOSimpleRegionFile open = regionMap.get(regionKey);
        if (open == null) {
            return readDiskChunk(getRegionFile(worldFolder, regionKey), bounds, chunkKey);
        }
        try (DataInputStream input = open.getInputStream(chunkKey.x(), chunkKey.z())) {
            return decodeChunk(input, bounds, chunkKey);
        }
    }

    private static @Nullable ChunkStore readDiskChunk(File file, BitSetChunkStore.WorldBounds bounds,
            CoordinateKey key) throws IOException {
        try (DataInputStream input = McMMOSimpleRegionFile.readChunkData(file, key.x(), key.z())) {
            return decodeChunk(input, bounds, key);
        }
    }

    private static @Nullable ChunkStore decodeChunk(@Nullable DataInputStream input,
            BitSetChunkStore.WorldBounds bounds, CoordinateKey key) throws IOException {
        if (input == null) {
            return null;
        }
        final ChunkStore store = BitSetChunkStore.Serialization.readChunkStore(input, bounds);
        if (store == null) {
            throw new IOException("Unable to decode stored chunk data");
        }
        if (store.getChunkX() != key.x() || store.getChunkZ() != key.z()) {
            throw new IOException("Unexpected coordinates in chunk store");
        }
        return store;
    }

    private void writeChunkStore(@NotNull World world, @NotNull ChunkStore data) {
        writeChunkStore(world.getWorldFolder(), data);
    }

    private void writeChunkStore(File worldFolder, @NotNull ChunkStore data) {
        if (!data.isDirty()) {
            return; // Don't save unchanged data
        }
        final CoordinateKey region = toRegionKey(data.getWorldId(), data.getChunkX(), data.getChunkZ());
        // A disk read may overlap changed sector allocation for any chunk in this file.
        reconciliationLoads.keySet().removeIf(key -> toRegionKey(key.worldID(), key.x(), key.z()).equals(region));
        try {
            McMMOSimpleRegionFile rf = getWriteableSimpleRegionFile(worldFolder, region);
            try (DataOutputStream out = rf.getOutputStream(data.getChunkX(), data.getChunkZ())) {
                BitSetChunkStore.Serialization.writeChunkStore(out, data);
            }
            data.setDirty(false);
        } catch (IOException e) {
            throw new RuntimeException(
                    "Unable to write chunk meta data for " + data.getChunkX() + ", "
                            + data.getChunkZ(), e);
        }
    }

    private @NotNull McMMOSimpleRegionFile getWriteableSimpleRegionFile(
            File worldFolder, CoordinateKey regionKey) {
        return regionMap.computeIfAbsent(regionKey, k -> {
            File regionFile = getRegionFile(worldFolder, regionKey);
            regionFile.getParentFile().mkdirs();
            return new McMMOSimpleRegionFile(regionFile, regionKey.x(), regionKey.z());
        });
    }

    /**
     * Resolves the on-disk region file for a chunk's region.
     *
     * <p>Region files live inside the world folder at
     * {@code [worldFolder]/mcmmo_regions/mcmmo_[regionX]_[regionZ]_.mcm}, where
     * {@code worldFolder} is whatever {@link World#getWorldFolder()} returns on the running
     * server. On Spigot and pre-26.1 Paper this resolves to
     * {@code [container]/[worldName]/mcmmo_regions/}; on Paper 26.1+ (PaperMC/Paper PR #13736)
     * it resolves to
     * {@code [container]/[worldName]/dimensions/minecraft/<dim>/mcmmo_regions/}.
     *
     * <p>Because Paper's {@code LegacyCraftBukkitWorldMigration} runs before plugins load and
     * deletes the old per-world roots for non-overworld dimensions, mcMMO maintains a restore
     * store inside the mcMMO plugin data directory that is populated by
     * {@link McMMORegionBackupStore#backupWorld} on shutdown and replayed by
     * {@link McMMORegionBackupStore#restoreWorld} on the next startup if the in-world data has
     * been removed.
     */
    private static @NotNull File getRegionFile(@NotNull File worldFolder, @NotNull CoordinateKey regionKey) {
        final File worldRegionRoot = new File(worldFolder,
                McMMORegionBackupStore.IN_WORLD_FOLDER_NAME);
        return new File(worldRegionRoot,
                "mcmmo_" + regionKey.x() + "_" + regionKey.z() + "_.mcm");
    }

    private void unloadChunk(int cx, int cz, @NotNull World world) {
        CoordinateKey chunkKey = toChunkKey(world.getUID(), cx, cz);
        reconciliationLoads.remove(chunkKey);
        final FailedRead failure = failedReads.get(chunkKey);
        if (failure != null && invalidateForegroundRecovery(failure)) {
            failedReads.remove(chunkKey);
        }
        ChunkStore chunkStore = chunkMap.remove(chunkKey); // Remove from chunk map
        if (chunkStore == null) {
            return;
        }

        try {
            if (chunkStore.isDirty()) {
                writeChunkStore(world, chunkStore);
            }
        } catch (Exception e) {
            // Log-and-degrade: a failed save must not escape into the chunk unload event or
            // strand the region bookkeeping below
            logChunkSaveFailure(cx, cz, world.getName(), e);
        } finally {
            CoordinateKey regionKey = toRegionKey(world.getUID(), cx, cz);
            HashSet<CoordinateKey> chunkKeys = chunkUsageMap.get(regionKey);
            if (chunkKeys != null) {
                chunkKeys.remove(chunkKey); // remove from region file in-use set
                // If it was the last chunk in the region, close the region file and forget it
                if (chunkKeys.isEmpty()) {
                    chunkUsageMap.remove(regionKey);
                    closeQuietly(regionMap.remove(regionKey));
                }
            }
        }
    }

    @Override
    public synchronized void chunkUnloaded(int cx, int cz, @NotNull World world) {
        unloadChunk(cx, cz, world);
    }

    @Override
    public synchronized void unloadWorld(@NotNull World world) {
        UUID wID = world.getUID();
        reconciliationLoads.keySet().removeIf(key -> key.worldID().equals(wID));
        failedReads.entrySet().removeIf(entry -> entry.getKey().worldID().equals(wID)
                && invalidateForegroundRecovery(entry.getValue()));

        // Save and remove all the chunks
        List<CoordinateKey> chunkKeys = new ArrayList<>(chunkMap.keySet());
        for (CoordinateKey chunkKey : chunkKeys) {
            if (!wID.equals(chunkKey.worldID())) {
                continue;
            }
            ChunkStore chunkStore = chunkMap.remove(chunkKey);
            if (!chunkStore.isDirty()) {
                continue;
            }
            try {
                writeChunkStore(world, chunkStore);
            } catch (Exception e) {
                logChunkSaveFailure(chunkKey.x(), chunkKey.z(), world.getName(), e);
            }
        }
        // Clear all the region files
        List<CoordinateKey> regionKeys = new ArrayList<>(regionMap.keySet());
        for (CoordinateKey regionKey : regionKeys) {
            if (!wID.equals(regionKey.worldID())) {
                continue;
            }
            closeQuietly(regionMap.remove(regionKey));
        }
        chunkUsageMap.keySet().removeIf(key -> key.worldID().equals(wID));
    }

    /**
     * Read only a cold store. Install it later on the chunk's owning thread if it is still current.
     */
    public @Nullable ReconciliationLoad prepareReconciliation(@NotNull UUID worldId,
            @NotNull File worldFolder, int minHeight, int maxHeight, int x, int z) {
        final CoordinateKey key = toChunkKey(worldId, x, z);
        final ReconciliationLoad load;
        synchronized (this) {
            load = reserveRead(key);
        }
        if (load != null) {
            readReservedChunk(load, worldFolder, new BitSetChunkStore.WorldBounds(worldId, minHeight, maxHeight));
        }
        return load;
    }

    private @Nullable ReconciliationLoad reserveRead(CoordinateKey key) {
        if (reconciliationClosed || chunkMap.containsKey(key)) {
            return null;
        }
        final ReconciliationLoad load = new ReconciliationLoad(key);
        reconciliationLoads.put(key, load);
        return load;
    }

    private void readReservedChunk(ReconciliationLoad load, File worldFolder, BitSetChunkStore.WorldBounds bounds) {
        final CoordinateKey key = load.key;
        final File file = getRegionFile(worldFolder, toRegionKey(key.worldID(), key.x(), key.z()));
        try {
            load.store = readDiskChunk(file, bounds, key);
        } catch (IOException | RuntimeException ex) {
            load.readFailure = "Unable to pre-read placed-block data at " + key.x() + ", " + key.z()
                    + " in " + file + ": " + ex;
            discardReconciliation(load);
        }
    }

    public synchronized boolean completeReconciliation(@Nullable ReconciliationLoad load) {
        if (reconciliationClosed) {
            return false;
        }
        if (load == null) {
            return true;
        }
        if (load.readFailure != null || !reconciliationLoads.remove(load.key, load)) {
            return chunkMap.containsKey(load.key);
        }
        if (!chunkMap.containsKey(load.key)) {
            final FailedRead failure = failedReads.remove(load.key);
            if (failure != null) {
                installChunkStore(load.key, mergeChanges(load.key, failure, load.store));
            } else if (load.store != null) {
                installChunkStore(load.key, load.store);
            }
        }
        return true;
    }

    public synchronized void discardReconciliation(@Nullable ReconciliationLoad load) {
        if (load != null) {
            reconciliationLoads.remove(load.key, load);
        }
    }

    /** Even a chunk previously confirmed absent can have a later failed foreground read. */
    public synchronized boolean needsReadRecovery(@NotNull UUID worldId, int x, int z) {
        return !failedReads.isEmpty() && failedReads.containsKey(toChunkKey(worldId, x, z));
    }

    /** Scan at most `budget` live marks in an already-loaded chunk on its owning thread. */
    public int reconcileAir(@NotNull Chunk chunk, int fromIndex, int budget) {
        final World world = chunk.getWorld();
        final CoordinateKey key = toChunkKey(world.getUID(), chunk.getX(), chunk.getZ());
        final BitSetChunkStore store;
        final int[] batch;
        final int count;
        final int next;
        synchronized (this) {
            if (!(chunkMap.get(key) instanceof BitSetChunkStore found)) {
                return 0;
            }
            store = found;
            int index = store.nextMarkedIndex(fromIndex);
            if (index < 0) {
                return 0;
            }
            batch = new int[budget];
            int size = 0;
            while (index >= 0 && size < budget) {
                batch[size++] = index;
                index = store.nextMarkedIndex(index + 1);
            }
            count = size;
            next = Math.max(0, index);
        }

        final int minHeight = world.getMinHeight();
        final int maxHeight = world.getMaxHeight();
        for (int i = 0; i < count; i++) {
            final int index = batch[i];
            final int y = (index >>> 8) + store.getChunkMin();
            if (y < minHeight || y >= maxHeight) {
                continue;
            }
            // Invert the historic abs(worldCoordinate) % 16 mapping.
            final int x = chunk.getX() < 0 ? -index & 15 : index & 15;
            final int localZ = (index >>> 4) & 15;
            final int z = chunk.getZ() < 0 ? -localZ & 15 : localZ;
            if (chunk.getBlock(x, y, z).getType().isAir()) {
                synchronized (this) {
                    if (chunkMap.get(key) == store) {
                        store.clearMarkedIndex(index);
                    }
                }
            }
        }
        return next;
    }

    public static final class ReconciliationLoad {
        private final CoordinateKey key;
        private ChunkStore store;
        private String readFailure;

        private ReconciliationLoad(CoordinateKey key) {
            this.key = key;
        }

        /** Returns the diagnostic for an unsuccessful disk read. */
        public @Nullable String readFailure() {
            return readFailure;
        }
    }

    /** Null means unavailable. Confirmed absence is represented by a writable empty store. */
    private @Nullable ChunkStore getOrLoadChunkStore(@NotNull World world,
            @NotNull CoordinateKey chunkKey) {
        final ChunkStore cached = chunkMap.get(chunkKey);
        if (cached != null) {
            return cached;
        }
        final FailedRead failed = failedReads.get(chunkKey);
        if (failed != null) {
            requestReadRecovery(chunkKey, failed);
            return null;
        }
        final File folder = world.getWorldFolder();
        final var bounds = new BitSetChunkStore.WorldBounds(world.getUID(), world.getMinHeight(), world.getMaxHeight());
        try {
            final ChunkStore loaded = readChunkStore(folder, bounds, chunkKey);
            final ChunkStore store = loaded != null ? loaded
                    : new BitSetChunkStore(bounds, chunkKey.x(), chunkKey.z());
            installChunkStore(chunkKey, store);
            return store;
        } catch (IOException | RuntimeException ex) {
            final String worldName = world.getName();
            final FailedRead failure = new FailedRead(folder, worldName, bounds);
            failedReads.put(chunkKey, failure);
            requestReadRecovery(chunkKey, failure);
            mcMMO.p.getLogger().warning("Failed to read placed-block data for chunk (" + chunkKey.x()
                    + ", " + chunkKey.z() + ") in world '" + worldName + "', awaiting recovery: " + ex);
            return null;
        }
    }

    private void requestReadRecovery(CoordinateKey key, FailedRead failure) {
        if (recoveryExecutor == null || failure.recoveryInFlight || foregroundRecoveries >= MAX_FOREGROUND_RECOVERIES
                || (failure.retryDelaySeconds != 0 && failure.retryAtNanos - System.nanoTime() > 0)
                || scanCoverage.covers(key.worldID(), key.x(), key.z())) {
            return;
        }
        failure.recoveryInFlight = true;
        foregroundRecoveries++;
        final long generation = failure.generation;
        try {
            recoveryExecutor.accept(() -> recoverOnForegroundRequest(key, failure, generation));
        } catch (RuntimeException ex) {
            finishForegroundRecovery(failure, true);
            mcMMO.p.getLogger().warning("Could not schedule placed-block read recovery: " + ex);
        }
    }

    private void recoverOnForegroundRequest(CoordinateKey key, FailedRead failure, long generation) {
        final ReconciliationLoad load;
        synchronized (this) {
            if (reconciliationClosed || failedReads.get(key) != failure
                    || failure.generation != generation || reconciliationLoads.containsKey(key)
                    || scanCoverage.covers(key.worldID(), key.x(), key.z())) {
                finishForegroundRecovery(failure, false);
                return;
            }
            // Reserve before releasing the manager lock so unload can invalidate this read.
            load = reserveRead(key);
        }
        if (load != null) {
            readReservedChunk(load, failure.worldFolder, failure.bounds);
        }
        synchronized (this) {
            boolean retryLater = false;
            try {
                if (failedReads.get(key) == failure && failure.generation == generation) {
                    retryLater = !completeReconciliation(load);
                }
            } finally {
                discardReconciliation(load);
                finishForegroundRecovery(failure, retryLater);
            }
        }
    }

    private void finishForegroundRecovery(FailedRead failure, boolean retryLater) {
        failure.recoveryInFlight = false;
        foregroundRecoveries--;
        if (retryLater) {
            failure.retryDelaySeconds = Math.min(60, Math.max(1, failure.retryDelaySeconds * 2));
            failure.retryAtNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(failure.retryDelaySeconds);
        }
    }

    private static boolean invalidateForegroundRecovery(FailedRead failure) {
        failure.generation++;
        failure.retryDelaySeconds = 0;
        return failure.changes.isEmpty();
    }

    private void installChunkStore(CoordinateKey key, ChunkStore store) {
        chunkMap.put(key, store);
        final CoordinateKey region = toRegionKey(key.worldID(), key.x(), key.z());
        chunkUsageMap.computeIfAbsent(region, ignored -> new HashSet<>()).add(key);
    }

    private static ChunkStore mergeChanges(CoordinateKey key, FailedRead failure, @Nullable ChunkStore loaded) {
        final ChunkStore store = loaded != null ? loaded
                : new BitSetChunkStore(failure.bounds, key.x(), key.z());
        if (failure.captureBaseline) {
            failure.baseline = ((BitSetChunkStore) store).copyMarks();
            failure.recovered = store;
        }
        failure.changes.forEach((position, mark) -> {
            if (position.y >= store.getChunkMin() && position.y < store.getChunkMax()) {
                store.set(position.x, position.y, position.z, mark);
            }
        });
        failure.changes.clear();
        return store;
    }

    /**
     * Maps a world coordinate to this plugin's chunk-local index.
     *
     * <p>The mirrored mapping for negative coordinates (Math.abs instead of a proper floor
     * modulo) is load-bearing for on-disk compatibility: every existing region file was written
     * with it. Changing it to the mathematically correct {@code coordinate & 0xF} would
     * silently corrupt the stored markers of every chunk with negative coordinates.
     */
    private static int toChunkLocal(int worldCoordinate) {
        return Math.abs(worldCoordinate) % 16;
    }

    private synchronized boolean isIneligible(int x, int y, int z, @NotNull World world) {
        if (y < world.getMinHeight() || y >= world.getMaxHeight()) {
            // Invalid positions cannot award rewards and must not load a chunk store.
            return true;
        }
        CoordinateKey chunkKey = blockCoordinateToChunkKey(world.getUID(), x, y, z);
        ChunkStore check = getOrLoadChunkStore(world, chunkKey);
        if (check == null) {
            // Preserve the boolean query fallback without treating unknown bits as a store.
            return failedReads.get(chunkKey).changes.getOrDefault(Marker.at(x, y, z), false);
        }
        return check.isTrue(toChunkLocal(x), y, toChunkLocal(z));
    }

    @Override
    public synchronized boolean isIneligible(@NotNull Block block) {
        return isIneligible(block.getX(), block.getY(), block.getZ(), block.getWorld());
    }

    @Override
    public synchronized boolean isIneligible(@NotNull BlockState blockState) {
        return isIneligible(blockState.getX(), blockState.getY(), blockState.getZ(),
                blockState.getWorld());
    }

    @Override
    public synchronized boolean isEligible(@NotNull Block block) {
        return !isIneligible(block);
    }

    @Override
    public synchronized boolean isEligible(@NotNull BlockState blockState) {
        return !isIneligible(blockState);
    }

    @Override
    public synchronized void setIneligible(@NotNull Block block) {
        set(block.getX(), block.getY(), block.getZ(), block.getWorld(), true);
    }

    @Override
    public synchronized void setIneligible(@NotNull BlockState blockState) {
        set(blockState.getX(), blockState.getY(), blockState.getZ(), blockState.getWorld(), true);
    }

    @Override
    public synchronized void setEligible(@NotNull Block block) {
        set(block.getX(), block.getY(), block.getZ(), block.getWorld(), false);
    }

    @Override
    public synchronized void setEligible(@NotNull BlockState blockState) {
        set(blockState.getX(), blockState.getY(), blockState.getZ(), blockState.getWorld(), false);
    }

    private synchronized void set(int x, int y, int z, @NotNull World world, boolean value) {
        if (y < world.getMinHeight() || y >= world.getMaxHeight()) {
            return;
        }
        CoordinateKey chunkKey = blockCoordinateToChunkKey(world.getUID(), x, y, z);
        ChunkStore cStore = getOrLoadChunkStore(world, chunkKey);
        if (cStore == null) {
            failedReads.get(chunkKey).changes.put(Marker.at(x, y, z), value);
        } else {
            cStore.set(toChunkLocal(x), y, toChunkLocal(z), value);
        }
    }

    /**
     * Capture a marker before a tentative placement, including the distinction between
     * an unknown disk marker and an explicit local update during a failed read.
     */
    public synchronized Runnable capturePlacementMark(@NotNull Block block) {
        final World world = block.getWorld();
        final int x = block.getX(), y = block.getY(), z = block.getZ();
        if (y < world.getMinHeight() || y >= world.getMaxHeight()) {
            return () -> {};
        }
        final CoordinateKey key = blockCoordinateToChunkKey(world.getUID(), x, y, z);
        final ChunkStore store = getOrLoadChunkStore(world, key);
        final Marker position = Marker.at(x, y, z);
        if (store != null) {
            final boolean marked = store.isTrue(position.x, position.y, position.z);
            return () -> set(x, y, z, world, marked);
        }
        final FailedRead failure = failedReads.get(key);
        final Boolean changed = failure.changes.get(position);
        if (changed != null) {
            return () -> set(x, y, z, world, changed);
        }
        failure.captureBaseline = true;
        return () -> restoreUnknownMark(key, position, failure);
    }

    private synchronized void restoreUnknownMark(CoordinateKey key, Marker position, FailedRead failure) {
        if (failedReads.get(key) == failure) {
            failure.changes.remove(position);
        } else if (failure.recovered != null && chunkMap.get(key) == failure.recovered
                && position.y >= failure.recovered.getChunkMin()
                && position.y < failure.recovered.getChunkMax()) {
            failure.recovered.set(position.x, position.y, position.z,
                    failure.baseline.isTrue(position.x, position.y, position.z));
        }
    }

    private record Marker(int x, int y, int z) {
        private static Marker at(int x, int y, int z) {
            return new Marker(toChunkLocal(x), y, toChunkLocal(z));
        }
    }

    /** Changes to an unreadable chunk have no authoritative base and cannot be saved alone. */
    private static final class FailedRead {
        private final File worldFolder;
        private final String worldName;
        private final BitSetChunkStore.WorldBounds bounds;
        private final Map<Marker, Boolean> changes = new HashMap<>();
        private boolean recoveryInFlight;
        private long generation;
        private long retryDelaySeconds;
        private long retryAtNanos;
        private boolean captureBaseline;
        private BitSetChunkStore baseline;
        private ChunkStore recovered;

        private FailedRead(File worldFolder, String worldName, BitSetChunkStore.WorldBounds bounds) {
            this.worldFolder = worldFolder;
            this.worldName = worldName;
            this.bounds = bounds;
        }
    }

    private @NotNull CoordinateKey blockCoordinateToChunkKey(@NotNull UUID worldUid, int x, int y,
            int z) {
        return toChunkKey(worldUid, x >> 4, z >> 4);
    }

    private @NotNull CoordinateKey toChunkKey(@NotNull UUID worldUid, int cx, int cz) {
        return new CoordinateKey(worldUid, cx, cz);
    }

    private @NotNull CoordinateKey toRegionKey(@NotNull UUID worldUid, int cx, int cz) {
        // Compute region index (32x32 chunk regions)
        int rx = cx >> 5;
        int rz = cz >> 5;
        return new CoordinateKey(worldUid, rx, rz);
    }

    private record CoordinateKey(@NotNull UUID worldID, int x, int z) {
    }
}
