package com.lit.fire.flame;

import com.lit.fire.flame.actor.ActorDataCollectionService;
import com.lit.fire.flame.actor.SacnilkActorCrawlerService;
import com.lit.fire.flame.actor.SupplementalActorCrawlerService;
import com.lit.fire.flame.crawler.BoxOfficeCrawlerOrchestrator;
import com.lit.fire.flame.crawler.SacnilkCrawlerService;
import com.lit.fire.flame.credits.CreditsCrawlerService;
import com.lit.fire.flame.enrichment.EconomicEnrichmentService;
import com.lit.fire.flame.legacycsv.LegacyCsvBackfillService;
import com.lit.fire.flame.marketing.MarketingTacticsService;
import com.lit.fire.flame.runtimebudget.RuntimeBudgetCrawlerService;
import com.lit.fire.flame.youtube.YoutubeEnrichmentService;

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
            // Also starts the sacnilk actor crawler in parallel (see ActorDataCollectionService).
            new ActorDataCollectionService().run();
        } else if ("--actor-crawl".equals(args[0])) {
            // Run one sacnilk actor filmography crawl cycle and exit.
            new SacnilkActorCrawlerService().runOnce();
        } else if ("--actor-crawl-supplemental".equals(args[0])) {
            // Run one kulfiy/fandango supplemental actor crawl cycle and exit.
            new SupplementalActorCrawlerService().runOnce();
        } else if ("--youtube-scan".equals(args[0])) {
            // Search YouTube for trailer/teaser/first-song videos of movies released after
            // 2010 and populate promo-metrics columns, then repeat every 24 h.
            new YoutubeEnrichmentService().run();
        } else if ("--youtube-scan-once".equals(args[0])) {
            // Run one YouTube enrichment cycle and exit.
            new YoutubeEnrichmentService().runOnce();
        } else if ("--youtube-scan-movie".equals(args[0])) {
            // Run enrichment for exactly one (movie_name, year) and exit. For spot-checking
            // a real API key/match quality without scanning the whole candidate backlog.
            if (args.length < 3) {
                System.err.println("--youtube-scan-movie requires a movie name and a 4-digit year.");
                printUsage();
                System.exit(1);
            }
            new YoutubeEnrichmentService().runOnceForMovie(args[1], args[2]);
        } else if ("--econ-scan-once".equals(args[0])) {
            // Backfill gdp_usd_billions/inflation_rate_pct (World Bank API) for existing rows:
            // Indian-language movies released after 2000. One-shot, then exit.
            new EconomicEnrichmentService().runOnce();
        } else if ("--credits-scan".equals(args[0])) {
            // Fill "directors"/"production_companies" columns (sacnilk.com) for Indian-language
            // movies released after 2000, then repeat every 24 h.
            new CreditsCrawlerService().run();
        } else if ("--credits-scan-once".equals(args[0])) {
            // Run one directors/production_companies enrichment cycle and exit.
            new CreditsCrawlerService().runOnce();
        } else if ("--runtime-budget-scan".equals(args[0])) {
            // Fill "runtime"/"budget" columns (sacnilk.com) for Indian-language movies released
            // after 2000 with runtime=0 or budget=0, then repeat every 24 h.
            new RuntimeBudgetCrawlerService().run();
        } else if ("--runtime-budget-scan-once".equals(args[0])) {
            // Run one runtime/budget enrichment cycle and exit.
            new RuntimeBudgetCrawlerService().runOnce();
        } else if ("--legacy-csv-import".equals(args[0])) {
            // One-shot backfill of genre/release_event_type/revenue/budget/number_of_screens
            // (movies_data_collection.legacycsv.folder) for existing Hindi movies, matched by
            // title only (no year in the source CSVs). Never creates new columns or rows.
            new LegacyCsvBackfillService().runOnce();
        } else if ("--marketing-tactics-scan".equals(args[0])) {
            // Classify each movie's promotional campaign into the marketing tactic taxonomy via
            // AuraLLM (movies_data_collection + managed_entities, last 20 years), then repeat
            // every 24 h.
            new MarketingTacticsService().run();
        } else if ("--marketing-tactics-scan-once".equals(args[0])) {
            // Run one marketing-tactics classification cycle and exit.
            new MarketingTacticsService().runOnce();
        } else if ("--marketing-tactics-lookup".equals(args[0])) {
            // Print every marketing tactic on file for one (movie, language, year) as JSON and exit.
            if (args.length < 4) {
                System.err.println("--marketing-tactics-lookup requires a movie name, language, and 4-digit year.");
                printUsage();
                System.exit(1);
            }
            new MarketingTacticsService().printLookup(args[1], args[2], args[3]);
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
            startDaemonYoutubeService();
            startDaemonCreditsService();
            startDaemonRuntimeBudgetService();
            startDaemonMarketingTacticsService();
            if (args.length < 2) {
                System.err.println("--watch requires a folder path.");
                printUsage();
                System.exit(1);
            }
            new FolderWatcher(args[1]).watch();
        } else if ("--batch".equals(args[0])) {
            // One-shot: process existing CSVs and exit. Background services are daemon
            // threads and would be killed by the JVM before completing a cycle here, so
            // they are only started in --watch, which stays resident.
            if (args.length < 2) {
                System.err.println("--batch requires a folder path.");
                printUsage();
                System.exit(1);
            }
            new FolderWatcher(args[1]).runBatch();
        } else {
            // One-shot: process a single CSV and exit — see note above.
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

    private static void startDaemonYoutubeService() {
        Thread t = new Thread(new YoutubeEnrichmentService(), "youtube-enrichment");
        t.setDaemon(true);
        t.start();
    }

    private static void startDaemonCreditsService() {
        Thread t = new Thread(new CreditsCrawlerService(), "credits-crawler");
        t.setDaemon(true);
        t.start();
    }

    private static void startDaemonRuntimeBudgetService() {
        Thread t = new Thread(new RuntimeBudgetCrawlerService(), "runtime-budget-crawler");
        t.setDaemon(true);
        t.start();
    }

    private static void startDaemonMarketingTacticsService() {
        Thread t = new Thread(new MarketingTacticsService(), "marketing-tactics-classifier");
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
        System.err.println("  java -jar AuraDataFiller.jar --actor-crawl                           # run one sacnilk actor filmography crawl cycle and exit");
        System.err.println("  java -jar AuraDataFiller.jar --actor-crawl-supplemental              # run one kulfiy/fandango supplemental actor crawl cycle and exit");
        System.err.println("  java -jar AuraDataFiller.jar --youtube-scan                          # search YouTube for trailer/teaser/song data, repeat every 24 h");
        System.err.println("  java -jar AuraDataFiller.jar --youtube-scan-once                     # run one YouTube enrichment cycle and exit");
        System.err.println("  java -jar AuraDataFiller.jar --youtube-scan-movie \"Movie Name\" YYYY  # test enrichment for one movie and exit");
        System.err.println("  java -jar AuraDataFiller.jar --econ-scan-once                        # backfill GDP/inflation for Indian movies released after 2000, then exit");
        System.err.println("  java -jar AuraDataFiller.jar --credits-scan                         # fill directors/production_companies columns (sacnilk.com), repeat every 24 h");
        System.err.println("  java -jar AuraDataFiller.jar --credits-scan-once                    # run one directors/production_companies enrichment cycle and exit");
        System.err.println("  java -jar AuraDataFiller.jar --runtime-budget-scan                  # fill runtime/budget columns (sacnilk.com), repeat every 24 h");
        System.err.println("  java -jar AuraDataFiller.jar --runtime-budget-scan-once             # run one runtime/budget enrichment cycle and exit");
        System.err.println("  java -jar AuraDataFiller.jar --legacy-csv-import                     # backfill genre/release_event_type/revenue/budget/screens from legacycsv.folder, then exit");
        System.err.println("  java -jar AuraDataFiller.jar --marketing-tactics-scan                # classify movies' marketing tactics via AuraLLM, repeat every 24 h");
        System.err.println("  java -jar AuraDataFiller.jar --marketing-tactics-scan-once           # run one marketing-tactics classification cycle and exit");
        System.err.println("  java -jar AuraDataFiller.jar --marketing-tactics-lookup \"Movie\" Language YYYY # print a movie's marketing tactics as JSON and exit");
    }
}
