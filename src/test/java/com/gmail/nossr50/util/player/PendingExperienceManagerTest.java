package com.gmail.nossr50.util.player;

import static com.gmail.nossr50.datatypes.experience.XPGainReason.UNKNOWN;
import static com.gmail.nossr50.datatypes.experience.XPGainSource.CUSTOM;
import static com.gmail.nossr50.datatypes.skills.PrimarySkillType.HERBALISM;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.gmail.nossr50.datatypes.player.McMMOPlayer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.logging.Logger;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PendingExperienceManagerTest {
    @TempDir Path directory;

    private PendingExperienceManager manager() throws Exception {
        return new PendingExperienceManager(directory, Logger.getAnonymousLogger());
    }

    private Player player(UUID uuid) {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(uuid);
        when(player.isOnline()).thenReturn(true);
        return player;
    }

    @Test
    void preservesFractionsModesAndOrderAndCompletesOnlyOnce() throws Exception {
        var manager = manager();
        Player player = player(UUID.randomUUID());
        var session = manager.beginLoading(player);
        assertTrue(manager.enqueue(player, HERBALISM, 1.25f, UNKNOWN, PendingExperienceManager.Mode.RAW));
        assertTrue(manager.enqueue(player, HERBALISM, 2.5f, UNKNOWN, PendingExperienceManager.Mode.UNSHARED));
        assertTrue(manager.enqueue(player, HERBALISM, 3.75f, UNKNOWN, PendingExperienceManager.Mode.NORMAL));
        McMMOPlayer target = mock(McMMOPlayer.class);
        manager.complete(session, target);
        manager.complete(session, target);
        var order = inOrder(target);
        order.verify(target).applyXpGain(HERBALISM, 1.25f, UNKNOWN, CUSTOM);
        order.verify(target).beginUnsharedXpGain(HERBALISM, 2.5f, UNKNOWN, CUSTOM);
        order.verify(target).beginXpGain(HERBALISM, 3.75f, UNKNOWN, CUSTOM);
        verifyNoMoreInteractions(target);
        assertFalse(manager.isCurrent(session));
    }

    @Test
    void earlyQuitAndRestartRetainGrantsButRejectOldConnection() throws Exception {
        UUID uuid = UUID.randomUUID();
        var manager = manager();
        Player oldPlayer = player(uuid);
        var oldSession = manager.beginLoading(oldPlayer);
        manager.enqueue(oldPlayer, HERBALISM, 1.25f, UNKNOWN, PendingExperienceManager.Mode.RAW);
        manager.quit(oldPlayer);
        assertFalse(manager.enqueue(oldPlayer, HERBALISM, 9, UNKNOWN, PendingExperienceManager.Mode.RAW));
        manager.close();
        var restored = manager();
        Player newPlayer = player(uuid);
        var current = restored.beginLoading(newPlayer);
        McMMOPlayer target = mock(McMMOPlayer.class);
        restored.complete(oldSession, target);
        restored.quit(oldPlayer);
        assertTrue(restored.isCurrent(current));
        restored.complete(current, target);
        verify(target).applyXpGain(HERBALISM, 1.25f, UNKNOWN, CUSTOM);
        restored.close();
        var again = manager();
        again.complete(again.beginLoading(player(uuid)), target);
        verifyNoMoreInteractions(target);
    }

    @Test
    void replacementSessionRejectsStaleCompletionWithoutLosingQueue() throws Exception {
        var manager = manager();
        UUID uuid = UUID.randomUUID();
        Player oldPlayer = player(uuid);
        var oldSession = manager.beginLoading(oldPlayer);
        manager.enqueue(oldPlayer, HERBALISM, 1, UNKNOWN, PendingExperienceManager.Mode.RAW);
        var current = manager.beginLoading(player(uuid));
        McMMOPlayer target = mock(McMMOPlayer.class);
        manager.complete(oldSession, target);
        verifyNoInteractions(target);
        assertTrue(manager.isCurrent(current));
        manager.complete(current, target);
        verify(target).applyXpGain(HERBALISM, 1, UNKNOWN, CUSTOM);
    }

    @Test
    void capacityRejectionLeavesPreviouslyAcceptedGrantsIntact() throws Exception {
        var manager = manager();
        Player player = player(UUID.randomUUID());
        var session = manager.beginLoading(player);
        for (int i = 0; i < 1024; i++) {
            manager.enqueue(player, HERBALISM, 1.25f, UNKNOWN, PendingExperienceManager.Mode.RAW);
        }
        assertThrows(IllegalStateException.class, () -> manager.enqueue(player, HERBALISM, 9,
                UNKNOWN, PendingExperienceManager.Mode.RAW));
        McMMOPlayer target = mock(McMMOPlayer.class);
        manager.complete(session, target);
        verify(target, times(1024)).applyXpGain(HERBALISM, 1.25f, UNKNOWN, CUSTOM);
    }

    @Test
    void failedReplayIsRetainedForReviewWithoutAutomaticDuplication() throws Exception {
        var manager = manager();
        UUID uuid = UUID.randomUUID();
        Player player = player(uuid);
        var session = manager.beginLoading(player);
        manager.enqueue(player, HERBALISM, 1.25f, UNKNOWN, PendingExperienceManager.Mode.RAW);
        McMMOPlayer target = mock(McMMOPlayer.class);
        doThrow(new IllegalStateException("injected failure")).when(target)
                .applyXpGain(HERBALISM, 1.25f, UNKNOWN, CUSTOM);
        manager.complete(session, target);
        manager.close();
        var restored = manager();
        McMMOPlayer next = mock(McMMOPlayer.class);
        restored.complete(restored.beginLoading(player(uuid)), next);
        verifyNoInteractions(next);
        assertTrue(Files.size(directory.resolve("pending-experience.bin")) > 12);
    }

    @Test
    void malformedStoreIsPreserved() throws Exception {
        Path file = directory.resolve("pending-experience.bin");
        byte[] corrupt = {1, 2, 3};
        Files.write(file, corrupt);
        assertThrows(java.io.IOException.class, this::manager);
        assertArrayEquals(corrupt, Files.readAllBytes(file));
    }

    @Test
    void incompleteShutdownQuarantinesUncertainDiskGrants() throws Exception {
        UUID uuid = UUID.randomUUID();
        var manager = manager();
        Player player = player(uuid);
        var session = manager.beginLoading(player);
        manager.enqueue(player, HERBALISM, 1.25f, UNKNOWN, PendingExperienceManager.Mode.RAW);
        manager.save();
        McMMOPlayer target = mock(McMMOPlayer.class);
        manager.complete(session, target);
        verify(target).applyXpGain(HERBALISM, 1.25f, UNKNOWN, CUSTOM);
        // A directory at the temporary filename deterministically makes replacement fail.
        Files.createDirectory(directory.resolve("pending-experience.bin.tmp"));
        manager.close();
        assertTrue(Files.exists(directory.resolve("pending-experience.active")));
        var restored = manager();
        McMMOPlayer next = mock(McMMOPlayer.class);
        restored.complete(restored.beginLoading(player(uuid)), next);
        verifyNoInteractions(next);
        Files.delete(directory.resolve("pending-experience.bin.tmp"));
        restored.close();
        assertFalse(Files.exists(directory.resolve("pending-experience.active")));
        var third = manager();
        third.complete(third.beginLoading(player(uuid)), next);
        verifyNoInteractions(next);
    }
}
