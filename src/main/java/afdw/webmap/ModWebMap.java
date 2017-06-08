package afdw.webmap;

import io.netty.channel.ChannelHandlerContext;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.DimensionType;
import net.minecraft.world.World;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.event.FMLServerStartedEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.apache.commons.io.FileUtils;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Mod(modid = "webmap", name = "Web Map", version = "1.0")
public class ModWebMap {
    @Mod.Instance
    public static ModWebMap INSTANCE;

    private static final Map<IBlockState, ImageEntry> imageCache = new ConcurrentHashMap<>();
    private static final ThreadPoolExecutor executor = (ThreadPoolExecutor) Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
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

    @Mod.EventHandler
    public void onPreInitialization(FMLPreInitializationEvent event) {
        MinecraftForge.EVENT_BUS.register(this);
        MinecraftForge.EVENT_BUS.register(BlockStateRenderer.INSTANCE);
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
                    BufferedImage image = new BufferedImage(Tile.SIZE, Tile.SIZE, BufferedImage.TYPE_INT_ARGB);
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
        BufferedImage image = new BufferedImage(Tile.SIZE, Tile.SIZE, BufferedImage.TYPE_INT_ARGB);
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
                        tileOffset.getX() * Tile.SIZE / 2,
                        tileOffset.getZ() * Tile.SIZE / 2,
                        (tileOffset.getX() + 1) * Tile.SIZE / 2,
                        (tileOffset.getZ() + 1) * Tile.SIZE / 2,
                        0,
                        0,
                        Tile.SIZE,
                        Tile.SIZE,
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
        message.put("tileSize", Tile.SIZE);
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
}
