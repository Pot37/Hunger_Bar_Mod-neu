package de.hungerfoods;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;

/**
 * Zeigt nach dem Essen die passende Hungerleiste an.
 * Die Leiste bleibt so, bis man etwas anderes (mit eigener Textur) isst
 * oder stirbt / die Welt wechselt. Vorher gilt die normale Leiste.
 */
public class HungerFoodsClient implements ClientModInitializer {
	public static final String MOD_ID = "hungerfoods";

	/** Item-ID (ohne "minecraft:") -> Ordnername der Textur. */
	private static final Map<String, String> ITEM_TO_TEXTURE = new HashMap<>();

	static {
		// Rohe und gebratene Varianten teilen sich die Textur.
		map("apple", "apple");
		map("baked_potato", "baked_potato");
		map("beef", "beef");
		map("cooked_beef", "beef");
		map("beetroot", "beetroot");
		map("beetroot_soup", "beetroot_soup");
		map("bread", "bread");
		map("cake", "cake");
		map("carrot", "carrot");
		map("chicken", "chicken");
		map("cooked_chicken", "chicken");
		map("chorus_fruit", "chorus_fruit");
		map("cod", "cod");
		map("cooked_cod", "cod");
		map("cookie", "cookie");
		map("dried_kelp", "dried_kelp");
		map("glow_berries", "glow_berries");
		map("golden_apple", "golden_apple");
		map("enchanted_golden_apple", "golden_apple");
		map("golden_carrot", "golden_carrot");
		map("honey_bottle", "honey");
		map("melon_slice", "melon");
		map("mushroom_stew", "mushroom_stew");
		map("mutton", "mutton");
		map("cooked_mutton", "mutton");
		map("poisonous_potato", "poisonous_potato");
		map("porkchop", "porkchop");
		map("cooked_porkchop", "porkchop");
		map("potato", "potato");
		map("pufferfish", "pufferfish");
		map("pumpkin_pie", "pumpkin_pie");
		map("rabbit", "rabbit");
		map("cooked_rabbit", "rabbit");
		map("rabbit_stew", "rabbit_stew");
		map("rotten_flesh", "rotten_flesh");
		map("salmon", "salmon");
		map("cooked_salmon", "salmon");
		map("spider_eye", "spider_eye");
		map("suspicious_stew", "suspicious_stew");
		map("sweet_berries", "sweet_berries");
		map("tropical_fish", "tropical_fish");
	}

	private static void map(String item, String texture) {
		ITEM_TO_TEXTURE.put(item, texture);
	}

	// Zeilen in der Textur-Datei (9x54 Pixel, 6 Bilder untereinander)
	private static final int ROW_EMPTY = 0;
	private static final int ROW_HALF = 1;
	private static final int ROW_FULL = 2;
	private static final int ROW_EMPTY_HUNGER = 3;
	private static final int ROW_HALF_HUNGER = 4;
	private static final int ROW_FULL_HUNGER = 5;

	private static String currentFood = null;

	// Zustand fuer die Erkennung "Essen fertig gegessen"
	private static String candidate = null;
	private static boolean wasUsing = false;
	private static int lastRemaining = 99;
	private static LocalPlayer lastPlayer = null;
	private static int tick = 0;

	private static final Map<String, Identifier> CUSTOM_CACHE = new HashMap<>();

	@Override
	public void onInitializeClient() {
		ClientTickEvents.END_CLIENT_TICK.register(HungerFoodsClient::onTick);

		// Kuchen ist ein Block, kein Item -> extra erkennen
		UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
			Minecraft mc = Minecraft.getInstance();
			if (player == mc.player) {
				if (level.getBlockState(hit.getBlockPos()).is(Blocks.CAKE)
						&& player.getFoodData().needsFood()) {
					currentFood = "cake";
				}
			}
			return InteractionResult.PASS;
		});

		// Vanilla-Hungerleiste ersetzen
		HudElementRegistry.replaceElement(VanillaHudElements.FOOD_BAR, previous -> HungerFoodsClient::renderFood);
	}

	private static void onTick(Minecraft mc) {
		tick++;
		LocalPlayer player = mc.player;

		if (player != lastPlayer) { // neue Welt oder Respawn
			lastPlayer = player;
			currentFood = null;
			candidate = null;
			wasUsing = false;
		}
		if (player == null) {
			return;
		}

		if (player.isUsingItem()) {
			ItemStack stack = player.getUseItem();
			String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
			candidate = ITEM_TO_TEXTURE.get(id);
			lastRemaining = player.getUseItemRemainingTicks();
			wasUsing = true;
		} else {
			// Essen gilt als gegessen, wenn die Nutzung ganz am Ende endete
			// (und nicht frueher abgebrochen wurde).
			if (wasUsing && candidate != null && lastRemaining <= 2) {
				currentFood = candidate;
			}
			wasUsing = false;
			candidate = null;
		}
	}

	private static Identifier customSheet(String food) {
		return CUSTOM_CACHE.computeIfAbsent(food,
				f -> Identifier.fromNamespaceAndPath(MOD_ID, "textures/gui/hungerbars/" + f + ".png"));
	}

	private static Identifier vanillaIcon(String name) {
		return Identifier.withDefaultNamespace("textures/gui/sprites/hud/" + name + ".png");
	}

	private static void drawIcon(GuiGraphicsExtractor g, int x, int y, int row, boolean hunger) {
		String[] names = {"food_empty", "food_half", "food_full"};
		if (currentFood != null) {
			g.blit(RenderPipelines.GUI_TEXTURED, customSheet(currentFood), x, y, 0f, (float) (row * 9), 9, 9, 9, 54);
		} else {
			int base = row % 3;
			String name = names[base] + (hunger ? "_hunger" : "");
			g.blit(RenderPipelines.GUI_TEXTURED, vanillaIcon(name), x, y, 0f, 0f, 9, 9, 9, 9);
		}
	}

	private static void renderFood(GuiGraphicsExtractor g, DeltaTracker delta) {
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer player = mc.player;
		if (player == null || mc.gameMode == null || mc.options.hideGui) {
			return;
		}
		if (!mc.gameMode.canHurtPlayer()) { // Creative/Spectator: keine Leiste
			return;
		}
		if (player.getVehicle() instanceof LivingEntity) { // Reittier zeigt eigene Leiste
			return;
		}

		int food = player.getFoodData().getFoodLevel();
		float saturation = player.getFoodData().getSaturationLevel();
		boolean hunger = player.hasEffect(MobEffects.HUNGER);

		int right = mc.getWindow().getGuiScaledWidth() / 2 + 91;
		int baseY = mc.getWindow().getGuiScaledHeight() - 39;

		for (int i = 0; i < 10; i++) {
			int y = baseY;
			if (saturation <= 0.0F && tick % (food * 3 + 1) == 0) {
				y += ThreadLocalRandom.current().nextInt(3) - 1;
			}
			int x = right - i * 8 - 9;

			drawIcon(g, x, y, hunger ? ROW_EMPTY_HUNGER : ROW_EMPTY, hunger);
			int level = i * 2 + 1;
			if (level < food) {
				drawIcon(g, x, y, hunger ? ROW_FULL_HUNGER : ROW_FULL, hunger);
			} else if (level == food) {
				drawIcon(g, x, y, hunger ? ROW_HALF_HUNGER : ROW_HALF, hunger);
			}
		}
	}
}
