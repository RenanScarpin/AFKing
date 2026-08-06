package dev.renan.afking.client;

import dev.renan.afking.client.automation.AutomationController;
import dev.renan.afking.client.behavior.AutoEatBehavior;
import dev.renan.afking.client.behavior.LowHealthDisconnectBehavior;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

public final class AFKingClient
		implements ClientModInitializer {

	public static final String MOD_ID = "afking";

	private static final float DISCONNECT_HEALTH = 8.0F;

	private static final int START_EATING_HUNGER = 14;
	private static final int STOP_EATING_HUNGER = 18;

	private final AFKingKeyMappings keyMappings =
			new AFKingKeyMappings();

	private final AutomationController controller =
			new AutomationController();

	@Override
	public void onInitializeClient() {
		controller.register(
				new LowHealthDisconnectBehavior(
						DISCONNECT_HEALTH
				),
				true
		);

		controller.register(
				new AutoEatBehavior(
						START_EATING_HUNGER,
						STOP_EATING_HUNGER
				),
				true
		);

		ClientTickEvents.END_CLIENT_TICK.register(
				client -> {
					while (
							keyMappings.consumeMasterToggle()
					) {
						controller.toggleMaster(client);
					}

					controller.tick(client);
				}
		);
	}
}