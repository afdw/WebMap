package com.anton.webmap;

import java.io.Closeable;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;

public class Tile implements Closeable {
    public static final int SIZE = 256;
    public static final int FILE_LENGTH = SIZE * SIZE * 4;
    public static final String EXTENSION = ".tile";

    private final RandomAccessFile randomAccessFile;
    private final FileChannel fileChannel;
    public final MappedByteBuffer buffer;

    public Tile(Path path) throws IOException {
        randomAccessFile = new RandomAccessFile(path.toFile(), "rw");
        fileChannel = randomAccessFile.getChannel();
        buffer = fileChannel.map(FileChannel.MapMode.READ_WRITE, 0, FILE_LENGTH);
    }

    @Override
    public void close() throws IOException {
        randomAccessFile.close();
        fileChannel.close();
    }
}
