package dev.zaz.treasurespotter;

import java.util.ArrayDeque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import org.joml.Matrix4f;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.ARGB;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;

public final class TreasureSpotterClient implements ClientModInitializer {
  	public static final String MOD_ID = "treasurespotter";

  	private static final int LOCAL_COLUMN_X = 9;
  	private static final int LOCAL_COLUMN_Z = 9;

  	private static final double BOX_INFLATE = 0.002D;

  	private static final float COLOR_ALPHA = 0.45F;
  	private static final float COLOR_RED = 1.0F;
  	private static final float COLOR_GREEN = 0.82F;
  	private static final float COLOR_BLUE = 0.08F;

  	private static final int SCAN_BUDGET_PER_TICK = 64;

  	private static final Map<ChunkPos, BlockPos> HIGHLIGHTS = new ConcurrentHashMap<>();

  	private static final ArrayDeque<ChunkPos> PENDING_SCAN = new ArrayDeque<>();

  	private static volatile boolean enabled = false;
  	private static ClientLevel trackedLevel = null;

  	private static KeyMapping toggleKeyBinding;

  	@Override
  	public void onInitializeClient() {
      		KeyMapping.Category category = KeyMapping.Category.register(
            				Identifier.fromNamespaceAndPath(MOD_ID, "main"));

      		toggleKeyBinding = KeyBindingHelper.registerKeyBinding(new KeyMapping(
            				"key.treasurespotter.toggle",
            				InputConstants.Type.KEYSYM,
            				InputConstants.KEY_B,
            				category
            		));

      		ClientChunkEvents.CHUNK_LOAD.register((level, chunk) -> {
            			if (enabled) {
                    				updateHighlightForChunk(level, chunk);
                  }
          });

      		ClientChunkEvents.CHUNK_UNLOAD.register((level, chunk) -> HIGHLIGHTS.remove(chunk.getPos()));

      		ClientTickEvents.END_CLIENT_TICK.register(TreasureSpotterClient::onEndClientTick);

      		WorldRenderEvents.BEFORE_TRANSLUCENT.register(TreasureSpotterClient::renderHighlights);
    }

  	private static void onEndClientTick(Minecraft client) {
      		while (toggleKeyBinding.consumeClick()) {
            			toggleEnabled(client);
          }

      		if (client.level != trackedLevel) {
            			trackedLevel = client.level;
            			HIGHLIGHTS.clear();
            			PENDING_SCAN.clear();

            			if (enabled && trackedLevel != null) {
                    				queueFullScan(client, trackedLevel);
                  }
          }

      		if (enabled && client.level != null && !PENDING_SCAN.isEmpty()) {
            			drainScanQueue(client.level);
          }
    }

  	private static void toggleEnabled(Minecraft client) {
      		enabled = !enabled;
      		HIGHLIGHTS.clear();
      		PENDING_SCAN.clear();

      		if (enabled && client.level != null) {
            			queueFullScan(client, client.level);
          }

      		if (client.player != null) {
            			String state = enabled ? "ON" : "OFF";
            			client.player.displayClientMessage(
                    					Component.literal("Treasure Spotter: " + state), true);
          }
    }

  	private static void queueFullScan(Minecraft client, ClientLevel level) {
      		if (client.player == null) {
            			return;
          }

      		int viewDistance = client.options.getEffectiveRenderDistance();
      		ChunkPos center = client.player.chunkPosition();

      		for (int dz = -viewDistance; dz <= viewDistance; dz++) {
            			for (int dx = -viewDistance; dx <= viewDistance; dx++) {
                    				PENDING_SCAN.add(new ChunkPos(center.x + dx, center.z + dz));
                  }
          }
    }

  	private static void drainScanQueue(ClientLevel level) {
      		int budget = SCAN_BUDGET_PER_TICK;

      		while (budget > 0) {
            			ChunkPos pos = PENDING_SCAN.poll();

            			if (pos == null) {
                    				break;
                  }

            			LevelChunk chunk = level.getChunk(pos.x, pos.z, ChunkStatus.FULL, false);

            			if (chunk != null) {
                    				updateHighlightForChunk(level, chunk);
                  }

            			budget--;
          }
    }

  	private static void updateHighlightForChunk(ClientLevel level, LevelChunk chunk) {
      		BlockPos surface = findSurfaceBlock(level, chunk);

      		if (surface != null) {
            			HIGHLIGHTS.put(chunk.getPos(), surface);
          } else {
            			HIGHLIGHTS.remove(chunk.getPos());
          }
    }

