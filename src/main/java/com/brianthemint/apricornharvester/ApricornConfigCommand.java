package com.brianthemint.apricornharvester;

import baritone.api.IBaritone;
import baritone.api.command.ICommand;
import baritone.api.command.argument.IArgConsumer;
import com.brianthemint.apricornharvester.pokeball.PokeballFactory;
import net.minecraft.client.Minecraft;

import java.util.List;
import java.util.stream.Stream;

/**
 * {@code #config} command: opens {@link ApricornConfigScreen}, the one screen that holds every
 * setting. The same screen is on a key binding (Options &gt; Controls &gt; Apricorn Harvester).
 */
public class ApricornConfigCommand implements ICommand {

    private final IBaritone baritone;
    private final ApricornPlantProcess plantProcess;
    private final PokeballFactory pokeballFactory;

    public ApricornConfigCommand(IBaritone baritone, ApricornPlantProcess plantProcess,
                                 PokeballFactory pokeballFactory) {
        this.baritone = baritone;
        this.plantProcess = plantProcess;
        this.pokeballFactory = pokeballFactory;
    }

    @Override
    public void execute(String label, IArgConsumer args) {
        Minecraft.getInstance().setScreen(
                new ApricornConfigScreen(baritone, plantProcess, pokeballFactory));
    }

    @Override
    public Stream<String> tabComplete(String label, IArgConsumer args) {
        return Stream.empty();
    }

    @Override
    public String getShortDesc() {
        return "Open the Apricorn Harvester settings screen";
    }

    @Override
    public List<String> getLongDesc() {
        return List.of(
                "Opens the settings screen: harvesting (tops, deposit, chest search radius),",
                "planting (spacing, clearance, row direction, default colour), bone meal",
                "(applications per sapling) and the task areas with their travel commands.",
                "",
                "Everything is saved as you change it, to",
                "  .minecraft/baritone/apricorn-settings.properties  (settings)",
                "  .minecraft/baritone/apricorn-tasks.properties     (task areas)",
                "",
                "The screen also has a key binding in Options > Controls > Apricorn Harvester."
        );
    }

    @Override
    public List<String> getNames() {
        return List.of("config", "apricornconfig");
    }
}
