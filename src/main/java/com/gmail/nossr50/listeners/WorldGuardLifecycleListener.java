package com.gmail.nossr50.listeners;

import com.gmail.nossr50.worldguard.WorldGuardUtils;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.event.server.PluginEnableEvent;

public final class WorldGuardLifecycleListener implements Listener {
    @EventHandler(priority = EventPriority.LOWEST)
    public void onPluginDisable(PluginDisableEvent event) {
        if (event.getPlugin().getName().equalsIgnoreCase("WorldGuard")) {
            WorldGuardUtils.clearHook();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPluginEnable(PluginEnableEvent event) {
        if (event.getPlugin().getName().equalsIgnoreCase("WorldGuard")) {
            WorldGuardUtils.refreshHook();
        }
    }
}
