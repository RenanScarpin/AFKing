# AFKing

AFKing is a client-side Fabric mod that adds toggleable safety automation for players who need to step away from the keyboard.

Instead of creating a separate bot player, AFKing controls the Minecraft client you are already using. Autonomous behavior remains disabled until you explicitly enable it and is automatically disabled whenever you leave the world or disconnect.

## Features

### Master automation toggle

Press **F8** to enable or disable AFKing.

AFKing always starts disabled. It will not automatically activate when Minecraft launches, when you enter a world, or when you reconnect to a server.

The key can be changed in Keybinds

### Automatic eating

When AFKing is enabled, it automatically:

* Starts eating when hunger reaches 8 or less.
* Continues eating until hunger reaches at least 18.
* Searches both the hotbar and the main inventory.
* Temporarily moves food into the hotbar when necessary.
* Restores the displaced hotbar item after eating.
* Restores the previously selected hotbar slot.
* Stops immediately when AFKing is disabled.

AFKing recognizes compatible food added by other mods.

A food item must:

* Restore hunger.
* Provide positive saturation.
* Have no harmful or neutral status effects.
* Have no unusual consumption behavior, such as random teleportation.

Careful! It will eat golden apples and other rare food items.

### Low-health emergency disconnect

When AFKing is enabled, it disconnects the client if the player reaches **four hearts or less**.

## Multiplayer usage

AFKing performs client-side automation. Some multiplayer servers prohibit automated gameplay even when the mod does not bypass anti-cheat systems.

Check the rules of every server before using AFKing.

AFKing is not intended to conceal automation, bypass server restrictions, or evade detection.
