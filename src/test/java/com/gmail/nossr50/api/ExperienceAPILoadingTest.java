package com.gmail.nossr50.api;

import static com.gmail.nossr50.datatypes.experience.XPGainReason.UNKNOWN;
import static com.gmail.nossr50.datatypes.experience.XPGainSource.CUSTOM;
import static com.gmail.nossr50.datatypes.skills.PrimarySkillType.HERBALISM;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.gmail.nossr50.MMOTestEnvironment;
import com.gmail.nossr50.api.exceptions.InvalidSkillException;
import com.gmail.nossr50.api.exceptions.InvalidXPGainReasonException;
import com.gmail.nossr50.api.exceptions.McMMOPlayerNotFoundException;
import com.gmail.nossr50.datatypes.player.McMMOPlayer;
import com.gmail.nossr50.mcMMO;
import com.gmail.nossr50.util.player.PendingExperienceManager;
import com.gmail.nossr50.util.player.UserManager;
import java.util.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ExperienceAPILoadingTest extends MMOTestEnvironment {
    private PendingExperienceManager pending;
    private McMMOPlayer target;

    @BeforeEach
    void setUp() {
        mockBaseEnvironment(Logger.getAnonymousLogger());
        pending = mcMMO.p.getPendingExperienceManager();
        target = mock(McMMOPlayer.class);
        when(player.isOnline()).thenReturn(true);
        when(UserManager.getPlayer(player)).thenReturn(null);
    }

    @AfterEach
    void tearDown() {
        cleanUpStaticMocks();
    }

    @Test
    void readyPathUsesSingleLookupWithoutConsultingQueue() {
        when(UserManager.getPlayer(player)).thenReturn(target);
        clearInvocations(mcMMO.p);
        ExperienceAPI.addRawXP(player, "HERBALISM", 1.25f, "UNKNOWN");
        verify(target).applyXpGain(HERBALISM, 1.25f, UNKNOWN, CUSTOM);
        mockedUserManager.verify(() -> UserManager.getPlayer(player), times(1));
        verify(mcMMO.p, never()).getPendingExperienceManager();
    }

    @Test
    void customCropsCallShapeIsAcceptedDuringLoadingAndReplayedAsFloat() {
        var session = pending.beginLoading(player);
        for (int i = 0; i < 8; i++) {
            ExperienceAPI.addRawXP(player, "HERBALISM", 1.25f, "UNKNOWN");
        }
        verifyNoInteractions(target);
        pending.complete(session, target);
        verify(target, times(8)).applyXpGain(HERBALISM, 1.25f, UNKNOWN, CUSTOM);
    }

    @Test
    void allOnlineGainVariantsPreserveOriginalRoutingAndScaling() {
        when(experienceConfigInstance.getFormulaSkillModifier(HERBALISM)).thenReturn(2.0);
        when(experienceConfigInstance.getExperienceGainsMultiplier(HERBALISM)).thenReturn(3.0);
        var session = pending.beginLoading(player);
        ExperienceAPI.addRawXP(player, "HERBALISM", 1.25f, "UNKNOWN", true);
        ExperienceAPI.addXP(player, "HERBALISM", 2, "UNKNOWN");
        ExperienceAPI.addXP(player, "HERBALISM", 3, "UNKNOWN", true);
        ExperienceAPI.addMultipliedXP(player, "HERBALISM", 5, "UNKNOWN");
        ExperienceAPI.addModifiedXP(player, "HERBALISM", 5, "UNKNOWN", false);
        ExperienceAPI.addModifiedXP(player, "HERBALISM", 5, "UNKNOWN", true);
        pending.complete(session, target);
        var order = inOrder(target);
        order.verify(target).beginUnsharedXpGain(HERBALISM, 1.25f, UNKNOWN, CUSTOM);
        order.verify(target).beginXpGain(HERBALISM, 2, UNKNOWN, CUSTOM);
        order.verify(target).beginUnsharedXpGain(HERBALISM, 3, UNKNOWN, CUSTOM);
        order.verify(target).applyXpGain(HERBALISM, 15, UNKNOWN, CUSTOM);
        order.verify(target).applyXpGain(HERBALISM, 7, UNKNOWN, CUSTOM);
        order.verify(target).beginUnsharedXpGain(HERBALISM, 7, UNKNOWN, CUSTOM);
        verifyNoMoreInteractions(target);
    }

    @Test
    void unknownSessionAndInvalidInputsKeepExplicitFailures() {
        assertThrows(McMMOPlayerNotFoundException.class,
                () -> ExperienceAPI.addRawXP(player, "HERBALISM", 1.25f));
        pending.beginLoading(player);
        assertThrows(InvalidSkillException.class,
                () -> ExperienceAPI.addRawXP(player, "not-a-skill", 1.25f));
        assertThrows(InvalidXPGainReasonException.class,
                () -> ExperienceAPI.addRawXP(player, "HERBALISM", 1.25f, "not-a-reason"));
    }
}
