package com.gmail.nossr50.util;

import com.gmail.nossr50.mcMMO;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.lang.reflect.Method;
import java.util.Objects;
import java.util.UUID;
import java.util.logging.Level;
import org.bukkit.damage.DamageSource;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Accesses the server's component API without coupling the Spigot build to its Adventure version.
 */
public final class HealthbarDeathMessageUtils {
    private static final @Nullable ComponentAccess COMPONENTS = resolveComponentAccess();

    private HealthbarDeathMessageUtils() {
    }

    /** Immutable names encoded by the server's Adventure serializer on the attacker's region. */
    public record DeathMessageName(@NotNull UUID attackerId, @NotNull String healthbarJson,
                                   @NotNull String originalNameJson) {
    }

    /** Captures the current name or the mob's untranslated default name on its owning thread. */
    static @Nullable String captureName(@NotNull LivingEntity mob) {
        if (COMPONENTS == null) {
            return null;
        }
        try {
            final Object customName = COMPONENTS.customName().invoke(mob);
            if (customName != null) {
                return COMPONENTS.serialize(customName);
            }
            final JsonObject translated = new JsonObject();
            translated.addProperty("translate", mob.getType().getTranslationKey());
            return translated.toString();
        } catch (ReflectiveOperationException | RuntimeException failure) {
            mcMMO.p.getLogger().log(Level.WARNING, "Could not capture a mob name for death messages", failure);
            return null;
        }
    }

    /** Restores the exact healthbar component of this attacker while retaining message styling. */
    public static void restoreAttackerName(@NotNull PlayerDeathEvent event, @NotNull DamageSource damage,
                                          @NotNull DeathMessageName name) {
        if (COMPONENTS == null) {
            return;
        }
        try {
            if (COMPONENTS.getDamageSource() != null) {
                final DamageSource death = (DamageSource) COMPONENTS.getDamageSource().invoke(event);
                if (damage.getDirectEntity() != death.getDirectEntity()
                        || !Objects.equals(damage.getDamageType(), death.getDamageType())) {
                    return;
                }
            }
            final Object message = COMPONENTS.getDeathMessage().invoke(event);
            if (message == null) {
                return;
            }
            final String encoded = COMPONENTS.serialize(message);
            final String restored = restoreAttackerNameJson(encoded, name);
            if (!encoded.equals(restored)) {
                final Object component = COMPONENTS.deserialize().invoke(COMPONENTS.serializer(), restored);
                COMPONENTS.setDeathMessage().invoke(event, component);
            }
        } catch (ReflectiveOperationException | RuntimeException failure) {
            mcMMO.p.getLogger().log(Level.WARNING, "Could not restore a mob name in a death message", failure);
        }
    }

    static @NotNull String restoreAttackerNameJson(@NotNull String encoded, @NotNull DeathMessageName name) {
        final JsonParser parser = new JsonParser();
        final JsonElement message = parser.parse(encoded);
        final JsonObject healthbar = asComponent(parser.parse(name.healthbarJson()));
        final JsonObject original = asComponent(parser.parse(name.originalNameJson()));
        return restoreComponents(message, name.attackerId(), healthbar, original) ? message.toString() : encoded;
    }

    private static boolean restoreComponents(@Nullable JsonElement message, UUID attackerId,
                                             JsonObject healthbar, JsonObject original) {
        if (message == null) {
            return false;
        }
        if (message.isJsonArray()) {
            boolean changed = false;
            for (final JsonElement child : message.getAsJsonArray()) {
                changed |= restoreComponents(child, attackerId, healthbar, original);
            }
            return changed;
        }
        final JsonObject component = asObject(message);
        if (component == null) {
            return false;
        }

        final JsonObject hover = asObject(component.get(
                component.has("hover_event") ? "hover_event" : "hoverEvent"));
        if (hover != null && "show_entity".equals(hover.get("action").getAsString())) {
            final JsonObject entity = hover.has("contents") ? asObject(hover.get("contents")) : hover;
            if (entity != null && matchesUuid(entity.get(entity.has("uuid") ? "uuid" : "id"), attackerId)
                    && replaceHealthbar(component, healthbar, original)) {
                final JsonObject hoverName = asComponent(entity.get("name"));
                if (hoverName != null && replaceHealthbar(hoverName, healthbar, original)) {
                    entity.add("name", hoverName);
                }
                return true;
            }
        }
        return restoreComponents(component.get("with"), attackerId, healthbar, original)
                | restoreComponents(component.get("extra"), attackerId, healthbar, original);
    }

