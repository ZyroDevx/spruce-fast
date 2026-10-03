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

    /*
     * How many crafting-result clicks to send
     * each client tick.
     *
     * Higher = faster, but servers can reject
     * excessive inventory packets.
     */
    private static final int CRAFTS_PER_TICK = 8;

    private static int timer = 0;

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

            while (startKey.wasPressed()) {

                enabled = !enabled;
                timer = 0;

                if (client.player != null) {

                    client.player.sendMessage(
                        Text.literal(
                            "Spruce Fast: "
                                + (enabled ? "ON" : "OFF")
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

            timer++;

            if (timer < 1) {
                return;
            }

            timer = 0;

            run(client);
        });
    }

    private static void run(MinecraftClient client) {

        if (!(client.player.currentScreenHandler
                instanceof PlayerScreenHandler)) {

            return;
        }

        PlayerScreenHandler handler =
            (PlayerScreenHandler)
                client.player.currentScreenHandler;

        /*
         * Don't do anything if the cursor is holding
         * an item.
         */
        if (!handler.getCursorStack().isEmpty()) {
            return;
        }

        /*
         * Make sure the 2x2 crafting grid is empty
         * before starting.
         *
         * We don't want to destroy items the player
         * manually placed in the crafting grid.
         */
        if (!craftingGridIsEmpty(handler)) {

            enabled = false;

            client.player.sendMessage(
                Text.literal(
                    "Spruce Fast: Empty the inventory crafting grid first."
                ),
                true
            );

            return;
        }

        /*
         * If there is already a spruce log in the
         * crafting grid, don't put another stack in.
         *
         * This normally happens between clicks while
         * the server is processing the recipe.
         */
        if (hasSpruceLogInCraftingGrid(handler)) {

            takeCraftingResults(client, handler);

            return;
        }

        /*
         * Find a spruce-log stack in the player's
         * inventory.
         */
        int logSlot =
            findLogInventorySlot(client);

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
         * Put the ENTIRE spruce-log stack into
         * the first inventory crafting slot.
         *
         * PlayerScreenHandler:
         *
         * 0 = crafting result
         * 1-4 = 2x2 crafting input
         */
        client.interactionManager.clickSlot(
            handler.syncId,
            logSlot,
            0,
            SlotActionType.PICKUP,
            client.player
        );

        client.interactionManager.clickSlot(
            handler.syncId,
            1,
            0,
            SlotActionType.PICKUP,
            client.player
        );

        /*
         * If anything remains on the cursor,
         * put it back into the original inventory slot.
         *
         * Normally there shouldn't be anything left,
         * because the whole stack fits into the
         * crafting slot.
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

        /*
         * Immediately process crafting.
         */
        takeCraftingResults(client, handler);
    }

    private static void takeCraftingResults(
            MinecraftClient client,
            PlayerScreenHandler handler) {

        /*
         * The result slot is slot 0.
         *
         * Each successful crafting-result pickup
         * consumes ONE spruce log and gives
         * FOUR spruce planks.
         *
         * The whole log stack can remain in the
         * crafting input while we repeatedly
         * take the result.
         */
        for (int i = 0; i < CRAFTS_PER_TICK; i++) {

            ItemStack result =
                handler.getOutputSlot().getStack();

            if (result.isEmpty()) {
                break;
            }

            if (!result.isOf(Items.SPRUCE_PLANKS)) {
                break;
            }

            client.interactionManager.clickSlot(
                handler.syncId,
                0,
                0,
                SlotActionType.QUICK_MOVE,
                client.player
            );
        }
    }

    private static boolean craftingGridIsEmpty(
            PlayerScreenHandler handler) {

        for (var slot : handler.getInputSlots()) {

            if (!slot.getStack().isEmpty()) {
                return false;
            }
        }

        return true;
    }

    private static boolean hasSpruceLogInCraftingGrid(
            PlayerScreenHandler handler) {

        for (var slot : handler.getInputSlots()) {

            if (slot.getStack().isOf(Items.SPRUCE_LOG)) {
                return true;
            }
        }

        return false;
    }

    private static int findLogInventorySlot(
            MinecraftClient client) {

        var stacks =
            client.player
                .getInventory()
                .getMainStacks();

        /*
         * PlayerScreenHandler slot layout:
         *
         * 0      = crafting result
         * 1-4    = 2x2 crafting grid
         * 5-8    = armor
         * 9-35   = main inventory
         * 36-44  = hotbar
         * 45     = offhand
         */

        /*
         * Search main inventory + hotbar.
         *
         * PlayerInventory indexes:
         *
         * 0-8   = hotbar
         * 9-35  = main inventory
         */
        for (int i = 0; i < stacks.size(); i++) {

            ItemStack stack = stacks.get(i);

            if (!stack.isOf(Items.SPRUCE_LOG)) {
                continue;
            }

            if (i < 9) {

                // Hotbar inventory index 0-8
                // -> handler slot 36-44
                return 36 + i;

            } else {

                // Main inventory index 9-35
                // -> handler slot 9-35
                return i;
            }
        }

        /*
         * Check offhand separately.
         */
        ItemStack offhand =
            client.player.getOffHandStack();

        if (offhand.isOf(Items.SPRUCE_LOG)) {
            return 45;
        }

        return -1;
    }
}
