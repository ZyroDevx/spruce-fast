package com.example.sprucefast;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

public class SpruceFastClient implements ClientModInitializer {

    private static KeyBinding startKey;
    private static boolean enabled = false;
    private static int timer = 0;

    @Override
    public void onInitializeClient() {

        startKey = KeyBindingHelper.registerKeyBinding(
            new KeyBinding(
                "key.spruce_fast.start",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_BACKSPACE,
                "category.spruce_fast"
            )
        );

        ClientTickEvents.END_CLIENT_TICK.register(client -> {

            while (startKey.wasPressed()) {
                enabled = !enabled;

                if (client.player != null) {
                    client.player.sendMessage(
                        Text.literal("Spruce Fast: " + (enabled ? "ON" : "OFF")),
                        true
                    );
                }
            }

            if (!enabled || client.player == null || client.interactionManager == null) {
                return;
            }

            timer++;

            if (timer < 2) {
                return;
            }

            timer = 0;

            int logs = countLogs(client);

            if (logs == 0) {
                enabled = false;

                client.player.sendMessage(
                    Text.literal("Spruce Fast: No spruce logs left."),
                    true
                );

                return;
            }

            craft(client);
        });
    }

    private static int countLogs(MinecraftClient client) {

        int amount = 0;

        for (ItemStack stack : client.player.getInventory().main) {
            if (stack.isOf(Items.SPRUCE_LOG)) {
                amount += stack.getCount();
            }
        }

        if (client.player.getOffHandStack().isOf(Items.SPRUCE_LOG)) {
            amount += client.player.getOffHandStack().getCount();
        }

        return amount;
    }

    private static void craft(MinecraftClient client) {

        var handler = client.player.currentScreenHandler;

        if (handler == null) {
            return;
        }

        int logSlot = findLogSlot(client);

        if (logSlot == -1) {
            return;
        }

        /*
         * Move a spruce log into the inventory crafting grid.
         */
        client.interactionManager.clickSlot(
            handler.syncId,
            logSlot,
            0,
            SlotActionType.QUICK_MOVE,
            client.player
        );

        /*
         * Take the resulting spruce planks.
         */
        client.interactionManager.clickSlot(
            handler.syncId,
            0,
            0,
            SlotActionType.QUICK_MOVE,
            client.player
        );
    }

    private static int findLogSlot(MinecraftClient client) {

        for (int i = 0; i < client.player.getInventory().main.size(); i++) {

            ItemStack stack = client.player.getInventory().main.get(i);

            if (!stack.isOf(Items.SPRUCE_LOG)) {
                continue;
            }

            if (i >= 9) {
                return i;
            }

            return 36 + i;
        }

        return -1;
    }
}
