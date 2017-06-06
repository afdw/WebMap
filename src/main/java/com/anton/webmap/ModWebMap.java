package com.anton.webmap;

import com.google.common.collect.ImmutableMap;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.block.Block;
import net.minecraft.block.material.EnumPushReaction;
import net.minecraft.block.material.MapColor;
import net.minecraft.block.material.Material;
import net.minecraft.block.properties.IProperty;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.VertexBuffer;
import net.minecraft.client.renderer.block.statemap.BlockStateMapper;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumBlockRenderType;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.Mirror;
import net.minecraft.util.Rotation;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.DimensionType;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.World;
import net.minecraft.world.WorldType;
import net.minecraft.world.biome.Biome;
import net.minecraftforge.client.event.GuiScreenEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.event.FMLServerStartedEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.apache.commons.io.FileUtils;
import org.lwjgl.BufferUtils;
import org.lwjgl.input.Keyboard;
import org.lwjgl.opengl.GL11;

import javax.annotation.Nullable;
import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.util.*;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Mod(modid = "webmap", version = "1.0", name = "Web Map")
public class ModWebMap {
    @Mod.Instance
    public static ModWebMap INSTANCE;

    private static final Queue<IBlockState> blockStateQueue = new ArrayDeque<>();
    private static final Map<IBlockState, ImageEntry> imageCache = new ConcurrentHashMap<>();
    private static final ThreadPoolExecutor executor = (ThreadPoolExecutor) Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
    private static final int TILE_SIZE = 256;
    private static final int WORLD_SIZE = 64;
    private static final int WORLD_HEIGHT = 256;
    private static final int CHUNK_SIZE = 16;
    private static final Future<?>[][][] layers = new Future<?>[32 - Integer.numberOfLeadingZeros(WORLD_SIZE)][][];
    private static int lastFilesCount = 0;
    private static int[] lastSpeeds = new int[2048];

    static {
        for (int i = 0; i < layers.length; i++) {
            layers[i] = new Future<?>[1 << i][1 << i];
        }
    }

    private static BlockPos currentPos = BlockPos.ORIGIN;
    private static IBlockState[] currentRowBlockStates = new IBlockState[WORLD_HEIGHT];
    private static List<IBlockState> allBlockStates = null;

    @Mod.EventHandler
    public void onPreInitialization(FMLPreInitializationEvent event) {
        MinecraftForge.EVENT_BUS.register(this);
    }

