package com.hunted.mod.config;

import net.neoforged.neoforge.common.ModConfigSpec;
import java.util.List;

public class HuntedConfig {

    public static final ModConfigSpec SPEC;

    public static final ModConfigSpec.IntValue    PREP_TIME_SECONDS;
    public static final ModConfigSpec.IntValue    BROADCAST_INTERVAL_SECONDS;
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
            .comment("How often (seconds) the target coords are broadcast and waypoint updates. Default: 15")
            .defineInRange("broadcastIntervalSeconds", 15, 5, 120);
        b.pop().push("world");
        CHEST_SPAWN_RADIUS = b
            .comment("Random X/Z radius from spawn to place the event chest. Default: 200")
            .defineInRange("chestSpawnRadius", 200, 20, 2000);
        b.pop().push("loot");
        CHEST_LOOT = b
            .comment("Bonus items placed in the chest alongside the cursed crown. Format: 'modid:item count'")
            .defineListAllowEmpty("chestLoot",
                List.of("minecraft:diamond 3", "minecraft:golden_apple 2", "minecraft:experience_bottle 5"),
                e -> e instanceof String);
        b.pop().push("messages");
        MSG_EVENT_START = b
            .define("eventStart", "§6[Hunted] §eA cursed chest will spawn in §c{time}s§e! Claim the crown — and survive the hunt!");
        MSG_CHEST_SPAWNED = b
            .define("chestSpawned", "§6[Hunted] §aThe cursed chest has spawned at §f{x}, {y}, {z}§a! Claim the crown — become the target!");
        MSG_TARGET_ACQUIRED = b
            .define("targetAcquired", "§6[Hunted] §c☠ {player} §ehas claimed the cursed crown! §cTHEY ARE THE TARGET!");
        MSG_COORDS_BROADCAST = b
            .define("coordsBroadcast", "§6[Hunted] §cTARGET §f{player} §7| §e{x}, {y}, {z} §7| {dir} §7| §e{dist}m");
        MSG_TARGET_KILLED = b
            .define("targetKilled", "§6[Hunted] §b{killer} §eeliminated §c{target}§e! The crown dropped — grab it to become the next target!");
        MSG_EVENT_END = b
            .define("eventEnd", "§6[Hunted] §7The cursed crown has been lost. The hunt is over.");
        b.pop();
        SPEC = b.build();
    }
}