  	private static BlockPos findSurfaceBlock(ClientLevel level, LevelChunk chunk) {
      		ChunkPos chunkPos = chunk.getPos();
      		int worldX = (chunkPos.x << 4) + LOCAL_COLUMN_X;
      		int worldZ = (chunkPos.z << 4) + LOCAL_COLUMN_Z;

      		int minY = level.getMinY();
      		int maxY = level.getMaxY();

      		BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos(worldX, maxY - 1, worldZ);

      		for (int y = maxY - 1; y >= minY; y--) {
            			cursor.setY(y);
            			BlockState state = chunk.getBlockState(cursor);

            			if (isAirOrWater(state)) {
                    				continue;
                  }

            			return cursor.immutable();
          }

      		return null;
    }

  	private static boolean isAirOrWater(BlockState state) {
      		if (state.isAir()) {
            			return true;
          }

      		FluidState fluidState = state.getFluidState();
      		return !fluidState.isEmpty() && fluidState.is(FluidTags.WATER);
    }

  	private static void renderHighlights(WorldRenderContext context) {
      		if (!enabled || HIGHLIGHTS.isEmpty()) {
            			return;
          }

      		MultiBufferSource consumers = context.consumers();

      		if (consumers == null) {
            			return;
          }

      		Vec3 camera = context.worldState().cameraRenderState.pos;
      		PoseStack matrices = context.matrices();

      		matrices.pushPose();
      		matrices.translate(-camera.x, -camera.y, -camera.z);

      		VertexConsumer buffer = consumers.getBuffer(RenderTypes.debugFilledBox());
      		int color = ARGB.colorFromFloat(COLOR_ALPHA, COLOR_RED, COLOR_GREEN, COLOR_BLUE);

      		for (BlockPos pos : HIGHLIGHTS.values()) {
            			AABB box = new AABB(pos).inflate(BOX_INFLATE);
            			drawFilledBox(matrices, buffer, box, color);
          }

      		matrices.popPose();
    }

  	private static void drawFilledBox(PoseStack matrices, VertexConsumer buffer, AABB box, int color) {
      		Matrix4f matrix4f = matrices.last().pose();

      		buffer.addVertex(matrix4f, (float) box.minX, (float) box.minY, (float) box.minZ).setColor(color);
      		buffer.addVertex(matrix4f, (float) box.maxX, (float) box.minY, (float) box.minZ).setColor(color);
      		buffer.addVertex(matrix4f, (float) box.maxX, (float) box.maxY, (float) box.minZ).setColor(color);
      		buffer.addVertex(matrix4f, (float) box.minX, (float) box.maxY, (float) box.minZ).setColor(color);
      		buffer.addVertex(matrix4f, (float) box.maxX, (float) box.minY, (float) box.maxZ).setColor(color);
      		buffer.addVertex(matrix4f, (float) box.minX, (float) box.minY, (float) box.maxZ).setColor(color);
      		buffer.addVertex(matrix4f, (float) box.minX, (float) box.maxY, (float) box.maxZ).setColor(color);
      		buffer.addVertex(matrix4f, (float) box.maxX, (float) box.maxY, (float) box.maxZ).setColor(color);
      		buffer.addVertex(matrix4f, (float) box.minX, (float) box.minY, (float) box.maxZ).setColor(color);
      		buffer.addVertex(matrix4f, (float) box.minX, (float) box.minY, (float) box.minZ).setColor(color);
      		buffer.addVertex(matrix4f, (float) box.minX, (float) box.maxY, (float) box.minZ).setColor(color);
      		buffer.addVertex(matrix4f, (float) box.minX, (float) box.maxY, (float) box.maxZ).setColor(color);
      		buffer.addVertex(matrix4f, (float) box.maxX, (float) box.minY, (float) box.minZ).setColor(color);
      		buffer.addVertex(matrix4f, (float) box.maxX, (float) box.minY, (float) box.maxZ).setColor(color);
      		buffer.addVertex(matrix4f, (float) box.maxX, (float) box.maxY, (float) box.maxZ).setColor(color);
      		buffer.addVertex(matrix4f, (float) box.maxX, (float) box.maxY, (float) box.minZ).setColor(color);
      		buffer.addVertex(matrix4f, (float) box.minX, (float) box.maxY, (float) box.minZ).setColor(color);
      		buffer.addVertex(matrix4f, (float) box.maxX, (float) box.maxY, (float) box.minZ).setColor(color);
      		buffer.addVertex(matrix4f, (float) box.maxX, (float) box.maxY, (float) box.maxZ).setColor(color);
      		buffer.addVertex(matrix4f, (float) box.minX, (float) box.maxY, (float) box.maxZ).setColor(color);
      		buffer.addVertex(matrix4f, (float) box.minX, (float) box.minY, (float) box.maxZ).setColor(color);
      		buffer.addVertex(matrix4f, (float) box.maxX, (float) box.minY, (float) box.maxZ).setColor(color);
      		buffer.addVertex(matrix4f, (float) box.maxX, (float) box.minY, (float) box.minZ).setColor(color);
      		buffer.addVertex(matrix4f, (float) box.minX, (float) box.minY, (float) box.minZ).setColor(color);
    }
}
