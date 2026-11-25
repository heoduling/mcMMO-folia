package com.gmail.nossr50.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.gmail.nossr50.util.HealthbarDeathMessageUtils.DeathMessageName;
import com.google.gson.JsonParser;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class HealthbarDeathMessageUtilsTest {
    private static final UUID ATTACKER = UUID.fromString("12345678-9abc-def0-0fed-cba987654321");
    private static final UUID OTHER = UUID.fromString("98765432-1234-5678-90ab-cdef98765432");
    private static final String BAR = """
            {"text":"","extra":[{"text":"❤❤","color":"dark_red"},{"text":"❤","color":"gray"}]}""";
    private static final String ORIGINAL = """
            {"text":"OriginalCross","bold":true,"color":"gold"}""";

    @Test
    void restoresOnlyMatchingAttackersNameAndHover() {
        final String encoded = """
                {"translate":"death.attack.fireball","color":"aqua","extra":["suffix"],"with":["Victim",
                {"text":"","extra":[{"text":"[Team] "},%s],"insertion":"preserve",
                "hover_event":{"action":"show_entity","id":"minecraft:cow","uuid":"%s","name":%s}},
                {"text":"","extra":[{"text":"❤❤","color":"dark_red"},{"text":"❤","color":"gray"}],
                "hover_event":{"action":"show_entity","id":"minecraft:cow","uuid":"%s","name":%s}}]}"""
                .formatted(BAR, ATTACKER, BAR, OTHER, BAR);
        final String expected = encoded.replace("[Team] \"}," + BAR, "[Team] \"}," + ORIGINAL)
                .replace("\"uuid\":\"" + ATTACKER + "\",\"name\":" + BAR,
                        "\"uuid\":\"" + ATTACKER + "\",\"name\":" + ORIGINAL);

        final JsonParser parser = new JsonParser();
        assertEquals(parser.parse(expected), parser.parse(
                HealthbarDeathMessageUtils.restoreAttackerNameJson(encoded,
                        new DeathMessageName(ATTACKER, BAR, ORIGINAL))));
    }

    @Test
    void keepsThirdPartyNameWithMatchingUuid() {
        final String encoded = """
                {"text":"Third-party name","hover_event":{"action":"show_entity","id":"minecraft:cow",
                "uuid":"%s","name":{"text":"Third-party name"}}}""".formatted(ATTACKER);
        assertSame(encoded, HealthbarDeathMessageUtils.restoreAttackerNameJson(encoded,
                new DeathMessageName(ATTACKER, BAR, ORIGINAL)));
    }

    @Test
    void matchesOlderHoverEncodingAndKeepsTranslationForAnUnnamedMob() {
        final String encoded = """
                {"text":"","extra":[{"text":"❤❤","color":"dark_red"},{"text":"❤","color":"gray"}],
                "hoverEvent":{"action":"show_entity","contents":{"type":"minecraft:cow",
                "id":[305419896,-1698898192,267242409,-2023406815],"name":%s}}}""".formatted(BAR);
        final String translation = "{\"translate\":\"entity.minecraft.cow\"}";
        final String expected = """
                {"translate":"entity.minecraft.cow",
                "hoverEvent":{"action":"show_entity","contents":{"type":"minecraft:cow",
                "id":[305419896,-1698898192,267242409,-2023406815],"name":%s}}}""".formatted(translation);
        final JsonParser parser = new JsonParser();
        assertEquals(parser.parse(expected), parser.parse(
                HealthbarDeathMessageUtils.restoreAttackerNameJson(encoded,
                        new DeathMessageName(ATTACKER, BAR, translation))));
    }
}
