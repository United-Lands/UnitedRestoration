package org.unitedlands.restoration.commands.handlers;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;

import org.bukkit.Bukkit;
import org.bukkit.ChunkSnapshot;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.unitedlands.classes.BaseCommandHandler;
import org.unitedlands.interfaces.IMessageProvider;
import org.unitedlands.restoration.UnitedRestoration;
import org.unitedlands.restoration.classes.ChunkSnapshotManager;

public class SaveChunks extends BaseCommandHandler<UnitedRestoration> {

    // -------------------------------------------------------------------------
    // Defaults
    // -------------------------------------------------------------------------

    /** Chunks processed per scheduler tick by default. */
    private static final int DEFAULT_BATCH_SIZE = 4;

    /**
     * How many ticks to wait between batches.
     * 1 tick = 50 ms at 20 TPS. A value of 2 gives the server breathing room
     * between bursts.
     */
    private static final int DEFAULT_INTERVAL_TICKS = 2;

    /**
     * Maximum chunks allowed in a single batch. Prevents operators from
     * accidentally setting a value that freezes the server.
     */
    private static final int MAX_BATCH_SIZE = 32;

    // -------------------------------------------------------------------------
    // State
    // -------------------------------------------------------------------------

    private ChunkSnapshotManager snapshotManager;

    /** Non-null while a bulk save is in progress. */
    private volatile BulkSaveOperation activeOperation = null;

    // -------------------------------------------------------------------------
    // Constructor
    // -------------------------------------------------------------------------

    public SaveChunks(UnitedRestoration plugin, IMessageProvider messageProvider) {
        super(plugin, messageProvider);

        snapshotManager = plugin.getChunkSnapshotManager();
    }

    @Override
    public List<String> handleTab(CommandSender sender, String[] args) {
        return null;
    }

    @Override
    public void handleCommand(CommandSender sender, String[] args) {

        // ---- Sub-commands ----
        if (args.length == 1 && args[0].equalsIgnoreCase("status")) {
            handleStatus(sender);
            return;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("cancel")) {
            handleCancel(sender);
            return;
        }

        // ---- Argument validation ----
        if (args.length < 5 || args.length > 7) {
            sender.sendMessage("§7Usage: /urst savechunks <world> <x1> <z1> <x2> <z2> [batchSize] [intervalTicks]");
            sender.sendMessage("§7       /urst savechunks status | cancel");
            return;
        }

        // World
        World world = Bukkit.getWorld(args[0]);
        if (world == null) {
            sender.sendMessage("§cUnknown world: " + args[0]);
            return;
        }

        // Coordinates
        int x1, z1, x2, z2;
        try {
            x1 = Integer.parseInt(args[1]);
            z1 = Integer.parseInt(args[2]);
            x2 = Integer.parseInt(args[3]);
            z2 = Integer.parseInt(args[4]);
        } catch (NumberFormatException e) {
            sender.sendMessage("§cCoordinates must be integers.");
            return;
        }

        // Normalise so min <= max
        int minX = Math.min(x1, x2), maxX = Math.max(x1, x2);
        int minZ = Math.min(z1, z2), maxZ = Math.max(z1, z2);

        // Optional tuning parameters
        int batchSize     = DEFAULT_BATCH_SIZE;
        int intervalTicks = DEFAULT_INTERVAL_TICKS;

        if (args.length >= 6) {
            try {
                batchSize = Math.min(Math.max(1, Integer.parseInt(args[5])), MAX_BATCH_SIZE);
            } catch (NumberFormatException e) {
                sender.sendMessage("§cbatchSize must be an integer.");
                return;
            }
        }
        if (args.length == 7) {
            try {
                intervalTicks = Math.max(1, Integer.parseInt(args[6]));
            } catch (NumberFormatException e) {
                sender.sendMessage("§cintervalTicks must be an integer.");
                return;
            }
        }

        // ---- Concurrency guard ----
        if (activeOperation != null && !activeOperation.isFinished()) {
            sender.sendMessage("§eA bulk save is already running. Use /savechunks status or cancel.");
            return;
        }

        // ---- Build and start ----
        long totalChunks = (long)(maxX - minX + 1) * (maxZ - minZ + 1);

        sender.sendMessage("§aStarting bulk save: " + totalChunks + " chunks in world '"
                + world.getName() + "' (batch=" + batchSize
                + ", interval=" + intervalTicks + " ticks).");
        sender.sendMessage("§aUse /savechunks status to check progress, or cancel to abort.");

        activeOperation = new BulkSaveOperation(
                plugin, snapshotManager, sender,
                world, minX, minZ, maxX, maxZ,
                batchSize, intervalTicks);

        activeOperation.start();
        return;

    }