    @Mod.EventHandler
    public void onServerStarted(FMLServerStartedEvent event) {
        try {
            FileUtils.deleteDirectory(new File("webmap"));
            FileUtils.copyURLToFile(getClass().getResource("/assets/webmap/index.html"), new File("webmap/index.html"));
            FileUtils.copyURLToFile(getClass().getResource("/assets/webmap/ol-debug.js"), new File("webmap/ol-debug.js"));
            FileUtils.copyURLToFile(getClass().getResource("/assets/webmap/placeholder.png"), new File("webmap/placeholder.png"));
            FileUtils.deleteDirectory(new File("cache"));
            new File("cache").mkdir();
        } catch (IOException e) {
            e.printStackTrace();
        }
        new Thread(() -> {
            try {
                HttpServer.run();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }, "WebMap Starter").start();
    }

    private synchronized ImageEntry getImageEntry(IBlockState state) {
        if (!imageCache.containsKey(state)) {
            try {
                imageCache.put(state, new ImageEntry(state));
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        return imageCache.get(state);
    }

    private void drawRow(IBlockState[] rowBlockStates, BlockPos tilePos) {
        List<ImageEntry> imageEntries = Arrays.stream(rowBlockStates)
            .filter(blockState -> blockState.getBlock() != Blocks.AIR)
            .map(this::getImageEntry)
            .distinct()
            .collect(Collectors.toList());
        for (int i = imageEntries.size() - 1; i >= 0; i--) {
            ImageEntry entry = imageEntries.get(i);
            if (entry.hasNoTransparentPixels) {
                imageEntries = imageEntries.subList(i, imageEntries.size());
                break;
            }
        }
        File file = getTileFile(layers.length - 1, tilePos);
        if (file.exists()) {
            file.delete();
        }
        try {
            if (imageEntries.size() == 1) {
                ImageEntry imageEntry = imageEntries.get(0);
                Files.copy(imageEntry.file.toPath(), file.toPath());
            } else {
                File cacheFile = new File(
                    "cache/" + imageEntries.stream()
                        .map(imageEntry -> imageEntry.state)
                        .map(Object::toString)
                        .reduce((a, b) -> a + ";" + b)
                        .orElse(null)
                );
                if (!cacheFile.exists()) {
                    BufferedImage image = new BufferedImage(TILE_SIZE, TILE_SIZE, BufferedImage.TYPE_INT_ARGB);
                    Graphics2D graphics = image.createGraphics();
                    imageEntries.stream()
                        .map(imageEntry -> imageEntry.image)
                        .forEach(blockStateImage -> graphics.drawImage(blockStateImage, 0, 0, null));
                    ImageIO.write(image, "PNG", cacheFile);
                }
                Files.copy(cacheFile.toPath(), file.toPath());
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void merge(int layer, BlockPos tilePos) {
        BufferedImage image = new BufferedImage(TILE_SIZE, TILE_SIZE, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        Stream.of(
            new BlockPos(0, 0, 0),
            new BlockPos(1, 0, 0),
            new BlockPos(0, 0, 1),
            new BlockPos(1, 0, 1)
        )
            .forEach(tileOffset -> {
                BlockPos tilePosCurrent = new BlockPos(tilePos.getX() * 2, tilePos.getY(), tilePos.getZ() * 2).add(tileOffset);
                int layerCurrent = layer + 1;
                try {
                    graphics.drawImage(
                        ImageIO.read(getTileFile(layerCurrent, tilePosCurrent)),
                        tileOffset.getX() * TILE_SIZE / 2,
                        tileOffset.getZ() * TILE_SIZE / 2,
                        (tileOffset.getX() + 1) * TILE_SIZE / 2,
                        (tileOffset.getZ() + 1) * TILE_SIZE / 2,
                        0,
                        0,
                        TILE_SIZE,
                        TILE_SIZE,
                        null
                    );
                } catch (IOException e) {
                    e.printStackTrace();
                }
            });
        try {
            ImageIO.write(image, "PNG", getTileFile(layer, tilePos));
        } catch (IOException e) {
            e.printStackTrace();
        }

    }

    private File getTileFile(int layer, BlockPos tilePos) {
        return new File("webmap/tile_" + layer + "_" + tilePos.getX() + "_" + tilePos.getZ() + ".png");
    }

    private void submitTasks() {
        IBlockState[] rowBlockStatesCopy = currentRowBlockStates.clone();
        BlockPos finalCurrentPos = currentPos;
        layers[layers.length - 1][currentPos.getX()][currentPos.getZ()] = executor.submit(() -> {
            drawRow(rowBlockStatesCopy, finalCurrentPos);
            return null;
        });
        int layer = layers.length - 1;
        BlockPos currentPosCopy = currentPos;
        while (layer >= 0 && currentPosCopy.getX() % 2 == 1 && currentPosCopy.getZ() % 2 == 1) {
            Future<?>[] futures = {
                layers
                    [layer]
                    [currentPosCopy.getX() - 1]
                    [currentPosCopy.getZ() - 1],
                layers
                    [layer]
                    [currentPosCopy.getX()]
                    [currentPosCopy.getZ() - 1],
                layers
                    [layer]
                    [currentPosCopy.getX() - 1]
                    [currentPosCopy.getZ()],
                layers
                    [layer]
                    [currentPosCopy.getX()]
                    [currentPosCopy.getZ()]
            };
            currentPosCopy = new BlockPos(currentPosCopy.getX() / 2, currentPosCopy.getY(), currentPosCopy.getZ() / 2);
            layer--;
            int finalLayer = layer;
            BlockPos finalTilePosCopy = currentPosCopy;
            layers[layer][currentPosCopy.getX()][currentPosCopy.getZ()] = executor.submit(() -> {
                for (Future<?> future : futures) {
                    future.get();
                }
                merge(finalLayer, finalTilePosCopy);
                return null;
            });
        }
    }

    private void nextPos() {
        if (currentPos.getY() == WORLD_HEIGHT - 1) {
            if (currentPos.getX() % CHUNK_SIZE != CHUNK_SIZE - 1) {
                currentPos = new BlockPos(currentPos.getX() + 1, 0, currentPos.getZ());
            } else {
                if (currentPos.getZ() % CHUNK_SIZE != CHUNK_SIZE - 1) {
                    currentPos = new BlockPos(currentPos.getX() - CHUNK_SIZE + 1, 0, currentPos.getZ() + 1);
                } else {
                    if (currentPos.getX() < WORLD_SIZE - 1) {
                        currentPos = new BlockPos(currentPos.getX() + 1, 0, currentPos.getZ() - CHUNK_SIZE + 1);
                    } else {
                        if (currentPos.getZ() < WORLD_SIZE - 1) {
                            currentPos = new BlockPos(0, 0, currentPos.getZ() + 1);
                        } else {
                            currentPos = new BlockPos(0, 0, 0);
                        }
                    }
                }
            }
        } else {
            currentPos = new BlockPos(currentPos.getX(), currentPos.getY() + 1, currentPos.getZ());
        }
    }

    public void sendStartMessageTo(ChannelHandlerContext ctx) {
        HashMap<String, Object> message = new HashMap<>();
        message.put("type", "start");
        message.put("tileSize", TILE_SIZE);
        message.put("worldSize", WORLD_SIZE);
        message.put("layersCount", layers.length);
        message.put("totalFilesCount", Arrays.stream(layers).mapToInt(futures -> futures.length).map(c -> (int) Math.pow(c, 2)).sum());
        HttpServerWebSocketHandler.sendTo(ctx.channel(), message);
    }

    private void sendTickMessageToAll() {
        String[] fileList = new File("webmap").list();
        int filesCount = 0;
        if (fileList != null) {
            filesCount = fileList.length;
        }
        HashMap<String, Object> message = new HashMap<>();
        System.arraycopy(lastSpeeds, 0, lastSpeeds, 1, lastSpeeds.length - 1);
        lastSpeeds[0] = (filesCount - lastFilesCount) * 20;
        message.put("type", "tick");
        message.put("filesCount", filesCount);
        message.put("speed", Arrays.stream(lastSpeeds).filter(speed -> speed != 0).average().orElse(0));
        message.put("queueSize", executor.getQueue().size());
        lastFilesCount = filesCount;
        HttpServerWebSocketHandler.sendToAll(message);
    }

    private static BlockPos convertToWorldPos(BlockPos tilePos) {
        return new BlockPos(tilePos.getX() - WORLD_SIZE / 2, tilePos.getY(), tilePos.getZ() + WORLD_SIZE / 2);
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        MinecraftServer server = FMLCommonHandler.instance().getMinecraftServerInstance();
        World world = server.worldServerForDimension(DimensionType.OVERWORLD.getId()); // FIXME: dimensions
        while (System.currentTimeMillis() < server.getCurrentTime() + 40 && executor.getQueue().size() < executor.getMaximumPoolSize() * 64) {
            currentRowBlockStates[currentPos.getY()] = world.getBlockState(convertToWorldPos(currentPos));
            if (currentPos.getY() == WORLD_HEIGHT - 1) {
                submitTasks();
            }
            nextPos();
        }
        sendTickMessageToAll();
    }

    @SubscribeEvent
    @SideOnly(Side.CLIENT)
    public void onKeyboardInput(GuiScreenEvent.KeyboardInputEvent.Post event) {
        if (allBlockStates == null) {
            allBlockStates = new ArrayList<>(new BlockStateMapper().putAllStateModelLocations().keySet());
            allBlockStates.sort(Comparator.comparing(Object::toString));
        }
        if (Keyboard.isKeyDown(Keyboard.KEY_MINUS)) {
            try {
                FileUtils.deleteDirectory(new File("blockstates"));
                new File("blockstates").mkdirs();
            } catch (IOException e) {
                e.printStackTrace();
            }
            if (blockStateQueue.isEmpty()) {
                blockStateQueue.addAll(allBlockStates);
            }
//            blockStateQueue.add(Blocks.CAULDRON.getDefaultState());
        }
    }

    // FIXME: move to another class and pull inner classes up and fix using this event
    @SubscribeEvent
    @SideOnly(Side.CLIENT)
    @SuppressWarnings("deprecation")
    public void onGuiScreen(GuiScreenEvent.DrawScreenEvent.Post event) {
        int displayWidth = Minecraft.getMinecraft().displayWidth;
        int displayHeight = Minecraft.getMinecraft().displayHeight;
        long time = System.currentTimeMillis();
        IBlockState state = Blocks.AIR.getDefaultState();
        while (!blockStateQueue.isEmpty() && (System.currentTimeMillis() - time) < 1000 / 60) {
            state = blockStateQueue.poll();
            GlStateManager.clearColor(0, 0, 0, 0);
            GlStateManager.clear(GL11.GL_DEPTH_BUFFER_BIT | GL11.GL_COLOR_BUFFER_BIT);
            GlStateManager.viewport(0, 0, displayWidth, displayHeight);
            GL11.glMatrixMode(GL11.GL_PROJECTION);
            GL11.glLoadIdentity();
            GL11.glOrtho(0, displayWidth, displayHeight, 0, -TILE_SIZE, TILE_SIZE);
            GL11.glMatrixMode(GL11.GL_MODELVIEW);
            GL11.glLoadIdentity();
            VertexBuffer vb = Tessellator.getInstance().getBuffer();
            vb.begin(GL11.GL_QUADS, DefaultVertexFormats.BLOCK);
            IBlockState finalState = state;
            Minecraft.getMinecraft().getBlockRendererDispatcher().renderBlock(state, BlockPos.ORIGIN, new IBlockAccess() {
                @Nullable
                @Override
                public TileEntity getTileEntity(BlockPos pos) {
                    return null;
                }

                @Override
                public int getCombinedLight(BlockPos pos, int lightValue) {
                    return 15 << 20 | 15 << 4;
                }

                @Override
                public IBlockState getBlockState(BlockPos pos) {
                    return pos.equals(BlockPos.ORIGIN) ? finalState : new IBlockState() {
                        @Override
                        public Collection<IProperty<?>> getPropertyKeys() {
                            return null;
                        }

                        @Override
                        public <T extends Comparable<T>> T getValue(IProperty<T> property) {
                            return null;
                        }

                        @Override
                        public <T extends Comparable<T>, V extends T> IBlockState withProperty(IProperty<T> property, V value) {
                            return null;
                        }

                        @Override
                        public <T extends Comparable<T>> IBlockState cycleProperty(IProperty<T> property) {
                            return null;
                        }

                        @Override
                        public ImmutableMap<IProperty<?>, Comparable<?>> getProperties() {
                            return null;
                        }

                        @Override
                        public Block getBlock() {
                            return Blocks.STONE;
                        }

                        @Override
                        public boolean onBlockEventReceived(World worldIn, BlockPos pos, int id, int param) {
                            return false;
                        }

                        @Override
                        public void neighborChanged(World worldIn, BlockPos pos, Block blockIn, BlockPos p_189546_4_) {

                        }

                        @Override
                        public Material getMaterial() {
                            return Material.ROCK;
                        }

                        @Override
                        public boolean isFullBlock() {
                            return false;
                        }

                        @Override
                        public boolean canEntitySpawn(Entity entityIn) {
                            return false;
                        }

                        @Override
                        public int getLightOpacity() {
                            return 0;
                        }

                        @Override
                        public int getLightOpacity(IBlockAccess world, BlockPos pos) {
                            return 0;
                        }

                        @Override
                        public int getLightValue() {
                            return 0;
                        }

                        @Override
                        public int getLightValue(IBlockAccess world, BlockPos pos) {
                            return 0;
                        }

                        @Override
                        public boolean isTranslucent() {
                            return false;
                        }

                        @Override
                        public boolean useNeighborBrightness() {
                            return false;
                        }

                        @Override
                        public MapColor getMapColor() {
                            return null;
                        }

                        @Override
                        public IBlockState withRotation(Rotation rot) {
                            return null;
                        }

                        @Override
                        public IBlockState withMirror(Mirror mirrorIn) {
                            return null;
                        }

                        @Override
                        public boolean isFullCube() {
                            return false;
                        }

                        @Override
                        public boolean hasCustomBreakingProgress() {
                            return false;
                        }

                        @Override
                        public EnumBlockRenderType getRenderType() {
                            return null;
                        }

                        @Override
                        public int getPackedLightmapCoords(IBlockAccess source, BlockPos pos) {
                            return source.getCombinedLight(pos, 0);
                        }

                        @Override
                        public float getAmbientOcclusionLightValue() {
                            return 1;
                        }

                        @Override
                        public boolean isBlockNormalCube() {
                            return false;
                        }

                        @Override
                        public boolean isNormalCube() {
                            return false;
                        }

                        @Override
                        public boolean canProvidePower() {
                            return false;
                        }

                        @Override
                        public int getWeakPower(IBlockAccess blockAccess, BlockPos pos, EnumFacing side) {
                            return 0;
                        }

                        @Override
                        public boolean hasComparatorInputOverride() {
                            return false;
                        }

                        @Override
                        public int getComparatorInputOverride(World worldIn, BlockPos pos) {
                            return 0;
                        }

                        @Override
                        public float getBlockHardness(World worldIn, BlockPos pos) {
                            return 0;
                        }

                        @Override
                        public float getPlayerRelativeBlockHardness(EntityPlayer player, World worldIn, BlockPos pos) {
                            return 0;
                        }

                        @Override
                        public int getStrongPower(IBlockAccess blockAccess, BlockPos pos, EnumFacing side) {
                            return 0;
                        }

                        @Override
                        public EnumPushReaction getMobilityFlag() {
                            return null;
                        }

                        @Override
                        public IBlockState getActualState(IBlockAccess blockAccess, BlockPos pos) {
                            return null;
                        }

                        @Override
                        public AxisAlignedBB getSelectedBoundingBox(World worldIn, BlockPos pos) {
                            return null;
                        }

                        @Override
                        public boolean shouldSideBeRendered(IBlockAccess blockAccess, BlockPos pos, EnumFacing facing) {
                            return false;
                        }

                        @Override
                        public boolean isOpaqueCube() {
                            return false;
                        }

                        @Nullable
                        @Override
                        public AxisAlignedBB getCollisionBoundingBox(IBlockAccess worldIn, BlockPos pos) {
                            return null;
                        }

                        @Override
                        public void addCollisionBoxToList(World worldIn, BlockPos pos, AxisAlignedBB entityBox, List<AxisAlignedBB> collidingBoxes, @Nullable Entity entityIn, boolean p_185908_6_) {

                        }

                        @Override
                        public AxisAlignedBB getBoundingBox(IBlockAccess blockAccess, BlockPos pos) {
                            return null;
                        }

                        @Override
                        public RayTraceResult collisionRayTrace(World worldIn, BlockPos pos, Vec3d start, Vec3d end) {
                            return null;
                        }

                        @Override
                        public boolean isFullyOpaque() {
                            return false;
                        }

                        @Override
                        public boolean doesSideBlockRendering(IBlockAccess world, BlockPos pos, EnumFacing side) {
                            return false;
                        }

                        @Override
                        public boolean isSideSolid(IBlockAccess world, BlockPos pos, EnumFacing side) {
                            return false;
                        }

                        @Override
                        public Vec3d getOffset(IBlockAccess access, BlockPos pos) {
                            return null;
                        }

                        @Override
                        public boolean causesSuffocation() {
                            return false;
                        }
                    };
                }

                @Override
                public boolean isAirBlock(BlockPos pos) {
                    return false;
                }

                @Override
                public Biome getBiome(BlockPos pos) {
                    return Biome.getBiome(1);
                }

                @Override
                public int getStrongPower(BlockPos pos, EnumFacing direction) {
                    return 0;
                }

                @Override
                public WorldType getWorldType() {
                    return WorldType.DEBUG_WORLD;
                }

                @Override
                public boolean isSideSolid(BlockPos pos, EnumFacing side, boolean _default) {
                    return _default;
                }
            }, vb);
            Minecraft.getMinecraft().renderEngine.bindTexture(TextureMap.LOCATION_BLOCKS_TEXTURE);
            GlStateManager.pushMatrix();
            GlStateManager.scale(TILE_SIZE, TILE_SIZE, TILE_SIZE);
            GlStateManager.translate(0.5, 0.5, 0.5);
            GlStateManager.rotate(270, 1, 0, 0);
            GlStateManager.translate(-0.5, -0.5, -0.5);
            Tessellator.getInstance().draw();
            GlStateManager.popMatrix();
            ByteBuffer buffer = BufferUtils.createByteBuffer(TILE_SIZE * TILE_SIZE * 4);
            GL11.glReadPixels(0, displayHeight - TILE_SIZE, TILE_SIZE, TILE_SIZE, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, buffer);
            BufferedImage image = new BufferedImage(TILE_SIZE, TILE_SIZE, BufferedImage.TYPE_INT_ARGB);
            int[] rgbArray = new int[TILE_SIZE * TILE_SIZE];
            for (int i = 0; i < TILE_SIZE * TILE_SIZE; i++) {
                int p = i * 4;
                rgbArray[i] = (buffer.get(p + 3) & 0xFF) << 24 | (buffer.get(p) & 0xFF) << 16 | (buffer.get(p + 1) & 0xFF) << 8 | buffer.get(p + 2) & 0xFF;
            }
            image.setRGB(0, 0, TILE_SIZE, TILE_SIZE, rgbArray, 0, TILE_SIZE);
            try {
                ImageIO.write(image, "PNG", new File("blockstates/" + state.toString() + ".png"));
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        if (allBlockStates != null) {
            GlStateManager.disableTexture2D();
            GlStateManager.color(1, 1, 1, 1);
            GlStateManager.glBegin(GL11.GL_QUADS);
            double posX = displayWidth - (double) displayWidth / allBlockStates.size() * blockStateQueue.size();
            GL11.glVertex2d(0, displayHeight - 16);
            GL11.glVertex2d(0, displayHeight);
            GL11.glVertex2d(posX, displayHeight);
            GL11.glVertex2d(posX, displayHeight - 16);
            GlStateManager.glEnd();
            GlStateManager.enableTexture2D();
            Minecraft.getMinecraft().fontRendererObj.drawString(state.toString(), 0, displayHeight - 32, 0xFFFFFFFF);
        }
    }
}
