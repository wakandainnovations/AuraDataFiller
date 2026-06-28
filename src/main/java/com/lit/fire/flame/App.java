package com.lit.fire.flame;

import com.lit.fire.flame.crawler.SacnilkCrawlerService;

public class App {

    public static void main(String[] args) throws Exception {
        // Start the Sacnilk box-office crawler as a background daemon thread at every startup.
        // Being a daemon, it is automatically terminated when the JVM exits.
        Thread crawlerThread = new Thread(new SacnilkCrawlerService(), "sacnilk-crawler");
        crawlerThread.setDaemon(true);
        crawlerThread.start();

        if (args.length < 1) {
            printUsage();
            System.exit(1);
        }

        if ("--watch".equals(args[0])) {
            if (args.length < 2) {
                System.err.println("--watch requires a folder path.");
                printUsage();
                System.exit(1);
            }
            new FolderWatcher(args[1]).watch();
        } else if ("--batch".equals(args[0])) {
            if (args.length < 2) {
                System.err.println("--batch requires a folder path.");
                printUsage();
                System.exit(1);
            }
            new FolderWatcher(args[1]).runBatch();
        } else {
            new CsvDataFiller().process(args[0]);
        }
    }

    private static void printUsage() {
        System.err.println("Usage:");
        System.err.println("  java -jar AuraDataFiller.jar <path-to-csv-file>");
        System.err.println("  java -jar AuraDataFiller.jar --batch <folder-path>   # process all unprocessed CSVs and exit");
        System.err.println("  java -jar AuraDataFiller.jar --watch <folder-path>   # process existing + watch for new files");
    }
}