    // -------------------------------------------------------------------------
    // Sub-command handlers
    // -------------------------------------------------------------------------

    private void handleStatus(CommandSender sender) {
        BulkSaveOperation op = activeOperation;
        if (op == null || op.isFinished()) {
            sender.sendMessage("§eNo bulk save is currently running.");
            return;
        }
        sender.sendMessage(op.buildStatusMessage());
    }

    private void handleCancel(CommandSender sender) {
        BulkSaveOperation op = activeOperation;
        if (op == null || op.isFinished()) {
            sender.sendMessage("§eNo bulk save is currently running.");
            return;
        }
        op.cancel();
        sender.sendMessage("§eBulk save cancelled.");
    }

    // =========================================================================
    // BulkSaveOperation — inner class
    // =========================================================================

    /**
     * Encapsulates the full lifecycle of a single bulk-save run.
     *
     * <p>A queue of every (chunkX, chunkZ) coordinate in the region is built
     * upfront. A repeating Bukkit task then drains the queue in fixed-size
     * batches. Each batch:
     * <ol>
     *   <li>Pulls up to {@code batchSize} coordinates from the queue.</li>
     *   <li>For each coordinate, uses Paper's async {@code getChunkAtAsync} so
     *       the chunk is loaded (or generated) without blocking the main thread.</li>
     *   <li>Once the chunk is available, takes a {@link ChunkSnapshot} and hands
     *       it to {@link ChunkSnapshotManager#saveSnapshot} for serialisation and
     *       DB write — both of which also run off the main thread.</li>
     * </ol>
     *
     * <p>Because {@code getChunkAtAsync} may briefly touch the main thread to
     * finalise chunk loading, we track how many futures are still in-flight
     * ({@code inFlight}) and only pull the next batch once the previous one has
     * fully settled. This prevents runaway memory growth on large regions.
     */
    private static class BulkSaveOperation {

        // -- Dependencies --
        private final JavaPlugin           plugin;
        private final ChunkSnapshotManager snapshotManager;
        private final CommandSender        initiator;
        private final World                world;

        // -- Configuration --
        private final int batchSize;
        private final int intervalTicks;

        // -- Work queue --
        private final Queue<long[]> queue; // each entry: {chunkX, chunkZ}
        private final long          totalChunks;

        // -- Progress tracking --
        private final AtomicInteger saved    = new AtomicInteger(0);
        private final AtomicInteger failed   = new AtomicInteger(0);
        private final AtomicInteger inFlight = new AtomicInteger(0);
        private final long          startedAt;

        // -- Scheduler handle --
        private BukkitTask schedulerTask;

        // -- State flags --
        private volatile boolean cancelled = false;
        private volatile boolean finished  = false;

        // -----------------------------------------------------------------
        // Constructor
        // -----------------------------------------------------------------

        BulkSaveOperation(JavaPlugin plugin, ChunkSnapshotManager snapshotManager,
                          CommandSender initiator, World world,
                          int minX, int minZ, int maxX, int maxZ,
                          int batchSize, int intervalTicks) {

            this.plugin          = plugin;
            this.snapshotManager = snapshotManager;
            this.initiator       = initiator;
            this.world           = world;
            this.batchSize       = batchSize;
            this.intervalTicks   = intervalTicks;
            this.startedAt       = System.currentTimeMillis();

            // Pre-populate the work queue with every coordinate in the region.
            int width  = maxX - minX + 1;
            int depth  = maxZ - minZ + 1;
            this.totalChunks = (long) width * depth;
            this.queue = new ArrayDeque<>((int) Math.min(totalChunks, Integer.MAX_VALUE));

            for (int x = minX; x <= maxX; x++)
                for (int z = minZ; z <= maxZ; z++)
                    queue.add(new long[]{x, z});
        }

        // -----------------------------------------------------------------
        // Lifecycle
        // -----------------------------------------------------------------

        /** Starts the repeating scheduler task that drains the queue. */
        void start() {
            schedulerTask = Bukkit.getScheduler().runTaskTimer(
                    plugin, this::tick, 0L, intervalTicks);
        }

        /** Requests cancellation; the current in-flight batch finishes cleanly. */
        void cancel() {
            cancelled = true;
        }

        boolean isFinished() {
            return finished;
        }

        // -----------------------------------------------------------------
        // Tick — called by the repeating Bukkit task (main thread)
        // -----------------------------------------------------------------

