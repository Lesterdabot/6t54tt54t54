package com.hunted.mod.event;

import com.hunted.mod.HuntedMod;
import com.hunted.mod.config.HuntedConfig;
import com.hunted.mod.item.HuntedItems;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.living.LivingDropsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.*;

public class HuntedEventManager {

    public enum Phase { IDLE, PREP, ACTIVE }

    private static Phase           phase               = Phase.IDLE;
    private static MinecraftServer server              = null;

    private static int prepTicksLeft     = 0;
    private static int lastPrepAnnounced = -1;

    private static BlockPos    chestPos   = null;
    private static ServerLevel chestLevel = null;

    private static UUID targetUUID         = null;
    private static int  broadcastTicksLeft = 0;
    private static int  eventTicksLeft     = 0;

    private static boolean scanningForNewTarget = false;
    private static int     scanCooldown         = 0;

    private static int particleTick = 0;

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent e) {
        server = e.getServer();
    }

    public static boolean startEvent() {
        if (phase != Phase.IDLE) return false;
        int secs = HuntedConfig.PREP_TIME_SECONDS.get();
        prepTicksLeft     = secs * 20;
        lastPrepAnnounced = secs;
        phase             = Phase.PREP;
        broadcast(HuntedConfig.MSG_EVENT_START.get().replace("{time}", String.valueOf(secs)));
        return true;
    }

    public static Phase  getPhase()      { return phase; }
    public static String getTargetName() {
        if (server == null || targetUUID == null) return "none";
        ServerPlayer p = server.getPlayerList().getPlayer(targetUUID);
        return p != null ? p.getName().getString() : "none";
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post e) {
        if (server == null) return;
        switch (phase) {
            case PREP   -> tickPrep();
            case ACTIVE -> tickActive();
            default     -> {}
        }
    }

    private static void tickPrep() {
        prepTicksLeft--;
        int secsLeft = prepTicksLeft / 20;
        if (secsLeft != lastPrepAnnounced) {
            lastPrepAnnounced = secsLeft;
            if (secsLeft > 0 && (secsLeft % 10 == 0 || secsLeft <= 5))
                broadcast("§6[Hunted] §eCursed chest spawning in §c" + secsLeft + "s§e!");
        }
        if (prepTicksLeft <= 0) spawnChest();
    }

    private static void spawnChest() {
        if (server == null) { reset(); return; }

        ServerLevel overworld = server.overworld();
        int radius = HuntedConfig.CHEST_SPAWN_RADIUS.get();
        Random rand = new Random();

        BlockPos landPos = null;
        for (int attempt = 0; attempt < 200; attempt++) {
            int x = rand.nextInt(radius * 2) - radius;
            int z = rand.nextInt(radius * 2) - radius;
            int y = overworld.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
            BlockPos surface = new BlockPos(x, y, z);
            if (overworld.getFluidState(surface).isEmpty()
                    && overworld.getFluidState(surface.below()).isEmpty()) {
                landPos = surface;
                break;
            }
        }

        if (landPos == null) {
            int y = overworld.getHeight(Heightmap.Types.MOTION_BLOCKING, 0, 0);
            landPos = new BlockPos(0, y, 0);
        }

        overworld.setBlock(landPos, Blocks.CHEST.defaultBlockState(), 3);
        chestPos   = landPos;
        chestLevel = overworld;

        if (overworld.getBlockEntity(landPos) instanceof ChestBlockEntity chest) {
            chest.clearContent();
            chest.setItem(13, new ItemStack(HuntedItems.CURSED_CROWN.get(), 1));
            chest.setChanged();
        }

        phase              = Phase.ACTIVE;
        eventTicksLeft     = HuntedConfig.EVENT_DURATION_SECONDS.get() * 20;
        broadcastTicksLeft = HuntedConfig.BROADCAST_INTERVAL_SECONDS.get() * 20;

        int totalMins = HuntedConfig.EVENT_DURATION_SECONDS.get() / 60;
        broadcast(HuntedConfig.MSG_CHEST_SPAWNED.get()
            .replace("{x}", String.valueOf(landPos.getX()))
            .replace("{y}", String.valueOf(landPos.getY()))
            .replace("{z}", String.valueOf(landPos.getZ())));
        broadcast("§6[Hunted] §eThe hunt lasts §c" + totalMins + " minutes§e. Last one holding the crown wins!");

        HuntedMod.LOGGER.info("[Hunted] Chest spawned at {}", landPos);
    }

    private static void tickActive() {
        if (scanningForNewTarget) {
            scanCooldown--;
            if (scanCooldown <= 0) {
                scanCooldown = 20;
                doNewTargetScan();
            }
        }

        if (targetUUID != null || scanningForNewTarget) {
            eventTicksLeft--;
            int secsLeft = eventTicksLeft / 20;
            if (eventTicksLeft % 20 == 0) {
                if (secsLeft == 1800) broadcast("§6[Hunted] §e30 minutes remaining!");
                if (secsLeft == 600)  broadcast("§6[Hunted] §e10 minutes remaining!");
                if (secsLeft == 300)  broadcast("§6[Hunted] §c5 minutes remaining!");
                if (secsLeft == 60)   broadcast("§6[Hunted] §c1 minute remaining!");
                if (secsLeft == 30)   broadcast("§6[Hunted] §c30 seconds!");
                if (secsLeft == 10)   broadcast("§6[Hunted] §c10 seconds!");
                if (secsLeft <= 5 && secsLeft > 0) broadcast("§6[Hunted] §c" + secsLeft + "...");
            }
            if (eventTicksLeft <= 0) {
                endEventWithWinner();
                return;
            }
        }

        if (scanningForNewTarget || targetUUID == null) return;

        broadcastTicksLeft--;
        if (broadcastTicksLeft <= 0) {
            broadcastTargetCoords();
            broadcastTicksLeft = HuntedConfig.BROADCAST_INTERVAL_SECONDS.get() * 20;
        }

        if (broadcastTicksLeft % 60 == 0) {
            ServerPlayer target = server.getPlayerList().getPlayer(targetUUID);
            if (target != null)
                target.addEffect(new MobEffectInstance(MobEffects.GLOWING, 80, 0, false, false));
        }

        particleTick++;
        if (particleTick >= 20) {
            particleTick = 0;
            spawnTargetParticles();
        }
    }

    private static void endEventWithWinner() {
        ServerPlayer winner = null;
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (playerHasCrown(p)) { winner = p; break; }
        }
        if (winner != null) {
            broadcast("§6[Hunted] §aTime's up! §b" + winner.getName().getString() + " §asurvived the hunt and wins!");
            winner.addEffect(new MobEffectInstance(MobEffects.HERO_OF_THE_VILLAGE, 20 * 60, 0, false, false));
            winner.addEffect(new MobEffectInstance(MobEffects.ABSORPTION, 20 * 30, 3, false, false));
            removeAllCrowns(winner);
        } else {
            broadcast("§6[Hunted] §eTime's up — nobody was holding the crown!");
        }
        reset();
    }

    private static void spawnTargetParticles() {
        ServerPlayer target = server.getPlayerList().getPlayer(targetUUID);
        if (target == null) return;
        double x = target.getX(), y = target.getY(), z = target.getZ();
        ServerLevel level = (ServerLevel) target.level();
        for (int i = 0; i < 6; i++) {
            level.sendParticles(ParticleTypes.FLAME,            x, y + (i * 1.8), z, 2, 0.1,  0.1,  0.1,  0.02);
            level.sendParticles(ParticleTypes.TOTEM_OF_UNDYING, x, y + (i * 1.8), z, 1, 0.15, 0.15, 0.15, 0.05);
        }
    }

    private static void doNewTargetScan() {
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (playerHasCrown(p)) {
                scanningForNewTarget = false;
                setTarget(p);
                return;
            }
        }
        for (ServerLevel level : server.getAllLevels()) {
            if (!level.getEntitiesOfClass(ItemEntity.class,
                    new AABB(-30000, -64, -30000, 30000, 320, 30000),
                    ie -> ie.getItem().is(HuntedItems.CURSED_CROWN.get())).isEmpty()) {
                return;
            }
        }
        broadcast("§6[Hunted] §eThe crown vanished! Hunt over.");
        reset();
    }

    private static void broadcastTargetCoords() {
        ServerPlayer target = server.getPlayerList().getPlayer(targetUUID);
        if (target == null) return;
        int tx = (int) target.getX(), ty = (int) target.getY(), tz = (int) target.getZ();

        int secsLeft = eventTicksLeft / 20;
        String timeStr = (secsLeft / 60) + "m " + (secsLeft % 60) + "s";

        for (ServerPlayer viewer : server.getPlayerList().getPlayers()) {
            if (viewer.getUUID().equals(targetUUID)) {
                viewer.sendSystemMessage(Component.literal(
                    "§6[Hunted] §c⚠ You have the crown! §7Time left: §e" + timeStr));
                continue;
            }
            double dx = tx - viewer.getX();
            double dz = tz - viewer.getZ();
            int dist   = (int) Math.sqrt(dx * dx + dz * dz);
            String dir = getCardinalDirection(dx, dz);
            String msg = HuntedConfig.MSG_COORDS_BROADCAST.get()
                .replace("{player}", target.getName().getString())
                .replace("{x}", String.valueOf(tx))
                .replace("{y}", String.valueOf(ty))
                .replace("{z}", String.valueOf(tz))
                .replace("{dir}", dir)
                .replace("{dist}", String.valueOf(dist))
                + " §7| §e" + timeStr;
            viewer.sendSystemMessage(Component.literal(msg));
        }
    }

    @SubscribeEvent
    public static void onChestOpen(PlayerInteractEvent.RightClickBlock e) {
        if (phase != Phase.ACTIVE) return;
        if (chestPos == null || targetUUID != null) return;
        if (!e.getPos().equals(chestPos)) return;
        if (!(e.getEntity() instanceof ServerPlayer player)) return;

        if (chestLevel.getBlockEntity(chestPos) instanceof ChestBlockEntity chest) {
            for (int i = 0; i < chest.getContainerSize(); i++) {
                if (chest.getItem(i).is(HuntedItems.CURSED_CROWN.get())) {
                    chest.removeItem(i, 1);
                    chest.setChanged();
                    break;
                }
            }
        }

        ItemStack currentOffhand = player.getInventory().offhand.get(0);
        if (!currentOffhand.isEmpty()) player.getInventory().add(currentOffhand);
        player.getInventory().offhand.set(0, new ItemStack(HuntedItems.CURSED_CROWN.get(), 1));
        chestLevel.setBlock(chestPos, Blocks.AIR.defaultBlockState(), 3);
        setTarget(player);
    }

    /** Lock crown to offhand every tick */
    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent.Post e) {
        if (phase != Phase.ACTIVE || targetUUID == null) return;
        if (!(e.getEntity() instanceof ServerPlayer player)) return;
        if (!player.getUUID().equals(targetUUID)) return;
        if (!player.isAlive()) return;

        boolean inOffhand   = player.getInventory().offhand.get(0).is(HuntedItems.CURSED_CROWN.get());
        boolean inInventory = player.getInventory().items.stream().anyMatch(s -> s.is(HuntedItems.CURSED_CROWN.get()));

        if (inOffhand) return;

        if (inInventory) {
            for (int i = 0; i < player.getInventory().items.size(); i++) {
                if (player.getInventory().items.get(i).is(HuntedItems.CURSED_CROWN.get())) {
                    ItemStack crown = player.getInventory().items.get(i).copy();
                    player.getInventory().items.set(i, ItemStack.EMPTY);
                    ItemStack curOffhand = player.getInventory().offhand.get(0);
                    if (!curOffhand.isEmpty()) player.getInventory().add(curOffhand);
                    player.getInventory().offhand.set(0, crown);
                    break;
                }
            }
        } else {
            player.getInventory().offhand.set(0, new ItemStack(HuntedItems.CURSED_CROWN.get(), 1));
        }
    }

    /** Block crown from dropping unless target is dying */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onLivingDrops(LivingDropsEvent e) {
        if (targetUUID == null) return;
        if (!(e.getEntity() instanceof ServerPlayer player)) return;
        if (player.getUUID().equals(targetUUID) && !player.isAlive()) return;
        e.getDrops().removeIf(drop -> drop.getItem().is(HuntedItems.CURSED_CROWN.get()));
    }

    @SubscribeEvent
    public static void onPlayerDeath(LivingDeathEvent e) {
        if (phase != Phase.ACTIVE || targetUUID == null) return;
        if (!(e.getEntity() instanceof ServerPlayer dead)) return;
        if (!dead.getUUID().equals(targetUUID)) return;

        String killerName = "the environment";
        if (e.getSource().getEntity() instanceof ServerPlayer killer)
            killerName = killer.getName().getString();

        broadcast(HuntedConfig.MSG_TARGET_KILLED.get()
            .replace("{killer}", killerName)
            .replace("{target}", dead.getName().getString()));

        targetUUID           = null;
        scanningForNewTarget = true;
        scanCooldown         = 40;
    }

    @SubscribeEvent
    public static void onBlockBreak(BlockEvent.BreakEvent e) {
        if (phase != Phase.ACTIVE || chestPos == null || targetUUID != null) return;
        if (!e.getPos().equals(chestPos)) return;
        if (e.getPlayer() instanceof ServerPlayer p)
            p.sendSystemMessage(Component.literal("§c[Hunted] This chest is protected!"));
        e.setCanceled(true);
    }

    private static void setTarget(ServerPlayer player) {
        targetUUID         = player.getUUID();
        broadcastTicksLeft = HuntedConfig.BROADCAST_INTERVAL_SECONDS.get() * 20;
        particleTick       = 0;
        player.addEffect(new MobEffectInstance(MobEffects.GLOWING, 80, 0, false, false));
        broadcast(HuntedConfig.MSG_TARGET_ACQUIRED.get().replace("{player}", player.getName().getString()));
        HuntedMod.LOGGER.info("[Hunted] New target: {}", player.getName().getString());
    }

    private static boolean playerHasCrown(ServerPlayer p) {
        return p.getInventory().items.stream().anyMatch(s -> s.is(HuntedItems.CURSED_CROWN.get()))
            || p.getInventory().offhand.stream().anyMatch(s -> s.is(HuntedItems.CURSED_CROWN.get()));
    }

    private static void removeAllCrowns(ServerPlayer p) {
        p.getInventory().offhand.replaceAll(s -> s.is(HuntedItems.CURSED_CROWN.get()) ? ItemStack.EMPTY : s);
        p.getInventory().items.replaceAll(s -> s.is(HuntedItems.CURSED_CROWN.get()) ? ItemStack.EMPTY : s);
    }

    private static String getCardinalDirection(double dx, double dz) {
        double angle = Math.toDegrees(Math.atan2(dz, dx));
        if (angle < 0) angle += 360;
        if (angle >= 337.5 || angle < 22.5) return "East →";
        if (angle < 67.5)  return "SE ↘";
        if (angle < 112.5) return "South ↓";
        if (angle < 157.5) return "SW ↙";
        if (angle < 202.5) return "West ←";
        if (angle < 247.5) return "NW ↖";
        if (angle < 292.5) return "North ↑";
        return "NE ↗";
    }

    private static void broadcast(String msg) {
        if (server == null) return;
        server.getPlayerList().broadcastSystemMessage(Component.literal(msg), false);
    }

    private static void reset() {
        phase                = Phase.IDLE;
        prepTicksLeft        = 0;
        lastPrepAnnounced    = -1;
        chestPos             = null;
        chestLevel           = null;
        targetUUID           = null;
        broadcastTicksLeft   = 0;
        eventTicksLeft       = 0;
        scanningForNewTarget = false;
        scanCooldown         = 0;
        particleTick         = 0;
        HuntedMod.LOGGER.info("[Hunted] Reset to IDLE.");
    }
}
