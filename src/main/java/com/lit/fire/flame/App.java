package com.lit.fire.flame;

import com.lit.fire.flame.crawler.SacnilkCrawlerService;

public class App {

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            printUsage();
            System.exit(1);
        }

        if ("--crawl".equals(args[0])) {
            // Run one crawl cycle synchronously in the main thread; no daemon needed.
            new SacnilkCrawlerService().runOnce();
        } else if ("--watch".equals(args[0])) {
            startDaemonCrawler();
            if (args.length < 2) {
                System.err.println("--watch requires a folder path.");
                printUsage();
                System.exit(1);
            }
            new FolderWatcher(args[1]).watch();
        } else if ("--batch".equals(args[0])) {
            startDaemonCrawler();
            if (args.length < 2) {
                System.err.println("--batch requires a folder path.");
                printUsage();
                System.exit(1);
            }
            new FolderWatcher(args[1]).runBatch();
        } else {
            startDaemonCrawler();
            new CsvDataFiller().process(args[0]);
        }
    }

    private static void startDaemonCrawler() {
        Thread t = new Thread(new SacnilkCrawlerService(), "sacnilk-crawler");
        t.setDaemon(true);
        t.start();
    }

    private static void printUsage() {
        System.err.println("Usage:");
        System.err.println("  java -jar AuraDataFiller.jar <path-to-csv-file>");
        System.err.println("  java -jar AuraDataFiller.jar --batch <folder-path>   # process all unprocessed CSVs and exit");
        System.err.println("  java -jar AuraDataFiller.jar --watch <folder-path>   # process existing + watch for new files");
        System.err.println("  java -jar AuraDataFiller.jar --crawl                 # run one sacnilk.com crawl cycle and exit");
    }
}
