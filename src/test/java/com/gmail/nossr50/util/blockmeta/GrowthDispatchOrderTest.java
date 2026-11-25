package com.gmail.nossr50.util.blockmeta;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.bukkit.Location;
import org.bukkit.TreeType;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockFertilizeEvent;
import org.bukkit.event.world.StructureGrowEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredListener;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GrowthDispatchOrderTest {
    private final PendingBlockChanges changes = new PendingBlockChanges();
    private final World world = mock(World.class);
    private final Block log = mock(Block.class);
    private final BlockState generatedLog = mock(BlockState.class);
    private final Plugin plugin = mock(Plugin.class);
    private final Listener listener = new Listener() {};
    private final AtomicReference<PendingBlockChanges.Growth> growth = new AtomicReference<>();

    @BeforeEach
    void setup() {
        when(world.getUID()).thenReturn(UUID.randomUUID());
        when(log.getWorld()).thenReturn(world);
        when(log.getX()).thenReturn(2);
        when(generatedLog.getBlock()).thenReturn(log);
        when(generatedLog.getX()).thenReturn(2);
    }

    @Test
    void placementDuringDispatchIsOlderThanTheTreeThatOverwritesIt() throws Exception {
        final StructureGrowEvent event = growthWith(generatedLog);
        dispatch(event, register(this::begin), register(() -> changes.place(log, false, () -> true)));

        assertTrue(changes.mayApply(changes.resolveGrowth(growth.get()).get(generatedLog)));
    }

    @Test
    void placementAfterDispatchRemainsNewerThanTheTree() throws Exception {
        final StructureGrowEvent event = growthWith(generatedLog);
        dispatch(event, register(this::begin));
        final var playerPlacement = changes.place(log, false, () -> true);
        changes.finish(playerPlacement, true);

        assertFalse(changes.mayApply(changes.resolveGrowth(growth.get()).get(generatedLog)));
    }

    @Test
    void finalGrowthListControlsWhichNestedPlacementsAreOverwritten() throws Exception {
        final StructureGrowEvent appended = growthWith();
        dispatch(appended, register(this::begin), register(() -> {
            changes.place(log, false, () -> true);
            appended.getBlocks().add(generatedLog);
        }));
        assertTrue(changes.mayApply(changes.resolveGrowth(growth.get()).get(generatedLog)));

        final StructureGrowEvent pruned = growthWith(generatedLog);
        dispatch(pruned, register(this::begin), register(() -> {
            changes.place(log, false, () -> true);
            pruned.getBlocks().clear();
        }));
        assertTrue(changes.resolveGrowth(growth.get()).isEmpty());
    }

    @Test
    void fertilizeDispatchSharesGrowthOrderUntilActualApplication() throws Exception {
        final StructureGrowEvent event = growthWith(generatedLog);
        dispatch(event, register(this::begin));
        final var fertilize = new BlockFertilizeEvent(log, null, event.getBlocks());
        dispatch(fertilize, register(current -> changes.beginFertilize((BlockFertilizeEvent) current)),
                register(() -> changes.place(log, false, () -> true)));

        assertTrue(changes.mayApply(changes.resolveGrowth(growth.get()).get(generatedLog)));
    }

    @Test
    void canceledFertilizePreventsGrowthCleanup() throws Exception {
        final StructureGrowEvent event = growthWith(generatedLog);
        dispatch(event, register(this::begin));
        final var fertilize = new BlockFertilizeEvent(log, null, event.getBlocks());
        dispatch(fertilize, register(current -> changes.beginFertilize((BlockFertilizeEvent) current)),
                register(() -> fertilize.setCancelled(true)));

        assertTrue(changes.resolveGrowth(growth.get()).isEmpty());
    }

    @Test
    void sequentialDispatchesAtTheSameCallSiteDoNotReopenTheFirstTree() throws Exception {
        final StructureGrowEvent first = growthWith(generatedLog);
        final StructureGrowEvent second = growthWith(generatedLog);
        final List<PendingBlockChanges.Growth> pending = new ArrayList<>();
        final var enroll = register(event -> {
            begin(event);
            pending.add(growth.get());
        });
        final var replace = register(event -> {
            if (event == second) {
                changes.place(log, false, () -> true);
                second.setCancelled(true);
            }
        });
        for (var event : List.of(first, second)) {
            dispatch(event, enroll, replace);
        }

        assertFalse(changes.mayApply(changes.resolveGrowth(pending.get(0)).get(generatedLog)));
        assertTrue(changes.resolveGrowth(pending.get(1)).isEmpty());
    }

    @Test
    void nestedTreeIsOlderThanTheOuterTreeThatAppliesAfterIt() throws Exception {
        final StructureGrowEvent outer = growthWith(generatedLog);
        final StructureGrowEvent inner = growthWith(generatedLog);
        final var outerGrowth = new AtomicReference<PendingBlockChanges.Growth>();
        dispatch(outer, register(event -> {
            begin(event);
            outerGrowth.set(growth.get());
        }), register(() -> dispatch(inner, register(this::begin))));

        assertFalse(changes.mayApply(changes.resolveGrowth(growth.get()).get(generatedLog)));
        assertTrue(changes.mayApply(changes.resolveGrowth(outerGrowth.get()).get(generatedLog)));
    }

    private void begin(Event event) {
        final StructureGrowEvent structure = (StructureGrowEvent) event;
        final var pending = changes.beginGrowth(structure, () -> true);
        growth.set(pending);
        structure.getBlocks().forEach(state -> changes.watchGrowth(pending, state));
    }

    private StructureGrowEvent growthWith(BlockState... states) {
        return new StructureGrowEvent(new Location(world, 1, 64, 1), TreeType.TREE, true,
                null, new ArrayList<>(List.of(states)));
    }

    private RegisteredListener register(Consumer<Event> action) {
        return new RegisteredListener(listener, (ignored, event) -> action.accept(event), EventPriority.NORMAL,
                plugin, false);
    }

    private RegisteredListener register(Runnable action) {
        return register(event -> action.run());
    }

    private void dispatch(Event event, RegisteredListener... listeners) {
        for (var registered : listeners) {
            try {
                registered.callEvent(event);
            } catch (org.bukkit.event.EventException error) {
                throw new AssertionError("Test dispatch failed", error);
            }
        }
    }
}
