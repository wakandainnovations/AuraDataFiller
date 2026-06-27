package com.lit.fire.flame;

public class App {

    public static void main(String[] args) throws Exception {
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
