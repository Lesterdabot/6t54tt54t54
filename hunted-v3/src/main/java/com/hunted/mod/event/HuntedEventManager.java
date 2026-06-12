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

    // Prep
    private static int prepTicksLeft     = 0;
    private static int lastPrepAnnounced = -1;

    // Chest
    private static BlockPos    chestPos   = null;
    private static ServerLevel chestLevel = null;

    // Target
    private static UUID targetUUID         = null;
    private static int  broadcastTicksLeft = 0;

    // Event timer — counts DOWN to zero, then whoever holds crown wins
    private static int eventTicksLeft = 0;

    // Post-death scan for new target
    private static boolean scanningForNewTarget = false;
    private static int     scanCooldown         = 0;

    private static int particleTick = 0;

    private static final String CHEST_WP  = "Cursed Chest";
    private static final String TARGET_WP = "TARGET";

    // ── Boot ───────────────────────────────────────────────────────────────

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent e) {
        server = e.getServer();
    }

    // ── Public API ─────────────────────────────────────────────────────────

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

    // ── Tick ───────────────────────────────────────────────────────────────

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post e) {
        if (server == null) return;
        switch (phase) {
            case PREP   -> tickPrep();
            case ACTIVE -> tickActive();
            default     -> {}
        }
    }

    // ── PREP ───────────────────────────────────────────────────────────────

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

    // ── CHEST SPAWN ────────────────────────────────────────────────────────

    private static void spawnChest() {
        if (server == null) { reset(); return; }

        ServerLevel overworld = server.overworld();
        int radius = HuntedConfig.CHEST_SPAWN_RADIUS.get();
        Random rand = new Random();

        BlockPos landPos = null;
        for (int attempt = 0; attempt < 50; attempt++) {
            int x = rand.nextInt(radius * 2) - radius;
            int z = rand.nextInt(radius * 2) - radius;
            int y = overworld.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            BlockPos surface = new BlockPos(x, y, z);
            BlockPos below   = surface.below();
            if (overworld.getBlockState(below).isSolid()
                    && overworld.getBlockState(surface).isAir()
                    && overworld.getFluidState(below).isEmpty()) {
                landPos = surface;
                break;
            }
        }

        if (landPos == null) {
            broadcast("§c[Hunted] Could not find a safe surface spot! Try again.");
            reset();
            return;
        }

        overworld.setBlock(landPos, Blocks.CHEST.defaultBlockState(), 3);
        chestPos   = landPos;
        chestLevel = overworld;

        if (overworld.getBlockEntity(landPos) instanceof ChestBlockEntity chest) {
            chest.clearContent();
            chest.setItem(13, new ItemStack(HuntedItems.CURSED_CROWN.get(), 1));
            chest.setChanged();
        }

        phase          = Phase.ACTIVE;
        eventTicksLeft = HuntedConfig.EVENT_DURATION_SECONDS.get() * 20;
        broadcastTicksLeft = HuntedConfig.BROADCAST_INTERVAL_SECONDS.get() * 20;

        int totalMins = HuntedConfig.EVENT_DURATION_SECONDS.get() / 60;
        String msg = HuntedConfig.MSG_CHEST_SPAWNED.get()
            .replace("{x}", String.valueOf(landPos.getX()))
            .replace("{y}", String.valueOf(landPos.getY()))
            .replace("{z}", String.valueOf(landPos.getZ()));
        broadcast(msg);
        broadcast("§6[Hunted] §eThe hunt lasts §c" + totalMins + " minutes§e. Last one holding the crown wins!");

        sendWaypointToAll(CHEST_WP, "C", landPos.getX(), landPos.getY(), landPos.getZ(), 4);
        HuntedMod.LOGGER.info("[Hunted] Chest spawned at {}, event duration {}s", landPos, HuntedConfig.EVENT_DURATION_SECONDS.get());
    }

    // ── ACTIVE ─────────────────────────────────────────────────────────────

    private static void tickActive() {
        // Post-death scan for new crown holder
        if (scanningForNewTarget) {
            scanCooldown--;
            if (scanCooldown <= 0) {
                scanCooldown = 20;
                doNewTargetScan();
            }
            // Still count down the event timer during scan
        }

        // Event timer countdown
        if (targetUUID != null || scanningForNewTarget) {
            eventTicksLeft--;

            // Announce time remaining at certain milestones
            int secsLeft = eventTicksLeft / 20;
            if (eventTicksLeft % 20 == 0) {
                if (secsLeft == 1800) broadcast("§6[Hunted] §e30 minutes remaining!");
                if (secsLeft == 600)  broadcast("§6[Hunted] §e10 minutes remaining!");
                if (secsLeft == 300)  broadcast("§6[Hunted] §c5 minutes remaining!");
                if (secsLeft == 60)   broadcast("§6[Hunted] §c1 minute remaining! Who holds the crown?!");
                if (secsLeft == 30)   broadcast("§6[Hunted] §c30 seconds!");
                if (secsLeft == 10)   broadcast("§6[Hunted] §c10 seconds!");
                if (secsLeft <= 5 && secsLeft > 0) broadcast("§6[Hunted] §c" + secsLeft + "...");
            }

            // Timer expired — whoever holds the crown wins
            if (eventTicksLeft <= 0) {
                endEventWithWinner();
                return;
            }
        }

        if (scanningForNewTarget) return;
        if (targetUUID == null) return;

        // Coord broadcast + waypoint update
        broadcastTicksLeft--;
        if (broadcastTicksLeft <= 0) {
            broadcastTargetCoords();
            broadcastTicksLeft = HuntedConfig.BROADCAST_INTERVAL_SECONDS.get() * 20;
        }

        // Refresh glowing effect every 3s
        if (broadcastTicksLeft % 60 == 0) {
            ServerPlayer target = server.getPlayerList().getPlayer(targetUUID);
            if (target != null)
                target.addEffect(new MobEffectInstance(MobEffects.GLOWING, 80, 0, false, false));
        }

        // Particle column every second
        particleTick++;
        if (particleTick >= 20) {
            particleTick = 0;
            spawnTargetParticles();
        }
    }

    private static void endEventWithWinner() {
        // Find who has the crown
        ServerPlayer winner = null;
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (playerHasCrown(p)) {
                winner = p;
                break;
            }
        }

        if (winner != null) {
            broadcast("§6[Hunted] §aTime's up! §b" + winner.getName().getString()
                + " §asurvived the hunt and wins!");
            // Give winner a totem + fireworks as celebration
            winner.addEffect(new MobEffectInstance(MobEffects.HERO_OF_THE_VILLAGE, 20 * 60, 0, false, false));
            winner.addEffect(new MobEffectInstance(MobEffects.ABSORPTION, 20 * 30, 3, false, false));
            // Remove crown from their inventory
            removeAllCrowns(winner);
        } else {
            broadcast("§6[Hunted] §eThe hunt is over — no one was holding the crown!");
        }

        removeWaypointFromAll(TARGET_WP);
        removeWaypointFromAll(CHEST_WP);
        reset();
    }

    private static void spawnTargetParticles() {
        ServerPlayer target = server.getPlayerList().getPlayer(targetUUID);
        if (target == null) return;
        double x = target.getX(), y = target.getY(), z = target.getZ();
        ServerLevel level = (ServerLevel) target.level();
        for (int i = 0; i < 6; i++) {
            level.sendParticles(ParticleTypes.FLAME,
                x, y + (i * 1.8), z, 2, 0.1, 0.1, 0.1, 0.02);
            level.sendParticles(ParticleTypes.TOTEM_OF_UNDYING,
                x, y + (i * 1.8), z, 1, 0.15, 0.15, 0.15, 0.05);
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
        // Crown still on ground as item entity — keep scanning
        for (ServerLevel level : server.getAllLevels()) {
            if (!level.getEntitiesOfClass(ItemEntity.class,
                    new AABB(-30000, -64, -30000, 30000, 320, 30000),
                    ie -> ie.getItem().is(HuntedItems.CURSED_CROWN.get())).isEmpty()) {
                return; // still waiting
            }
        }
        // Crown truly gone — event continues but nobody is the target yet
        // This shouldn't happen normally since crown auto-returns, but just in case
        broadcast("§6[Hunted] §eThe crown is lost! Event ending...");
        removeWaypointFromAll(TARGET_WP);
        reset();
    }

    private static void broadcastTargetCoords() {
        ServerPlayer target = server.getPlayerList().getPlayer(targetUUID);
        if (target == null) return;
        int tx = (int) target.getX(), ty = (int) target.getY(), tz = (int) target.getZ();

        // Time remaining
        int secsLeft = eventTicksLeft / 20;
        int minsLeft = secsLeft / 60;
        int secs     = secsLeft % 60;
        String timeStr = minsLeft + "m " + secs + "s";

        sendWaypointToAll(TARGET_WP, "T", tx, ty, tz, 4);

        for (ServerPlayer viewer : server.getPlayerList().getPlayers()) {
            if (viewer.getUUID().equals(targetUUID)) {
                viewer.sendSystemMessage(Component.literal(
                    "§6[Hunted] §c⚠ You have the crown! §7Time left: §e" + timeStr));
                continue;
            }
            double dx = tx - viewer.getX();
            double dz = tz - viewer.getZ();
            int dist  = (int) Math.sqrt(dx * dx + dz * dz);
            String dir = getCardinalDirection(dx, dz);
            String msg = HuntedConfig.MSG_COORDS_BROADCAST.get()
                .replace("{player}", target.getName().getString())
                .replace("{x}", String.valueOf(tx))
                .replace("{y}", String.valueOf(ty))
                .replace("{z}", String.valueOf(tz))
                .replace("{dir}", dir)
                .replace("{dist}", String.valueOf(dist))
                + " §7| §eTime: " + timeStr;
            viewer.sendSystemMessage(Component.literal(msg));
        }
    }

    // ── Events ─────────────────────────────────────────────────────────────

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

        player.getInventory().offhand.set(0, new ItemStack(HuntedItems.CURSED_CROWN.get(), 1));
        removeWaypointFromAll(CHEST_WP);
        chestLevel.setBlock(chestPos, Blocks.AIR.defaultBlockState(), 3);
        setTarget(player);
    }

    /** Keep crown locked to offhand every tick */
    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent.Post e) {
        if (phase != Phase.ACTIVE || targetUUID == null) return;
        if (!(e.getEntity() instanceof ServerPlayer player)) return;
        if (!player.getUUID().equals(targetUUID)) return;
        if (!player.isAlive()) return;

        boolean inOffhand   = player.getInventory().offhand.get(0).is(HuntedItems.CURSED_CROWN.get());
        boolean inInventory = player.getInventory().items.stream().anyMatch(s -> s.is(HuntedItems.CURSED_CROWN.get()));

        if (!inOffhand && inInventory) {
            // Moved to main inventory — push back to offhand
            for (int i = 0; i < player.getInventory().items.size(); i++) {
                if (player.getInventory().items.get(i).is(HuntedItems.CURSED_CROWN.get())) {
                    ItemStack crown = player.getInventory().items.get(i).copy();
                    player.getInventory().items.set(i, ItemStack.EMPTY);
                    ItemStack currentOffhand = player.getInventory().offhand.get(0);
                    if (!currentOffhand.isEmpty()) player.getInventory().add(currentOffhand);
                    player.getInventory().offhand.set(0, crown);
                    break;
                }
            }
        } else if (!inOffhand && !inInventory) {
            // Gone entirely while alive — restore it
            player.getInventory().offhand.set(0, new ItemStack(HuntedItems.CURSED_CROWN.get(), 1));
            player.sendSystemMessage(Component.literal("§c[Hunted] The curse won't let you escape!"));
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onLivingDrops(LivingDropsEvent e) {
        if (targetUUID == null) return;
        if (!(e.getEntity() instanceof ServerPlayer player)) return;
        // Allow crown to drop on death (transfer mechanic)
        if (player.getUUID().equals(targetUUID)) return;
        e.getDrops().removeIf(item -> item.getItem().is(HuntedItems.CURSED_CROWN.get()));
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

        removeWaypointFromAll(TARGET_WP);
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

    // ── Xaero Waypoints ────────────────────────────────────────────────────

    private static void sendWaypointToAll(String name, String initials, int x, int y, int z, int color) {
        if (server == null) return;
        String raw = "xaero_waypoint_add:" + name + ":" + initials + ":"
            + x + ":" + y + ":" + z + ":"
            + color + ":false:normal:gui.xaero_default:false:0:global:false";
        for (ServerPlayer p : server.getPlayerList().getPlayers())
            p.sendSystemMessage(Component.literal(raw));
    }

    private static void removeWaypointFromAll(String name) {
        if (server == null) return;
        String raw = "xaero_waypoint_add:" + name + ":X:0:64:0:8:true:normal:gui.xaero_default:false:0:global:false";
        for (ServerPlayer p : server.getPlayerList().getPlayers())
            p.sendSystemMessage(Component.literal(raw));
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private static void setTarget(ServerPlayer player) {
        targetUUID         = player.getUUID();
        broadcastTicksLeft = HuntedConfig.BROADCAST_INTERVAL_SECONDS.get() * 20;
        particleTick       = 0;
        player.addEffect(new MobEffectInstance(MobEffects.GLOWING, 80, 0, false, false));
        broadcast(HuntedConfig.MSG_TARGET_ACQUIRED.get()
            .replace("{player}", player.getName().getString()));
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
