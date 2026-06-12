package com.hunted.mod.event;

import com.hunted.mod.HuntedMod;
import com.hunted.mod.config.HuntedConfig;
import com.hunted.mod.item.HuntedItems;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
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
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.*;

public class HuntedEventManager {

    // ── State ──────────────────────────────────────────────────────────────
    public enum Phase { IDLE, PREP, ACTIVE }

    private static Phase           phase               = Phase.IDLE;
    private static MinecraftServer server              = null;

    // Prep
    private static int  prepTicksLeft     = 0;
    private static int  lastPrepAnnounced = -1;

    // Chest — spawned fresh on the surface
    private static BlockPos    chestPos   = null;
    private static ServerLevel chestLevel = null;

    // Beacon column we placed at the chest (glass + beacon base)
    private static final List<BlockPos> beaconBlocks = new ArrayList<>();

    // Target
    private static UUID targetUUID         = null;
    private static int  broadcastTicksLeft = 0;

    // Post-death scan for new target
    private static boolean scanningForNewTarget = false;
    private static int     scanCooldown         = 0;

    // Delayed pickup check after chest open
    private static UUID pendingPickupUUID    = null;
    private static int  pickupCheckCountdown = 0;

    // Particle tick for target visual
    private static int particleTick = 0;

    // Xaero waypoint IDs so we can remove them when event ends
    private static final String CHEST_WAYPOINT_NAME  = "§c[Hunted] Cursed Chest";
    private static final String TARGET_WAYPOINT_NAME = "§c[Hunted] TARGET";

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
        HuntedMod.LOGGER.info("[Hunted] Event started — prep {}s", secs);
        return true;
    }

    public static Phase  getPhase()      { return phase; }
    public static String getTargetName() {
        if (server == null || targetUUID == null) return "none";
        ServerPlayer p = server.getPlayerList().getPlayer(targetUUID);
        return p != null ? p.getName().getString() : "none (offline)";
    }

    // ── Main tick ──────────────────────────────────────────────────────────

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

        // Fast surface finder — no chunk scan, just pick a random X/Z and get height
        BlockPos landPos = null;
        for (int attempt = 0; attempt < 20; attempt++) {
            int x = rand.nextInt(radius * 2) - radius;
            int z = rand.nextInt(radius * 2) - radius;
            // getHeight returns the Y of the first non-air block from above
            int y = overworld.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            BlockPos surface = new BlockPos(x, y, z);
            // Make sure it's not in water/lava
            if (!overworld.getBlockState(surface.below()).getFluidState().isEmpty()) continue;
            landPos = surface;
            break;
        }

        if (landPos == null) {
            broadcast("§c[Hunted] Could not find a safe spawn location! Try again.");
            reset();
            return;
        }

        // Place chest
        overworld.setBlock(landPos, Blocks.CHEST.defaultBlockState(), 3);
        chestPos   = landPos;
        chestLevel = overworld;

        // Fill chest
        if (overworld.getBlockEntity(landPos) instanceof ChestBlockEntity chest) {
            chest.clearContent();
            chest.setItem(13, new ItemStack(HuntedItems.CURSED_CROWN.get(), 1));
            int slot = 0;
            for (String entry : HuntedConfig.CHEST_LOOT.get()) {
                if (slot == 13) slot++;
                if (slot >= chest.getContainerSize()) break;
                String[] parts = entry.trim().split(" ");
                int count = parts.length > 1 ? parseSafe(parts[1], 1) : 1;
                var item = net.minecraft.core.registries.BuiltInRegistries.ITEM
                    .get(ResourceLocation.tryParse(parts[0]));
                if (item != null && item != net.minecraft.world.item.Items.AIR)
                    chest.setItem(slot, new ItemStack(item, count));
                slot++;
            }
            chest.setChanged();
        }

        // Place beacon column above chest for visibility
        placeBeaconColumn(overworld, landPos);

        // Broadcast
        phase = Phase.ACTIVE;
        broadcastTicksLeft = HuntedConfig.BROADCAST_INTERVAL_SECONDS.get() * 20;

        String msg = HuntedConfig.MSG_CHEST_SPAWNED.get()
            .replace("{x}", String.valueOf(landPos.getX()))
            .replace("{y}", String.valueOf(landPos.getY()))
            .replace("{z}", String.valueOf(landPos.getZ()));
        broadcast(msg);

        // Send Xaero waypoint for chest to all players
        sendXaeroWaypointToAll(CHEST_WAYPOINT_NAME, "X", landPos.getX(), landPos.getY(), landPos.getZ(), 4 /* red */);

        HuntedMod.LOGGER.info("[Hunted] Chest spawned at {}", landPos);
    }

    /**
     * Places a beacon beam column: iron block base → beacon → 10 blocks of glass.
     * Tracked so we can remove it on reset.
     */
    private static void placeBeaconColumn(ServerLevel level, BlockPos chestBase) {
        beaconBlocks.clear();

        // Beacon needs a pyramid base — place iron block below beacon
        BlockPos ironPos   = chestBase.above();
        BlockPos beaconPos = chestBase.above(2);

        level.setBlock(ironPos,   Blocks.IRON_BLOCK.defaultBlockState(), 3);
        level.setBlock(beaconPos, Blocks.BEACON.defaultBlockState(),     3);
        beaconBlocks.add(ironPos);
        beaconBlocks.add(beaconPos);

        // Glass column so beam passes through
        for (int i = 3; i <= 12; i++) {
            BlockPos glassPos = chestBase.above(i);
            // Only place if air (don't overwrite existing blocks)
            if (level.getBlockState(glassPos).isAir()) {
                level.setBlock(glassPos, Blocks.GLASS.defaultBlockState(), 3);
                beaconBlocks.add(glassPos);
            }
        }
    }

    private static void removeBeaconColumn() {
        if (chestLevel == null) return;
        for (BlockPos pos : beaconBlocks) {
            if (!chestLevel.getBlockState(pos).isAir()) {
                chestLevel.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
            }
        }
        beaconBlocks.clear();
    }

    // ── ACTIVE ─────────────────────────────────────────────────────────────

    private static void tickActive() {
        // Delayed pickup check
        if (pendingPickupUUID != null) {
            pickupCheckCountdown--;
            if (pickupCheckCountdown <= 0) checkPendingPickup();
        }

        // Post-death scan for new target
        if (scanningForNewTarget) {
            scanCooldown--;
            if (scanCooldown <= 0) {
                scanCooldown = 20;
                doNewTargetScan();
            }
            return;
        }

        if (targetUUID == null) return;

        // Broadcast + waypoint update
        broadcastTicksLeft--;
        if (broadcastTicksLeft <= 0) {
            broadcastTargetCoords();
            broadcastTicksLeft = HuntedConfig.BROADCAST_INTERVAL_SECONDS.get() * 20;
        }

        // Keep glowing effect active on target (refresh every 5s)
        if (broadcastTicksLeft % 100 == 0) {
            refreshGlowingEffect();
        }

        // Particle beam above target every second
        particleTick++;
        if (particleTick >= 20) {
            particleTick = 0;
            spawnTargetParticles();
        }

        // Sanity: verify target still has crown
        ServerPlayer target = server.getPlayerList().getPlayer(targetUUID);
        if (target != null && !playerHasCrown(target)) {
            // Crown was removed somehow (admin /clear etc.) — end event
            broadcast(HuntedConfig.MSG_EVENT_END.get());
            reset();
        }
    }

    private static void refreshGlowingEffect() {
        ServerPlayer target = server.getPlayerList().getPlayer(targetUUID);
        if (target == null) return;
        // Glowing shows white outline through walls — very visible
        target.addEffect(new MobEffectInstance(MobEffects.GLOWING, 120, 0, false, false));
    }

    private static void spawnTargetParticles() {
        ServerPlayer target = server.getPlayerList().getPlayer(targetUUID);
        if (target == null) return;

        double x = target.getX();
        double y = target.getY() + 0.5;
        double z = target.getZ();

        ServerLevel level = (ServerLevel) target.level();

        // Shoot a column of flame + totem particles upward — visible from distance
        for (int i = 0; i < 8; i++) {
            level.sendParticles(ParticleTypes.FLAME,
                x, y + (i * 1.5), z,
                3, 0.1, 0.1, 0.1, 0.02);
            level.sendParticles(ParticleTypes.TOTEM_OF_UNDYING,
                x, y + (i * 1.5), z,
                2, 0.15, 0.15, 0.15, 0.05);
        }
    }

    private static void checkPendingPickup() {
        ServerPlayer player = server.getPlayerList().getPlayer(pendingPickupUUID);
        pendingPickupUUID    = null;
        pickupCheckCountdown = 0;
        if (player == null) return;
        if (targetUUID == null && playerHasCrown(player)) {
            setTarget(player);
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
        // Check if crown is still a dropped item somewhere
        boolean crownExists = false;
        for (ServerLevel level : server.getAllLevels()) {
            if (!level.getEntitiesOfClass(ItemEntity.class,
                    new AABB(-30000, -64, -30000, 30000, 320, 30000),
                    ie -> ie.getItem().is(HuntedItems.CURSED_CROWN.get())).isEmpty()) {
                crownExists = true;
                break;
            }
        }
        if (!crownExists) {
            broadcast(HuntedConfig.MSG_EVENT_END.get());
            removeXaeroWaypointFromAll(TARGET_WAYPOINT_NAME);
            reset();
        }
    }

    private static void broadcastTargetCoords() {
        ServerPlayer target = server.getPlayerList().getPlayer(targetUUID);
        if (target == null) return;

        int tx = (int) target.getX();
        int ty = (int) target.getY();
        int tz = (int) target.getZ();

        // Update Xaero waypoint for all players
        sendXaeroWaypointToAll(TARGET_WAYPOINT_NAME, "T", tx, ty, tz, 4 /* red */);

        for (ServerPlayer viewer : server.getPlayerList().getPlayers()) {
            if (viewer.getUUID().equals(targetUUID)) {
                viewer.sendSystemMessage(Component.literal(
                    "§6[Hunted] §c⚠ You are the target! Position broadcasting every "
                    + HuntedConfig.BROADCAST_INTERVAL_SECONDS.get() + "s!"));
                continue;
            }
            double dx = tx - viewer.getX();
            double dz = tz - viewer.getZ();
            int dist = (int) Math.sqrt(dx * dx + dz * dz);
            String dir = getCardinalDirection(dx, dz);
            String msg = HuntedConfig.MSG_COORDS_BROADCAST.get()
                .replace("{player}", target.getName().getString())
                .replace("{x}", String.valueOf(tx))
                .replace("{y}", String.valueOf(ty))
                .replace("{z}", String.valueOf(tz))
                .replace("{dir}", dir)
                .replace("{dist}", String.valueOf(dist));
            viewer.sendSystemMessage(Component.literal(msg));
        }
    }

    // ── Events ─────────────────────────────────────────────────────────────

    /** Chest right-click — schedule pickup check */
    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock e) {
        if (phase != Phase.ACTIVE) return;
        if (chestPos == null || targetUUID != null) return;
        if (!e.getPos().equals(chestPos)) return;
        if (!(e.getEntity() instanceof ServerPlayer player)) return;
        pendingPickupUUID    = player.getUUID();
        pickupCheckCountdown = 20; // check after 1 second
    }

    /** Prevent dropping the crown */
    @SubscribeEvent
    public static void onItemDrop(PlayerEvent.ItemPickupEvent e) {
        // handled via death only
    }

    /** Cancel crown drop attempts */
    @SubscribeEvent
    public static void onDropItem(net.neoforged.neoforge.event.entity.player.PlayerDropsEvent e) {
        if (targetUUID == null) return;
        if (!(e.getEntity() instanceof ServerPlayer player)) return;
        // Only prevent drop if player is NOT dying (living players can't drop it)
        // On death we WANT it to drop — that's the transfer mechanic
        // So we only cancel drops that happen while alive
        if (player.isAlive()) {
            e.getDrops().removeIf(item -> item.getItem().is(HuntedItems.CURSED_CROWN.get()));
            player.getInventory().add(new ItemStack(HuntedItems.CURSED_CROWN.get(), 1));
        }
    }

    /** Target death — drop crown, start scanning */
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

        // Remove old target waypoint
        removeXaeroWaypointFromAll(TARGET_WAYPOINT_NAME);

        targetUUID           = null;
        scanningForNewTarget = true;
        scanCooldown         = 40; // give 2s for item to drop
        HuntedMod.LOGGER.info("[Hunted] {} eliminated by {}", dead.getName().getString(), killerName);
    }

    /** Protect the event chest until crown is claimed */
    @SubscribeEvent
    public static void onBlockBreak(BlockEvent.BreakEvent e) {
        if (phase != Phase.ACTIVE) return;
        if (chestPos == null || targetUUID != null) return;
        if (!e.getPos().equals(chestPos)) return;
        if (e.getPlayer() instanceof ServerPlayer p)
            p.sendSystemMessage(Component.literal("§c[Hunted] This chest is protected until the crown is claimed!"));
        e.setCanceled(true);
    }

    // ── Xaero Waypoint API ─────────────────────────────────────────────────

    /**
     * Sends a waypoint add command to all online players via Xaero's chat protocol.
     * Format: xaero_waypoint_add:name:initials:x:y:z:color:disabled:type:set:rotate_on_tp:tp_yaw:visibility_type:destination
     *
     * color: 0=black,1=darkBlue,2=darkGreen,3=darkAqua,4=darkRed,5=darkPurple,
     *        6=gold,7=gray,8=darkGray,9=blue,10=green,11=aqua,12=red,13=lightPurple,14=yellow,15=white
     */
    private static void sendXaeroWaypointToAll(String name, String initials, int x, int y, int z, int color) {
        if (server == null) return;
        // Xaero reads this as a chat message from the server — it intercepts the prefix client-side
        String waypointMsg = "xaero_waypoint_add:" + name + ":" + initials + ":"
            + x + ":" + y + ":" + z + ":"
            + color + ":false:normal:gui.xaero_default:false:0:global:false";
        Component pkt = Component.literal(waypointMsg);
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            p.sendSystemMessage(pkt);
        }
    }

    /**
     * Removes a waypoint by sending a delete command.
     * Xaero doesn't have a direct remove packet via chat — we disable it instead
     * by resending with disabled=true, which hides it.
     */
    private static void removeXaeroWaypointFromAll(String name) {
        if (server == null) return;
        // Overwrite with disabled waypoint at 0,0,0 — effectively hides it
        String waypointMsg = "xaero_waypoint_add:" + name + ":X:0:0:0:8:true:normal:gui.xaero_default:false:0:global:false";
        Component pkt = Component.literal(waypointMsg);
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            p.sendSystemMessage(pkt);
        }
    }

    // ── Utilities ──────────────────────────────────────────────────────────

    private static void setTarget(ServerPlayer player) {
        targetUUID         = player.getUUID();
        broadcastTicksLeft = 3 * 20;
        particleTick       = 0;

        // Remove chest waypoint (no longer needed)
        removeXaeroWaypointFromAll(CHEST_WAYPOINT_NAME);
        // Remove beacon column at chest
        removeBeaconColumn();

        // Give glowing effect immediately
        player.addEffect(new MobEffectInstance(MobEffects.GLOWING, 120, 0, false, false));

        broadcast(HuntedConfig.MSG_TARGET_ACQUIRED.get()
            .replace("{player}", player.getName().getString()));
        HuntedMod.LOGGER.info("[Hunted] New target: {}", player.getName().getString());
    }

    private static boolean playerHasCrown(ServerPlayer p) {
        return p.getInventory().items.stream().anyMatch(s -> s.is(HuntedItems.CURSED_CROWN.get()))
            || p.getInventory().offhand.stream().anyMatch(s -> s.is(HuntedItems.CURSED_CROWN.get()));
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
        removeBeaconColumn();
        phase                = Phase.IDLE;
        prepTicksLeft        = 0;
        lastPrepAnnounced    = -1;
        chestPos             = null;
        chestLevel           = null;
        targetUUID           = null;
        broadcastTicksLeft   = 0;
        scanningForNewTarget = false;
        scanCooldown         = 0;
        pendingPickupUUID    = null;
        pickupCheckCountdown = 0;
        particleTick         = 0;
        HuntedMod.LOGGER.info("[Hunted] Reset to IDLE.");
    }

    private static int parseSafe(String s, int def) {
        try { return Integer.parseInt(s); } catch (NumberFormatException ex) { return def; }
    }
}
