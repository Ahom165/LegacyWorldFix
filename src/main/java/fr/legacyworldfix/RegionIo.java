package fr.legacyworldfix;

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
import java.util.ArrayList;
import java.util.List;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.GZIPInputStream;
import java.util.zip.Inflater;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;

/**
 * Minimal reader/writer for the region container format, which is identical
 * between McRegion (.mcr, 2011) and Anvil (.mca): an 8 KiB header of 1024
 * chunk locations and 1024 timestamps, followed by 4 KiB sectors of chunk
 * payloads. Chunk payloads are NBT, gzip or zlib compressed in McRegion
 * files; we always write zlib like modern versions do.
 *
 * <p>The v1.7.0 codec is one-shot and pooled: each worker thread owns an
 * {@link Inflater}/{@link Deflater} pair plus a 64 KiB scratch buffer
 * (ThreadLocal, reset per use), and every payload is inflated or deflated
 * straight from/to its byte array. The previous shape paid a fresh native
 * z_stream per chunk and pulled compressed bytes through an
 * {@code InflaterInputStream} with the 512-byte default buffer (~100 refills
 * per chunk, each a virtual-call round trip).
 */
final class RegionIo {

        /**
         * A chunk payload copied out of a region container, still compressed.
         * Decoding it (inflate + NBT parse + datafixers) is the expensive part of
         * a conversion and is done later, in parallel, one chunk at a time.
         */
        record RawChunk(int slot, int timestamp, byte[] data, int compressionType) {
        }

        /** A fully transformed chunk, ready to be written into an Anvil container. */
        record CompressedChunk(Path source, int slot, int timestamp, byte[] payload) {
        }

