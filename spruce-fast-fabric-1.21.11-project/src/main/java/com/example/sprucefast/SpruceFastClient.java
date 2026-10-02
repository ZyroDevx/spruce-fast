package com.example.sprucefast;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import org.lwjgl.glfw.GLFW;

public class SpruceFastClient implements ClientModInitializer {
    private static KeyBinding toggleKey;
    private static boolean enabled = false;

    @Override
    public void onInitializeClient() {
        toggleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.spruce_fast.toggle",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_BACKSPACE,
                "category.spruce_fast"
        ));

        ClientTickEvents.END_CLIENT_TICK.register(SpruceFastClient::tick);
    }

    private static void tick(MinecraftClient client) {
        while (toggleKey.wasPressed()) {
            enabled = !enabled;
            if (client.player != null) {
                client.player.sendMessage(
                        net.minecraft.text.Text.literal("Spruce Fast: " + (enabled ? "ON" : "OFF")),
                        true
                );
            }
        }

        if (!enabled || client.player == null || client.world == null) return;

        // The current implementation intentionally only toggles the feature.
        // Crafting automation can be added here after confirming the target
        // server's container/interaction rules.
    }

    public static boolean isEnabled() {
        return enabled;
    }
}
