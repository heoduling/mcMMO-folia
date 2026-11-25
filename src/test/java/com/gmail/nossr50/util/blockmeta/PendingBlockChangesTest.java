package com.gmail.nossr50.util.blockmeta;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.UUID;
import java.util.List;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.world.StructureGrowEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PendingBlockChangesTest {
    private final PendingBlockChanges changes = new PendingBlockChanges();
    private final UserBlockTracker tracker = mock(UserBlockTracker.class);
    private final Block block = mock(Block.class);
    private final World world = mock(World.class);

    @BeforeEach
    void setup() {
        when(block.getWorld()).thenReturn(world);
        when(block.getX()).thenReturn(1);
        when(world.getUID()).thenReturn(UUID.randomUUID());
    }

    @Test
    void successfulPlacementSupersedesOlderMovementEvenAfterItsCallback() {
        final var movement = changes.watch(block, () -> true);
        final var placement = changes.place(block, false, () -> true);
        changes.settlePlacement(placement, block, tracker);
        changes.finish(placement, true);
        assertFalse(changes.mayApply(movement));
        verifyNoInteractions(tracker);
    }

    @Test
    void multipleLateCancellationsRestoreTheMarkBeforeBothAttempts() {
        final var movement = changes.watch(block, () -> true);
        final var first = changes.place(block, false, () -> false);
        final var second = changes.place(block, true, () -> false);
        changes.settlePlacement(first, block, tracker);
        changes.finish(first, false);
        changes.settlePlacement(second, block, tracker);
        verify(tracker).setEligible(block);
        assertTrue(changes.mayApply(movement));
    }

    @Test
    void repeatedFailuresUseTheOriginalStorageRestoreToken() {
        final Runnable originalMark = mock(Runnable.class);
        final Runnable provisionalMark = mock(Runnable.class);
        final var first = changes.place(block, ignored -> originalMark.run(), () -> false);
        changes.place(block, ignored -> provisionalMark.run(), () -> false);

        changes.settlePlacement(first, block, tracker);

        verify(originalMark).run();
        verifyNoInteractions(provisionalMark, tracker);
    }

    @Test
    void lateFailurePreservesEitherAnOldMarkOrAnEarlierSuccessfulPlacement() {
        changes.place(block, false, () -> true);
        final var failed = changes.place(block, true, () -> false);
        changes.settlePlacement(failed, block, tracker);
        verify(tracker).setIneligible(block);
    }

    @Test
    void oldCallbacksCannotTouchTheNextLoadAndLateFailureSettlesBeforeUnload() {
        final var movement = changes.watch(block, () -> true);
        changes.place(block, false, () -> false);
        changes.forgetChunk(world, 0, 0, tracker);
        verify(tracker).setEligible(block);
        final var newLoad = changes.watch(block, () -> true);
        assertFalse(changes.mayApply(movement));
        assertTrue(changes.mayApply(newLoad));
    }

    @Test
    void onlySuccessfulBreakSupersedesWatch() {
        final var movement = changes.watch(block, () -> true);
        final BlockBreakEvent event = mock(BlockBreakEvent.class);
        when(event.getBlock()).thenReturn(block);
        changes.breaking(event);
        when(event.isCancelled()).thenReturn(true);
        assertTrue(changes.mayApply(movement));
        when(event.isCancelled()).thenReturn(false);
        assertFalse(changes.mayApply(movement));
    }

    @Test
    void finishedHistoryIsReleasedBeforeTheNextWatch() {
        final var first = changes.watch(block, () -> true);
        changes.finish(first, true);
        final var next = changes.watch(block, () -> true);
        assertFalse(changes.mayApply(first));
        assertTrue(changes.mayApply(next));
    }

    @Test
    void lateGrowthEntryKeepsItsOriginalOrderAfterACompletedReplacement() {
        final StructureGrowEvent event = mock(StructureGrowEvent.class);
        final BlockState added = mock(BlockState.class);
        when(event.getWorld()).thenReturn(world);
        when(added.getBlock()).thenReturn(block);
        when(added.getX()).thenReturn(1);
        final var growth = changes.beginGrowth(event, () -> true);
        when(event.getBlocks()).thenReturn(List.of(added));

        final var placement = changes.place(block, false, () -> true);
        changes.settlePlacement(placement, block, tracker);
        changes.finish(placement, true);

        final var result = changes.resolveGrowth(growth);
        assertFalse(changes.mayApply(result.get(added)));
        verifyNoInteractions(tracker);
    }
}
