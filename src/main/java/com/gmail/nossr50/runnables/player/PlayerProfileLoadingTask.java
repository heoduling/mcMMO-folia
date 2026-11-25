package com.gmail.nossr50.runnables.player;

import com.gmail.nossr50.datatypes.player.McMMOPlayer;
import com.gmail.nossr50.datatypes.player.PlayerProfile;
import com.gmail.nossr50.locale.LocaleLoader;
import com.gmail.nossr50.mcMMO;
import com.gmail.nossr50.runnables.commands.McScoreboardKeepTask;
import com.gmail.nossr50.util.CancellableRunnable;
import com.gmail.nossr50.util.EventUtils;
import com.gmail.nossr50.util.LogUtils;
import com.gmail.nossr50.util.Misc;
import com.gmail.nossr50.util.player.UserManager;
import com.gmail.nossr50.util.player.PendingExperienceManager.LoadingSession;
import com.gmail.nossr50.util.scoreboards.ScoreboardManager;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.plugin.IllegalPluginAccessException;

public class PlayerProfileLoadingTask extends CancellableRunnable {
    private final Player player;
    private final LoadingSession session;
    private final mcMMO plugin = mcMMO.p;
    private final int attempt;

    public PlayerProfileLoadingTask(Player player) {
        this.player = player;
        this.session = mcMMO.p.getPendingExperienceManager().beginLoading(player);
        this.attempt = 0;
    }

    private PlayerProfileLoadingTask(Player player, int attempt, LoadingSession session) {
        this.player = player;
        this.attempt = attempt;
        this.session = session;
    }

    @Override
    public void run() {
        final var scheduler = plugin.getFoliaLib().getScheduler();
        submit(() -> scheduler.runAtEntity(player, task -> {
            if (!player.isOnline() || !plugin.getPendingExperienceManager().isCurrent(session)) {
                return;
            }
            if (Misc.isNPCIncludingVillagers(player)) {
                plugin.getPendingExperienceManager().quit(player);
                return;
            }
            final String playerName = player.getName();
            submit(() -> scheduler.runAsync(callback -> loadProfileAsync(playerName)));
        }));
    }

    private void loadProfileAsync(String playerName) {
        if (!plugin.isEnabled() || !plugin.getPendingExperienceManager().isCurrent(session)) {
            return;
        }
        // The database overload uses the player's stable identity, including legacy name lookup.
        PlayerProfile profile = mcMMO.getDatabaseManager().loadPlayerProfile(player);
        if (!profile.isLoaded()) {
            LogUtils.debug(plugin.getLogger(),
                    "Creating new data for player: " + playerName);
            profile = mcMMO.getDatabaseManager().newUser(player);
        }

        final var scheduler = plugin.getFoliaLib().getScheduler();
        if (profile.isLoaded()) {
            final PlayerProfile loaded = profile;
            submit(() -> scheduler.runAtEntity(player, task -> {
                if (!player.isOnline() || !plugin.getPendingExperienceManager().isCurrent(session)) {
                    return;
                }
                final McMMOPlayer mmoPlayer = new McMMOPlayer(player, loaded);
                // The load event historically exposes the loaded profile before tracking and XP delivery.
                EventUtils.callPlayerProfileLoadEvent(player, loaded);
                new ApplySuccessfulProfile(mmoPlayer).run();
            }));
            return;
        }

        if (attempt >= 3) {
            plugin.getLogger().severe(LocaleLoader.getString("Profile.Loading.FailureNotice",
                    playerName, String.valueOf(attempt)));
            final String adminMessage = LocaleLoader.getString("Profile.Loading.FailureNotice", playerName);
            final String playerMessage = LocaleLoader.getString("Profile.Loading.FailurePlayer",
                    String.valueOf(attempt));
            submit(() -> scheduler.runNextTick(task -> plugin.getServer().broadcast(
                    adminMessage, Server.BROADCAST_CHANNEL_ADMINISTRATIVE)));
            submit(() -> scheduler.runAtEntity(player, task -> {
                if (player.isOnline() && plugin.getPendingExperienceManager().isCurrent(session)) {
                    player.sendMessage(playerMessage.split("\n"));
                }
            }));
        }

        final int nextAttempt = attempt + 1;
        submit(() -> scheduler.runLaterAsync(new PlayerProfileLoadingTask(player, nextAttempt, session),
                100 + nextAttempt * 100L));
    }

    private void submit(Runnable schedule) {
        if (!plugin.isEnabled() || !plugin.getPendingExperienceManager().isCurrent(session)) {
            return;
        }
        try {
            schedule.run();
        } catch (IllegalPluginAccessException ignoredDuringShutdown) {
            // Disable may run between the check above and Folia accepting the task.
            if (plugin.isEnabled()) {
                throw ignoredDuringShutdown;
            }
        }
    }

    private class ApplySuccessfulProfile extends CancellableRunnable {
        private final McMMOPlayer mmoPlayer;

        private ApplySuccessfulProfile(McMMOPlayer mmoPlayer) {
            this.mmoPlayer = mmoPlayer;
        }

        // Synchronized task
        // No database access permitted
        @Override
        public void run() {
            if (!player.isOnline() || !plugin.getPendingExperienceManager().isCurrent(session)) {
                plugin.getLogger().info("Aborting profile loading recovery for " + player.getName()
                        + " - player logged out");
                return;
            }

            mmoPlayer.getProfile().updateLastLogin();

            mmoPlayer.setupPartyData();
            UserManager.track(mmoPlayer);
            mmoPlayer.actualizeRespawnATS();

            if (mcMMO.p.getGeneralConfig().getScoreboardsEnabled()) {
                ScoreboardManager.setupPlayer(player);

                if (mcMMO.p.getGeneralConfig().getShowStatsAfterLogin()) {
                    ScoreboardManager.enablePlayerStatsScoreboard(player);
                    mcMMO.p.getFoliaLib().getScheduler()
                            .runAtEntityLater(player, new McScoreboardKeepTask(player),
                                    Misc.TICK_CONVERSION_FACTOR);
                }
            }

            if (mcMMO.p.getGeneralConfig().getShowProfileLoadedMessage()) {
                player.sendMessage(LocaleLoader.getString("Profile.Loading.Success"));
            }
            plugin.getPendingExperienceManager().complete(session, mmoPlayer);
        }
    }
}
