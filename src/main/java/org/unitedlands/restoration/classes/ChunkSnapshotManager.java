package org.unitedlands.restoration.classes;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.ChunkSnapshot;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.*;
import java.nio.file.Files;
import java.sql.*;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Manages saving and restoring chunk snapshots using a SQLite database.
 *
 * <p>This is a drop-in alternative to the flat-file ChunkSnapshotManager.
 * The public API is identical; only the storage backend changes.
 *
 * <p>All disk I/O runs on async threads. Block placement is always dispatched
 * back onto the main thread, as required by the Bukkit API.
 *
 * <p>Database location: &lt;plugin data folder&gt;/chunk_snapshots.db
 *
 * <p>Schema:
 * <pre>
 *   TABLE chunk_snapshots (
 *     world        TEXT     NOT NULL,
 *     chunk_x      INTEGER  NOT NULL,
 *     chunk_z      INTEGER  NOT NULL,
 *     data         BLOB     NOT NULL,   -- GZIP-compressed binary snapshot
 *     captured_at  INTEGER  NOT NULL,   -- Unix millis
 *     PRIMARY KEY (world, chunk_x, chunk_z)
 *   )
 * </pre>
 */
public class ChunkSnapshotManager {

    // -------------------------------------------------------------------------
    // Constants
    // -------------------------------------------------------------------------

    private static final int   FILE_MAGIC   = 0x43534E50; // "CSNP"
    private static final short FILE_VERSION = 1;

    // SQLite JDBC driver class — bundled via sqlite-jdbc dependency.
    private static final String JDBC_CLASS = "org.sqlite.JDBC";

    // -------------------------------------------------------------------------
    // State
    // -------------------------------------------------------------------------

    private final JavaPlugin plugin;
    private Connection connection;

    /**
     * In-memory cache: avoids redundant DB reads within a session.
     * Key: "world:chunkX:chunkZ"
     */
    private final ConcurrentHashMap<String, byte[]> cache = new ConcurrentHashMap<>();

    // -------------------------------------------------------------------------
    // Constructor & lifecycle
    // -------------------------------------------------------------------------

    /**
     * Creates the manager and opens (or creates) the SQLite database.
     * Call {@link #close()} when your plugin disables.
     *
     * @param plugin the owning plugin
     * @throws SQLException if the database cannot be opened or initialised
     */
    public ChunkSnapshotManager(JavaPlugin plugin) throws SQLException {
        this.plugin = plugin;
        initialiseDatabase();
    }

    /**
     * Opens the JDBC connection and creates the schema if it does not yet exist.
     */
    private void initialiseDatabase() throws SQLException {
        try {
            Class.forName(JDBC_CLASS);
        } catch (ClassNotFoundException e) {
            throw new SQLException("sqlite-jdbc driver not found. "
                    + "Add it to your plugin's dependencies.", e);
        }

        try {
            Files.createDirectories(plugin.getDataFolder().toPath());
        } catch (IOException e) {
            throw new SQLException("Could not create plugin data folder.", e);
        }

        String url = "jdbc:sqlite:"
                + plugin.getDataFolder().toPath().resolve("chunk_snapshots.db");

        connection = DriverManager.getConnection(url);

        // Performance pragmas — applied once at connection time.
        try (Statement st = connection.createStatement()) {
            // WAL mode: allows concurrent reads while a write is in progress.
            st.execute("PRAGMA journal_mode=WAL");
            // fsync only on checkpoint, not every commit — safe for game data.
            st.execute("PRAGMA synchronous=NORMAL");
            // 4 MB page cache in memory.
            st.execute("PRAGMA cache_size=-4096");
        }

        createSchema();
    }

    /** Creates the snapshots table if it does not exist. */
    private void createSchema() throws SQLException {
        String sql = """
                CREATE TABLE IF NOT EXISTS chunk_snapshots (
                    world       TEXT    NOT NULL,
                    chunk_x     INTEGER NOT NULL,
                    chunk_z     INTEGER NOT NULL,
                    data        BLOB    NOT NULL,
                    captured_at INTEGER NOT NULL,
                    PRIMARY KEY (world, chunk_x, chunk_z)
                )
                """;
        try (Statement st = connection.createStatement()) {
            st.execute(sql);
        }
    }

