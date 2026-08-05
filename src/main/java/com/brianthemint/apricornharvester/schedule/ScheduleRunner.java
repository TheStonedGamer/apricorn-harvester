package com.brianthemint.apricornharvester.schedule;

import baritone.api.utils.Helper;
import com.brianthemint.apricornharvester.AddonContext;
import com.brianthemint.apricornharvester.ApricornHunter;
import com.brianthemint.apricornharvester.OreMineController;
import com.brianthemint.apricornharvester.TaskLocations;
import com.brianthemint.apricornharvester.ToolUpkeep;
import com.pixelmonmod.pixelmon.enums.items.ApricornType;
import net.minecraft.client.Minecraft;

import java.util.EnumSet;
import java.util.List;

/**
 * Works through the {@link Schedule}: starts each enabled module, waits for it to finish, then
 * moves on to the next.
 *
 * <p>It is deliberately thin. Every module already knows how to run itself and when it is done, so
 * the runner only has to start things in order, travel to each job's area first, and keep out of
 * the way while a job is running. A run can loop, which is what turns the addon from a set of
 * commands into something that keeps a farm going on its own.
 */
public final class ScheduleRunner {

    /** Ticks between a module finishing and the next one starting. */
    private static final int STEP_GAP = 20;
    /** Ticks a module may take before the run gives up on it and moves on. */
    private static final int STEP_TIMEOUT = 20 * 60 * 45;

    private final AddonContext context;

    private boolean running;
    private List<ScheduleStep> steps = List.of();
    private int index;
    private int ticks;
    private boolean stepStarted;
    private int laps;

    public ScheduleRunner(AddonContext context) {
        this.context = context;
    }

    private static void logDirect(String message) {
        Helper.HELPER.logDirect(message);
    }

    public boolean isRunning() {
        return running;
    }

    public String status() {
        if (!running) {
            return "Idle";
        }
        if (index >= steps.size()) {
            return "Finishing";
        }
        return "Schedule " + (index + 1) + "/" + steps.size() + ": " + steps.get(index).label();
    }

    /** The step being run, or null when the schedule is not running. */
    public ScheduleStep currentStep() {
        return running && index < steps.size() ? steps.get(index) : null;
    }

    public void start() {
        if (running) {
            logDirect("The schedule is already running. Use #schedule stop first.");
            return;
        }
        steps = Schedule.enabledSteps();
        if (steps.isEmpty()) {
            logDirect("No modules are switched on. Open the Schedule tab and pick some.");
            return;
        }
        if (context.anyRunning()) {
            logDirect("Another job is running - cancel it before starting the schedule.");
            return;
        }
        index = 0;
        ticks = 0;
        laps = 0;
        stepStarted = false;
        running = true;
        StringBuilder plan = new StringBuilder();
        for (ScheduleStep step : steps) {
            if (plan.length() > 0) {
                plan.append(" -> ");
            }
            plan.append(step.label());
        }
        logDirect("Schedule started: " + plan + (Schedule.isRepeat() ? " (repeating)" : ""));
    }

    public void stop() {
        if (!running) {
            logDirect("The schedule is not running.");
            return;
        }
        running = false;
        stopCurrentJob();
        logDirect("Schedule stopped.");
    }

    // ---------------------------------------------------------------- tick

    /** Called every client tick. */
    public void tick() {
        if (!running || Minecraft.getInstance().player == null) {
            return;
        }
        if (index >= steps.size()) {
            if (Schedule.isRepeat()) {
                laps++;
                index = 0;
                stepStarted = false;
                ticks = 0;
                logDirect("Schedule lap " + laps + " done - starting again.");
                return;
            }
            running = false;
            logDirect("Schedule complete.");
            return;
        }

        ScheduleStep step = steps.get(index);
        if (!stepStarted) {
            ticks++;
            if (ticks < STEP_GAP) {
                return;
            }
            logDirect("Schedule: " + step.label() + "...");
            startStep(step);
            stepStarted = true;
            ticks = 0;
            return;
        }

        ticks++;
        if (isStepRunning(step)) {
            if (ticks > STEP_TIMEOUT) {
                logDirect("Schedule: " + step.label() + " ran too long - moving on.");
                stopCurrentJob();
                nextStep();
            }
            return;
        }
        // A module that never started (nothing to do, missing area, no dirt) simply falls through
        // after a moment, which is what the small gap before the check is for.
        if (ticks > STEP_GAP) {
            nextStep();
        }
    }

