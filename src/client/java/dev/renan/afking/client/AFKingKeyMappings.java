package dev.renan.afking.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;

public final class AFKingKeyMappings {

    private static final KeyMapping.Category CATEGORY =
            KeyMapping.Category.register(
                    Identifier.fromNamespaceAndPath(
                            AFKingClient.MOD_ID,
                            "general"
                    )
            );

    private final KeyMapping masterToggle =
            KeyMappingHelper.registerKeyMapping(
                    new KeyMapping(
                            "key.afking.toggle",
                            InputConstants.Type.KEYSYM,
                            InputConstants.KEY_F8,
                            CATEGORY
                    )
            );

    public boolean consumeMasterToggle() {
        return masterToggle.consumeClick();
    }
}