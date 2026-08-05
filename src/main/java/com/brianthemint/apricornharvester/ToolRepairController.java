package com.brianthemint.apricornharvester;

import baritone.api.IBaritone;
import baritone.api.pathing.goals.GoalNear;
import baritone.api.utils.Helper;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * Repairs a tool the cheap way: two worn ones of the same kind, put together at a crafting table,
 * come out as a single tool with their durability combined plus a bonus.
 *
 * <p>Vanilla's repair is a special recipe that the recipe book cannot place, so the two tools are
 * put into the grid by hand and the result taken out - the same clicks a player would make.
 */
public final class ToolRepairController {

    private static final int CLICK_DELAY = 3;
    private static final int OPEN_TIMEOUT = 40;
    private static final int WALK_TIMEOUT = 20 * 60;

    private enum Phase { IDLE, WALK, OPEN, PLACE_FIRST, PLACE_SECOND, TAKE, CLOSE }

    private final IBaritone baritone;

    private Phase phase = Phase.IDLE;
    private ToolConfig.ToolKind kind;
    private BlockPos table;
    private int ticks;
    private int clickDelay;

    public ToolRepairController(IBaritone baritone) {
        this.baritone = baritone;
    }

    private static void logDirect(String message) {
        Helper.HELPER.logDirect(message);
    }

    public boolean isRunning() {
        return phase != Phase.IDLE;
    }

    public String status() {
        return isRunning() ? "Repairing a " + kind.label().toLowerCase() : "Idle";
    }

    /** True when there are two worn tools of this kind to combine, and a table to do it at. */
    public boolean canCombine(ToolConfig.ToolKind kind) {
        return wornSlots(kind).size() >= 2;
    }

    /**
     * Inventory slots holding a worn tool of the kind, worst first. Only damaged ones are worth
     * combining: two undamaged tools would just waste one.
     */
    private List<Integer> wornSlots(ToolConfig.ToolKind kind) {
        List<Integer> slots = new ArrayList<>();
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return slots;
        }
        var inv = mc.player.getInventory();
        for (int i = 0; i < inv.items.size(); i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.isEmpty() || !stack.isDamageableItem() || stack.getDamageValue() <= 0) {
                continue;
            }
            ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
            if (id != null && id.getPath().endsWith(kind.suffix())) {
                slots.add(i);
            }
        }
        return slots;
    }

    public void start(ToolConfig.ToolKind kind) {
        if (isRunning() || Minecraft.getInstance().player == null) {
            return;
        }
        if (!canCombine(kind)) {
            logDirect("Nothing to combine: two worn " + kind.label().toLowerCase()
                    + "s of the same material are needed.");
            return;
        }
        this.kind = kind;
        this.table = nearestTable();
        if (table == null) {
            logDirect("No crafting table nearby to repair at.");
            return;
        }
        phase = Phase.WALK;
        ticks = 0;
    }

    public void stop() {
        if (!isRunning()) {
            return;
        }
        finish("Repair stopped.");
    }

    private void finish(String message) {
        phase = Phase.IDLE;
        table = null;
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
            case WALK -> {
                if (mc.player.position().distanceToSqr(Vec3.atCenterOf(table)) <= 16.0) {
                    baritone.getCustomGoalProcess().setGoal(null);
                    phase = Phase.OPEN;
                    ticks = 0;
                    return;
                }
                ticks++;
                if (ticks == 1 || ticks % 80 == 0) {
                    baritone.getCustomGoalProcess().setGoalAndPath(new GoalNear(table, 2));
                }
                if (ticks > WALK_TIMEOUT) {
                    finish("Could not reach the crafting table to repair at.");
                }
            }
            case OPEN -> {
                if (mc.screen != null && mc.player.containerMenu != mc.player.inventoryMenu) {
                    phase = Phase.PLACE_FIRST;
                    ticks = 0;
                    return;
                }
                ticks++;
                if (ticks % 10 == 1) {
                    Vec3 hitVec = Vec3.atCenterOf(table);
                    baritone.getLookBehavior().updateTarget(rotationTo(mc.player.getEyePosition(),
                            hitVec), true);
                    mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND,
                            new BlockHitResult(hitVec, Direction.UP, table, false));
                }
                if (ticks > OPEN_TIMEOUT) {
                    finish("The crafting table did not open.");
                }
            }
            case PLACE_FIRST, PLACE_SECOND -> {
                AbstractContainerMenu menu = mc.player.containerMenu;
                if (menu == null || menu.slots.size() < 10) {
                    finish("That is not a crafting table.");
                    return;
                }
                // Crafting table menu: 0 is the result, 1..9 the grid, then the inventory.
                int gridSlot = phase == Phase.PLACE_FIRST ? 1 : 2;
                Integer from = firstWornMenuSlot(menu);
                if (from == null) {
                    finish("Ran out of worn tools to combine.");
                    return;
                }
                click(menu, from, ClickType.PICKUP);
                click(menu, gridSlot, ClickType.PICKUP);
                phase = phase == Phase.PLACE_FIRST ? Phase.PLACE_SECOND : Phase.TAKE;
                ticks = 0;
            }
            case TAKE -> {
                AbstractContainerMenu menu = mc.player.containerMenu;
                ticks++;
                if (menu != null && menu.getSlot(0).hasItem()) {
                    click(menu, 0, ClickType.QUICK_MOVE);
                    phase = Phase.CLOSE;
                    return;
                }
                if (ticks > 40) {
                    // The two would not combine (different materials, most likely): put them back.
                    click(menu, 1, ClickType.QUICK_MOVE);
                    click(menu, 2, ClickType.QUICK_MOVE);
                    finish("Those two would not combine - different materials?");
                }
            }
            case CLOSE -> finish("Repaired a " + kind.label().toLowerCase() + ".");
            default -> {
            }
        }
    }

    /** The menu slot of a worn tool of the current kind, searching the player's inventory part. */
    private Integer firstWornMenuSlot(AbstractContainerMenu menu) {
        int playerStart = Math.max(0, menu.slots.size() - 36);
        for (int i = playerStart; i < menu.slots.size(); i++) {
            Slot slot = menu.getSlot(i);
            if (!slot.hasItem()) {
                continue;
            }
            ItemStack stack = slot.getItem();
            if (!stack.isDamageableItem() || stack.getDamageValue() <= 0) {
                continue;
            }
            ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
            if (id != null && id.getPath().endsWith(kind.suffix())) {
                return i;
            }
        }
        return null;
    }

    private BlockPos nearestTable() {
        Minecraft mc = Minecraft.getInstance();
        BlockPos origin = mc.player.blockPosition();
        int r = AddonSettings.getChestRadius();
        BlockPos best = null;
        double bestDistSq = Double.MAX_VALUE;
        for (int x = -r; x <= r; x++) {
            for (int z = -r; z <= r; z++) {
                for (int y = -8; y <= 8; y++) {
                    BlockPos pos = origin.offset(x, y, z);
                    if (!mc.level.getBlockState(pos).is(Blocks.CRAFTING_TABLE)) {
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

    private void click(AbstractContainerMenu menu, int slot, ClickType type) {
        Minecraft mc = Minecraft.getInstance();
        mc.gameMode.handleInventoryMouseClick(menu.containerId, slot, 0, type, mc.player);
        clickDelay = CLICK_DELAY;
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
