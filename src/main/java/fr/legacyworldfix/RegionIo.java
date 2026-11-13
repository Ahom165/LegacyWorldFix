package fr.legacyworldfix;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;

/**
 * Minimal reader/writer for the region container format, which is identical
 * between McRegion (.mcr, 2011) and Anvil (.mca): an 8 KiB header of 1024
 * chunk locations and 1024 timestamps, followed by 4 KiB sectors of chunk
 * payloads. Chunk payloads are NBT, gzip or zlib compressed in McRegion
 * files; we always write zlib like modern versions do.
 */
final class RegionIo {
        /** Transforms one chunk NBT (may throw IOException). */
        @FunctionalInterface
        interface ChunkTransformer {
                CompoundTag transform(CompoundTag chunk) throws IOException;
        }

        private static final int SECTOR_BYTES = 4096;
        static final int HEADER_BYTES = 8192;
        private static final int COMPRESSION_GZIP = 1;
        private static final int COMPRESSION_ZLIB = 2;
        private static final int COMPRESSION_NONE = 3;

        private RegionIo() {
        }

        /**
         * Accumulates transformed chunks and saves them as one complete Anvil
         * (.mca) region file. Kept independent from any reader so it can be fed
         * from McRegion containers as well as from loose pre-region chunk files.
         */
        static final class AnvilRegionWriter {
                private final byte[] locations = new byte[4096];
                private final byte[] timestamps = new byte[4096];
                private final ByteArrayOutputStream payload = new ByteArrayOutputStream();
                private int nextSector = 2;
                private int written;

                void writeChunk(int slot, int timestamp, CompoundTag nbt) throws IOException {
                        byte[] compressed = zlibNbt(nbt);
                        int total = compressed.length + 5;
                        int sectorCount = (total + SECTOR_BYTES - 1) / SECTOR_BYTES;
                        if (sectorCount > 255) {
                                throw new IOException("Chunk payload too large: " + total + " bytes");
                        }

                        writeInt(this.locations, slot * 4, (this.nextSector << 8) | sectorCount);
                        writeInt(this.timestamps, slot * 4, timestamp);

                        this.payload.write(intBytes(total));
                        this.payload.write(COMPRESSION_ZLIB);
                        this.payload.write(compressed);
                        for (int pad = total; pad < sectorCount * SECTOR_BYTES; pad++) {
                                this.payload.write(0);
                        }

                        this.nextSector += sectorCount;
                        this.written++;
                }

                int count() {
                        return this.written;
                }

                boolean saveTo(Path target) throws IOException {
                        if (this.written == 0) {
                                return false;
                        }

                        ByteArrayOutputStream out = new ByteArrayOutputStream(HEADER_BYTES + this.payload.size());
                        out.write(this.locations);
                        out.write(this.timestamps);
                        this.payload.writeTo(out);

                        Path temp = target.resolveSibling(target.getFileName() + ".legacyworldfix.tmp");
                        Files.write(temp, out.toByteArray());
                        Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
                        return true;
                }
        }

        /**
         * Reads every chunk of the McRegion file, pipes it through the transformer
         * and writes the result as an Anvil region file. Chunks that cannot be read
         * (empty slot, corrupt payload) are skipped so one bad chunk does not sink
         * the whole world conversion.
         *
         * @return the number of chunks written
         */
        static int convertMcRegionToAnvil(Path source, Path target, ChunkTransformer chunkTransformer) throws IOException {
                AnvilRegionWriter writer = new AnvilRegionWriter();

                try (RegionReader reader = new RegionReader(source)) {
                        for (int slot = 0; slot < 1024; slot++) {
                                CompoundTag chunk;
                                try {
                                        chunk = reader.readChunk(slot);
                                } catch (Exception e) {
                                        LegacyWorldFix.LOGGER.error("[LegacyWorldFix] Skipping unreadable chunk in {} (slot {}): {}", source, slot, e.toString());
                                        continue;
                                }

                                if (chunk == null) {
                                        continue;
                                }

                                writer.writeChunk(slot, reader.chunkTimestamp(slot), chunkTransformer.transform(chunk));
                        }
                }

                writer.saveTo(target);
                return writer.count();
        }

