package com.brianthemint.apricornharvester;

import baritone.api.IBaritone;
import baritone.api.pathing.goals.GoalNear;
import baritone.api.utils.Helper;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Empties the inventory into whatever containers are around, moving on to the next one as each
 * fills up.
 *
 * <p>This is the "put it all away" job: after a harvest, a mining trip or a crafting run the
 * inventory is full of things that belong in a chest. Everything goes except what the other jobs
 * need to keep working - the dirt they scaffold with, the bone meal, the furnace fuel, and any
 * tool - so the bot can go straight back out afterwards.
 */
public final class DepositController {

    private static final int CLICK_DELAY = 3;
    /** Dud clicks tolerated before a container is written off as full. */
    private static final int STALL_CLICKS = 3;
    private static final int OPEN_TIMEOUT = 40;
    private static final int WALK_TIMEOUT = 20 * 60;

    private enum Phase { IDLE, SCAN, WALK, OPEN, DEPOSIT, CLOSE, SETTLE }

    /** Ticks to wait after warping to another base before looking for its chests. */
    private static final int SETTLE_TICKS = 80;

    private final IBaritone baritone;

    private Phase phase = Phase.IDLE;
    private BlockPos target;
    private final Set<BlockPos> tried = new HashSet<>();
    private int ticks;
    private int clickDelay;
    private int noProgress;
    private int lastCount = -1;
    private int deposited;
    /** How far through the list of bases this run has got. */
    private int homeIndex;

    public DepositController(IBaritone baritone) {
        this.baritone = baritone;
    }

    private static void logDirect(String message) {
        Helper.HELPER.logDirect(message);
    }

    public boolean isRunning() {
        return phase != Phase.IDLE;
    }

    public String status() {
        return isRunning() ? "Depositing (" + deposited + " stacks)" : "Idle";
    }

    /** Starts emptying out into nearby containers. */
    public void start() {
        if (isRunning()) {
            logDirect("Already depositing.");
            return;
        }
        if (Minecraft.getInstance().player == null) {
            return;
        }
        if (depositableSlots().isEmpty()) {
            logDirect("Nothing to deposit.");
            return;
        }
        tried.clear();
        homeIndex = 0;
        deposited = 0;
        ticks = 0;
        noProgress = 0;
        lastCount = -1;
        phase = Phase.SCAN;
    }

    public void stop() {
        if (!isRunning()) {
            logDirect("Not depositing.");
            return;
        }
        finish("Depositing stopped after " + deposited + " stack(s).");
    }

    private void finish(String message) {
        phase = Phase.IDLE;
        target = null;
        closeScreen();
        baritone.getCustomGoalProcess().setGoal(null);
        logDirect(message);
    }

    // ---------------------------------------------------------------- tick

