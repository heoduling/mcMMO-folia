package com.gmail.nossr50.skills.archery;

import com.gmail.nossr50.util.CancellableRunnable;
import java.lang.ref.WeakReference;
import java.util.UUID;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.LivingEntity;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.Nullable;

public class TrackedEntity extends CancellableRunnable {
    private static final NamespacedKey ARROWS = new NamespacedKey("mcmmo", "arrow_retrieval");
    private static volatile UUID trackingSession;

    // The legacy tracker API remains available without holding or scheduling an entity.
    private final WeakReference<LivingEntity> entityRef;
    private final UUID id;
    private final UUID session;

    /** @deprecated Arrow tracking now uses the entity's saved data directly. */
    @Deprecated
    protected TrackedEntity(LivingEntity livingEntity) {
        this.entityRef = new WeakReference<>(livingEntity);
        this.id = livingEntity.getUniqueId();
        this.session = trackingSession;
    }

    /** Starts arrow tracking for the current plugin enable cycle. */
    public static void startTracking() {
        trackingSession = UUID.randomUUID();
    }

    /** Invalidates loaded and saved arrow counts without visiting entities from the shutdown thread. */
    public static void clearTracking() {
        trackingSession = null;
    }

    @Override
    public void run() {
        final LivingEntity livingEntity = getLivingEntity();
        if (livingEntity == null || !livingEntity.isValid()) {
            removeIfCurrent();
            cancel();
        }
    }

    protected @Nullable LivingEntity getLivingEntity() {
        return entityRef.get();
    }

    protected UUID getID() {
        return id;
    }

    protected int getArrowCount() {
        final LivingEntity entity = getLivingEntity();
        return entity != null && session != null && session.equals(trackingSession)
                ? count(entity.getPersistentDataContainer(), id, session) : 0;
    }

    protected void incrementArrowCount() {
        final LivingEntity entity = getLivingEntity();
        if (entity != null && session != null && session.equals(trackingSession)) {
            increment(entity, session);
        }
    }

    void removeIfCurrent() {
        final LivingEntity entity = getLivingEntity();
        if (entity != null && session != null && session.equals(trackingSession)) {
            remove(entity);
        }
    }

    static void increment(LivingEntity entity) {
        final UUID session = trackingSession;
        if (session != null) {
            increment(entity, session);
        }
    }

    private static void increment(LivingEntity entity, UUID session) {
        final PersistentDataContainer data = entity.getPersistentDataContainer();
        final UUID id = entity.getUniqueId();
        final int count = count(data, id, session);
        if (count < Integer.MAX_VALUE) {
            // Saving with the entity bounds the storage to the entity's own lifetime.
            data.set(ARROWS, PersistentDataType.LONG_ARRAY,
                    new long[]{session.getMostSignificantBits(), session.getLeastSignificantBits(),
                            id.getMostSignificantBits(), id.getLeastSignificantBits(), count + 1L});
        }
    }

    static int take(LivingEntity entity) {
        final UUID session = trackingSession;
        final PersistentDataContainer data = entity.getPersistentDataContainer();
        final int count = session == null ? 0 : count(data, entity.getUniqueId(), session);
        // Hit and death handlers are serialized by the entity's owning region. Consume before spawning.
        data.remove(ARROWS);
        return session == trackingSession ? count : 0;
    }

    static void remove(LivingEntity entity) {
        entity.getPersistentDataContainer().remove(ARROWS);
    }

    private static int count(PersistentDataContainer data, UUID id, UUID session) {
        final long[] value = data.get(ARROWS, PersistentDataType.LONG_ARRAY);
        if (value == null || value.length != 5
                || value[0] != session.getMostSignificantBits() || value[1] != session.getLeastSignificantBits()
                || value[2] != id.getMostSignificantBits() || value[3] != id.getLeastSignificantBits()
                || value[4] <= 0 || value[4] > Integer.MAX_VALUE) {
            return 0;
        }
        return (int) value[4];
    }
}
