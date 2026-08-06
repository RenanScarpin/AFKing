package dev.renan.afking.client.food;

import net.minecraft.core.component.DataComponents;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.Consumable;
import net.minecraft.world.item.consume_effects.ApplyStatusEffectsConsumeEffect;
import net.minecraft.world.item.consume_effects.ConsumeEffect;

public final class SafeFoodPolicy implements FoodPolicy {

    @Override
    public boolean isAllowed(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }

        FoodProperties food =
                stack.get(DataComponents.FOOD);

        Consumable consumable =
                stack.get(DataComponents.CONSUMABLE);

        /*
         * Require the standard Minecraft food and consumption
         * components. This supports vanilla and component-based
         * modded food items.
         */
        if (food == null || consumable == null) {
            return false;
        }

        /*
         * Require actual nutritional value and positive saturation.
         */
        if (food.nutrition() <= 0) {
            return false;
        }

        if (food.saturation() <= 0.0F) {
            return false;
        }

        return hasOnlySafeConsumptionEffects(consumable);
    }

    private boolean hasOnlySafeConsumptionEffects(
            Consumable consumable
    ) {
        for (
                ConsumeEffect consumeEffect
                : consumable.onConsumeEffects()
        ) {
            /*
             * Status effects are allowed only if every effect is
             * classified by Minecraft as beneficial.
             *
             * This rejects harmful and neutral effects.
             */
            if (
                    consumeEffect
                            instanceof ApplyStatusEffectsConsumeEffect
                            statusEffects
            ) {
                if (!hasOnlyBeneficialEffects(statusEffects)) {
                    return false;
                }

                continue;
            }

            /*
             * Conservatively reject all other effect types.
             *
             * This prevents effects such as random teleportation,
             * clearing effects, or unknown modded behavior.
             */
            return false;
        }

        /*
         * An empty effect list is safe.
         */
        return true;
    }

    private boolean hasOnlyBeneficialEffects(
            ApplyStatusEffectsConsumeEffect statusEffects
    ) {
        for (
                MobEffectInstance effectInstance
                : statusEffects.effects()
        ) {
            boolean beneficial =
                    effectInstance
                            .getEffect()
                            .value()
                            .isBeneficial();

            if (!beneficial) {
                return false;
            }
        }

        return true;
    }
}