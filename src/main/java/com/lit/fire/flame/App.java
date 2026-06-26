package com.lit.fire.flame;

public class App {

    public static void main(String[] args) {
        if (args.length < 1) {
            System.err.println("Usage: java -jar AuraDataFiller.jar <path-to-csv-file>");
            System.exit(1);
        }
        new CsvDataFiller().process(args[0]);
    }
}
