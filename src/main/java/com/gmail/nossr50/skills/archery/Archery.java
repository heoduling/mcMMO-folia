package com.gmail.nossr50.skills.archery;

import com.gmail.nossr50.api.ItemSpawnReason;
import com.gmail.nossr50.config.experience.ExperienceConfig;
import com.gmail.nossr50.datatypes.skills.SubSkillType;
import com.gmail.nossr50.mcMMO;
import com.gmail.nossr50.util.ItemUtils;
import com.gmail.nossr50.util.skills.RankUtils;

import org.bukkit.Material;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

public class Archery {
    public static double skillShotMaxBonusDamage = mcMMO.p.getAdvancedConfig()
            .getSkillShotDamageMax();

    public static double dazeBonusDamage = mcMMO.p.getAdvancedConfig().getDazeBonusDamage();

    public static final double DISTANCE_XP_MULTIPLIER = ExperienceConfig.getInstance()
            .getArcheryDistanceMultiplier();

    protected static void incrementTrackerValue(LivingEntity livingEntity) {
        TrackedEntity.increment(livingEntity);
    }

    /** @deprecated Entity arrow data is now managed by the hit and death handlers. */
    @Deprecated
    protected static void removeFromTracker(TrackedEntity trackedEntity) {
        trackedEntity.removeIfCurrent();
    }

    /** Removes arrows when this entity's death is excluded from arrow retrieval. */
    public static void removeTrackedEntity(final @NotNull LivingEntity livingEntity) {
        TrackedEntity.remove(livingEntity);
    }

    /**
     * Check for arrow retrieval.
     *
     * @param livingEntity The entity hit by the arrows
     */
    public static void arrowRetrievalCheck(@NotNull LivingEntity livingEntity) {
        final int arrowCount = TrackedEntity.take(livingEntity);
        if (arrowCount > 0) {
            ItemUtils.spawnItems(null, livingEntity.getLocation(),
                    new ItemStack(Material.ARROW), arrowCount,
                    ItemSpawnReason.ARROW_RETRIEVAL_ACTIVATED);
        }
    }

    public static double getSkillShotBonusDamage(Player player, double oldDamage) {
        final double damageBonusPercent = getDamageBonusPercent(player);
        final double newDamage = oldDamage + (oldDamage * damageBonusPercent);
        return Math.min(newDamage, oldDamage + Archery.skillShotMaxBonusDamage);
    }

    public static double getDamageBonusPercent(Player player) {
        return ((RankUtils.getRank(player, SubSkillType.ARCHERY_SKILL_SHOT))
                * (mcMMO.p.getAdvancedConfig().getSkillShotRankDamageMultiplier()) / 100.0D);
    }
}
