package com.gmail.nossr50.listeners;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.gmail.nossr50.MMOTestEnvironment;
import com.gmail.nossr50.mcMMO;
import com.gmail.nossr50.util.HealthbarDeathMessageUtils;
import com.gmail.nossr50.util.HealthbarDeathMessageUtils.DeathMessageName;
import com.gmail.nossr50.util.MobHealthbarUtils;
import com.tcoded.folialib.enums.EntityTaskResult;
import com.tcoded.folialib.wrapper.task.WrappedTask;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.logging.Logger;
import org.bukkit.damage.DamageSource;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.SmallFireball;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityResurrectEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

class PlayerListenerLethalDamageTaskTest extends MMOTestEnvironment {
    private PlayerListener listener;
    private LivingEntity attacker;
    private EntityDamageByEntityEvent event;
    private MockedStatic<MobHealthbarUtils> healthbars;

    @BeforeEach
    void setUp() {
        mockBaseEnvironment(Logger.getAnonymousLogger());
        listener = new PlayerListener(mcMMO.p);
        attacker = mock(LivingEntity.class);
        event = mock(EntityDamageByEntityEvent.class);
        when(event.getEntity()).thenReturn(player);
        when(event.getDamager()).thenReturn(attacker);
        when(event.getFinalDamage()).thenReturn(40.0);
        when(foliaLib.getScheduler().runAtEntityWithFallback(eq(player), any(Consumer.class),
                any(Runnable.class))).thenReturn(new CompletableFuture<EntityTaskResult>());
        healthbars = mockStatic(MobHealthbarUtils.class);
    }

    @AfterEach
    void tearDown() {
        healthbars.close();
        cleanUpStaticMocks();
    }

    @Test
    void activeAttackerRestoresNameBeforeDeferredHealthbarUpdate() {
        AtomicReference<Consumer<WrappedTask>> pending = new AtomicReference<>();
        when(foliaLib.getScheduler().runAtEntity(eq(attacker), any(Consumer.class)))
                .thenAnswer(invocation -> {
                    pending.set(invocation.getArgument(1));
                    return new CompletableFuture<EntityTaskResult>();
                });
        listener.onEntityDamageByEntityHighest(event);
        healthbars.verify(() -> MobHealthbarUtils.restoreNameFromSnapshot(attacker));
        healthbars.verify(() -> MobHealthbarUtils.handleMobHealthbars(attacker, 0, mcMMO.p), never());
        assertNotNull(pending.get());
        pending.get().accept(mock(WrappedTask.class));
        healthbars.verify(() -> MobHealthbarUtils.handleMobHealthbars(attacker, 0, mcMMO.p));
        verify(foliaLib.getScheduler(), never()).runAtEntityLater(any(), any(Runnable.class), anyLong());
    }

    @Test
    void retiredFireballShooterEndsAfterSchedulingRejection() {
        SmallFireball fireball = mock(SmallFireball.class);
        when(fireball.getShooter()).thenReturn(attacker);
        when(event.getDamager()).thenReturn(fireball);
        when(foliaLib.getScheduler().runAtEntity(eq(attacker), any(Consumer.class)))
                .thenReturn(CompletableFuture.completedFuture(EntityTaskResult.SCHEDULER_RETIRED));
        assertDoesNotThrow(() -> listener.onEntityDamageByEntityHighest(event));
        verify(foliaLib.getScheduler()).runAtEntity(eq(attacker), any(Consumer.class));
        healthbars.verify(() -> MobHealthbarUtils.restoreNameFromSnapshot(attacker));
        healthbars.verify(() -> MobHealthbarUtils.handleMobHealthbars(attacker, 0, mcMMO.p), never());
    }

    @Test
    void crossRegionAttackerIsRefreshedOnItsScheduler() {
        when(foliaLib.isFolia()).thenReturn(true);
        AtomicReference<Consumer<WrappedTask>> pending = new AtomicReference<>();
        when(foliaLib.getScheduler().runAtEntity(eq(attacker), any(Consumer.class)))
                .thenAnswer(invocation -> {
                    pending.set(invocation.getArgument(1));
                    return new CompletableFuture<EntityTaskResult>();
                });

        listener.onEntityDamageByEntityHighest(event);

        healthbars.verify(() -> MobHealthbarUtils.restoreNameFromSnapshot(attacker), never());
        healthbars.verify(() -> MobHealthbarUtils.handleMobHealthbars(attacker, 0, mcMMO.p), never());
        assertNotNull(pending.get());
        pending.get().accept(mock(WrappedTask.class));
        healthbars.verify(() -> MobHealthbarUtils.handleMobHealthbars(attacker, 0, mcMMO.p));
    }

    @Test
    void crossRegionNameIsUsedOnceForMatchingDamage() {
        DeathMessageName name = prepareCrossRegionDeath();
        final PlayerDeathEvent death = createDeathEvent(event);

        try (var messages = mockStatic(HealthbarDeathMessageUtils.class)) {
            listener.onPlayerDeathHealthbar(death);
            listener.onPlayerDeathHealthbar(death);
            messages.verify(() -> HealthbarDeathMessageUtils.restoreAttackerName(death, event.getDamageSource(), name));
        }
    }

