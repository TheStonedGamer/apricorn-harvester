package com.brianthemint.apricornharvester.pokeball;

import baritone.api.IBaritone;
import baritone.api.pathing.goals.GoalNear;
import baritone.api.utils.Helper;
import com.brianthemint.apricornharvester.ApricornHarvestProcess;
import com.brianthemint.apricornharvester.TaskLocations;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Runs a {@link CraftPlan}: mines what is missing, smelts what has to be cooked and crafts the
 * rest, ending with the requested Poke Balls in the player's inventory.
 *
 * <p>This is a client-tick controller rather than an {@code IBaritoneProcess}, because it has to
 * hand control to Baritone's own mining process for the mining steps. It never fights another
 * process for the pathing goal: while mining, Baritone's miner owns the goal; the rest of the time
 * the factory drives {@code ICustomGoalProcess} directly.
 *
 * <p>Travel between the mining area and the crafting base uses whatever server commands are
 * configured in {@link PokeballConfig} ({@code /rtp}, {@code /home home2}, ...); with empty
 * commands the bot simply mines and crafts where it stands.
 */
public final class PokeballFactory {

    /** Ticks between container clicks, so the server can process each one. */
    private static final int CLICK_DELAY = 4;
    /** Ticks to wait for a container screen to open before giving up on the block. */
    private static final int SCREEN_TIMEOUT = 60;
    /** Ticks a single mining step may run before it is treated as failed. */
    private static final int MINE_TIMEOUT = 20 * 60 * 20;
    /** Ticks a single harvest step may run before it is treated as failed. */
    private static final int HARVEST_TIMEOUT = 20 * 60 * 20;
    /** Ticks a furnace batch may take before the factory stops waiting for it. */
    private static final int SMELT_TIMEOUT = 20 * 60 * 15;
    /** Items one fuel item smelts (coal/charcoal); used to size the fuel demand. */
    private static final int ITEMS_PER_FUEL = 8;

    private enum Stage { IDLE, TRAVEL_TO_MINE, STEP, TRAVEL_HOME, TRAVEL_FARM, DONE }

    private enum StepPhase { START, WALK, OPEN, WORK, WAIT, CLOSE, FINISHED }

    private final IBaritone baritone;
    /** The harvester process, so apricorn shortfalls can be picked off the farm. */
    private final ApricornHarvestProcess harvester;

    private Stage stage = Stage.IDLE;
    private StepPhase phase = StepPhase.START;
    private CraftPlan plan;
    private List<CraftPlan.Step> steps = new ArrayList<>();
    private int stepIndex;
    private int phaseTicks;
    private int clickDelay;
    private boolean travelledToMine;
    private boolean travelledHome;
    /** True once the bot has teleported to the tree farm for the harvest steps of this run. */
    private boolean travelledToFarm;

    /** Station (furnace / crafting table) the current step is working at. */
    private BlockPos station;
    /** Item count of the step's output when the step started, so progress is measured, not totals. */
    private int baselineCount;
    /** Crafts already placed in the current crafting step. */
    private int craftsDone;
    /** Items already put into the furnace for the current smelting step. */
    private int smeltQueued;
    /** Items already taken out of the furnace for the current smelting step. */
    private int smeltCollected;

    public PokeballFactory(IBaritone baritone, ApricornHarvestProcess harvester) {
        this.baritone = baritone;
        this.harvester = harvester;
    }

    private static void logDirect(String message) {
        Helper.HELPER.logDirect(message);
    }

    private static Minecraft mc() {
        return Minecraft.getInstance();
    }

    private static LocalPlayer player() {
        return mc().player;
    }

    // ------------------------------------------------------------------ lifecycle

    public boolean isRunning() {
        return stage != Stage.IDLE && stage != Stage.DONE;
    }

    /** Current step description, for the GUI's progress line. */
    public String status() {
        if (!isRunning()) {
            return "Idle";
        }
        return switch (stage) {
            case TRAVEL_TO_MINE -> "Travelling to the mining area";
            case TRAVEL_HOME -> "Travelling home";
            case TRAVEL_FARM -> "Travelling to the tree farm";
            case STEP -> (stepIndex + 1) + "/" + steps.size() + ": " + steps.get(stepIndex).describe();
            default -> "Working";
        };
    }

