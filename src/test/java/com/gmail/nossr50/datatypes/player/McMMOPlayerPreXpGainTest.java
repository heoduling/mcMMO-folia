package com.gmail.nossr50.datatypes.player;

import static com.gmail.nossr50.datatypes.experience.XPGainReason.UNKNOWN;
import static com.gmail.nossr50.datatypes.experience.XPGainSource.CUSTOM;
import static com.gmail.nossr50.datatypes.skills.PrimarySkillType.HERBALISM;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.gmail.nossr50.MMOTestEnvironment;
import com.gmail.nossr50.events.experience.McMMOPlayerPreXpGainEvent;
import com.gmail.nossr50.events.experience.McMMOPlayerXpGainEvent;
import com.gmail.nossr50.util.Permissions;
import java.util.logging.Logger;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.*;

class McMMOPlayerPreXpGainTest extends MMOTestEnvironment {
    @BeforeEach
    void setUp() {
        mockBaseEnvironment(Logger.getAnonymousLogger());
        when(Permissions.skillEnabled(any(Player.class), eq(HERBALISM))).thenReturn(true);
    }

    @AfterEach
    void tearDown() {
        cleanUpStaticMocks();
    }

    @Test
    void preEventRetainsFractionalXp() {
        mmoPlayer.applyXpGain(HERBALISM, 1.25f, UNKNOWN, CUSTOM);
        org.junit.jupiter.api.Assertions.assertEquals(1.25f,
                playerProfile.getSkillXpLevelRaw(HERBALISM));
    }

    @Test
    void cancelledPreEventStopsBeforeGainEvent() {
        doAnswer(invocation -> {
            if (invocation.getArgument(0) instanceof McMMOPlayerPreXpGainEvent event) {
                event.setCancelled(true);
            }
            return null;
        }).when(pluginManager).callEvent(any());
        mmoPlayer.applyXpGain(HERBALISM, 1.25f, UNKNOWN, CUSTOM);
        verify(pluginManager, never()).callEvent(isA(McMMOPlayerXpGainEvent.class));
    }
}
