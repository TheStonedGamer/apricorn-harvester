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

import java.util.ArrayList;
import java.util.List;

/**
 * Keeps the bot's tools in working order: notices when one is worn out or missing, combines two
 * worn ones where that is enough, and otherwise makes a new one - mining and smelting whatever that
 * takes.
 *
 * <p>It needs no crafting knowledge of its own. The Poke Ball planner already resolves any item
 * back through the server's recipes to the raw blocks, so asking for an iron pickaxe plans its
 * sticks, planks, logs and ore the same way a ball plans its lids and bases.
 *
 * <p>What it looks after is up to {@link ToolConfig}: which kinds of tool, what they should be made
 * of, how worn they may get and whether to try repairing first.
 */
public final class ToolUpkeep {

    private ToolUpkeep() {
    }

    /** How worn a stack is, as a fraction of its durability left; 1 for anything indestructible. */
    private static float condition(ItemStack stack) {
        if (!stack.isDamageableItem() || stack.getMaxDamage() <= 0) {
            return 1f;
        }
        return (float) (stack.getMaxDamage() - stack.getDamageValue()) / stack.getMaxDamage();
    }

    private static boolean isKind(ItemStack stack, ToolConfig.ToolKind kind) {
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        return id != null && id.getPath().endsWith(kind.suffix());
    }

    /** Every stack of that kind the player is carrying. */
    private static List<ItemStack> toolsOfKind(ToolConfig.ToolKind kind) {
        List<ItemStack> found = new ArrayList<>();
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return found;
        }
        Inventory inv = mc.player.getInventory();
        for (int i = 0; i < inv.items.size(); i++) {
            ItemStack stack = inv.getItem(i);
            if (!stack.isEmpty() && isKind(stack, kind)) {
                found.add(stack);
            }
        }
        return found;
    }

    /** True when this kind of tool is missing, or every one of them is past the wear limit. */
    public static boolean needsAttention(ToolConfig.ToolKind kind) {
        float limit = ToolConfig.getWornPercent() / 100f;
        int usable = 0;
        for (ItemStack stack : toolsOfKind(kind)) {
            if (condition(stack) > limit) {
                usable++;
            }
        }
        return usable <= ToolConfig.getSpares();
    }

    /** True when any tool the config looks after wants attention. */
    public static boolean needsAttention() {
        for (ToolConfig.ToolKind kind : ToolConfig.kept()) {
            if (needsAttention(kind)) {
                return true;
            }
        }
        return false;
    }

    /** A short note on the state of the tools, for the GUI. */
    public static String summary() {
        List<ToolConfig.ToolKind> kept = ToolConfig.kept();
        if (kept.isEmpty()) {
            return "No tools are being looked after.";
        }
        StringBuilder sb = new StringBuilder();
        for (ToolConfig.ToolKind kind : kept) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            List<ItemStack> tools = toolsOfKind(kind);
            if (tools.isEmpty()) {
                sb.append(kind.label()).append(": none");
                continue;
            }
            float best = 0f;
            for (ItemStack stack : tools) {
                best = Math.max(best, condition(stack));
            }
            sb.append(kind.label()).append(": ").append(Math.round(best * 100)).append("%");
        }
        return sb.toString();
    }

    /**
     * Deals with whichever tool needs it most: two worn ones of a kind are combined if that is
     * allowed, otherwise a new one is planned in the best material that can actually be made.
     *
     * @return true when something was started
     */
    public static boolean ensureTools(PokeballFactory factory, ToolRepairController repairer) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return false;
        }
        for (ToolConfig.ToolKind kind : ToolConfig.kept()) {
            if (!needsAttention(kind)) {
                continue;
            }
            // Two worn ones make one good one, and that costs nothing but a click.
            if (ToolConfig.isRepairByCombining() && repairer != null
                    && repairer.canCombine(kind)) {
                Helper.HELPER.logDirect("Combining two worn " + kind.label().toLowerCase()
                        + "s at the crafting table.");
                repairer.start(kind);
                return true;
            }
            if (factory == null) {
                continue;
            }
            for (ToolConfig.Material material : ToolConfig.materialOrder()) {
                Item item = BuiltInRegistries.ITEM.get(ResourceLocation.parse(material.idFor(kind)));
                if (item == null || item == Items.AIR) {
                    continue;
                }
                CraftPlan plan = PokeballRecipes.planItem(item, 1, mc.player.getInventory());
                if (!plan.isPossible() || plan.isEmpty()) {
                    continue;
                }
                Helper.HELPER.logDirect(kind.label() + " is worn out - making a "
                        + PokeballRecipes.nameOf(item) + ".");
                return factory.runPlan(plan, "1x " + PokeballRecipes.nameOf(item));
            }
            Helper.HELPER.logDirect("No " + kind.label().toLowerCase()
                    + " left and none can be made from what is to hand.");
        }
        return false;
    }
}