    /**
     * Plans and starts a run for the configured ball and count. Returns the plan so the caller can
     * report what it is going to do (or what is missing).
     */
    public CraftPlan start() {
        if (isRunning()) {
            logDirect("The Poke Ball factory is already running. Use #pokeball stop first.");
            return null;
        }
        LocalPlayer player = player();
        if (player == null) {
            return null;
        }
        RecipeHolder<?> ball = PokeballRecipes.ballRecipeById(PokeballConfig.getBallRecipeId());
        if (ball == null) {
            logDirect("No ball selected (or the server has no recipe for it). Open the GUI or use #pokeball ball <name>.");
            return null;
        }
        CraftPlan planned = PokeballRecipes.plan(ball, PokeballConfig.getCount(), player.getInventory());
        if (!planned.isPossible()) {
            logDirect("Cannot make " + PokeballConfig.getCount() + "x "
                    + PokeballRecipes.ballName(ball) + ": nothing can produce "
                    + String.join(", ", planned.missing) + ".");
            return planned;
        }
        if (planned.isEmpty()) {
            logDirect("Nothing to do: you already have everything.");
            return planned;
        }

        this.plan = planned;
        // Mining always comes first: it is the only step that needs the mining area, and every
        // mined item is a leaf of the recipe tree, so pulling those forward keeps dependencies.
        this.steps = new ArrayList<>(planned.steps);
        this.steps.sort(Comparator.comparingInt(PokeballFactory::stepOrder));
        this.stepIndex = 0;
        this.phase = StepPhase.START;
        this.phaseTicks = 0;
        this.clickDelay = 0;
        this.station = null;
        this.travelledToMine = false;
        this.travelledHome = false;
        this.travelledToFarm = false;
        this.stage = hasMiningSteps() && !PokeballConfig.getMineCommand().isEmpty()
                ? Stage.TRAVEL_TO_MINE
                : Stage.STEP;

        logDirect("Poke Ball factory started: " + PokeballConfig.getCount() + "x "
                + PokeballRecipes.ballName(ball) + ", " + steps.size() + " steps.");
        for (CraftPlan.Step step : steps) {
            logDirect("  - " + step.describe());
        }
        return planned;
    }

    public void stop() {
        if (!isRunning()) {
            logDirect("The Poke Ball factory is not running.");
            return;
        }
        finish("Poke Ball factory stopped.");
    }

    private void finish(String message) {
        stage = Stage.IDLE;
        phase = StepPhase.START;
        steps = new ArrayList<>();
        stepIndex = 0;
        station = null;
        try {
            baritone.getMineProcess().cancel();
            baritone.getCustomGoalProcess().setGoal(null);
            baritone.getPathingBehavior().cancelEverything();
        } catch (Throwable ignored) {
            // Baritone may already be idle; nothing to clean up then.
        }
        closeScreen();
        logDirect(message);
    }

    /**
     * Step order: mine first (the only steps that need the mining area), then harvest at the farm,
     * then everything that happens at the crafting base. Mined and harvested items are always
     * leaves of the recipe tree, so pulling them forward can never break a dependency.
     */
    private static int stepOrder(CraftPlan.Step step) {
        return switch (step.kind) {
            case MINE -> 0;
            case HARVEST -> 1;
            default -> 2;
        };
    }