    private void nextStep() {
        index++;
        stepStarted = false;
        ticks = 0;
    }

    /** Sends the bot to where a module works before starting it. */
    private void travelFor(TaskLocations.Task task) {
        TaskLocations.applySelection(context.baritone(), task);
        TaskLocations.sendTravel(task);
    }

    private void startStep(ScheduleStep step) {
        switch (step) {
            case HARVEST -> {
                travelFor(TaskLocations.Task.HARVEST);
                if (context.harvest() != null) {
                    context.harvest().start();
                }
            }
            case BONEMEAL -> {
                travelFor(TaskLocations.Task.BONEMEAL);
                if (context.bonemeal() != null) {
                    context.bonemeal().start();
                }
            }
            case PLANT -> {
                travelFor(TaskLocations.Task.PLANT);
                if (context.plant() != null) {
                    context.plant().start();
                }
            }
            case MINE -> {
                if (context.ore() != null) {
                    // The ore module handles its own travel, cooldown included.
                    context.ore().start(scheduledOre(), Schedule.getMineAmount());
                }
            }
            case HUNT -> {
                if (context.hunter() != null) {
                    context.hunter().start(EnumSet.noneOf(ApricornType.class), Schedule.getHuntHops());
                }
            }
            case POKEBALL -> {
                if (context.pokeball() != null) {
                    context.pokeball().start();
                }
            }
            case DEPOSIT -> {
                if (context.deposit() != null) {
                    context.deposit().start();
                }
            }
            case TOOLS -> ToolUpkeep.ensurePickaxe(context.pokeball());
        }
    }

    private boolean isStepRunning(ScheduleStep step) {
        return switch (step) {
            case HARVEST -> context.harvest() != null && context.harvest().isActive();
            case BONEMEAL -> context.bonemeal() != null && context.bonemeal().isRunning();
            case PLANT -> context.plant() != null && context.plant().isRunning();
            case MINE -> context.ore() != null && context.ore().isRunning();
            case HUNT -> context.hunter() != null && context.hunter().isRunning();
            case POKEBALL -> context.pokeball() != null && context.pokeball().isRunning();
            case DEPOSIT -> context.deposit() != null && context.deposit().isRunning();
            // Tool upkeep is carried out by the crafting factory, so that is what to watch.
            case TOOLS -> context.pokeball() != null && context.pokeball().isRunning();
        };
    }

    private void stopCurrentJob() {
        if (context.harvest() != null && context.harvest().isActive()) {
            context.harvest().stop();
        }
        if (context.plant() != null && context.plant().isRunning()) {
            context.plant().stop();
        }
        if (context.bonemeal() != null && context.bonemeal().isRunning()) {
            context.bonemeal().stop();
        }
        if (context.ore() != null && context.ore().isRunning()) {
            context.ore().stop();
        }
        if (context.hunter() != null && context.hunter().isRunning()) {
            context.hunter().stop();
        }
        if (context.pokeball() != null && context.pokeball().isRunning()) {
            context.pokeball().stop();
        }
        if (context.deposit() != null && context.deposit().isRunning()) {
            context.deposit().stop();
        }
    }

    private static OreMineController.Ore scheduledOre() {
        OreMineController.Ore ore = OreMineController.Ore.parse(Schedule.getMineOre());
        if (ore != null) {
            return ore;
        }
        List<OreMineController.Ore> available = OreMineController.Ore.available();
        return available.isEmpty() ? OreMineController.Ore.STONE : available.get(0);
    }
}
