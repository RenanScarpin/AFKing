package dev.renan.afking.client.input;

import net.minecraft.client.KeyMapping;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

public final class ControlledInput {

    /*
     * Identity semantics are appropriate because Minecraft's KeyMapping
     * instances represent specific controls.
     */
    private final Set<KeyMapping> pressedKeys =
            Collections.newSetFromMap(new IdentityHashMap<>());

    public void press(KeyMapping keyMapping) {
        keyMapping.setDown(true);
        pressedKeys.add(keyMapping);
    }

    public void release(KeyMapping keyMapping) {
        keyMapping.setDown(false);
        pressedKeys.remove(keyMapping);
    }

    public void releaseAll() {
        for (KeyMapping keyMapping : pressedKeys) {
            keyMapping.setDown(false);
        }

        pressedKeys.clear();
    }
}