    private static boolean replaceHealthbar(JsonObject component, JsonObject healthbar, JsonObject original) {
        // The entity/team wrapper may carry styles or prefix/suffix components of its own.
        if (healthbar.has("text") && Objects.equals(component.get("text"), healthbar.get("text"))
                && Objects.equals(component.get("extra"), healthbar.get("extra"))) {
            component.remove("text");
            component.remove("extra");
            healthbar.entrySet().forEach(field -> {
                if (field.getValue().equals(component.get(field.getKey()))) {
                    component.remove(field.getKey());
                }
            });
            original.entrySet().forEach(field -> {
                if (!component.has(field.getKey())) {
                    component.add(field.getKey(), field.getValue());
                }
            });
            return true;
        }
        final JsonElement extra = component.get("extra");
        if (extra != null && extra.isJsonArray()) {
            final JsonArray children = extra.getAsJsonArray();
            for (int index = 0; index < children.size(); index++) {
                final JsonObject child = asComponent(children.get(index));
                if (child != null && replaceHealthbar(child, healthbar, original)) {
                    children.set(index, child);
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean matchesUuid(@Nullable JsonElement encoded, UUID uuid) {
        if (encoded == null || encoded.isJsonNull()) {
            return false;
        }
        if (encoded.isJsonPrimitive()) {
            return uuid.toString().equals(encoded.getAsString());
        }
        if (!encoded.isJsonArray() || encoded.getAsJsonArray().size() != 4) {
            return false;
        }
        final JsonArray parts = encoded.getAsJsonArray();
        return uuid.getMostSignificantBits() == uuidHalf(parts, 0)
                && uuid.getLeastSignificantBits() == uuidHalf(parts, 2);
    }

    private static long uuidHalf(JsonArray parts, int index) {
        return ((long) parts.get(index).getAsInt() << Integer.SIZE)
                | Integer.toUnsignedLong(parts.get(index + 1).getAsInt());
    }

    private static @Nullable JsonObject asObject(@Nullable JsonElement value) {
        return value != null && value.isJsonObject() ? value.getAsJsonObject() : null;
    }

    private static @Nullable JsonObject asComponent(@Nullable JsonElement value) {
        if (value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
            final JsonObject component = new JsonObject();
            component.add("text", value);
            return component;
        }
        return asObject(value);
    }

    private static @Nullable ComponentAccess resolveComponentAccess() {
        try {
            final Method getDeathMessage = PlayerDeathEvent.class.getMethod("deathMessage");
            final Class<?> component = getDeathMessage.getReturnType();
            final Class<?> gson = Class.forName(component.getName().replace(".text.Component",
                            ".text.serializer.gson.GsonComponentSerializer"), true, component.getClassLoader());
            return new ComponentAccess(LivingEntity.class.getMethod("customName"), getDeathMessage,
                    PlayerDeathEvent.class.getMethod("deathMessage", component), gson.getMethod("gson").invoke(null),
                    gson.getMethod("serialize", component), gson.getMethod("deserialize", Object.class),
                    resolveDeathDamageSource());
        } catch (ReflectiveOperationException | LinkageError unsupported) {
            return null;
        }
    }

    private static @Nullable Method resolveDeathDamageSource() {
        try {
            return PlayerDeathEvent.class.getMethod("getDamageSource");
        } catch (NoSuchMethodException unsupported) {
            return null;
        }
    }

    private record ComponentAccess(Method customName, Method getDeathMessage, Method setDeathMessage,
                                   Object serializer, Method serialize, Method deserialize,
                                   @Nullable Method getDamageSource) {
        String serialize(Object component) throws ReflectiveOperationException {
            return (String) serialize.invoke(serializer, component);
        }
    }
}
