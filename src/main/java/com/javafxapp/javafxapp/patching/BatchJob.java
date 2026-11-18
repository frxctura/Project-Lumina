package com.javafxapp.javafxapp.patching;

import com.javafxapp.javafxapp.backup.History;
import com.javafxapp.javafxapp.backup.HistoryEntry;
import com.javafxapp.javafxapp.common.Errors;
import com.javafxapp.javafxapp.common.FileOps;
import com.javafxapp.javafxapp.common.Job;
import com.javafxapp.javafxapp.common.Log;
import com.javafxapp.javafxapp.common.StatusSink;
import com.javafxapp.javafxapp.emulator.Emulator;
import com.javafxapp.javafxapp.safety.ProcessGuard;

import javafx.application.Platform;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutionException;

public final class BatchJob extends Job<BatchJob.Summary> {

    public enum State { QUEUED, RUNNING, DONE, FAILED, SKIPPED, CANCELLED }

    private static final long WAIT_POLL_MS = 2000;

    public record Item(Path dir, String name, String appId) {}

    public record Settings(Emulator emulator, boolean backupDlls, Optional<Path> backupRoot, Optional<Path> moveRoot,
                           boolean unpackDrm) {}

    public record Summary(int done, int failed, int skipped, boolean stopped) {}

    public interface Listener {
        void update(int index, State state, String detail, CrackJob job);
    }

    private final List<Item> items;
    private final Settings settings;
    private final Listener listener;
    private volatile boolean stopRequested;
    private volatile CrackJob current;

    public BatchJob(List<Item> items, Settings settings, Listener listener, StatusSink log) {
        super(log);
        this.items = List.copyOf(items);
        this.settings = settings;
        this.listener = listener;
    }

    @Override
    public List<String> steps() {
        return List.of(CrackJob.PATCH);
    }

    public void stop() {
        stopRequested = true;
        CrackJob c = current;
        if (c != null) c.cancel();
    }

    @Override
    protected Summary call() {
        int[] counts = new int[3]; // done, failed, skipped
        List<Integer> waiting = new ArrayList<>();
        for (int i = 0; i < items.size() && !stopRequested; i++) {
            Item item = items.get(i);
            List<ProcessGuard.RunningProcess> running = ProcessGuard.runningFrom(item.dir());
            if (!running.isEmpty()) {
                waiting.add(i);
                log.info(item.name() + ": " + names(running) + " is running, patching it at the end.");
                notify(i, State.QUEUED, names(running) + " is running, will patch it at the end", null);
                continue;
            }
            count(counts, patch(i, item));
        }

        int next = 0;
        while (next < waiting.size() && !stopRequested) {
            int i = waiting.get(next);
            if (!waitUntilClosed(i, items.get(i))) break;
            count(counts, patch(i, items.get(i)));
            next++;
        }
        for (int i : waiting.subList(next, waiting.size())) notify(i, State.CANCELLED, "Stopped while waiting", null);

        updateProgress(1, 1);
        updateMessage(stopRequested ? "Batch stopped" : "Batch finished");
        return new Summary(counts[0], counts[1], counts[2], stopRequested);
    }

    private static void count(int[] counts, State state) {
        switch (state) {
            case DONE -> counts[0]++;
            case FAILED -> counts[1]++;
            case SKIPPED -> counts[2]++;
            default -> { }
        }
    }

    private boolean waitUntilClosed(int index, Item item) {
        String shown = "";
        while (!stopRequested) {
            List<ProcessGuard.RunningProcess> running = ProcessGuard.runningFrom(item.dir());
            if (running.isEmpty()) return true;
            String text = "Waiting for " + names(running) + " to close";
            if (!text.equals(shown)) {
                shown = text;
                updateMessage(item.name() + ": " + text + "...");
                notify(index, State.QUEUED, text + "...", null);
            }
            try {
                Thread.sleep(WAIT_POLL_MS);
            } catch (InterruptedException e) {
                return false;
            }
        }
        return false;
    }

    private State patch(int index, Item item) {
        updateTitle(CrackJob.PATCH);
        updateMessage("Patching " + item.name() + "  (" + (index + 1) + " of " + items.size() + ")");
        updateProgress(index, items.size());

        String[] lastError = {""};
        StatusSink sink = (message, error) -> {
            if (error) lastError[0] = message;
            log.accept(item.name() + ": " + message, error);
        };
        String appId = item.appId().isBlank() ? findAppId(item.dir()) : item.appId();
        CrackJob crack = new CrackJob(new CrackJob.Request(item.dir(), settings.emulator(), settings.backupDlls(), appId,
                settings.backupRoot(), settings.moveRoot(), settings.unpackDrm()), sink);
        current = crack;
        if (stopRequested) crack.cancel();
        notify(index, State.RUNNING, "Starting...", crack);
        crack.run();
        current = null;

        CrackJob.Result r;
        try {
            r = crack.get();
        } catch (ExecutionException | RuntimeException | InterruptedException e) {
            Thread.interrupted(); // a cancelled CrackJob leaves the interrupt flag set
            if (stopRequested) {
                notify(index, State.CANCELLED, "Cancelled, changes undone", null);
                return State.CANCELLED;
            }
            Throwable cause = e instanceof ExecutionException ? e.getCause() : e;
            Log.error("Batch item failed: " + item.dir(), cause);
            notify(index, State.FAILED, Errors.describe(cause), null);
            return State.FAILED;
        }

        if (r.rolledBack()) {
            notify(index, State.FAILED, r.failed() + " file(s) failed, changes undone"
                    + (lastError[0].isEmpty() ? "" : ": " + lastError[0]), null);
            return State.FAILED;
        }
        boolean moved = !r.location().equals(item.dir());
        if (r.patched() == 0 && r.unpacked() == 0 && !moved) {
            notify(index, State.SKIPPED, r.upToDate() > 0 ? "Already up to date" : "No steam_api DLLs found", null);
            return State.SKIPPED;
        }
        History.add(HistoryEntry.patch(item.name(), r.location(), settings.emulator().toString(), r.patched(), r.failed(),
                r.unpacked(), r.dllBackups(), r.folderBackup()));
        String text = r.patched() > 0 ? "Patched " + r.patched() + " file(s)" : "Already up to date";
        if (r.unpacked() > 0) text += "  ·  unpacked " + r.unpacked() + " exe";
        if (r.unpackFailed() > 0) text += "  ·  " + r.unpackFailed() + " exe could not be unpacked";
        notify(index, State.DONE, text + (moved ? "  ·  moved" : ""), null);
        return State.DONE;
    }

    private static String names(List<ProcessGuard.RunningProcess> running) {
        return String.join(", ", running.stream().map(ProcessGuard.RunningProcess::name).distinct().toList());
    }

    private static String findAppId(Path dir) {
        try {
            return FileOps.findFiles(dir, SteamApi.APPID_FILE::equals).stream()
                    .map(SteamApi::readAppId).flatMap(Optional::stream).findFirst().orElse("");
        } catch (IOException e) {
            return "";
        }
    }

    private void notify(int index, State state, String detail, CrackJob job) {
        Platform.runLater(() -> listener.update(index, state, detail, job));
    }
}
