package com.gmail.nossr50.util.blockmeta;

import java.lang.StackWalker.StackFrame;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Predicate;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockFertilizeEvent;
import org.bukkit.event.world.StructureGrowEvent;
import org.bukkit.plugin.RegisteredListener;

/** Orders delayed marker updates at coordinates with an outstanding callback. */
public final class PendingBlockChanges {
    private static final StackWalker STACK_WALKER = StackWalker.getInstance();
    private final Map<Position, History> histories = new HashMap<>();
    private final Map<UUID, List<Growth>> growths = new HashMap<>();
    private long sequence;

    public synchronized Ticket watch(Block block, BooleanSupplier succeeds) {
        final Position position = Position.of(block);
        captureGrowthAt(position);
        final Ticket ticket = add(position, ++sequence, succeeds, null);
        advanceDispatchedGrowths(position.world);
        return ticket;
    }

    private Ticket add(Position position, long order, BooleanSupplier succeeds, Growth growth) {
        final History history = histories.computeIfAbsent(position, ignored -> new History());
        final Ticket ticket = new Ticket(position, history, order, succeeds, growth);
        history.pending.add(ticket);
        return ticket;
    }

    public synchronized Growth beginGrowth(StructureGrowEvent event, BooleanSupplier ownsEvent) {
        final UUID world = event.getWorld().getUID();
        final List<Growth> pending = growths.computeIfAbsent(world, ignored -> new ArrayList<>());
        final List<DispatchFrame> dispatch = dispatchStack();
        final Growth growth = new Growth(event, ownsEvent, world, ++sequence, dispatch);
        advanceDispatchedGrowths(pending, dispatch, true);
        pending.add(growth);
        return growth;
    }

    /** Bone meal commits this list only after its second, fertilize event has returned. */
    public synchronized void beginFertilize(BlockFertilizeEvent event) {
        final List<Growth> pending = growths.get(event.getBlock().getWorld().getUID());
        if (pending == null) {
            return;
        }
        for (int i = pending.size() - 1; i >= 0; i--) {
            final Growth growth = pending.get(i);
            if (growth.dispatchThread == Thread.currentThread() && growth.event.getBlocks() == event.getBlocks()) {
                final List<DispatchFrame> dispatch = dispatchStack();
                growth.fertilize = event;
                growth.order = ++sequence;
                advanceDispatchedGrowths(pending, dispatch, true);
                growth.dispatch = dispatch;
                return;
            }
        }
    }

    private void advanceDispatchedGrowths(UUID world) {
        final List<Growth> pending = growths.get(world);
        if (pending != null && pending.stream().anyMatch(Growth::dispatchMayBeActive)) {
            advanceDispatchedGrowths(pending, dispatchStack(), false);
        }
    }

    private void advanceDispatchedGrowths(List<Growth> pending, List<DispatchFrame> stack, boolean newDispatch) {
        // Nested growth applies first. An outer event's own block writes still follow its listeners.
        for (int i = pending.size() - 1; i >= 0; i--) {
            final Growth growth = pending.get(i);
            if (!growth.dispatchMayBeActive()) {
                continue;
            }
            final int start = stack.size() - growth.dispatch.size();
            if (start >= (newDispatch ? 1 : 0) && stack.subList(start, stack.size()).equals(growth.dispatch)) {
                growth.order = ++sequence;
            } else {
                growth.dispatch = List.of();
            }
        }
    }

    private static List<DispatchFrame> dispatchStack() {
        // Bukkit exposes no event-end hook. The dispatcher and its caller stay on the stack
        // across all listeners, including when another synchronous event is dispatched inside one.
        return STACK_WALKER.walk(frames -> frames
                .dropWhile(frame -> !frame.getClassName().equals(RegisteredListener.class.getName())
                        || !frame.getMethodName().equals("callEvent"))
                .map(DispatchFrame::of).toList());
    }

    public synchronized Ticket watchGrowth(Growth growth, BlockState state) {
        final Position position = Position.of(state.getBlock());
        captureGrowthAt(position, growth);
        return addGrowth(growth, position);
    }

    public synchronized void endGrowth(Growth growth) {
        final List<Growth> list = growths.get(growth.world);
        if (list != null) {
            list.remove(growth);
            if (list.isEmpty()) {
                growths.remove(growth.world);
            }
        }
    }

