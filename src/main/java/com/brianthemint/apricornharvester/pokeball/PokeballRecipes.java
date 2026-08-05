package com.brianthemint.apricornharvester.pokeball;

import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.AbstractCookingRecipe;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.SmeltingRecipe;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Reads the Poke Ball crafting tree straight out of the client's recipe manager, so nothing about
 * Pixelmon's recipes is hard-coded: every ball type the server has a recipe for shows up, with the
 * base tier, lid and apricorns that recipe actually asks for.
 *
 * <p>In Pixelmon 9.3 the chain is plain vanilla crafting:
 * <ol>
 *   <li>apricorn -&gt; furnace -&gt; cooked apricorn,</li>
 *   <li>3 cooked apricorns (shaped {@code ABA}) -&gt; 3 lids carrying the ball id as a data
 *       component,</li>
 *   <li>3 ingots -&gt; 5 bases,</li>
 *   <li>base + stone button + matching lid -&gt; 1 Poke Ball.</li>
 * </ol>
 *
 * <p>{@link #plan} walks that tree backwards from the ball and turns everything the player is
 * short of into mining, smelting and crafting steps.
 */
public final class PokeballRecipes {

    /** Recipe id prefix of the final ball recipes (Pixelmon groups them under this path). */
    private static final String BALL_PATH = "pokeball/ball/";
    /** Recursion guard for pathological recipe trees (block -&gt; ingot -&gt; block loops). */
    private static final int MAX_DEPTH = 8;
    /**
     * Items whose "mine this" block is not the block of the same name. Baritone mines the block,
     * the block drops the item.
     */
    private static final Map<String, List<String>> MINE_OVERRIDES = Map.of(
            "minecraft:cobblestone", List.of("minecraft:stone", "minecraft:cobblestone"),
            "minecraft:cobbled_deepslate", List.of("minecraft:deepslate"),
            "minecraft:raw_iron", List.of("minecraft:iron_ore", "minecraft:deepslate_iron_ore"),
            "minecraft:raw_copper", List.of("minecraft:copper_ore", "minecraft:deepslate_copper_ore"),
            "minecraft:raw_gold", List.of("minecraft:gold_ore", "minecraft:deepslate_gold_ore"),
            "minecraft:coal", List.of("minecraft:coal_ore", "minecraft:deepslate_coal_ore"));

    private PokeballRecipes() {
    }

    // ---------------------------------------------------------------- discovery

    private static RecipeManager recipeManager() {
        Minecraft mc = Minecraft.getInstance();
        return mc.level == null ? null : mc.level.getRecipeManager();
    }

    /** Display name of an item, without needing a stack at the call site. */
    public static String nameOf(Item item) {
        return new ItemStack(item).getHoverName().getString();
    }

    /** Display name of a ball, taken from the crafted result stack (keeps the ball's components). */
    public static String ballName(RecipeHolder<?> ballRecipe) {
        ItemStack result = resultOf(ballRecipe);
        if (!result.isEmpty()) {
            return result.getHoverName().getString();
        }
        String path = ballRecipe.id().getPath();
        String last = path.substring(path.lastIndexOf('/') + 1).replace('_', ' ');
        return Character.toUpperCase(last.charAt(0)) + last.substring(1);
    }

    public static ItemStack resultOf(RecipeHolder<?> holder) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return ItemStack.EMPTY;
        }
        try {
            return holder.value().getResultItem(mc.level.registryAccess());
        } catch (Throwable t) {
            return ItemStack.EMPTY;
        }
    }

    /**
     * Every Poke Ball recipe the client knows, sorted by display name. These are the entries the
     * GUI's ball dropdown is built from.
     */
    public static List<RecipeHolder<?>> ballRecipes() {
        RecipeManager manager = recipeManager();
        List<RecipeHolder<?>> balls = new ArrayList<>();
        if (manager == null) {
            return balls;
        }
        for (RecipeHolder<?> holder : manager.getRecipes()) {
            if (!(holder.value() instanceof CraftingRecipe)) {
                continue;
            }
            ResourceLocation id = holder.id();
            if (!id.getPath().startsWith(BALL_PATH)) {
                continue;
            }
            if (resultOf(holder).isEmpty()) {
                continue;
            }
            balls.add(holder);
        }
        balls.sort(Comparator.comparing(PokeballRecipes::ballName));
        return balls;
    }

    /** The ball recipe with the given recipe id, or null when the server does not have it. */
    public static RecipeHolder<?> ballRecipeById(String id) {
        for (RecipeHolder<?> holder : ballRecipes()) {
            if (holder.id().toString().equals(id)) {
                return holder;
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- planning

    /**
     * Builds the step list that produces {@code count} balls of {@code ballRecipe}, given what the
     * player currently carries. Steps come out deepest-dependency-first, so running them in order
     * always has the inputs of the next step ready.
     */
    /**
     * Plans however many of any item, not just Poke Balls: the resolver only knows about recipes,
     * so "make me an iron pickaxe" is the same problem as "make me a Great Ball". Used by the tool
     * upkeep module.
     */
    public static CraftPlan planItem(Item target, int count, Inventory inventory) {
        CraftPlan plan = new CraftPlan();
        if (target == null || count <= 0) {
            return plan;
        }
        Resolver resolver = new Resolver(inventoryCounts(inventory));
        if (!resolver.ensure(target, count, 0)) {
            plan.missing.addAll(resolver.missing);
            return plan;
        }
        plan.steps.addAll(resolver.steps);
        plan.missing.addAll(resolver.missing);
        return plan;
    }

    public static CraftPlan plan(RecipeHolder<?> ballRecipe, int count, Inventory inventory) {
        CraftPlan plan = new CraftPlan();
        ItemStack result = resultOf(ballRecipe);
        if (result.isEmpty() || count <= 0) {
            return plan;
        }
        Resolver resolver = new Resolver(inventoryCounts(inventory));
        int perCraft = Math.max(1, result.getCount());
        int crafts = ceilDiv(count, perCraft);
        if (!resolver.craft(ballRecipe, crafts, 0)) {
            plan.missing.addAll(resolver.missing);
            return plan;
        }
        plan.steps.addAll(resolver.steps);
        plan.missing.addAll(resolver.missing);
        return plan;
    }

    /** Everything in the player's inventory, counted per item. */
    public static Map<Item, Integer> inventoryCounts(Inventory inventory) {
        Map<Item, Integer> counts = new HashMap<>();
        if (inventory == null) {
            return counts;
        }
        for (int i = 0; i < inventory.items.size(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty()) {
                counts.merge(stack.getItem(), stack.getCount(), Integer::sum);
            }
        }
        return counts;
    }

    private static int ceilDiv(int a, int b) {
        return (a + b - 1) / b;
    }

    /**
     * Backwards walk over the recipe tree. Keeps a virtual inventory so an item that is used by
     * two branches is only produced once, and records one step per production it has to add.
     */
    private static final class Resolver {

        private final Map<Item, Integer> virtual;
        private final List<CraftPlan.Step> steps = new ArrayList<>();
        private final List<String> missing = new ArrayList<>();
        private final Set<Item> inProgress = new HashSet<>();

        Resolver(Map<Item, Integer> inventory) {
            this.virtual = new LinkedHashMap<>(inventory);
        }

        /** Ensures {@code need} of {@code item} exist, producing them if the player is short. */
        boolean ensure(Item item, int need, int depth) {
            int have = virtual.getOrDefault(item, 0);
            if (have >= need) {
                virtual.put(item, have - need);
                return true;
            }
            int deficit = need - have;
            virtual.put(item, 0);

            if (depth > MAX_DEPTH || !inProgress.add(item)) {
                missing.add(nameOf(item) + " x" + deficit);
                return false;
            }
            try {
                if (produceByCrafting(item, deficit, depth) || produceBySmelting(item, deficit, depth)
                        || produceByHarvesting(item, deficit) || produceByMining(item, deficit)) {
                    return true;
                }
                missing.add(nameOf(item) + " x" + deficit);
                return false;
            } finally {
                inProgress.remove(item);
            }
        }

        private boolean produceByCrafting(Item item, int deficit, int depth) {
            for (RecipeHolder<?> holder : craftingRecipesFor(item)) {
                ItemStack out = resultOf(holder);
                int per = Math.max(1, out.getCount());
                int crafts = ceilDiv(deficit, per);
                Map<Item, Integer> savedVirtual = new LinkedHashMap<>(virtual);
                int savedSteps = steps.size();
                int savedMissing = missing.size();
                if (craft(holder, crafts, depth + 1)) {
                    // craft() already banked the surplus and recorded the step
                    virtual.merge(item, -deficit, Integer::sum);
                    if (virtual.get(item) < 0) {
                        virtual.put(item, 0);
                    }
                    return true;
                }
                virtual.clear();
                virtual.putAll(savedVirtual);
                while (steps.size() > savedSteps) {
                    steps.remove(steps.size() - 1);
                }
                while (missing.size() > savedMissing) {
                    missing.remove(missing.size() - 1);
                }
            }
            return false;
        }

        /** Resolves every ingredient of the recipe, then records the craft step. */
        boolean craft(RecipeHolder<?> holder, int crafts, int depth) {
            ItemStack out = resultOf(holder);
            if (out.isEmpty()) {
                return false;
            }
            Map<Item, Integer> perCraft = ingredientCounts(holder);
            if (perCraft == null) {
                return false;
            }
            for (Map.Entry<Item, Integer> entry : perCraft.entrySet()) {
                if (!ensure(entry.getKey(), entry.getValue() * crafts, depth + 1)) {
                    return false;
                }
            }
            steps.add(CraftPlan.Step.craft(out.getItem(), out.getCount() * crafts, holder, crafts));
            virtual.merge(out.getItem(), out.getCount() * crafts, Integer::sum);
            return true;
        }

        private boolean produceBySmelting(Item item, int deficit, int depth) {
            RecipeHolder<?> smelting = smeltingRecipeFor(item);
            if (smelting == null) {
                return false;
            }
            Item input = firstIngredientItem(smelting);
            if (input == null) {
                return false;
            }
            if (!ensure(input, deficit, depth + 1)) {
                return false;
            }
            steps.add(CraftPlan.Step.smelt(item, deficit, input, deficit));
            return true;
        }

        /** Raw apricorns come off the farm's trees, which the harvester process picks. */
        private boolean produceByHarvesting(Item item, int deficit) {
            if (!isRawApricorn(item)) {
                return false;
            }
            steps.add(CraftPlan.Step.harvest(item, deficit));
            return true;
        }

        private boolean produceByMining(Item item, int deficit) {
            List<String> blocks = mineBlocksFor(item);
            if (blocks.isEmpty()) {
                return false;
            }
            steps.add(CraftPlan.Step.mine(item, deficit, blocks));
            return true;
        }
    }

    // ---------------------------------------------------------------- recipe lookup helpers

    /** All crafting recipes whose result is the given item, simplest (fewest ingredients) first. */
    private static List<RecipeHolder<?>> craftingRecipesFor(Item item) {
        RecipeManager manager = recipeManager();
        List<RecipeHolder<?>> out = new ArrayList<>();
        if (manager == null) {
            return out;
        }
        for (RecipeHolder<?> holder : manager.getRecipes()) {
            if (!(holder.value() instanceof CraftingRecipe)) {
                continue;
            }
            ItemStack result = resultOf(holder);
            if (!result.isEmpty() && result.getItem() == item) {
                out.add(holder);
            }
        }
        out.sort(Comparator.comparingInt(h -> h.value().getIngredients().size()));
        return out;
    }

    /** The furnace recipe producing the item, or null. Blasting/smoking are ignored on purpose. */
    private static RecipeHolder<?> smeltingRecipeFor(Item item) {
        RecipeManager manager = recipeManager();
        if (manager == null) {
            return null;
        }
        for (RecipeHolder<?> holder : manager.getRecipes()) {
            if (!(holder.value() instanceof SmeltingRecipe)) {
                continue;
            }
            ItemStack result = resultOf(holder);
            if (!result.isEmpty() && result.getItem() == item) {
                return holder;
            }
        }
        return null;
    }

    /**
     * How many of each concrete item one craft of the recipe needs. Every ingredient is resolved
     * to one concrete item: the one the client can actually name (tags such as
     * {@code c:ingots/platinum} or {@code minecraft:stone_buttons} pick their first entry, which
     * for Pixelmon's recipes is the intended one).
     */
    private static Map<Item, Integer> ingredientCounts(RecipeHolder<?> holder) {
        Map<Item, Integer> counts = new LinkedHashMap<>();
        for (Ingredient ingredient : holder.value().getIngredients()) {
            if (ingredient.isEmpty()) {
                continue;
            }
            ItemStack[] options = ingredient.getItems();
            if (options.length == 0) {
                return null;
            }
            counts.merge(options[0].getItem(), 1, Integer::sum);
        }
        return counts.isEmpty() ? null : counts;
    }

    /** The item that goes into the furnace for a cooking recipe. */
    private static Item firstIngredientItem(RecipeHolder<?> holder) {
        if (!(holder.value() instanceof AbstractCookingRecipe)) {
            return null;
        }
        for (Ingredient ingredient : holder.value().getIngredients()) {
            ItemStack[] options = ingredient.getItems();
            if (options.length > 0) {
                return options[0].getItem();
            }
        }
        return null;
    }

    /**
     * The blocks Baritone should mine to obtain the item: the block of the same registry name,
     * plus the handful of items whose source block is named differently (cobblestone from stone,
     * raw ores from their ore blocks).
     */
    public static List<String> mineBlocksFor(Item item) {
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(item);
        if (id == null) {
            return List.of();
        }
        List<String> override = MINE_OVERRIDES.get(id.toString());
        if (override != null) {
            return override;
        }
        Block block = BuiltInRegistries.BLOCK.get(id);
        if (block != null && block != Blocks.AIR) {
            return List.of(id.toString());
        }
        return List.of();
    }

    /**
     * True for a raw (uncooked) apricorn item - {@code pixelmon:red_apricorn} and friends. Those
     * are the only inputs of the whole tree that are neither crafted, smelted nor mined: they are
     * picked off the farm's trees by the harvester.
     */
    public static boolean isRawApricorn(Item item) {
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(item);
        if (id == null) {
            return false;
        }
        String path = id.getPath();
        return path.endsWith("_apricorn") && !path.startsWith("cooked_");
    }

    /** Registry name of an item, for settings and chat. */
    public static String idOf(Item item) {
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(item);
        return id == null ? item.toString() : id.toString();
    }

    /** Case-insensitive lookup of an item by registry name, or null. */
    public static Item itemById(String id) {
        ResourceLocation key = ResourceLocation.tryParse(id.toLowerCase(Locale.ROOT));
        if (key == null) {
            return null;
        }
        Item item = BuiltInRegistries.ITEM.get(key);
        return item == null || item == net.minecraft.world.item.Items.AIR ? null : item;
    }
}
