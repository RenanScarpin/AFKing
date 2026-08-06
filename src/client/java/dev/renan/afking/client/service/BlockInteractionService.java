package dev.renan.afking.client.service;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.BlockHitResult;

public final class BlockInteractionService {

    public boolean useItemOnBlock(
            Minecraft client,
            InteractionHand hand,
            BlockHitResult hitResult
    ) {
        if (
                client.player == null
                        || client.gameMode == null
        ) {
            return false;
        }

        client.gameMode.useItemOn(
                client.player,
                hand,
                hitResult
        );

        return true;
    }

    /*
     * Begins normal progressive block mining.
     */
    public boolean startMining(
            Minecraft client,
            BlockPos blockPosition,
            Direction interactionFace
    ) {
        if (
                client.player == null
                        || client.gameMode == null
        ) {
            return false;
        }

        boolean started =
                client.gameMode.startDestroyBlock(
                        blockPosition,
                        interactionFace
                );

        if (started) {
            client.player.swing(
                    InteractionHand.MAIN_HAND
            );
        }

        return started;
    }

    /*
     * Must be called once per client tick while mining.
     */
    public boolean continueMining(
            Minecraft client,
            BlockPos blockPosition,
            Direction interactionFace
    ) {
        if (
                client.player == null
                        || client.gameMode == null
        ) {
            return false;
        }

        boolean continuing =
                client.gameMode.continueDestroyBlock(
                        blockPosition,
                        interactionFace
                );

        if (continuing) {
            client.player.swing(
                    InteractionHand.MAIN_HAND
            );
        }

        return continuing;
    }

    /*
     * Cancels any active client mining operation.
     *
     * This must be called whenever copper processing is paused,
     * cancelled, fails, or loses control.
     */
    public void stopMining(Minecraft client) {
        if (client.gameMode != null) {
            client.gameMode.stopDestroyBlock();
        }
    }
}