    private boolean hasMiningSteps() {
        for (CraftPlan.Step step : steps) {
            if (step.kind == CraftPlan.Kind.MINE) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ tick

    /** Called every client tick from {@link com.brianthemint.apricornharvester.ApricornKeybinds}. */
    public void tick() {
        if (!isRunning() || player() == null || mc().level == null) {
            return;
        }
        if (clickDelay > 0) {
            clickDelay--;
            return;
        }
        switch (stage) {
            case TRAVEL_TO_MINE -> tickTravel(TaskLocations.Task.MINE, Stage.STEP);
            case TRAVEL_HOME -> tickTravel(TaskLocations.Task.CRAFT, Stage.STEP);
            case TRAVEL_FARM -> tickTravel(TaskLocations.Task.HARVEST, Stage.STEP);
            case STEP -> tickStep();
            default -> {
            }
        }
    }

    /** Sends the task's travel command once, then waits out the teleport. */
    private void tickTravel(TaskLocations.Task task, Stage next) {
        String command = TaskLocations.getCommand(task);
        if (phaseTicks == 0) {
            switch (task) {
                case MINE -> travelledToMine = true;
                case CRAFT -> travelledHome = true;
                case HARVEST -> travelledToFarm = true;
                default -> {
                }
            }
            if (command.isEmpty()) {
                stage = next;
                return;
            }
            logDirect("Running /" + command + " ...");
            player().connection.sendCommand(command);
        }
        phaseTicks++;
        if (phaseTicks >= PokeballConfig.getTravelWaitTicks()) {
            phaseTicks = 0;
            stage = next;
        }
    }

    private void tickStep() {
        if (stepIndex >= steps.size()) {
            finish("Poke Ball factory complete: " + PokeballConfig.getCount() + "x "
                    + ballLabel() + " done.");
            return;
        }
        CraftPlan.Step step = steps.get(stepIndex);

        // Harvesting happens at the tree farm, everything after it at the crafting base.
        if (step.kind == CraftPlan.Kind.HARVEST && !travelledToFarm) {
            stage = Stage.TRAVEL_FARM;
            phaseTicks = 0;
            return;
        }
        if (step.kind != CraftPlan.Kind.MINE && step.kind != CraftPlan.Kind.HARVEST
                && (travelledToMine || travelledToFarm) && !travelledHome) {
            stage = Stage.TRAVEL_HOME;
            phaseTicks = 0;
            return;
        }

        boolean done = switch (step.kind) {
            case MINE -> tickMine(step);
            case HARVEST -> tickHarvest(step);
            case SMELT -> tickSmelt(step);
            case CRAFT -> tickCraft(step);
        };
        if (done) {
            nextStep();
        }
    }

    private void nextStep() {
        stepIndex++;
        phase = StepPhase.START;
        phaseTicks = 0;
        station = null;
        craftsDone = 0;
        smeltQueued = 0;
        smeltCollected = 0;
    }

    // ------------------------------------------------------------------ mining

    /** Hands the step to Baritone's mine process and waits until the items are in the inventory. */
    private boolean tickMine(CraftPlan.Step step) {
        if (phase == StepPhase.START) {
            baselineCount = count(step.output);
            logDirect("Mining " + step.count + "x " + PokeballRecipes.nameOf(step.output)
                    + " (" + String.join(", ", step.mineBlocks) + ")...");
            baritone.getMineProcess().mineByName(step.count, step.mineBlocks.toArray(new String[0]));
            phase = StepPhase.WAIT;
            phaseTicks = 0;
            return false;
        }
        phaseTicks++;
        int gained = count(step.output) - baselineCount;
        if (gained >= step.count) {
            baritone.getMineProcess().cancel();
            logDirect("Mined " + gained + "x " + PokeballRecipes.nameOf(step.output) + ".");
            return true;
        }
        if (phaseTicks > MINE_TIMEOUT) {
            baritone.getMineProcess().cancel();
            finish("Gave up mining " + PokeballRecipes.nameOf(step.output) + " (only got "
                    + gained + "/" + step.count + ").");
            return false;
        }
        return false;
    }

    // ------------------------------------------------------------------ harvesting

    /**
     * Picks apricorns off the farm with the normal harvester process, over the selection saved for
     * the harvest task ({@code #loc harvest sel <name>}). The step ends when enough apricorns are
     * in the inventory, or when the harvest pass is over - a farm can simply not have that many
     * ripe apricorns right now.
     */
    private boolean tickHarvest(CraftPlan.Step step) {
        if (phase == StepPhase.START) {
            if (harvester == null) {
                finish("No harvester available for the apricorn step.");
                return false;
            }
            if (!TaskLocations.applySelection(baritone, TaskLocations.Task.HARVEST)
                    && baritone.getSelectionManager().getLastSelection() == null) {
                finish("No harvest area set. Save one with #save <name>, then #loc harvest sel <name>.");
                return false;
            }
            baselineCount = count(step.output);
            logDirect("Harvesting " + step.count + "x " + PokeballRecipes.nameOf(step.output) + "...");
            // Keep what we pick: the factory needs the apricorns in the inventory, not in a chest.
            harvester.setDepositEnabled(false);
            harvester.start();
            phase = StepPhase.WAIT;
            phaseTicks = 0;
            return false;
        }
        phaseTicks++;
        int gained = count(step.output) - baselineCount;
        if (gained >= step.count) {
            harvester.stop();
            logDirect("Harvested " + gained + "x " + PokeballRecipes.nameOf(step.output) + ".");
            return true;
        }
        if (!harvester.isActive()) {
            // The pass finished on its own: either the farm gave enough, or it did not have it.
            if (gained > 0) {
                logDirect("Harvest pass done: " + gained + "/" + step.count + " "
                        + PokeballRecipes.nameOf(step.output) + ".");
            }
            if (gained < step.count) {
                finish("The farm only had " + gained + "/" + step.count + " "
                        + PokeballRecipes.nameOf(step.output)
                        + ". Grow more (#bonemeal) and run #pokeball again.");
                return false;
            }
            return true;
        }
        if (phaseTicks > HARVEST_TIMEOUT) {
            harvester.stop();
            finish("Harvesting timed out (" + gained + "/" + step.count + ").");
            return false;
        }
        return false;
    }

    // ------------------------------------------------------------------ smelting

    /**
     * Smelts the step's input in the nearest furnace: shift-click fuel and input in, wait for the
     * output to appear, shift-click it out, repeat until the whole batch is done.
     */
    private boolean tickSmelt(CraftPlan.Step step) {
        switch (phase) {
            case START: {
                station = findStation(Blocks.FURNACE, Blocks.BLAST_FURNACE);
                if (station == null) {
                    finish("No furnace within " + PokeballConfig.getStationRadius()
                            + " blocks. Stand near your furnaces (or raise #pokeball radius).");
                    return false;
                }
                int fuelNeeded = Math.max(1, (step.smeltCount + ITEMS_PER_FUEL - 1) / ITEMS_PER_FUEL);
                if (count(PokeballConfig.getFuel()) < fuelNeeded) {
                    finish("Not enough " + PokeballRecipes.nameOf(PokeballConfig.getFuel())
                            + ": need " + fuelNeeded + " to smelt " + step.smeltCount + " items.");
                    return false;
                }
                smeltQueued = 0;
                smeltCollected = 0;
                baselineCount = count(step.output);
                logDirect("Smelting " + step.smeltCount + "x " + PokeballRecipes.nameOf(step.smeltInput)
                        + " at " + station + "...");
                phase = StepPhase.WALK;
                phaseTicks = 0;
                return false;
            }
            case WALK: {
                if (walkTo(station)) {
                    phase = StepPhase.OPEN;
                    phaseTicks = 0;
                }
                return false;
            }
            case OPEN: {
                if (openStation()) {
                    phase = StepPhase.WORK;
                    phaseTicks = 0;
                }
                return false;
            }
            case WORK: {
                AbstractContainerMenu menu = player().containerMenu;
                if (menu == null || menu.slots.size() < 3) {
                    phase = StepPhase.OPEN;
                    return false;
                }
                // Furnace menu: 0 = input, 1 = fuel, 2 = output, then the player inventory.
                if (menu.getSlot(1).getItem().getCount() < 8
                        && quickMoveFirst(menu, PokeballConfig.getFuel())) {
                    return false;
                }
                if (smeltQueued < step.smeltCount && menu.getSlot(0).getItem().getCount() < 32
                        && quickMoveFirst(menu, step.smeltInput)) {
                    smeltQueued = Math.min(step.smeltCount, smeltQueued + menu.getSlot(0).getItem().getCount());
                    return false;
                }
                phase = StepPhase.WAIT;
                phaseTicks = 0;
                return false;
            }
            case WAIT: {
                AbstractContainerMenu menu = player().containerMenu;
                if (menu == null || menu.slots.size() < 3) {
                    phase = StepPhase.OPEN;
                    return false;
                }
                phaseTicks++;
                ItemStack output = menu.getSlot(2).getItem();
                if (!output.isEmpty()) {
                    click(menu, 2, ClickType.QUICK_MOVE);
                    smeltCollected += output.getCount();
                    return false;
                }
                int produced = count(step.output) - baselineCount;
                if (produced >= step.count) {
                    phase = StepPhase.CLOSE;
                    return false;
                }
                // Input empty and everything queued has come out: feed the next batch.
                if (menu.getSlot(0).getItem().isEmpty() && smeltQueued < step.smeltCount) {
                    phase = StepPhase.WORK;
                    return false;
                }
                if (phaseTicks > SMELT_TIMEOUT) {
                    closeScreen();
                    finish("Smelting timed out (" + produced + "/" + step.count + " done).");
                    return false;
                }
                return false;
            }
            case CLOSE: {
                closeScreen();
                logDirect("Smelted " + (count(step.output) - baselineCount) + "x "
                        + PokeballRecipes.nameOf(step.output) + ".");
                return true;
            }
            default:
                return false;
        }
    }

    // ------------------------------------------------------------------ crafting

    /**
     * Crafts the step's recipe at the nearest crafting table. Placement goes through the vanilla
     * recipe-book path ({@code handlePlaceRecipe}), which is what makes the data components on
     * Pixelmon's lids and balls come out right - stuffing the grid slot by slot does not.
     */
    private boolean tickCraft(CraftPlan.Step step) {
        switch (phase) {
            case START: {
                station = findStation(Blocks.CRAFTING_TABLE);
                if (station == null) {
                    finish("No crafting table within " + PokeballConfig.getStationRadius()
                            + " blocks. Stand near your crafting table.");
                    return false;
                }
                craftsDone = 0;
                baselineCount = count(step.output);
                logDirect("Crafting " + step.crafts + "x " + PokeballRecipes.nameOf(step.output)
                        + " at " + station + "...");
                phase = StepPhase.WALK;
                phaseTicks = 0;
                return false;
            }
            case WALK: {
                if (walkTo(station)) {
                    phase = StepPhase.OPEN;
                    phaseTicks = 0;
                }
                return false;
            }
            case OPEN: {
                if (openStation()) {
                    phase = StepPhase.WORK;
                    phaseTicks = 0;
                }
                return false;
            }
            case WORK: {
                AbstractContainerMenu menu = player().containerMenu;
                if (menu == null || menu.slots.size() < 10) {
                    phase = StepPhase.OPEN;
                    return false;
                }
                if (craftsDone >= step.crafts) {
                    phase = StepPhase.CLOSE;
                    return false;
                }
                if (menu.getSlot(0).hasItem()) {
                    // Result of the previous placement: take it, that finishes one craft.
                    click(menu, 0, ClickType.QUICK_MOVE);
                    craftsDone++;
                    return false;
                }
                try {
                    mc().gameMode.handlePlaceRecipe(menu.containerId, step.recipe, false);
                } catch (Throwable t) {
                    closeScreen();
                    finish("Crafting failed: " + t);
                    return false;
                }
                clickDelay = CLICK_DELAY;
                phaseTicks++;
                if (phaseTicks > 20 * 30) {
                    closeScreen();
                    finish("Crafting stalled at " + craftsDone + "/" + step.crafts
                            + " (missing ingredients?).");
                    return false;
                }
                return false;
            }
            case CLOSE: {
                closeScreen();
                logDirect("Crafted " + (count(step.output) - baselineCount) + "x "
                        + PokeballRecipes.nameOf(step.output) + ".");
                return true;
            }
            default:
                return false;
        }
    }

    // ------------------------------------------------------------------ helpers

    private String ballLabel() {
        RecipeHolder<?> ball = PokeballRecipes.ballRecipeById(PokeballConfig.getBallRecipeId());
        return ball == null ? "Poke Balls" : PokeballRecipes.ballName(ball);
    }

    /** How many of an item the player carries. */
    private int count(Item item) {
        Inventory inv = player().getInventory();
        int total = 0;
        for (int i = 0; i < inv.items.size(); i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.getItem() == item) {
                total += stack.getCount();
            }
        }
        return total;
    }

    /** Nearest block of any of the given types within the configured radius, or null. */
    private BlockPos findStation(net.minecraft.world.level.block.Block... blocks) {
        BlockPos origin = player().blockPosition();
        int r = PokeballConfig.getStationRadius();
        BlockPos best = null;
        double bestDistSq = Double.MAX_VALUE;
        for (int x = -r; x <= r; x++) {
            for (int z = -r; z <= r; z++) {
                for (int y = -8; y <= 8; y++) {
                    BlockPos pos = origin.offset(x, y, z);
                    BlockState state = mc().level.getBlockState(pos);
                    boolean match = false;
                    for (net.minecraft.world.level.block.Block block : blocks) {
                        if (state.is(block)) {
                            match = true;
                            break;
                        }
                    }
                    if (!match) {
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

    /** Walks to within interaction range of the station; true once close enough. */
    private boolean walkTo(BlockPos pos) {
        double distSq = player().position().distanceToSqr(Vec3.atCenterOf(pos));
        if (distSq <= 16.0) {
            baritone.getCustomGoalProcess().setGoal(null);
            return true;
        }
        phaseTicks++;
        if (phaseTicks == 1 || phaseTicks % 100 == 0) {
            baritone.getCustomGoalProcess().setGoalAndPath(new GoalNear(pos, 2));
        }
        if (phaseTicks > 20 * 60) {
            finish("Could not reach " + pos + ".");
        }
        return false;
    }

    /** Right-clicks the station block and waits for its screen; true once the menu is open. */
    private boolean openStation() {
        if (mc().screen != null && player().containerMenu != player().inventoryMenu) {
            return true;
        }
        phaseTicks++;
        if (phaseTicks % 10 == 1) {
            Vec3 eye = player().getEyePosition();
            Vec3 hitVec = Vec3.atCenterOf(station);
            BlockHitResult hit = new BlockHitResult(hitVec, Direction.UP, station, false);
            baritone.getLookBehavior().updateTarget(rotationTo(eye, hitVec), true);
            try {
                mc().gameMode.useItemOn(player(), InteractionHand.MAIN_HAND, hit);
            } catch (Throwable t) {
                logDirect("Could not open the station: " + t);
            }
        }
        if (phaseTicks > SCREEN_TIMEOUT) {
            finish("The station at " + station + " did not open.");
        }
        return false;
    }

    private baritone.api.utils.Rotation rotationTo(Vec3 eye, Vec3 target) {
        Vec3 d = target.subtract(eye);
        double len = d.length();
        double yaw = Math.toDegrees(Math.atan2(-d.x, d.z));
        double pitch = len < 1e-6 ? 0.0 : Math.toDegrees(Math.asin(-d.y / len));
        return new baritone.api.utils.Rotation((float) yaw, (float) pitch);
    }

    /** Shift-clicks the first player-inventory stack of the item into the open container. */
    private boolean quickMoveFirst(AbstractContainerMenu menu, Item item) {
        int playerStart = menu.slots.size() - 36;
        for (int i = Math.max(0, playerStart); i < menu.slots.size(); i++) {
            if (menu.getSlot(i).getItem().getItem() == item) {
                click(menu, i, ClickType.QUICK_MOVE);
                return true;
            }
        }
        return false;
    }

    private void click(AbstractContainerMenu menu, int slot, ClickType type) {
        try {
            mc().gameMode.handleInventoryMouseClick(menu.containerId, slot, 0, type, player());
        } catch (Throwable t) {
            logDirect("Container click failed: " + t);
        }
        clickDelay = CLICK_DELAY;
    }

    private void closeScreen() {
        LocalPlayer player = player();
        if (player == null) {
            return;
        }
        if (mc().screen != null) {
            mc().setScreen(null);
        }
        if (player.containerMenu != player.inventoryMenu) {
            player.closeContainer();
        }
    }

}
