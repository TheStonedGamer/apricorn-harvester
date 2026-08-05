package com.brianthemint.apricornharvester;

import baritone.api.utils.Helper;
import com.brianthemint.apricornharvester.pokeball.CraftPlan;
import com.brianthemint.apricornharvester.pokeball.PokeballFactory;
import com.brianthemint.apricornharvester.pokeball.PokeballRecipes;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.List;

/**
 * Keeps the bot's tools in working order: notices when a pickaxe is worn out or missing and makes
 * a new one, mining and smelting whatever that takes.
 *
 * <p>It needs no crafting knowledge of its own. The Poke Ball planner already resolves any item
 * back through the server's recipes to the raw blocks - craft it if a recipe makes it, else smelt
 * it, else mine it - so asking for an iron pickaxe plans the sticks, the planks, the logs and the
 * ore exactly the same way a ball plans its lids and bases.
 */
public final class ToolUpkeep {

    /** Below this fraction of durability a tool counts as worn out and is replaced. */
    private static final float WORN_FRACTION = 0.15f;

    /** Pickaxes worth making, best first: the run uses the first one it can plan. */
    private static final List<String> PICKAXES = List.of(
            "minecraft:diamond_pickaxe", "minecraft:iron_pickaxe", "minecraft:stone_pickaxe");

    private ToolUpkeep() {
    }

    /** True when the player has a pickaxe with meaningful durability left. */
    public static boolean hasUsablePickaxe() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return true;
        }
        Inventory inv = mc.player.getInventory();
        for (int i = 0; i < inv.items.size(); i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.isEmpty() || !isPickaxe(stack)) {
                continue;
            }
            if (!stack.isDamageableItem()) {
                return true;
            }
            int max = stack.getMaxDamage();
            int left = max - stack.getDamageValue();
            if (max <= 0 || (float) left / max > WORN_FRACTION) {
                return true;
            }
        }
        return false;
    }

    private static boolean isPickaxe(ItemStack stack) {
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        return id != null && id.getPath().endsWith("_pickaxe");
    }

    /**
     * Starts making a replacement pickaxe through the crafting factory, if one is needed and one
     * can be planned from what is reachable.
     *
     * @return true when a run was started
     */
    public static boolean ensurePickaxe(PokeballFactory factory) {
        Minecraft mc = Minecraft.getInstance();
        if (factory == null || mc.player == null || hasUsablePickaxe()) {
            return false;
        }
        for (String id : PICKAXES) {
            Item item = BuiltInRegistries.ITEM.get(ResourceLocation.parse(id));
            if (item == null || item == Items.AIR) {
                continue;
            }
            CraftPlan plan = PokeballRecipes.planItem(item, 1, mc.player.getInventory());
            if (!plan.isPossible() || plan.isEmpty()) {
                continue;
            }
            Helper.HELPER.logDirect("Tools are worn out - making a "
                    + PokeballRecipes.nameOf(item) + ".");
            return factory.runPlan(plan, "1x " + PokeballRecipes.nameOf(item));
        }
        Helper.HELPER.logDirect("No pickaxe left and none can be made from what is to hand.");
        return false;
    }
}