        private static final int SECTOR_BYTES = 4096;
        static final int HEADER_BYTES = 8192;
        private static final int GZIP_BUFFER = 1 << 16;
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
                        writeCompressedChunk(slot, timestamp, zlibNbt(nbt));
                }

                /**
                 * Appends an already zlib-compressed chunk. Lets parallel workers do
                 * the compression outside the lock when several threads feed the same
                 * in-memory region.
                 */
                void writeCompressedChunk(int slot, int timestamp, byte[] compressed) throws IOException {
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
                        Files.createDirectories(target.getParent());
                        Files.write(temp, out.toByteArray());
                        Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
                        return true;
                }
        }

        /**
         * Copies every stored chunk payload of the McRegion file out of the
         * container without decoding it. This pass is pure I/O and keeps the file
         * access single-threaded (the region reader is not thread-safe); the
         * expensive decode/transform/DFU work then runs on parallel per-chunk
         * tasks via {@link #decodeChunk} + {@link #compressChunk}.
         *
         * <p>Chunks that cannot be read (empty slot, corrupt payload) are skipped
         * so one bad chunk does not sink the whole world conversion.
         */
        static List<RawChunk> readRawChunks(Path source) throws IOException {
                List<RawChunk> rawChunks = new ArrayList<>();
                try (RegionReader reader = new RegionReader(source)) {
                        for (int slot = 0; slot < 1024; slot++) {
                                try {
                                        RawChunk raw = reader.readRawChunk(slot);
                                        if (raw != null) {
                                                rawChunks.add(raw);
                                        }
                                } catch (Exception e) {
                                        LegacyWorldFix.LOGGER.error("[LegacyWorldFix] Skipping unreadable chunk in {} (slot {}): {}", source, slot, e.toString());
                                }
                        }
                }

                return rawChunks;
        }

        /** Inflates and parses one raw chunk payload. */
        static CompoundTag decodeChunk(RawChunk raw) throws IOException {
                return switch (raw.compressionType()) {
                        case COMPRESSION_GZIP -> readNbt(gunzip(raw.data()));
                        case COMPRESSION_ZLIB -> readNbt(inflate(raw.data(), 0, raw.data().length, INFLATER_ZLIB.get()));
                        case COMPRESSION_NONE -> readNbt(raw.data());
                        default -> throw new IOException("Unknown chunk compression " + raw.compressionType());
                };
        }

        /** Parses one fully-inflated NBT payload. */
        private static CompoundTag readNbt(byte[] bytes) throws IOException {
                try (InputStream in = new ByteArrayInputStream(bytes)) {
                        return NbtIo.read(new DataInputStream(in), NbtAccounter.unlimitedHeap());
                }
        }

        /**
         * One-shot gzip inflate with a pooled engine. Old saves were written by
         * Java's own {@code GZIPOutputStream}: a plain 10-byte header (RFC 1952,
         * no optional fields) followed by the raw deflate stream. Anything that
         * does not match that exact shape falls back to {@link GZIPInputStream},
         * which knows every header variant and multi-member files.
         */
        private static byte[] gunzip(byte[] payload) throws IOException {
                if (payload.length >= 18
                        && payload[0] == (byte) 0x1f
                        && payload[1] == (byte) 0x8b
                        && payload[2] == 8
                        && payload[3] == 0) {
                        // The 8-byte CRC32+ISIZE trailer sits behind the deflate
                        // stream; the inflater stops exactly at the stream end and
                        // never reads it.
                        return inflate(payload, 10, payload.length - 10, INFLATER_GZIP.get());
                }
                try (InputStream in = new GZIPInputStream(new ByteArrayInputStream(payload), GZIP_BUFFER)) {
                        return in.readAllBytes();
                }
        }

        /**
         * Inflates {@code length} bytes at {@code offset} with the thread's pooled
         * engine into one contiguous result. The engine is {@link Inflater#reset()
         * reset} per call, so a payload that failed mid-stream cannot poison the
         * next one.
         */
        private static byte[] inflate(byte[] payload, int offset, int length, Inflater inflater) throws IOException {
                inflater.reset();
                inflater.setInput(payload, offset, length);
                ByteArrayOutputStream out = new ByteArrayOutputStream(length * 3);
                byte[] scratch = SCRATCH.get();
                try {
                        while (!inflater.finished()) {
                                int produced = inflater.inflate(scratch);
                                if (produced == 0) {
                                        if (inflater.needsInput() || inflater.needsDictionary()) {
                                                throw new IOException("Truncated compressed chunk payload");
                                        }
                                } else {
                                        out.write(scratch, 0, produced);
                                }
                        }
                } catch (DataFormatException e) {
                        throw new IOException("Corrupt compressed chunk payload: " + e.getMessage(), e);
                }
                return out.toByteArray();
        }

        /** Compresses a transformed chunk into its final Anvil payload. */
        static CompressedChunk compressChunk(Path source, RawChunk raw, CompoundTag transformed) throws IOException {
                return new CompressedChunk(source, raw.slot(), raw.timestamp(), zlibNbt(transformed));
        }

        /** Reads a loose, gzipped NBT file (pre-region chunk files and old level.dat variants). */
        static CompoundTag readGzippedNbt(Path file) throws IOException {
                return readNbt(gunzip(Files.readAllBytes(file)));
        }

        /**
         * Serializes a chunk to NBT and zlib-compresses it in one shot with the
         * thread's pooled {@link Deflater} ({@link Deflater#BEST_SPEED}, exactly
         * the parameters the per-chunk constructor used, so the bytes on disk are
         * identical).
         */
        static byte[] zlibNbt(CompoundTag tag) throws IOException {
                ByteArrayOutputStream nbtBytes = new ByteArrayOutputStream(1 << 16);
                NbtIo.write(tag, new DataOutputStream(nbtBytes));
                byte[] plain = nbtBytes.toByteArray();

                Deflater deflater = DEFLATER.get();
                deflater.reset();
                deflater.setInput(plain);
                deflater.finish();
                ByteArrayOutputStream out = new ByteArrayOutputStream(plain.length / 2);
                byte[] scratch = SCRATCH.get();
                while (!deflater.finished()) {
                        int produced = deflater.deflate(scratch);
                        out.write(scratch, 0, produced);
                }
                return out.toByteArray();
        }

        /**
         * Per-thread compression engines and scratch space. The converter pool is
         * a small fixed set of long-lived threads, so at most one engine pair per
         * thread exists for the whole process; reset() between uses keeps a
         * failed payload from corrupting the next.
         */
        private static final ThreadLocal<Inflater> INFLATER_ZLIB = ThreadLocal.withInitial(() -> new Inflater(false));
        private static final ThreadLocal<Inflater> INFLATER_GZIP = ThreadLocal.withInitial(() -> new Inflater(true));
        private static final ThreadLocal<Deflater> DEFLATER = ThreadLocal.withInitial(() -> new Deflater(Deflater.BEST_SPEED, false));
        private static final ThreadLocal<byte[]> SCRATCH = ThreadLocal.withInitial(() -> new byte[GZIP_BUFFER]);

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

                RawChunk readRawChunk(int slot) throws IOException {
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
                        return new RawChunk(slot, this.chunkTimestamp(slot), data, compressionType);
                }

                @Override
                public void close() throws IOException {
                        this.file.close();
                }
        }
}