    private void captureGrowthAt(Position position) {
        captureGrowthAt(position, null);
    }

    private void captureGrowthAt(Position position, Growth current) {
        final List<Growth> list = growths.get(position.world);
        if (list != null) {
            for (Growth growth : list) {
                if (growth != current && !growth.tickets.containsKey(position)
                        && growth.ownsEvent.getAsBoolean() && growth.contains(position)) {
                    addGrowth(growth, position);
                }
            }
        }
    }

    private Ticket addGrowth(Growth growth, Position position) {
        return growth.tickets.computeIfAbsent(position, key -> add(key, 0, () ->
                growth.finalPositions != null ? growth.finalPositions.contains(key)
                        : growth.ownsEvent.getAsBoolean() && !growth.isCancelled() && growth.contains(key), growth));
    }

    /**
     * Capture the final list before handing any block work across regions. An entry that a later
     * listener added was registered at the original growth order if a mutation already touched it.
     */
    public synchronized Map<BlockState, Ticket> resolveGrowth(Growth growth) {
        final List<Growth> open = growths.get(growth.world);
        if (open == null || !open.contains(growth)) {
            return Map.of();
        }
        endGrowth(growth);
        final Map<Position, BlockState> states = new HashMap<>();
        if (!growth.isCancelled()) {
            for (BlockState state : growth.event.getBlocks()) {
                states.put(Position.of(state.getBlock()), state);
            }
        }
        growth.finalPositions = Set.copyOf(states.keySet());
        final Map<BlockState, Ticket> result = new HashMap<>();
        for (var entry : states.entrySet()) {
            final Ticket ticket = addGrowth(growth, entry.getKey());
            if (histories.get(ticket.position) == ticket.history) {
                result.put(entry.getValue(), ticket);
            }
        }
        growth.tickets.forEach((key, ticket) -> {
            if (!states.containsKey(key)) {
                finish(ticket, false);
            }
        });
        return result;
    }

    public synchronized void cancelGrowth(Growth growth) {
        endGrowth(growth);
        growth.tickets.values().forEach(ticket -> finish(ticket, false));
    }

    public synchronized Ticket place(Block block, boolean previousMark, BooleanSupplier succeeds) {
        return place(block, tracker -> {
            if (previousMark) {
                tracker.setIneligible(block);
            } else {
                tracker.setEligible(block);
            }
        }, succeeds);
    }

    public synchronized Ticket place(Block block, Consumer<UserBlockTracker> restoreMark, BooleanSupplier succeeds) {
        final Ticket ticket = watch(block, succeeds);
        final History history = ticket.history;
        history.place = new Placement(ticket, block, restoreMark, history.place);
        return ticket;
    }

    public synchronized void breaking(BlockBreakEvent event) {
        if (histories.isEmpty() && growths.isEmpty()) {
            return;
        }
        final Position position = Position.of(event.getBlock());
        captureGrowthAt(position);
        final long order = ++sequence;
        advanceDispatchedGrowths(position.world);
        final History history = histories.get(position);
        if (history != null) {
            history.breaks.add(new Break(order, () -> !event.isCancelled()));
            // The regular break listener takes over the actual tracker value immediately.
            history.place = null;
        }
    }

    /**
     * Resolve tentative placements on this owner after event dispatch. Multiple failures
     * unwind to the last valid placement or the mark before the first attempt.
     */
    public void settlePlacement(Ticket ticket, Block block, UserBlockTracker tracker) {
        final Consumer<UserBlockTracker> restore;
        synchronized (this) {
            final History history = ticket.history;
            if (histories.get(ticket.position) != history || history.place == null) {
                return;
            }
            Placement place = history.place;
            history.place = null;
            if (place.ticket.succeeds.getAsBoolean()) {
                return;
            }
            while (place.previous != null && !place.previous.ticket.succeeds.getAsBoolean()) {
                place = place.previous;
            }
            restore = place.previous != null ? current -> current.setIneligible(block) : place.restoreMark;
        }
        restore.accept(tracker);
    }

    public synchronized boolean mayApply(Ticket ticket) {
        final History history = ticket.history;
        final long order = ticket.order();
        if (histories.get(ticket.position) != history || history.lastCompleted > order) {
            return false;
        }
        for (Ticket pending : history.pending) {
            if (pending.order() > order && pending.succeeds.getAsBoolean()) {
                return false;
            }
        }
        for (Break broken : history.breaks) {
            if (broken.order > order && broken.succeeds.getAsBoolean()) {
                return false;
            }
        }
        return true;
    }

