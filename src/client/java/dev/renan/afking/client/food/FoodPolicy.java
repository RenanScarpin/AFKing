package dev.renan.afking.client.food;

import net.minecraft.world.item.ItemStack;

import java.util.function.Predicate;

@FunctionalInterface
public interface FoodPolicy extends Predicate<ItemStack> {

    boolean isAllowed(ItemStack stack);

    @Override
    default boolean test(ItemStack stack) {
        return isAllowed(stack);
    }
}