package com.brianthemint.apricornharvester.pokeball;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.crafting.RecipeHolder;

import java.util.ArrayList;
import java.util.List;

/**
 * An ordered list of steps that turns whatever the player has into the requested Poke Balls.
 *
 * <p>The plan is produced by {@link PokeballRecipes#plan} by walking the recipe tree backwards
 * from the ball: every missing ingredient becomes either a mining step (no recipe produces it),
 * a smelting step or a crafting step, deepest dependency first. The executor
 * ({@link PokeballFactory}) then runs the steps in order without having to know anything about
 * Poke Ball recipes.
 */
public final class CraftPlan {

    public enum Kind { MINE, HARVEST, SMELT, CRAFT }

    /** One step: mine/smelt/craft {@code count} of {@link #output}. */
    public static final class Step {
        public final Kind kind;
        public final Item output;
        /** How many items this step must produce. */
        public final int count;
        /** For SMELT: the item that goes into the furnace. */
        public final Item smeltInput;
        /** For SMELT: how many inputs have to be smelted. */
        public final int smeltCount;
        /** For CRAFT: the recipe to place, and how often it has to be crafted. */
        public final RecipeHolder<?> recipe;
        public final int crafts;
        /** For MINE: the blocks that drop the item, as registry names for Baritone's miner. */
        public final List<String> mineBlocks;

        private Step(Kind kind, Item output, int count, Item smeltInput, int smeltCount,
                     RecipeHolder<?> recipe, int crafts, List<String> mineBlocks) {
            this.kind = kind;
            this.output = output;
            this.count = count;
            this.smeltInput = smeltInput;
            this.smeltCount = smeltCount;
            this.recipe = recipe;
            this.crafts = crafts;
            this.mineBlocks = mineBlocks;
        }

        public static Step mine(Item output, int count, List<String> blocks) {
            return new Step(Kind.MINE, output, count, null, 0, null, 0, blocks);
        }

        /** Apricorns are picked from the farm rather than mined or crafted. */
        public static Step harvest(Item output, int count) {
            return new Step(Kind.HARVEST, output, count, null, 0, null, 0, List.of());
        }

        public static Step smelt(Item output, int count, Item input, int inputCount) {
            return new Step(Kind.SMELT, output, count, input, inputCount, null, 0, List.of());
        }

        public static Step craft(Item output, int count, RecipeHolder<?> recipe, int crafts) {
            return new Step(Kind.CRAFT, output, count, null, 0, recipe, crafts, List.of());
        }

        /** One-line description for chat and the GUI's step list. */
        public String describe() {
            return switch (kind) {
                case MINE -> "Mine " + count + "x " + PokeballRecipes.nameOf(output);
                case HARVEST -> "Harvest " + count + "x " + PokeballRecipes.nameOf(output)
                        + " from the farm";
                case SMELT -> "Smelt " + smeltCount + "x " + PokeballRecipes.nameOf(smeltInput)
                        + " -> " + count + "x " + PokeballRecipes.nameOf(output);
                case CRAFT -> "Craft " + crafts + "x " + PokeballRecipes.nameOf(output)
                        + " (" + count + " total)";
            };
        }
    }

    public final List<Step> steps = new ArrayList<>();
    /** Items that nothing in the plan can produce - the run cannot start until they are supplied. */
    public final List<String> missing = new ArrayList<>();

    public boolean isEmpty() {
        return steps.isEmpty();
    }

    public boolean isPossible() {
        return missing.isEmpty();
    }
}
