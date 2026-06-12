package com.hunted.mod.config;

import net.neoforged.neoforge.common.ModConfigSpec;
import java.util.List;

public class HuntedConfig {

    public static final ModConfigSpec SPEC;

    public static final ModConfigSpec.IntValue    PREP_TIME_SECONDS;
    public static final ModConfigSpec.IntValue    BROADCAST_INTERVAL_SECONDS;
    public static final ModConfigSpec.IntValue    EVENT_DURATION_SECONDS;
    public static final ModConfigSpec.IntValue    CHEST_SPAWN_RADIUS;
    public static final ModConfigSpec.ConfigValue<List<? extends String>> CHEST_LOOT;
    public static final ModConfigSpec.ConfigValue<String> MSG_EVENT_START;
    public static final ModConfigSpec.ConfigValue<String> MSG_CHEST_SPAWNED;
    public static final ModConfigSpec.ConfigValue<String> MSG_TARGET_ACQUIRED;
    public static final ModConfigSpec.ConfigValue<String> MSG_COORDS_BROADCAST;
    public static final ModConfigSpec.ConfigValue<String> MSG_TARGET_KILLED;
    public static final ModConfigSpec.ConfigValue<String> MSG_EVENT_END;

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();

        b.push("timing");
        PREP_TIME_SECONDS = b
            .comment("Countdown in seconds before the cursed chest spawns. Default: 60")
            .defineInRange("prepTimeSeconds", 60, 10, 600);
        BROADCAST_INTERVAL_SECONDS = b
            .comment("How often (seconds) the target coords broadcast and waypoint updates. Default: 5")
            .defineInRange("broadcastIntervalSeconds", 5, 3, 120);
        EVENT_DURATION_SECONDS = b
            .comment("How long (seconds) the event runs before the crown holder wins. Default: 3600 (1 hour)")
            .defineInRange("eventDurationSeconds", 3600, 60, 86400);
        b.pop().push("world");
        CHEST_SPAWN_RADIUS = b
            .comment("Random X/Z radius from spawn to place the event chest. Default: 200")
            .defineInRange("chestSpawnRadius", 200, 20, 2000);
        b.pop().push("loot");
        CHEST_LOOT = b
            .comment("Bonus items placed in the chest alongside the cursed crown. Format: 'modid:item count'")
            .defineListAllowEmpty("chestLoot", List.of(), e -> e instanceof String);
        b.pop().push("messages");
        MSG_EVENT_START = b
            .define("eventStart", "§6[Hunted] §eA cursed chest will spawn in §c{time}s§e! Claim the crown — and survive the hunt!");
        MSG_CHEST_SPAWNED = b
            .define("chestSpawned", "§6[Hunted] §aThe cursed chest has spawned at §f{x}, {y}, {z}§a! Claim the crown — become the target!");
        MSG_TARGET_ACQUIRED = b
            .define("targetAcquired", "§6[Hunted] §c☠ {player} §ehas the crown! §cTHEY ARE THE TARGET!");
        MSG_COORDS_BROADCAST = b
            .define("coordsBroadcast", "§6[Hunted] §cTARGET §f{player} §7| §e{x}, {y}, {z} §7| {dir} §7| §e{dist}m");
        MSG_TARGET_KILLED = b
            .define("targetKilled", "§6[Hunted] §b{killer} §eeliminated §c{target}§e! The crown dropped — grab it!");
        MSG_EVENT_END = b
            .define("eventEnd", "§6[Hunted] §7The hunt is over.");
        b.pop();
        SPEC = b.build();
    }
}
