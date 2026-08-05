package com.brianthemint.apricornharvester;

import com.pixelmonmod.pixelmon.enums.items.ApricornType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Locale;

/**
 * Planting-side counterpart to {@link ApricornBlocks}: apricorn item/sapling lookup through the
 * official Pixelmon API ({@link ApricornType}) plus the soil test used to decide where an
 * apricorn may be planted.
 *
 * <p>{@code ApricornType} exposes one {@code ApricornItem} ({@link ApricornType#apricorn()}) and
 * one sapling block ({@link ApricornType#saplingBlock()}, registered as
 * {@code pixelmon:apricorn_plant_<colour>}) per colour, so no item ids have to be hard-coded.
 */
public final class ApricornPlanting {

    private ApricornPlanting() {
    }

    /** All apricorn colours, in the order Pixelmon declares them. */
    public static ApricornType[] types() {
        return ApricornType.values();
    }

    /** Human-readable colour name, e.g. {@code BLACK -> "Black"}. */
    public static String displayName(ApricornType type) {
        String n = type.name().toLowerCase(Locale.ROOT);
        return Character.toUpperCase(n.charAt(0)) + n.substring(1);
    }

    /** Parses a colour name ("black", "BLACK", "Black"); null when it is not an apricorn colour. */
    public static ApricornType parse(String name) {
        for (ApricornType t : ApricornType.values()) {
            if (t.name().equalsIgnoreCase(name)) {
                return t;
            }
        }
        return null;
    }

    /** The plantable apricorn item of a colour. */
    public static Item item(ApricornType type) {
        return type.apricorn();
    }

    /** The sapling ("apricorn plant") block a planted apricorn of this colour turns into. */
    public static Block saplingBlock(ApricornType type) {
        return type.saplingBlock();
    }

    public static boolean isStackOf(ItemStack stack, ApricornType type) {
        return stack != null && !stack.isEmpty() && stack.getItem() == item(type);
    }

    /** True for any apricorn sapling block ({@code pixelmon:apricorn_plant_*}) of any colour. */
    public static boolean isSapling(BlockState state) {
        Block b = state.getBlock();
        for (ApricornType t : ApricornType.values()) {
            if (b == t.saplingBlock()) {
                return true;
            }
        }
        return false;
    }

    /** True for the sapling of one specific colour - used to confirm a plant actually took. */
    public static boolean isSaplingOf(BlockState state, ApricornType type) {
        return state.getBlock() == saplingBlock(type);
    }

    /**
     * True when the block is soil an apricorn can be planted on. Pixelmon's {@code ApricornItem}
     * accepts the usual farmland-ish ground; the set below is the vanilla dirt family plus
     * farmland, matched by registry id so no Pixelmon-internal block list is needed.
     */
    public static boolean isPlantableSoil(BlockState state) {
        ResourceLocation id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        if (id == null) {
            return false;
        }
        if (!"minecraft".equals(id.getNamespace())) {
            return false;
        }
        return switch (id.getPath()) {
            case "grass_block", "dirt", "coarse_dirt", "rooted_dirt", "podzol", "mycelium",
                 "farmland", "moss_block", "mud", "muddy_mangrove_roots" -> true;
            default -> false;
        };
    }

    /**
     * True when a sapling can be placed at {@code above}: the soil below is plantable, the target
     * block itself is free (air or replaceable, and not already a sapling or tree part) and there
     * is no block entity in the way.
     */
    public static boolean canPlantAt(BlockGetter world, BlockPos soil) {
        BlockState soilState = world.getBlockState(soil);
        if (!isPlantableSoil(soilState)) {
            return false;
        }
        BlockPos above = soil.above();
        BlockState target = world.getBlockState(above);
        if (isSapling(target) || ApricornBlocks.isTreeBlock(target)) {
            return false;
        }
        return target.isAir() || target.canBeReplaced();
    }
}
