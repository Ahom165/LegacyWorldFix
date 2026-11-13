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
        private static final int COMPRESSION_GZIP = 1;
        private static final int COMPRESSION_ZLIB = 2;
        private static final int COMPRESSION_NONE = 3;

        private RegionIo() {
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
                int converted = 0;
                byte[] locations = new byte[4096];
                byte[] timestamps = new byte[4096];
                ByteArrayOutputStream payload = new ByteArrayOutputStream();

                try (RegionReader reader = new RegionReader(source)) {
                        int nextSector = 2;

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

                                CompoundTag transformed = chunkTransformer.transform(chunk);
                                byte[] compressed = zlibNbt(transformed);
                                int total = compressed.length + 5;
                                int sectorCount = (total + SECTOR_BYTES - 1) / SECTOR_BYTES;
                                if (sectorCount > 255) {
                                        throw new IOException("Chunk payload too large: " + total + " bytes");
                                }

                                writeInt(locations, slot * 4, (nextSector << 8) | sectorCount);
                                writeInt(timestamps, slot * 4, reader.chunkTimestamp(slot));

                                payload.write(intBytes(total));
                                payload.write(COMPRESSION_ZLIB);
                                payload.write(compressed);
                                for (int pad = total; pad < sectorCount * SECTOR_BYTES; pad++) {
                                        payload.write(0);
                                }

                                nextSector += sectorCount;
                                converted++;
                        }
                }

                if (converted > 0) {
                        ByteArrayOutputStream out = new ByteArrayOutputStream(HEADER_BYTES + payload.size());
                        out.write(locations);
                        out.write(timestamps);
                        payload.writeTo(out);

                        Path temp = target.resolveSibling(target.getFileName() + ".legacyworldfix.tmp");
                        Files.write(temp, out.toByteArray());
                        Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
                }

                return converted;
        }

        private static final int HEADER_BYTES = 8192;

        private static byte[] zlibNbt(CompoundTag tag) throws IOException {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                try (DeflaterOutputStream deflater = new DeflaterOutputStream(bytes)) {
                        NbtIo.write(tag, new DataOutputStream(deflater));
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

        private static final class RegionReader implements Closeable {
                private final RandomAccessFile file;

                RegionReader(Path path) throws IOException {
                        this.file = new RandomAccessFile(path.toFile(), "r");
                }

                private long locationValue(int slot) throws IOException {
                        this.file.seek(4L * slot);
                        return Integer.toUnsignedLong(this.file.readInt());
                }

                int chunkTimestamp(int slot) throws IOException {
                        this.file.seek(4096L + 4L * slot);
                        return this.file.readInt();
                }

                CompoundTag readChunk(int slot) throws IOException {
                        long location = this.locationValue(slot);
                        int sectorOffset = (int) (location >> 8);
                        int sectorCount = (int) (location & 0xFF);
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
