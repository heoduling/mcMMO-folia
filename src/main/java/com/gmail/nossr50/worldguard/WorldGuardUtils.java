package com.gmail.nossr50.worldguard;

import com.gmail.nossr50.mcMMO;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.bukkit.WorldGuardPlugin;
import com.sk89q.worldguard.protection.flags.registry.SimpleFlagRegistry;
import java.util.List;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.Nullable;

public class WorldGuardUtils {
    private static volatile WorldGuardPlugin worldGuardPluginRef;

    private static final List<String> REQUIRED_CLASSES = List.of(
            "com.sk89q.worldedit.bukkit.BukkitAdapter",
            "com.sk89q.worldedit.bukkit.BukkitPlayer",
            "com.sk89q.worldguard.WorldGuard",
            "com.sk89q.worldguard.bukkit.WorldGuardPlugin",
            "com.sk89q.worldguard.protection.flags.registry.FlagConflictException",
            "com.sk89q.worldguard.protection.flags.registry.FlagRegistry",
            "com.sk89q.worldguard.protection.regions.RegionContainer",
            "com.sk89q.worldguard.protection.regions.RegionQuery"
    );

    public static boolean isWorldGuardLoaded() {
        return getActiveWorldGuard() != null;
    }

    static @Nullable WorldGuardPlugin getActiveWorldGuard() {
        final WorldGuardPlugin plugin = worldGuardPluginRef;
        return plugin != null && plugin.isEnabled() ? plugin : null;
    }

    /** Detects WorldGuard after it has finished enabling. mcMMO's flags are registered separately in onLoad. */
    public static void refreshHook() {
        worldGuardPluginRef = null;
        final Plugin plugin = Bukkit.getPluginManager().getPlugin("WorldGuard");
        if (plugin == null || !plugin.isEnabled()) {
            return;
        }

        try {
            if (plugin instanceof WorldGuardPlugin worldGuard
                    && plugin.getDescription().getVersion().startsWith("7")) {
                for (final String requiredClass : REQUIRED_CLASSES) {
                    Class.forName(requiredClass, false, WorldGuardUtils.class.getClassLoader());
                }
                if (WorldGuard.getInstance().getFlagRegistry() instanceof SimpleFlagRegistry registry
                        && registry.isInitialized()) {
                    worldGuardPluginRef = worldGuard;
                    return;
                }
            }
            mcMMO.p.getLogger().warning("WorldGuard is incompatible or did not initialize. mcMMO integration disabled.");
        } catch (ClassNotFoundException | LinkageError | RuntimeException exception) {
            mcMMO.p.getLogger().log(Level.WARNING, "Could not initialize mcMMO's WorldGuard integration.", exception);
        }
    }

    /** Clears the runtime hook before WorldGuard tears down its platform. */
    public static void clearHook() {
        worldGuardPluginRef = null;
    }
}
