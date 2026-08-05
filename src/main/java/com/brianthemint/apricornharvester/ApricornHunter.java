package com.brianthemint.apricornharvester;

import baritone.api.IBaritone;
import baritone.api.pathing.goals.GoalNear;
import baritone.api.utils.Helper;
import com.pixelmonmod.pixelmon.enums.items.ApricornType;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;

/**
 * Hunts for apricorn colours you do not have yet.
 *
 * <p>Wild apricorn trees are the only source of a colour you have never grown, and finding one is a
 * matter of looking around, teleporting somewhere new, and looking again. This does exactly that:
 * it scans the loaded world for apricorn leaves, reports which colours are nearby, and - when it
 * only cares about colours you are missing - hops with the configured travel command until one
 * turns up, then paths to it.
 */
public final class ApricornHunter {

    /** Horizontal radius scanned around the player. Beyond this the chunks are rarely loaded. */
    private static final int SCAN_RADIUS = 96;
    /** Vertical range scanned around the player. Apricorn trees grow on the surface. */
    private static final int SCAN_HEIGHT = 32;
    /** Ticks to wait after a hop before scanning, so the new chunks are there. */
    private static final int SETTLE_TICKS = 80;

    private final IBaritone baritone;

    private boolean running;
    private EnumSet<ApricornType> wanted = EnumSet.noneOf(ApricornType.class);
    private int hopsLeft;
    private int ticks;
    /** True while waiting out the settle time after a hop. */
    private boolean settling;

    public ApricornHunter(IBaritone baritone) {
        this.baritone = baritone;
    }

    private static void logDirect(String message) {
        Helper.HELPER.logDirect(message);
    }

    public boolean isRunning() {
        return running;
    }

    public String status() {
        return running ? "Hunting (" + hopsLeft + " hops left)" : "Idle";
    }

    /** Colours the player has no apricorn of - the default hunting list. */
    public static EnumSet<ApricornType> missingColours() {
        EnumSet<ApricornType> missing = EnumSet.allOf(ApricornType.class);
        if (Minecraft.getInstance().player == null) {
            return missing;
        }
        var inv = Minecraft.getInstance().player.getInventory();
        for (int i = 0; i < inv.items.size(); i++) {
            ItemStack stack = inv.getItem(i);
            for (ApricornType type : ApricornType.values()) {
                if (ApricornPlanting.isStackOf(stack, type)) {
                    missing.remove(type);
                }
            }
        }
        return missing;
    }

    /**
     * Scans the loaded world around the player for apricorn leaves.
     *
     * @return the closest block of each colour found
     */
    public static Map<ApricornType, BlockPos> scan() {
        Map<ApricornType, BlockPos> found = new EnumMap<>(ApricornType.class);
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            return found;
        }
        BlockPos origin = mc.player.blockPosition();
        Map<ApricornType, Double> best = new EnumMap<>(ApricornType.class);
        for (int x = -SCAN_RADIUS; x <= SCAN_RADIUS; x++) {
            for (int z = -SCAN_RADIUS; z <= SCAN_RADIUS; z++) {
                for (int y = -SCAN_HEIGHT; y <= SCAN_HEIGHT; y++) {
                    BlockPos pos = origin.offset(x, y, z);
                    BlockState state = mc.level.getBlockState(pos);
                    ApricornType type = ApricornPlanting.typeOfLeaves(state);
                    if (type == null) {
                        continue;
                    }
                    double d = pos.distSqr(origin);
                    if (d < best.getOrDefault(type, Double.MAX_VALUE)) {
                        best.put(type, d);
                        found.put(type, pos);
                    }
                }
            }
        }
        return found;
    }

    /** Reports everything in range, without hunting. */
    public static void report() {
        Map<ApricornType, BlockPos> found = scan();
        if (found.isEmpty()) {
            logDirect("No apricorn trees within " + SCAN_RADIUS + " blocks.");
            return;
        }
        logDirect("Apricorn trees in range:");
        BlockPos origin = Minecraft.getInstance().player.blockPosition();
        for (Map.Entry<ApricornType, BlockPos> entry : found.entrySet()) {
            BlockPos pos = entry.getValue();
            logDirect("  " + ApricornPlanting.displayName(entry.getKey()) + " at "
                    + pos.getX() + ", " + pos.getY() + ", " + pos.getZ()
                    + " (" + (int) Math.sqrt(pos.distSqr(origin)) + " blocks)");
        }
        EnumSet<ApricornType> missing = missingColours();
        missing.removeAll(found.keySet());
        if (!missing.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (ApricornType type : missing) {
                if (sb.length() > 0) {
                    sb.append(", ");
                }
                sb.append(ApricornPlanting.displayName(type));
            }
            logDirect("Still missing: " + sb + ". #hunt goes looking for them.");
        }
    }

    /**
     * Starts hunting. With an empty colour set it looks for whatever the player has none of.
     *
     * @param colours colours to look for, or empty for "everything I am missing"
     * @param hops    how many times it may teleport before giving up
     */
    public void start(EnumSet<ApricornType> colours, int hops) {
        if (running) {
            logDirect("Already hunting. Use #hunt stop first.");
            return;
        }
        this.wanted = colours.isEmpty() ? missingColours() : EnumSet.copyOf(colours);
        if (wanted.isEmpty()) {
            logDirect("You already have every apricorn colour.");
            return;
        }
        this.hopsLeft = Math.max(0, hops);
        this.ticks = 0;
        this.settling = false;
        this.running = true;
        logDirect("Hunting for " + names(wanted) + " (" + hopsLeft + " hops max).");
    }

    public void stop() {
        if (!running) {
            logDirect("Not hunting.");
            return;
        }
        running = false;
        logDirect("Hunt stopped.");
    }

    /** Called every client tick. */
    public void tick() {
        if (!running || Minecraft.getInstance().player == null) {
            return;
        }
        if (settling) {
            if (++ticks < SETTLE_TICKS) {
                return;
            }
            settling = false;
            ticks = 0;
        }

        Map<ApricornType, BlockPos> found = scan();
        for (ApricornType type : wanted) {
            BlockPos pos = found.get(type);
            if (pos != null) {
                running = false;
                logDirect("Found " + ApricornPlanting.displayName(type) + " apricorns at "
                        + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + " - heading over.");
                baritone.getCustomGoalProcess().setGoalAndPath(new GoalNear(pos, 3));
                return;
            }
        }

        if (hopsLeft <= 0) {
            running = false;
            logDirect("No " + names(wanted) + " apricorns found, and no hops left.");
            return;
        }
        hopsLeft--;
        String command = TaskLocations.getCommand(TaskLocations.Task.HUNT);
        if (command.isEmpty()) {
            running = false;
            logDirect("Nothing here, and no hunt command set. Use #loc hunt cmd rtp.");
            return;
        }
        logDirect("Nothing here - hopping (" + hopsLeft + " left).");
        TaskLocations.sendTravel(TaskLocations.Task.HUNT);
        settling = true;
        ticks = 0;
    }

    private static String names(EnumSet<ApricornType> types) {
        StringBuilder sb = new StringBuilder();
        for (ApricornType type : types) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(ApricornPlanting.displayName(type));
        }
        return sb.toString();
    }
}
