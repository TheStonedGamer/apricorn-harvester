package com.brianthemint.apricornharvester;

import baritone.api.IBaritone;
import com.brianthemint.apricornharvester.pokeball.PokeballFactory;

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

    public AddonContext(IBaritone baritone, ApricornHarvestProcess harvest,
                        ApricornPlantProcess plant, ApricornBonemealProcess bonemeal,
                        PokeballFactory pokeball) {
        this.baritone = baritone;
        this.harvest = harvest;
        this.plant = plant;
        this.bonemeal = bonemeal;
        this.pokeball = pokeball;
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

    /** True when any of the addon's jobs is currently running. */
    public boolean anyRunning() {
        return (harvest != null && harvest.isActive())
                || (plant != null && plant.isRunning())
                || (bonemeal != null && bonemeal.isRunning())
                || (pokeball != null && pokeball.isRunning());
    }
}
