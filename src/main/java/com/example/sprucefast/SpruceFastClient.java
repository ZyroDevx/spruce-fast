package com.example.sprucefast;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;

import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;

import net.minecraft.screen.PlayerScreenHandler;
import net.minecraft.screen.slot.SlotActionType;

import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import org.lwjgl.glfw.GLFW;

public class SpruceFastClient implements ClientModInitializer {

    private static KeyBinding startKey;

    private static boolean enabled = false;

    // How many crafting-result clicks to attempt per tick
    private static final int CRAFTS_PER_TICK = 8;

    @Override
    public void onInitializeClient() {

        startKey = KeyBindingHelper.registerKeyBinding(
            new KeyBinding(
                "key.spruce_fast.start",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_BACKSPACE,
                KeyBinding.Category.create(
                    Identifier.of("spruce_fast", "main")
                )
            )
        );

        ClientTickEvents.END_CLIENT_TICK.register(client -> {

            // Toggle with Backspace
            while (startKey.wasPressed()) {

                enabled = !enabled;

                if (client.player != null) {
                    client.player.sendMessage(
                        Text.literal(
                            "Spruce Fast: " +
                            (enabled ? "ON" : "OFF")
                        ),
                        true
                    );
                }
            }

            if (!enabled) {
                return;
            }

            if (client.player == null ||
                client.interactionManager == null) {
                return;
            }

            run(client);
        });
    }

    private static void run(MinecraftClient client) {

        // Must have the normal player inventory screen handler
        if (!(client.player.currentScreenHandler
                instanceof PlayerScreenHandler)) {
            return;
        }

        PlayerScreenHandler handler =
            (PlayerScreenHandler) client.player.currentScreenHandler;

        // Don't interfere if the mouse cursor is holding something
        if (!handler.getCursorStack().isEmpty()) {
            return;
        }

        /*
         * FIRST:
         * If there is something in the 2x2 crafting grid,
         * check if it is a spruce log.
         */
        if (hasCraftingInput(handler)) {

            // Only allow spruce logs in the crafting grid
            if (!onlySpruceLogsInCraftingGrid(handler)) {

                enabled = false;

                client.player.sendMessage(
                    Text.literal(
                        "Spruce Fast: Remove other items from the inventory crafting grid."
                    ),
                    true
                );

                return;
            }

            // Take the spruce plank results
            takeCraftingResults(client, handler);

            return;
        }

        /*
         * SECOND:
         * Crafting grid is empty, so find a spruce log stack
         * in the player's inventory.
         */
        int logSlot = findLogInventorySlot(client);

        if (logSlot == -1) {

            enabled = false;

            client.player.sendMessage(
                Text.literal(
                    "Spruce Fast: Finished. No spruce logs left."
                ),
                true
            );

            return;
        }

        /*
         * Pick up the ENTIRE spruce log stack.
         */
        client.interactionManager.clickSlot(
            handler.syncId,
            logSlot,
            0,
            SlotActionType.PICKUP,
            client.player
        );

        /*
         * Put the ENTIRE stack into the first
         * inventory crafting slot.
         *
         * PlayerScreenHandler:
         * 0 = result
         * 1-4 = 2x2 crafting grid
         */
        client.interactionManager.clickSlot(
            handler.syncId,
            1,
            0,
            SlotActionType.PICKUP,
            client.player
        );

        /*
         * If something went wrong and there is still
         * something on the cursor, return it to the
         * original inventory slot.
         */
        if (!handler.getCursorStack().isEmpty()) {

            client.interactionManager.clickSlot(
                handler.syncId,
                logSlot,
                0,
                SlotActionType.PICKUP,
                client.player
            );
        }
    }

    /*
     * Checks whether there is anything in the 2x2 crafting grid.
     */
    private static boolean hasCraftingInput(
            PlayerScreenHandler handler) {

        for (var slot : handler.getInputSlots()) {

            if (!slot.getStack().isEmpty()) {
                return true;
            }
        }

        return false;
    }

    /*
     * Makes sure the crafting grid contains ONLY spruce logs.
     */
    private static boolean onlySpruceLogsInCraftingGrid(
            PlayerScreenHandler handler) {

        for (var slot : handler.getInputSlots()) {

            ItemStack stack = slot.getStack();

            if (stack.isEmpty()) {
                continue;
            }

            if (!stack.isOf(Items.SPRUCE_LOG)) {
                return false;
            }
        }

        return true;
    }

    /*
     * Takes the spruce plank result repeatedly.
     *
     * One spruce log -> 4 spruce planks.
     *
     * The entire log stack stays in the crafting slot.
     * We repeatedly take the result until the logs are gone.
     */
    private static void takeCraftingResults(
            MinecraftClient client,
            PlayerScreenHandler handler) {

        for (int i = 0; i < CRAFTS_PER_TICK; i++) {

            ItemStack result =
                handler.getOutputSlot().getStack();

            // Nothing to craft
            if (result.isEmpty()) {
                break;
            }

            // Safety check
            if (!result.isOf(Items.SPRUCE_PLANKS)) {
                break;
            }

            /*
             * QUICK_MOVE the result into the inventory.
             */
            client.interactionManager.clickSlot(
                handler.syncId,
                0,
                0,
                SlotActionType.QUICK_MOVE,
                client.player
            );
        }
    }

    /*
     * Finds a spruce log stack in the player's inventory.
     */
    private static int findLogInventorySlot(
            MinecraftClient client) {

        var stacks =
            client.player
                .getInventory()
                .getMainStacks();

        for (int i = 0; i < stacks.size(); i++) {

            ItemStack stack = stacks.get(i);

            if (!stack.isOf(Items.SPRUCE_LOG)) {
                continue;
            }

            /*
             * PlayerScreenHandler slot mapping:
             *
             * Inventory main slots 0-26 -> screen slots 9-35
             * Hotbar slots 0-8 -> screen slots 36-44
             */
            if (i < 9) {
                return 36 + i;
            } else {
                return i;
            }
        }

        /*
         * Offhand screen slot
         */
        ItemStack offhand =
            client.player.getOffHandStack();

        if (offhand.isOf(Items.SPRUCE_LOG)) {
            return 45;
        }

        return -1;
    }
}
