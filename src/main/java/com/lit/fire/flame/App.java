package com.lit.fire.flame;

import com.lit.fire.flame.actor.ActorDataCollectionService;
import com.lit.fire.flame.crawler.BoxOfficeCrawlerOrchestrator;
import com.lit.fire.flame.crawler.SacnilkCrawlerService;

public class App {

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            printUsage();
            System.exit(1);
        }

        if ("--crawl".equals(args[0])) {
            // Multi-source: sacnilk → boxofficemojo → koimoi (one cycle, then exit).
            new BoxOfficeCrawlerOrchestrator().runOnce();
        } else if ("--crawl-sacnilk".equals(args[0])) {
            // sacnilk only (single-source, legacy behaviour).
            new SacnilkCrawlerService().runOnce();
        } else if ("--actor-scan".equals(args[0])) {
            // Scan actor CSVs and populate actors_data_collection, then repeat every 24 h.
            new ActorDataCollectionService().run();
        } else if ("--actor-filmography".equals(args[0])) {
            if (args.length < 2) {
                System.err.println("--actor-filmography requires an actor name.");
                printUsage();
                System.exit(1);
            }
            String upToYear = args.length >= 3 && !args[2].isBlank() ? args[2] : null;
            new ActorDataCollectionService().printFilmography(args[1], upToYear);
        } else if ("--watch".equals(args[0])) {
            startDaemonCrawler();
            startDaemonActorCollector();
            if (args.length < 2) {
                System.err.println("--watch requires a folder path.");
                printUsage();
                System.exit(1);
            }
            new FolderWatcher(args[1]).watch();
        } else if ("--batch".equals(args[0])) {
            startDaemonCrawler();
            startDaemonActorCollector();
            if (args.length < 2) {
                System.err.println("--batch requires a folder path.");
                printUsage();
                System.exit(1);
            }
            new FolderWatcher(args[1]).runBatch();
        } else {
            startDaemonCrawler();
            startDaemonActorCollector();
            new CsvDataFiller().process(args[0]);
        }
    }

    private static void startDaemonCrawler() {
        Thread t = new Thread(new BoxOfficeCrawlerOrchestrator(), "box-office-crawler");
        t.setDaemon(true);
        t.start();
    }

    private static void startDaemonActorCollector() {
        Thread t = new Thread(new ActorDataCollectionService(), "actor-data-collector");
        t.setDaemon(true);
        t.start();
    }

    private static void printUsage() {
        System.err.println("Usage:");
        System.err.println("  java -jar AuraDataFiller.jar <path-to-csv-file>");
        System.err.println("  java -jar AuraDataFiller.jar --batch <folder-path>     # process all unprocessed CSVs and exit");
        System.err.println("  java -jar AuraDataFiller.jar --watch <folder-path>     # process existing + watch for new files");
        System.err.println("  java -jar AuraDataFiller.jar --crawl                   # run one multi-source cycle (sacnilk + BOM + koimoi) and exit");
        System.err.println("  java -jar AuraDataFiller.jar --crawl-sacnilk           # run one sacnilk-only crawl cycle and exit");
        System.err.println("  java -jar AuraDataFiller.jar --actor-scan                           # scan actor CSVs, update actors_data_collection, repeat every 24 h");
        System.err.println("  java -jar AuraDataFiller.jar --actor-filmography \"Actor Name\" [YYYY] # print actor's filmography (optionally up to a given year)");
    }
}
