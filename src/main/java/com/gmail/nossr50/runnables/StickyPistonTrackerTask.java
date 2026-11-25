package com.gmail.nossr50.runnables;

import com.gmail.nossr50.config.WorldBlacklist;
import com.gmail.nossr50.config.experience.ExperienceConfig;
import com.gmail.nossr50.mcMMO;
import com.gmail.nossr50.util.BlockUtils;
import com.gmail.nossr50.util.CancellableRunnable;
import com.gmail.nossr50.util.blockmeta.PendingBlockChanges;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.PistonMoveReaction;
import org.bukkit.event.block.BlockPistonEvent;

public class StickyPistonTrackerTask extends CancellableRunnable {
    private final BlockFace direction;
    private final Block block;
    private final Block movedBlock;

    /** @deprecated Use {@link #trackMovement} to snapshot the full move before blocks change. */
    @Deprecated
    public StickyPistonTrackerTask(BlockFace direction, Block block, Block movedBlock) {
        this.direction = direction;
        this.block = block;
        this.movedBlock = movedBlock;
    }

    /** Transfers the original placed marker of every block moved by one piston action. */
    public static void trackMovement(final mcMMO plugin, final BlockPistonEvent event, final List<Block> sources) {
        final Block piston = event.getBlock();
        final World world = piston.getWorld();
        if (sources.isEmpty() || WorldBlacklist.isWorldBlacklisted(world)
                || !ExperienceConfig.getInstance().isPistonCheatingPrevented()) {
            return;
        }

        final var scheduler = plugin.getFoliaLib().getScheduler();
        final var tracker = mcMMO.getUserBlockTracker();
        final Map<Block, MovedBlock> incoming = new HashMap<>();
        final Set<Block> affected = new LinkedHashSet<>();
        for (final Block source : sources) {
            if (!BlockUtils.isWithinWorldBounds(source) || !scheduler.isOwnedByCurrentRegion(source)
                    || !isChunkLoaded(source)) {
                continue;
            }

            affected.add(source);
            if (source.getPistonMoveReaction() == PistonMoveReaction.BREAK) {
                continue;
            }

            // For a retract that moves blocks, the event already gives the movement direction.
            final Block destination = source.getRelative(event.getDirection());
            if (BlockUtils.isWithinWorldBounds(destination)) {
                incoming.put(destination, new MovedBlock(source.getType(), tracker.isIneligible(source)));
                affected.add(destination);
            }
        }

        if (affected.isEmpty()) {
            return;
        }

        final PendingBlockChanges changes = plugin.getPendingBlockChanges();
        final Map<Block, PendingBlockChanges.Ticket> tickets = new HashMap<>();
        for (Block block : affected) {
            tickets.put(block, changes.watch(block, () -> !event.isCancelled()));
        }
        // The core places moving-piston placeholders first and resolves them in its block entity ticks.
        try {
            scheduler.runAtLocationLater(piston.getLocation(), task -> {
                if (event.isCancelled() || plugin.getServer().getWorld(world.getUID()) != world
                        || !isChunkLoaded(piston)) {
                    tickets.values().forEach(ticket -> changes.finish(ticket, false));
                    return;
                }

                for (final Block block : affected) {
                    final MovedBlock moved = incoming.get(block);
                    final PendingBlockChanges.Ticket ticket = tickets.get(block);
                    if (scheduler.isOwnedByCurrentRegion(block)) {
                        applyMovement(changes, ticket, block, moved);
                    } else if (isChunkLoaded(block)) {
                        try {
                            scheduler.runAtLocation(block.getLocation(), ownerTask -> {
                                if (plugin.getServer().getWorld(world.getUID()) == world) {
                                    applyMovement(changes, ticket, block, moved);
                                } else {
                                    changes.finish(ticket, false);
                                }
                            });
                        } catch (RuntimeException ex) {
                            changes.finish(ticket, false);
                            throw ex;
                        }
                    } else {
                        changes.finish(ticket, false);
                    }
                }
            }, 3);
        } catch (RuntimeException ex) {
            tickets.values().forEach(ticket -> changes.finish(ticket, false));
            throw ex;
        }
    }

    private static void applyMovement(PendingBlockChanges changes, PendingBlockChanges.Ticket ticket,
            Block block, MovedBlock moved) {
        final boolean loaded = isChunkLoaded(block);
        try {
            if (loaded) {
                changes.settlePlacement(ticket, block, mcMMO.getUserBlockTracker());
                if (changes.mayApply(ticket)) {
                    applyMovement(block, moved);
                }
            }
        } finally {
            changes.finish(ticket, loaded);
        }
    }

    private static void applyMovement(final Block block, final MovedBlock moved) {
        if (!isChunkLoaded(block)) {
            return;
        }

        final Material material = block.getType();
        final var tracker = mcMMO.getUserBlockTracker();
        if (moved != null && material == moved.type()) {
            if (moved.placed()) {
                BlockUtils.setUnnaturalBlock(block);
            } else if (tracker.isIneligible(block)) {
                tracker.setEligible(block);
            }
        } else if ((material.isAir() || material == Material.PISTON_HEAD) && tracker.isIneligible(block)) {
            BlockUtils.cleanupBlockMetadata(block);
        }
    }

    private static boolean isChunkLoaded(final Block block) {
        return block.getWorld().isChunkLoaded(block.getX() >> 4, block.getZ() >> 4);
    }

    private record MovedBlock(Material type, boolean placed) { }

    @Override
    public void run() {
        if (!mcMMO.getUserBlockTracker().isIneligible(movedBlock.getRelative(direction))) {
            return;
        }

        if (!BlockUtils.isPistonPiece(movedBlock.getState())) {
            // The block didn't move
            return;
        }

        // The sticky piston actually pulled the block so move the PlaceStore data
        mcMMO.getUserBlockTracker().setEligible(movedBlock.getRelative(direction));
        BlockUtils.setUnnaturalBlock(movedBlock);
    }
}