    @Test
    void staleNameDoesNotChangeAnUnrelatedDeath() {
        prepareCrossRegionDeath();
        final PlayerDeathEvent death = createDeathEvent(mock(EntityDamageByEntityEvent.class));

        try (var messages = mockStatic(HealthbarDeathMessageUtils.class)) {
            listener.onPlayerDeathHealthbar(death);
            messages.verifyNoInteractions();
        }
    }

    @Test
    void cancelledDamageDoesNotChangeDeathMessage() {
        prepareCrossRegionDeath();
        final PlayerDeathEvent death = createDeathEvent(event);
        when(event.isCancelled()).thenReturn(true);

        try (var messages = mockStatic(HealthbarDeathMessageUtils.class)) {
            listener.onPlayerDeathHealthbar(death);
            messages.verifyNoInteractions();
        }
    }

    @Test
    void successfulResurrectionDiscardsTheName() {
        prepareCrossRegionDeath();
        final PlayerDeathEvent death = createDeathEvent(event);
        final EntityResurrectEvent resurrection = mock(EntityResurrectEvent.class);
        when(resurrection.getEntity()).thenReturn(player);
        listener.onEntityResurrectHealthbar(resurrection);

        try (var messages = mockStatic(HealthbarDeathMessageUtils.class)) {
            listener.onPlayerDeathHealthbar(death);
            messages.verifyNoInteractions();
        }
    }

    @Test
    void aSurvivingPlayerHasNoPendingNameOnItsNextTick() {
        final AtomicReference<Consumer<WrappedTask>> nextTick = new AtomicReference<>();
        when(foliaLib.getScheduler().runAtEntityWithFallback(eq(player), any(Consumer.class),
                any(Runnable.class))).thenAnswer(invocation -> {
                    nextTick.set(invocation.getArgument(1));
                    return new CompletableFuture<EntityTaskResult>();
                });
        prepareCrossRegionDeath();
        final PlayerDeathEvent death = createDeathEvent(event);

        assertNotNull(nextTick.get());
        nextTick.get().accept(mock(WrappedTask.class));
        try (var messages = mockStatic(HealthbarDeathMessageUtils.class)) {
            listener.onPlayerDeathHealthbar(death);
            messages.verifyNoInteractions();
        }
    }

    private DeathMessageName prepareCrossRegionDeath() {
        when(foliaLib.isFolia()).thenReturn(true);
        final var name = new DeathMessageName(UUID.randomUUID(), "{\"text\":\"bar\"}",
                "{\"text\":\"original\"}");
        healthbars.when(() -> MobHealthbarUtils.getDeathMessageName(attacker)).thenReturn(name);
        listener.onEntityDamageByEntityHighest(event);
        return name;
    }

    private PlayerDeathEvent createDeathEvent(EntityDamageByEntityEvent latestDamage) {
        final DamageSource damageSource = mock(DamageSource.class);
        final PlayerDeathEvent death = mock(PlayerDeathEvent.class);
        when(death.getEntity()).thenReturn(player);
        when(event.getDamageSource()).thenReturn(damageSource);
        when(player.getLastDamageCause()).thenReturn(latestDamage);
        return death;
    }

    @Test
    void nonLethalDamageDoesNotRestoreOrSchedule() {
        when(event.getFinalDamage()).thenReturn(1.0);
        listener.onEntityDamageByEntityHighest(event);
        healthbars.verifyNoInteractions();
        verifyNoInteractions(foliaLib.getScheduler());
    }

    @Test
    void humanAttackerDoesNotRestoreOrSchedule() {
        when(event.getDamager()).thenReturn(mock(Player.class));
        listener.onEntityDamageByEntityHighest(event);
        healthbars.verifyNoInteractions();
        verifyNoInteractions(foliaLib.getScheduler());
    }

    @Test
    void nonPlayerVictimDoesNotRestoreOrSchedule() {
        when(event.getEntity()).thenReturn(mock(LivingEntity.class));
        listener.onEntityDamageByEntityHighest(event);
        healthbars.verifyNoInteractions();
        verifyNoInteractions(foliaLib.getScheduler());
    }

    @Test
    void projectileWithoutLivingShooterDoesNotRestoreOrSchedule() {
        when(event.getDamager()).thenReturn(mock(SmallFireball.class));
        listener.onEntityDamageByEntityHighest(event);
        healthbars.verifyNoInteractions();
        verifyNoInteractions(foliaLib.getScheduler());
    }

    @Test
    void cancelledEventsRemainIgnoredByRegisteredHandler() throws Exception {
        EventHandler handler = PlayerListener.class.getMethod("onEntityDamageByEntityHighest",
                EntityDamageByEntityEvent.class).getAnnotation(EventHandler.class);
        assertTrue(handler.ignoreCancelled());
        assertEquals(EventPriority.HIGHEST, handler.priority());
    }
}