        /** Reads a loose, gzipped NBT file (pre-region chunk files and old level.dat variants). */
        static CompoundTag readGzippedNbt(Path file) throws IOException {
                try (InputStream in = new GZIPInputStream(new BufferedInputStream(Files.newInputStream(file)))) {
                        return NbtIo.read(new DataInputStream(in), NbtAccounter.unlimitedHeap());
                }
        }

        static byte[] zlibNbt(CompoundTag tag) throws IOException {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                // BEST_SPEED: the mod only rewrites containers once and the game's own
                // save pipeline recompresses on the next save anyway, so we optimize for
                // conversion wall time rather than for file size.
                Deflater deflater = new Deflater(Deflater.BEST_SPEED);
                try {
                        try (DeflaterOutputStream deflaterStream = new DeflaterOutputStream(bytes, deflater)) {
                                NbtIo.write(tag, new DataOutputStream(deflaterStream));
                        }
                } finally {
                        deflater.end();
                }

                return bytes.toByteArray();
        }

        private static void writeInt(byte[] target, int offset, int value) {
                target[offset] = (byte) (value >>> 24);
                target[offset + 1] = (byte) (value >>> 16);
                target[offset + 2] = (byte) (value >>> 8);
                target[offset + 3] = (byte) value;
        }

        private static byte[] intBytes(int value) {
                return new byte[]{(byte) (value >>> 24), (byte) (value >>> 16), (byte) (value >>> 8), (byte) value};
        }

        private static int readInt(byte[] source, int offset) {
                return ((source[offset] & 0xFF) << 24) | ((source[offset + 1] & 0xFF) << 16) | ((source[offset + 2] & 0xFF) << 8) | (source[offset + 3] & 0xFF);
        }

        private static final class RegionReader implements Closeable {
                private final RandomAccessFile file;
                private final byte[] header = new byte[HEADER_BYTES];

                RegionReader(Path path) throws IOException {
                        this.file = new RandomAccessFile(path.toFile(), "r");
                        // Single 8 KiB read instead of 2048 random seeks over the header.
                        this.file.readFully(this.header);
                }

                private int locationValue(int slot) {
                        return readInt(this.header, slot * 4);
                }

                int chunkTimestamp(int slot) {
                        return readInt(this.header, 4096 + slot * 4);
                }

                CompoundTag readChunk(int slot) throws IOException {
                        int location = this.locationValue(slot);
                        int sectorOffset = location >>> 8;
                        int sectorCount = location & 0xFF;
                        if (sectorOffset == 0 || sectorCount == 0) {
                                return null;
                        }

                        this.file.seek(sectorOffset * (long) SECTOR_BYTES);
                        int length = this.file.readInt();
                        if (length <= 1 || length > sectorCount * SECTOR_BYTES) {
                                throw new IOException("Corrupt chunk header (slot " + slot + ", length " + length + ")");
                        }

                        int compressionType = this.file.readByte();
                        byte[] data = new byte[length - 1];
                        this.file.readFully(data);

                        InputStream raw = switch (compressionType) {
                                case COMPRESSION_GZIP -> new GZIPInputStream(new ByteArrayInputStream(data));
                                case COMPRESSION_ZLIB -> new InflaterInputStream(new ByteArrayInputStream(data));
                                case COMPRESSION_NONE -> new ByteArrayInputStream(data);
                                default -> throw new IOException("Unknown chunk compression " + compressionType);
                        };

                        try (InputStream in = new BufferedInputStream(raw)) {
                                return NbtIo.read(new DataInputStream(in), NbtAccounter.unlimitedHeap());
                        }
                }

                @Override
                public void close() throws IOException {
                        this.file.close();
                }
        }
}
