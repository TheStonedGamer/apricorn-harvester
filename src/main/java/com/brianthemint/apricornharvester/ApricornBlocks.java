package com.brianthemint.apricornharvester;

import com.pixelmonmod.pixelmon.blocks.ApricornLeavesBlock;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.WallBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Apricorn detection built on the official Pixelmon 9.3.x API
 * (Javadocs: https://reforged.gg/docs/1211/).
 *
 * <p>Apricorn leaves are instances of {@link ApricornLeavesBlock} (which extends vanilla
 * {@code LeavesBlock}) and carry the static {@link ApricornLeavesBlock#AGE}
 * {@code IntegerProperty}. Growth stages go bare leaf -&gt; flower -&gt; apricorn; a block is
 * mature (harvestable) when its age equals the maximum value of the property. Harvesting is
 * done by right-clicking the leaf (the block overrides {@code useWithoutItem}).
 */
public final class ApricornBlocks {

    /** The highest age value; the last growth stage is the one that carries the apricorn. */
    private static final int MAX_AGE = ApricornLeavesBlock.AGE.getPossibleValues()
            .stream()
            .mapToInt(Integer::intValue)
            .max()
            .orElse(-1);

    private ApricornBlocks() {
    }

    public static boolean isApricorn(BlockState state) {
        return state.getBlock() instanceof ApricornLeavesBlock;
    }

    public static boolean isApricorn(Block block) {
        return block instanceof ApricornLeavesBlock;
    }

    /** True when the apricorn leaf is fully grown (age == max age) and can be picked. */
    public static boolean isMature(BlockState state) {
        if (!isApricorn(state)) {
            return false;
        }
        try {
            return state.getValue(ApricornLeavesBlock.AGE) >= MAX_AGE;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * True for a dropped apricorn item. Matched on the registry id rather than an item class,
     * because Pixelmon registers one item per apricorn colour ({@code black_apricorn},
     * {@code red_apricorn}, ...) and the concrete class is not part of the public API.
     */
    public static boolean isApricornItem(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        return id != null && id.getPath().contains("apricorn");
    }

    public static boolean isApricornLog(BlockState state) {
        return state.getBlock() instanceof WallBlock;
    }

    /**
     * True for anything the bot must never walk on or through: tree trunks, canopies and other
     * plant/fence blocks. The Pixelmon jar has no {@code ApricornLogBlock} class - apricorn
     * trunks are {@code BerryLogBlock}, which extends vanilla {@link WallBlock} (and apricorn
     * leaves extend vanilla {@link LeavesBlock}). The bot is restricted to the farm's
     * 1-block-wide paths and must never stand on, climb into or path through trees.
     */
    public static boolean isTreeBlock(BlockState state) {
        Block b = state.getBlock();
        return b instanceof LeavesBlock
                || b instanceof RotatedPillarBlock
                || b instanceof WallBlock;
    }

    // ---------------------------------------------------------------- container detection

    /**
     * True when the block is a container the bot can deposit into (chest, barrel, shulker box).
     * Does not include double chests at the half-block level — the single block is enough.
     */
    public static boolean isContainer(BlockState state) {
        Block b = state.getBlock();
        return b instanceof ChestBlock
                || b == Blocks.BARREL
                || b instanceof ShulkerBoxBlock;
    }

    // ---------------------------------------------------------------- inventory helpers

    /**
     * Returns the total count of apricorn items in the given inventory (player main + hotbar).
     * @param inv the player's inventory
     * @return total number of apricorn items across all stacks
     */
    public static int countApricorns(net.minecraft.world.entity.player.Inventory inv) {
        int total = 0;
        for (int i = 0; i < inv.items.size(); i++) { // main inventory + hotbar
            ItemStack stack = inv.getItem(i);
            if (isApricornItem(stack)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    /**
     * True if the player has at least one apricorn anywhere in their inventory.
     */
    public static boolean hasApricorns(net.minecraft.world.entity.player.Inventory inv) {
        return countApricorns(inv) > 0;
    }
}
