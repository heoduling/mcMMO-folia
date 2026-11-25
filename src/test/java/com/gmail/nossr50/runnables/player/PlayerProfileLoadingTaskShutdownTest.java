package com.gmail.nossr50.runnables.player;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.gmail.nossr50.MMOTestEnvironment;
import com.gmail.nossr50.mcMMO;
import com.gmail.nossr50.util.player.PendingExperienceManager;
import com.gmail.nossr50.util.player.PendingExperienceManager.LoadingSession;
import com.tcoded.folialib.impl.PlatformScheduler;
import java.util.logging.Logger;
import org.bukkit.plugin.IllegalPluginAccessException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PlayerProfileLoadingTaskShutdownTest extends MMOTestEnvironment {
    private PlatformScheduler scheduler;

    @BeforeEach
    void setUp() {
        mockBaseEnvironment(Logger.getAnonymousLogger());
        final PendingExperienceManager manager = mock(PendingExperienceManager.class);
        final LoadingSession session = mock(LoadingSession.class);
        when(mcMMO.p.getPendingExperienceManager()).thenReturn(manager);
        when(manager.beginLoading(player)).thenReturn(session);
        when(manager.isCurrent(session)).thenReturn(true);
        scheduler = foliaLib.getScheduler();
    }

    @AfterEach
    void tearDown() {
        cleanUpStaticMocks();
    }

    @Test
    void delayedLoadSkipsSubmissionAfterDisable() {
        when(mcMMO.p.isEnabled()).thenReturn(false);
        new PlayerProfileLoadingTask(player).run();
        verifyNoInteractions(scheduler);
    }

    @Test
    void delayedLoadToleratesDisableWhileCoreAcceptsTask() {
        when(mcMMO.p.isEnabled()).thenReturn(true, false);
        when(scheduler.runAtEntity(eq(player), any()))
                .thenThrow(new IllegalPluginAccessException("Plugin attempted to register task while disabled"));
        assertDoesNotThrow(() -> new PlayerProfileLoadingTask(player).run());
    }

    @Test
    void delayedLoadReportsTaskFailureWhilePluginIsActive() {
        when(mcMMO.p.isEnabled()).thenReturn(true);
        when(scheduler.runAtEntity(eq(player), any()))
                .thenThrow(new IllegalPluginAccessException("Unexpected rejection"));
        assertThrows(IllegalPluginAccessException.class, () -> new PlayerProfileLoadingTask(player).run());
    }
}