        private void tick() {
            // Wait for the previous batch to fully settle before launching more
            // work. This bounds memory usage and prevents the async thread pool
            // from being flooded.
            if (inFlight.get() > 0) return;

            if (cancelled) {
                finish(false);
                return;
            }

            if (queue.isEmpty()) {
                finish(true);
                return;
            }

            // Pull the next batch from the queue.
            List<long[]> batch = new ArrayList<>(batchSize);
            for (int i = 0; i < batchSize && !queue.isEmpty(); i++)
                batch.add(queue.poll());

            inFlight.addAndGet(batch.size());

            for (long[] coord : batch) {
                int cx = (int) coord[0];
                int cz = (int) coord[1];
                processChunk(cx, cz);
            }
        }

        // -----------------------------------------------------------------
        // Per-chunk pipeline
        // -----------------------------------------------------------------

        /**
         * Loads the chunk asynchronously then saves its snapshot.
         * The {@code inFlight} counter is decremented when the full pipeline
         * for this chunk completes (success or failure).
         */
        private void processChunk(int cx, int cz) {
            // getChunkAtAsync is a Paper API — loads/generates the chunk
            // without blocking the main thread.
            world.getChunkAtAsync(cx, cz)
                .thenCompose(chunk -> {
                    // saveSnapshot captures the ChunkSnapshot on whichever thread
                    // the future completes on (main or async) — Paper guarantees
                    // the chunk is accessible here.
                    CompletableFuture<Boolean> saveFuture = snapshotManager.saveSnapshot(chunk);

                    // Unload the chunk if it wasn't loaded before we touched it,
                    // to avoid inflating the server's loaded-chunk count.
                    if (!chunk.isLoaded()) chunk.unload(false);

                    return saveFuture;
                })
                .whenComplete((success, ex) -> {
                    if (ex != null) {
                        plugin.getLogger().log(Level.WARNING,
                                "Error processing chunk " + cx + "," + cz, ex);
                        failed.incrementAndGet();
                    } else if (Boolean.TRUE.equals(success)) {
                        saved.incrementAndGet();
                    } else {
                        failed.incrementAndGet();
                    }
                    inFlight.decrementAndGet();
                });
        }

        // -----------------------------------------------------------------
        // Completion
        // -----------------------------------------------------------------

        /**
         * Cancels the scheduler task and sends a summary to the initiator.
         *
         * @param completed {@code true} if the queue was fully drained,
         *                  {@code false} if the run was cancelled early
         */
        private void finish(boolean completed) {
            if (finished) return;
            finished = true;

            if (schedulerTask != null) schedulerTask.cancel();

            long elapsedMs   = System.currentTimeMillis() - startedAt;
            long elapsedSecs = elapsedMs / 1000;
            int  savedCount  = saved.get();
            int  failedCount = failed.get();

            String header = completed
                    ? "§aBulk save complete!"
                    : "§eBulk save cancelled.";

            initiator.sendMessage(header);
            initiator.sendMessage("§f  Saved:   §a" + savedCount);

            if (failedCount > 0)
                initiator.sendMessage("§f   Failed:  §c" + failedCount);

            initiator.sendMessage("§f  Total:   " + totalChunks);
            initiator.sendMessage("§f  Elapsed: " + elapsedSecs + "s");

            if (elapsedSecs > 0) {
                long rate = savedCount / elapsedSecs;
                initiator.sendMessage("§f  Rate:    " + rate + " chunks/sec");
            }
        }

        // -----------------------------------------------------------------
        // Status
        // -----------------------------------------------------------------

        String buildStatusMessage() {
            int  done      = saved.get() + failed.get();
            int  flight    = inFlight.get();
            long remaining = queue.size() + flight;
            double pct     = totalChunks > 0 ? (done * 100.0 / totalChunks) : 100.0;

            long elapsedMs = System.currentTimeMillis() - startedAt;
            String eta     = buildEta(done, remaining, elapsedMs);

            return "§6Bulk save status — " + world.getName() + "\n"
                    + "§f  Progress:  "
                    + "§b" + String.format("%.1f%%", pct)
                    + "§f (" + done + " / " + totalChunks + ")\n"
                    + "§f  In-flight: §e" + flight + "\n"
                    + "§f  Saved:     §a" + saved.get() + "\n"
                    + (failed.get() > 0
                            ? "§f  Failed:    §c" + failed.get() + "\n"
                            : "")
                    + "§f  ETA:       §b" + eta;
        }

        private String buildEta(long done, long remaining, long elapsedMs) {
            if (done == 0 || remaining == 0) return "calculating...";
            long msPerChunk  = elapsedMs / done;
            long etaMs       = msPerChunk * remaining;
            long etaSecs     = etaMs / 1000;
            if (etaSecs < 60)  return etaSecs + "s";
            if (etaSecs < 3600) return (etaSecs / 60) + "m " + (etaSecs % 60) + "s";
            return (etaSecs / 3600) + "h " + ((etaSecs % 3600) / 60) + "m";
        }
    }

}
