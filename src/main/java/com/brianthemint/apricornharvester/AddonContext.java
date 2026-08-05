package com.brianthemint.apricornharvester;

import baritone.api.IBaritone;
import com.brianthemint.apricornharvester.pokeball.PokeballFactory;
import com.brianthemint.apricornharvester.schedule.ScheduleRunner;

/**
 * The addon's live objects in one place: Baritone plus every job the screens and commands drive.
 *
 * <p>Built once in {@link ApricornHarvesterMod} when Baritone becomes available, then handed to the
 * commands and screens, so a new job does not mean threading another constructor argument through
 * half the addon.
 */
public final class AddonContext {

    private final IBaritone baritone;
    private final ApricornHarvestProcess harvest;
    private final ApricornPlantProcess plant;
    private final ApricornBonemealProcess bonemeal;
    private final PokeballFactory pokeball;
    private final OreMineController ore;
    private final ApricornHunter hunter;
    private final FarmMapper mapper;
    private final DepositController deposit;
    private final ToolRepairController repairer;
    private ScheduleRunner schedule;

    public AddonContext(IBaritone baritone, ApricornHarvestProcess harvest,
                        ApricornPlantProcess plant, ApricornBonemealProcess bonemeal,
                        PokeballFactory pokeball, OreMineController ore, ApricornHunter hunter,
                        FarmMapper mapper, DepositController deposit,
                        ToolRepairController repairer) {
        this.mapper = mapper;
        this.deposit = deposit;
        this.repairer = repairer;
        this.baritone = baritone;
        this.harvest = harvest;
        this.plant = plant;
        this.bonemeal = bonemeal;
        this.pokeball = pokeball;
        this.ore = ore;
        this.hunter = hunter;
    }

    public OreMineController ore() {
        return ore;
    }

    public ApricornHunter hunter() {
        return hunter;
    }

    public FarmMapper mapper() {
        return mapper;
    }

    public DepositController deposit() {
        return deposit;
    }

    public ToolRepairController repairer() {
        return repairer;
    }

    public ScheduleRunner schedule() {
        return schedule;
    }

    /** The runner is built from this context, so it is attached once both exist. */
    public void setSchedule(ScheduleRunner schedule) {
        this.schedule = schedule;
    }

    /** Drives the jobs that are plain tick controllers rather than Baritone processes. */
    public void tickControllers() {
        if (pokeball != null) {
            pokeball.tick();
        }
        if (ore != null) {
            ore.tick();
        }
        if (hunter != null) {
            hunter.tick();
        }
        if (mapper != null) {
            mapper.tick();
        }
        if (deposit != null) {
            deposit.tick();
        }
        if (repairer != null) {
            repairer.tick();
        }
        // Last: the schedule only starts the next module once the others report themselves idle.
        if (schedule != null) {
            schedule.tick();
        }
    }

    public IBaritone baritone() {
        return baritone;
    }

    public ApricornHarvestProcess harvest() {
        return harvest;
    }

    public ApricornPlantProcess plant() {
        return plant;
    }

    public ApricornBonemealProcess bonemeal() {
        return bonemeal;
    }

    public PokeballFactory pokeball() {
        return pokeball;
    }

    /**
     * Loads the selection saved for a task (when it has one) before that task is started from a
     * screen, so "Run" on the harvest tab works on the harvest area without a separate {@code #loc}
     * step. Selections already made by hand are left alone when the task has no saved area.
     */
    public void applyAreaFor(TaskLocations.Task task) {
        TaskLocations.applySelection(baritone, task);
    }

    /**
     * Loads a task's saved area only when there is nothing selected.
     *
     * <p>Pressing Run should work on what you have just marked out with {@code #sel}: quietly
     * replacing it with whatever the task remembers from last week is a nasty surprise. The
     * schedule still applies areas outright, because there nobody is standing over it choosing.
     */
    public void applyAreaIfUnset(TaskLocations.Task task) {
        if (baritone.getSelectionManager().getLastSelection() == null) {
            TaskLocations.applySelection(baritone, task);
        }
    }

    /** True when any of the addon's jobs is currently running. */
    public boolean anyRunning() {
        return (harvest != null && harvest.isActive())
                || (plant != null && plant.isRunning())
                || (bonemeal != null && bonemeal.isRunning())
                || (pokeball != null && pokeball.isRunning())
                || (ore != null && ore.isRunning())
                || (hunter != null && hunter.isRunning())
                || (mapper != null && mapper.isRunning())
                || (deposit != null && deposit.isRunning())
                || (repairer != null && repairer.isRunning());
    }
}