    /** Called every client tick. */
    public void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (!isRunning() || mc.player == null || mc.level == null) {
            return;
        }
        if (clickDelay > 0) {
            clickDelay--;
            return;
        }
        switch (phase) {
            case SCAN -> {
                if (depositableSlots().isEmpty()) {
                    finish("Everything is put away (" + deposited + " stack(s)).");
                    return;
                }
                target = nearestContainer();
                if (target == null) {
                    // Out of chests here: move on to the next base, if there is one.
                    if (travelToNextHome()) {
                        return;
                    }
                    finish("No container with room left (" + deposited + " stack(s) done).");
                    return;
                }
                phase = Phase.WALK;
                ticks = 0;
            }
            case WALK -> {
                double distSq = mc.player.position().distanceToSqr(Vec3.atCenterOf(target));
                if (distSq <= 16.0) {
                    baritone.getCustomGoalProcess().setGoal(null);
                    phase = Phase.OPEN;
                    ticks = 0;
                    return;
                }
                ticks++;
                if (ticks == 1 || ticks % 80 == 0) {
                    baritone.getCustomGoalProcess().setGoalAndPath(new GoalNear(target, 2));
                }
                if (ticks > WALK_TIMEOUT) {
                    logDirect("Could not reach the container at " + target + ".");
                    tried.add(target);
                    phase = Phase.SCAN;
                }
            }
            case OPEN -> {
                if (mc.screen != null && mc.player.containerMenu != mc.player.inventoryMenu) {
                    phase = Phase.DEPOSIT;
                    ticks = 0;
                    noProgress = 0;
                    lastCount = -1;
                    return;
                }
                ticks++;
                if (ticks % 10 == 1) {
                    Vec3 eye = mc.player.getEyePosition();
                    Vec3 hitVec = Vec3.atCenterOf(target);
                    baritone.getLookBehavior().updateTarget(rotationTo(eye, hitVec), true);
                    mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND,
                            new BlockHitResult(hitVec, Direction.UP, target, false));
                }
                if (ticks > OPEN_TIMEOUT) {
                    logDirect("The container at " + target + " did not open.");
                    tried.add(target);
                    phase = Phase.SCAN;
                }
            }
            case DEPOSIT -> {
                AbstractContainerMenu menu = mc.player.containerMenu;
                if (menu == null || menu.slots.isEmpty()) {
                    phase = Phase.CLOSE;
                    return;
                }
                // Progress is judged by items leaving the inventory: a shift-click into a full
                // container does nothing at all, and clicking is not evidence of anything.
                int carrying = depositableSlots().size();
                if (lastCount >= 0 && carrying >= lastCount) {
                    noProgress++;
                } else if (lastCount >= 0) {
                    deposited += lastCount - carrying;
                    noProgress = 0;
                }
                lastCount = carrying;

                if (carrying == 0) {
                    phase = Phase.CLOSE;
                    return;
                }
                if (noProgress > STALL_CLICKS) {
                    logDirect("Container at " + target + " is full - looking for another.");
                    tried.add(target);
                    closeScreen();
                    phase = Phase.SCAN;
                    return;
                }
                int playerStart = Math.max(0, menu.slots.size() - 36);
                for (int i = playerStart; i < menu.slots.size(); i++) {
                    Slot slot = menu.getSlot(i);
                    if (slot.hasItem() && isDepositable(slot.getItem())) {
                        mc.gameMode.handleInventoryMouseClick(menu.containerId, i, 0,
                                ClickType.QUICK_MOVE, mc.player);
                        clickDelay = CLICK_DELAY;
                        return;
                    }
                }
                phase = Phase.CLOSE;
            }
            case CLOSE -> {
                closeScreen();
                phase = Phase.SCAN;
            }
            case SETTLE -> {
                if (++ticks >= SETTLE_TICKS) {
                    phase = Phase.SCAN;
                    ticks = 0;
                }
            }
            default -> {
            }
        }
    }

    // ---------------------------------------------------------------- helpers

    /**
     * Sends the bot to the next configured base and waits for the chunks there.
     *
     * <p>A farm's storage is rarely all in one place, so the job works through the list of homes:
     * fill what is at this one, then warp to the next and carry on. The containers already written
     * off as full stay written off, since they may still be in range from the new spot.
     *
     * @return true when a hop was started
     */
    private boolean travelToNextHome() {
        List<String> homes = TaskLocations.homes();
        if (homeIndex >= homes.size()) {
            return false;
        }
        String command = homes.get(homeIndex++);
        var player = Minecraft.getInstance().player;
        if (player == null) {
            return false;
        }
        logDirect("Nothing left here - going to /" + command + "...");
        player.connection.sendCommand(command);
        phase = Phase.SETTLE;
        ticks = 0;
        return true;
    }

    /**
     * True for anything that should go in a chest. The jobs need dirt to scaffold with, bone meal
     * to grow with, fuel to smelt with and their tools, so those stay in the inventory.
     */
    public static boolean isDepositable(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        if (stack.isDamageableItem()) {
            return false;
        }
        var item = stack.getItem();
        if (item == Items.DIRT || item == Items.COARSE_DIRT || item == Items.ROOTED_DIRT
                || item == Items.BONE_MEAL) {
            return false;
        }
        return item != com.brianthemint.apricornharvester.pokeball.PokeballConfig.getFuel();
    }

    /** Inventory slots holding something worth putting away. */
    private static List<Integer> depositableSlots() {
        List<Integer> slots = new ArrayList<>();
        var player = Minecraft.getInstance().player;
        if (player == null) {
            return slots;
        }
        var inv = player.getInventory();
        for (int i = 0; i < inv.items.size(); i++) {
            if (isDepositable(inv.getItem(i))) {
                slots.add(i);
            }
        }
        return slots;
    }

    /**
     * The nearest container with free space that has not already been filled, looking at both the
     * blocks loaded around the player and the ones the farm survey recorded.
     */
    private BlockPos nearestContainer() {
        Minecraft mc = Minecraft.getInstance();
        BlockPos origin = mc.player.blockPosition();
        int r = AddonSettings.getChestRadius();
        BlockPos best = null;
        double bestDistSq = Double.MAX_VALUE;

        FarmMap farm = FarmSelection.current();
        if (farm != null && farm.isMapped()) {
            for (BlockPos pos : farm.containers) {
                if (tried.contains(pos)) {
                    continue;
                }
                double d = pos.distSqr(origin);
                if (d < bestDistSq) {
                    bestDistSq = d;
                    best = pos;
                }
            }
        }
        for (int x = -r; x <= r; x++) {
            for (int z = -r; z <= r; z++) {
                for (int y = -8; y <= 8; y++) {
                    BlockPos pos = origin.offset(x, y, z);
                    if (tried.contains(pos)) {
                        continue;
                    }
                    BlockState state = mc.level.getBlockState(pos);
                    if (!ApricornBlocks.isContainer(state)) {
                        continue;
                    }
                    double d = pos.distSqr(origin);
                    if (d < bestDistSq) {
                        bestDistSq = d;
                        best = pos;
                    }
                }
            }
        }
        return best;
    }

    private static baritone.api.utils.Rotation rotationTo(Vec3 eye, Vec3 target) {
        Vec3 d = target.subtract(eye);
        double len = d.length();
        double yaw = Math.toDegrees(Math.atan2(-d.x, d.z));
        double pitch = len < 1e-6 ? 0.0 : Math.toDegrees(Math.asin(-d.y / len));
        return new baritone.api.utils.Rotation((float) yaw, (float) pitch);
    }

    private static void closeScreen() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen != null) {
            mc.setScreen(null);
        }
        if (mc.player != null && mc.player.containerMenu != mc.player.inventoryMenu) {
            mc.player.closeContainer();
        }
    }
}
