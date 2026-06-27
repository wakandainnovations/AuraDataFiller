package com.lit.fire.flame;

import java.io.IOException;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class FolderWatcher {

    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final Path watchDir;
    private final CsvDataFiller filler;
    private final Set<String> processed;
    private final Path processedLog;

    public FolderWatcher(String dirPath) {
        this.watchDir = Paths.get(dirPath).toAbsolutePath().normalize();
        this.filler = new CsvDataFiller();
        this.processed = ConcurrentHashMap.newKeySet();
        this.processedLog = watchDir.resolve(".aura_processed");
        loadProcessedLog();
    }

    private void loadProcessedLog() {
        if (Files.exists(processedLog)) {
            try {
                Files.readAllLines(processedLog).stream()
                    .map(String::trim)
                    .filter(l -> !l.isEmpty())
                    .forEach(processed::add);
                System.out.println("Loaded " + processed.size() + " previously processed file(s) from log.");
            } catch (IOException e) {
                System.err.println("Warning: could not read processed log: " + e.getMessage());
            }
        }
    }

    private void markProcessed(String fileName) {
        processed.add(fileName);
        try {
            Files.writeString(processedLog, fileName + "\n",
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            System.err.println("Warning: could not update processed log: " + e.getMessage());
        }
    }

    public void runBatch() {
        System.out.println("=== AuraDataFiller Batch Run ===");
        System.out.println("Directory : " + watchDir);
        System.out.println("Log file  : " + processedLog);
        System.out.println();
        processExisting();
        System.out.println("Batch run complete.");
    }

    public void watch() throws IOException, InterruptedException {
        System.out.println("=== AuraDataFiller Folder Watcher ===");
        System.out.println("Directory : " + watchDir);
        System.out.println("Log file  : " + processedLog);
        System.out.println();

        processExisting();

        try (WatchService watchService = FileSystems.getDefault().newWatchService()) {
            watchDir.register(watchService,
                StandardWatchEventKinds.ENTRY_CREATE,
                StandardWatchEventKinds.ENTRY_MODIFY);

            System.out.println("Watching for new CSV files — press Ctrl+C to stop.");
            System.out.println();

            while (true) {
                WatchKey key = watchService.take();

                // Small settle delay so the file is fully written before we read it
                Thread.sleep(500);

                for (WatchEvent<?> event : key.pollEvents()) {
                    if (event.kind() == StandardWatchEventKinds.OVERFLOW) continue;

                    @SuppressWarnings("unchecked")
                    WatchEvent<Path> pathEvent = (WatchEvent<Path>) event;
                    Path fileName = pathEvent.context();

                    if (fileName.toString().toLowerCase().endsWith(".csv")) {
                        Path fullPath = watchDir.resolve(fileName);
                        if (Files.exists(fullPath)) {
                            processFile(fullPath);
                        }
                    }
                }

                if (!key.reset()) {
                    System.err.println("Watch key invalidated — directory may have been deleted.");
                    break;
                }
            }
        }
    }

    private void processExisting() {
        System.out.println("Scanning for unprocessed CSV files...");
        try (var stream = Files.list(watchDir)) {
            long count = stream
                .filter(p -> p.toString().toLowerCase().endsWith(".csv"))
                .filter(p -> !processed.contains(p.getFileName().toString()))
                .sorted()
                .peek(this::processFile)
                .count();
            if (count == 0) System.out.println("None found — all CSVs already processed.");
        } catch (IOException e) {
            System.err.println("Warning: could not scan directory: " + e.getMessage());
        }
        System.out.println();
    }

    private void processFile(Path filePath) {
        String fileName = filePath.getFileName().toString();

        if (processed.contains(fileName)) {
            return;
        }

        System.out.println("─".repeat(70));
        System.out.printf("[%s] Processing: %s%n", now(), fileName);
        System.out.println();

        try {
            filler.process(filePath.toString());
            markProcessed(fileName);
        } catch (Exception e) {
            System.err.printf("[%s] ERROR processing %s: %s%n", now(), fileName, e.getMessage());
            e.printStackTrace(System.err);
        }
        System.out.println();
    }

    private static String now() {
        return LocalDateTime.now().format(TIMESTAMP);
    }
}