    public synchronized void finish(Ticket ticket, boolean completed) {
        final History history = ticket.history;
        if (histories.get(ticket.position) != history || !history.pending.remove(ticket)) {
            return;
        }
        if (completed) {
            history.lastCompleted = Math.max(history.lastCompleted, ticket.order());
        }
        if (history.pending.isEmpty()) {
            histories.remove(ticket.position);
        } else {
            final long oldest = history.pending.stream().mapToLong(Ticket::order).min().orElseThrow();
            history.breaks.removeIf(change -> change.order < oldest);
        }
    }

    public void forgetChunk(World world, int x, int z, UserBlockTracker tracker) {
        final UUID worldId = world.getUID();
        forget(key -> key.world.equals(worldId) && key.x >> 4 == x && key.z >> 4 == z, tracker);
    }

    public void forgetWorld(World world, UserBlockTracker tracker) {
        final UUID worldId = world.getUID();
        synchronized (this) {
            growths.remove(worldId);
        }
        forget(key -> key.world.equals(worldId), tracker);
    }

    public void clear(UserBlockTracker tracker) {
        synchronized (this) {
            growths.clear();
        }
        forget(key -> true, tracker);
    }

    private void forget(Predicate<Position> matches, UserBlockTracker tracker) {
        final List<Placement> unwritten = new ArrayList<>();
        synchronized (this) {
            histories.forEach((key, history) -> {
                if (matches.test(key) && history.place != null) {
                    unwritten.add(history.place);
                }
            });
        }
        try {
            for (Placement place : unwritten) {
                settlePlacement(place.ticket, place.block, tracker);
            }
        } finally {
            synchronized (this) {
                histories.keySet().removeIf(matches);
            }
        }
    }

    private record Position(UUID world, int x, int y, int z) {
        static Position of(Block block) {
            return new Position(block.getWorld().getUID(), block.getX(), block.getY(), block.getZ());
        }
    }

    private static final class History {
        private final List<Ticket> pending = new ArrayList<>();
        private final List<Break> breaks = new ArrayList<>();
        private long lastCompleted;
        private Placement place;
    }

    private record Break(long order, BooleanSupplier succeeds) {
    }

    private record DispatchFrame(String className, String methodName, int bytecodeIndex) {
        private static DispatchFrame of(StackFrame frame) {
            return new DispatchFrame(frame.getClassName(), frame.getMethodName(), frame.getByteCodeIndex());
        }
    }

    private record Placement(Ticket ticket, Block block, Consumer<UserBlockTracker> restoreMark, Placement previous) {
    }

    public static final class Ticket {
        private final Position position;
        private final History history;
        private final long order;
        private final BooleanSupplier succeeds;
        private final Growth growth;

        private Ticket(Position position, History history, long order, BooleanSupplier succeeds, Growth growth) {
            this.position = position;
            this.history = history;
            this.order = order;
            this.succeeds = succeeds;
            this.growth = growth;
        }

        private long order() {
            return growth == null ? order : growth.order;
        }
    }

    public static final class Growth {
        private final StructureGrowEvent event;
        private final BooleanSupplier ownsEvent;
        private final UUID world;
        private final Map<Position, Ticket> tickets = new HashMap<>();
        private final Thread dispatchThread = Thread.currentThread();
        private long order;
        private List<DispatchFrame> dispatch;
        private BlockFertilizeEvent fertilize;
        private Set<Position> finalPositions;

        private Growth(StructureGrowEvent event, BooleanSupplier ownsEvent, UUID world, long order,
                List<DispatchFrame> dispatch) {
            this.event = event;
            this.ownsEvent = ownsEvent;
            this.world = world;
            this.order = order;
            this.dispatch = dispatch;
        }

        private boolean dispatchMayBeActive() {
            return dispatchThread == Thread.currentThread() && !dispatch.isEmpty();
        }

        private boolean isCancelled() {
            return fertilize == null ? event.isCancelled() : fertilize.isCancelled();
        }

        private boolean contains(Position position) {
            return event.getBlocks().stream().anyMatch(state -> state.getX() == position.x
                    && state.getY() == position.y && state.getZ() == position.z);
        }
    }
}