    /**
     * Closes the JDBC connection. Call this from {@code JavaPlugin#onDisable()}.
     */
    public void close() {
        cache.clear();
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING, "Error closing snapshot database.", e);
            }
        }
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Captures a snapshot of {@code chunk} and persists it to the database
     * asynchronously.
     *
     * <p>The snapshot itself is taken on the calling (main) thread. Only
     * serialisation and the DB write are deferred.
     *
     * @param chunk the chunk to snapshot
     * @return a future that completes {@code true} on success, {@code false} on error
     */
    public CompletableFuture<Boolean> saveSnapshot(Chunk chunk) {
        // Must capture on the main thread.
        ChunkSnapshot snapshot = chunk.getChunkSnapshot(true, true, false);
        int minHeight  = chunk.getWorld().getMinHeight();
        int maxHeight  = chunk.getWorld().getMaxHeight();
        String world   = chunk.getWorld().getName();
        int chunkX     = chunk.getX();
        int chunkZ     = chunk.getZ();

        CompletableFuture<Boolean> future = new CompletableFuture<>();

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                byte[] data = serialize(snapshot, minHeight, maxHeight);

                upsertSnapshot(world, chunkX, chunkZ, data);
                cache.put(buildCacheKey(world, chunkX, chunkZ), data);

                future.complete(true);
                plugin.getLogger().fine(
                        "Saved snapshot for chunk " + chunkX + "," + chunkZ
                        + " in world " + world);

            } catch (Exception e) {
                plugin.getLogger().log(Level.SEVERE,
                        "Failed to save snapshot for chunk " + chunkX + ","
                        + chunkZ + " in world " + world, e);
                future.complete(false);
            }
        });

        return future;
    }

    /**
     * Restores the saved snapshot for the chunk at the given coordinates.
     *
     * <p>DB I/O and deserialisation run off the main thread; block placement
     * is dispatched back to the main thread automatically.
     *
     * @param world  the world containing the chunk
     * @param chunkX chunk X coordinate
     * @param chunkZ chunk Z coordinate
     * @return a future that completes {@code true} on success, {@code false} if
     *         no snapshot exists or an error occurs
     */
    public CompletableFuture<Boolean> restoreSnapshot(World world, int chunkX, int chunkZ) {
        String worldName = world.getName();
        String cacheKey  = buildCacheKey(worldName, chunkX, chunkZ);
        CompletableFuture<Boolean> future = new CompletableFuture<>();

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                // Check cache first to avoid a DB round-trip.
                byte[] data = cache.get(cacheKey);

                if (data == null) {
                    data = loadSnapshot(worldName, chunkX, chunkZ);

                    if (data == null) {
                        plugin.getLogger().warning(
                                "No snapshot found for chunk " + chunkX + ","
                                + chunkZ + " in world " + worldName);
                        future.complete(false);
                        return;
                    }

                    cache.put(cacheKey, data); // Warm the cache.
                }

                // Deserialise off-thread; only block placement needs main thread.
                DeserializedChunk dc = deserialize(data);

                Bukkit.getScheduler().runTask(plugin, () -> {
                    try {
                        applyToWorld(world, dc);
                        future.complete(true);
                    } catch (Exception e) {
                        plugin.getLogger().log(Level.SEVERE,
                                "Failed to apply snapshot for chunk "
                                + chunkX + "," + chunkZ, e);
                        future.complete(false);
                    }
                });

            } catch (Exception e) {
                plugin.getLogger().log(Level.SEVERE,
                        "Failed to restore snapshot for chunk "
                        + chunkX + "," + chunkZ + " in world " + worldName, e);
                future.complete(false);
            }
        });

        return future;
    }

    /**
     * Returns {@code true} if a snapshot row exists in the database for the
     * given chunk. Checks the in-memory cache first; if not cached, queries
     * the DB synchronously on the calling thread.
     *
     * <p>Keep this off the main thread if the DB may be under load.
     */
    public boolean hasSnapshot(World world, int chunkX, int chunkZ) {
        String worldName = world.getName();
        if (cache.containsKey(buildCacheKey(worldName, chunkX, chunkZ))) return true;

        String sql = "SELECT 1 FROM chunk_snapshots WHERE world=? AND chunk_x=? AND chunk_z=? LIMIT 1";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, worldName);
            ps.setInt(2, chunkX);
            ps.setInt(3, chunkZ);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to check snapshot existence.", e);
            return false;
        }
    }

    /**
     * Deletes the snapshot for the given chunk from both the cache and the database.
     *
     * @return a future that completes {@code true} if a row was deleted,
     *         {@code false} if no snapshot existed or an error occurred
     */
    public CompletableFuture<Boolean> deleteSnapshot(World world, int chunkX, int chunkZ) {
        String worldName = world.getName();
        String cacheKey  = buildCacheKey(worldName, chunkX, chunkZ);
        CompletableFuture<Boolean> future = new CompletableFuture<>();

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            cache.remove(cacheKey);
            String sql = "DELETE FROM chunk_snapshots WHERE world=? AND chunk_x=? AND chunk_z=?";
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setString(1, worldName);
                ps.setInt(2, chunkX);
                ps.setInt(3, chunkZ);
                int rows = ps.executeUpdate();
                future.complete(rows > 0);
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE,
                        "Failed to delete snapshot for chunk "
                        + chunkX + "," + chunkZ + " in world " + worldName, e);
                future.complete(false);
            }
        });

        return future;
    }

    /**
     * Evicts all entries from the in-memory cache. On-disk data is not affected.
     */
    public void clearCache() {
        cache.clear();
    }

    // -------------------------------------------------------------------------
    // Database helpers
    // -------------------------------------------------------------------------

    /**
     * Inserts or replaces a snapshot row. Uses {@code INSERT OR REPLACE} so
     * re-saving an already-snapshotted chunk updates it in place.
     */
    private void upsertSnapshot(String world, int chunkX, int chunkZ, byte[] data)
            throws SQLException {
        String sql = """
                INSERT OR REPLACE INTO chunk_snapshots
                    (world, chunk_x, chunk_z, data, captured_at)
                VALUES (?, ?, ?, ?, ?)
                """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, world);
            ps.setInt(2, chunkX);
            ps.setInt(3, chunkZ);
            ps.setBytes(4, data);
            ps.setLong(5, System.currentTimeMillis());
            ps.executeUpdate();
        }
    }

    /**
     * Loads raw snapshot bytes from the database, or returns {@code null} if
     * no row exists for the given coordinates.
     */
    private byte[] loadSnapshot(String world, int chunkX, int chunkZ)
            throws SQLException {
        String sql = "SELECT data FROM chunk_snapshots WHERE world=? AND chunk_x=? AND chunk_z=?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, world);
            ps.setInt(2, chunkX);
            ps.setInt(3, chunkZ);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getBytes("data");
                return null;
            }
        }
    }

    // -------------------------------------------------------------------------
    // Serialization  (identical to the flat-file implementation)
    // -------------------------------------------------------------------------

    private byte[] serialize(ChunkSnapshot snap, int minHeight, int maxHeight)
            throws IOException {
        Map<String, Short> palette = new LinkedHashMap<>();
        short nextId = 0;
        int height   = maxHeight - minHeight;
        short[] ids  = new short[16 * 16 * height];
        int idx      = 0;

        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                for (int y = 0; y < height; y++) {
                    String key = snap.getBlockData(x, minHeight + y, z).getAsString();
                    if (!palette.containsKey(key)) palette.put(key, nextId++);
                    ids[idx++] = palette.get(key);
                }
            }
        }

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (DataOutputStream dos = new DataOutputStream(new GZIPOutputStream(baos))) {
            dos.writeInt(FILE_MAGIC);
            dos.writeShort(FILE_VERSION);
            dos.writeInt(snap.getX());
            dos.writeInt(snap.getZ());
            dos.writeInt(minHeight);
            dos.writeInt(maxHeight);

            dos.writeShort(palette.size());
            for (Map.Entry<String, Short> entry : palette.entrySet()) {
                dos.writeShort(entry.getValue());
                dos.writeUTF(entry.getKey());
            }

            for (short id : ids) dos.writeShort(id);
        }

        return baos.toByteArray();
    }

    // -------------------------------------------------------------------------
    // Deserialization  (identical to the flat-file implementation)
    // -------------------------------------------------------------------------

    private DeserializedChunk deserialize(byte[] data) throws IOException {
        try (DataInputStream dis = new DataInputStream(
                new GZIPInputStream(new ByteArrayInputStream(data)))) {

            int magic = dis.readInt();
            if (magic != FILE_MAGIC)
                throw new IOException("Invalid snapshot data (bad magic: 0x"
                        + Integer.toHexString(magic) + ")");

            short version = dis.readShort();
            if (version != FILE_VERSION)
                throw new IOException("Unsupported snapshot version: " + version);

            int chunkX    = dis.readInt();
            int chunkZ    = dis.readInt();
            int minHeight = dis.readInt();
            int maxHeight = dis.readInt();
            int height    = maxHeight - minHeight;

            int paletteSize  = dis.readShort() & 0xFFFF;
            String[] palette = new String[paletteSize];
            for (int i = 0; i < paletteSize; i++) {
                short id       = dis.readShort();
                palette[id]    = dis.readUTF();
            }

            BlockData[][][] blocks = new BlockData[16][16][height];
            for (int x = 0; x < 16; x++)
                for (int z = 0; z < 16; z++)
                    for (int y = 0; y < height; y++)
                        blocks[x][z][y] = Bukkit.createBlockData(palette[dis.readShort()]);

            return new DeserializedChunk(chunkX, chunkZ, minHeight, maxHeight, blocks);
        }
    }

    // -------------------------------------------------------------------------
    // Block placement  (main thread only)
    // -------------------------------------------------------------------------

    private void applyToWorld(World world, DeserializedChunk dc) {
        Chunk chunk = world.getChunkAt(dc.chunkX, dc.chunkZ);
        int height  = dc.maxHeight - dc.minHeight;

        for (int x = 0; x < 16; x++)
            for (int z = 0; z < 16; z++)
                for (int y = 0; y < height; y++)
                    chunk.getBlock(x, dc.minHeight + y, z)
                         .setBlockData(dc.blocks[x][z][y], false);

        world.refreshChunk(dc.chunkX, dc.chunkZ);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static String buildCacheKey(String world, int chunkX, int chunkZ) {
        return world + ":" + chunkX + ":" + chunkZ;
    }

    // -------------------------------------------------------------------------
    // Inner types
    // -------------------------------------------------------------------------

    private static final class DeserializedChunk {
        final int chunkX, chunkZ, minHeight, maxHeight;
        final BlockData[][][] blocks;

        DeserializedChunk(int chunkX, int chunkZ, int minHeight, int maxHeight,
                          BlockData[][][] blocks) {
            this.chunkX    = chunkX;
            this.chunkZ    = chunkZ;
            this.minHeight = minHeight;
            this.maxHeight = maxHeight;
            this.blocks    = blocks;
        }
    }
}