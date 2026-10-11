package com.lit.fire.flame;

import com.lit.fire.flame.actor.ActorDataCollectionService;
import com.lit.fire.flame.actor.SacnilkActorCrawlerService;
import com.lit.fire.flame.actor.SupplementalActorCrawlerService;
import com.lit.fire.flame.crawler.BoxOfficeCrawlerOrchestrator;
import com.lit.fire.flame.crawler.SacnilkCrawlerService;
import com.lit.fire.flame.credits.CreditsCrawlerService;
import com.lit.fire.flame.enrichment.EconomicEnrichmentService;
import com.lit.fire.flame.indiamovies.IndiaMoviesCopyService;
import com.lit.fire.flame.indiamovies.IndiaMoviesImportService;
import com.lit.fire.flame.actor.TmdbActorImportService;
import com.lit.fire.flame.directors.DirectorFillService;
import com.lit.fire.flame.kannadabudget.KannadaBudgetBackfillService;
import com.lit.fire.flame.languageid.LanguageIdService;
import com.lit.fire.flame.legacycsv.LegacyCsvBackfillService;
import com.lit.fire.flame.marketing.MarketingTacticsService;
import com.lit.fire.flame.moviedetails.MovieDetailsService;
import com.lit.fire.flame.releaseevent.ReleaseEventService;
import com.lit.fire.flame.tamilcsv.TamilCsvImportService;
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
            // Indian-language movies released after 2000. One-shot, then exit. An optional table
            // argument (e.g. india_movies_data_collection) targets that table instead, treating
            // every row in it as an Indian movie.
            new EconomicEnrichmentService().runOnce(args.length > 1 ? args[1] : null);
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
            // One-shot backfill of genre/release_event_type/revenue/budget/number_of_screens/
            // runtime/rating_10 from every .csv/.xlsx file in legacycsv.folder, for existing
            // Hindi movies matched by title only (no year in the source files). Never creates
            // new columns or rows.
            new LegacyCsvBackfillService().runOnce();
        } else if ("--tamil-csv-import".equals(args[0])) {
            // One-shot import of genre/runtime/budget/revenue/rating_10/directors/
            // production_companies/release_day from tamilcsv.files, matched by (movie_name,
            // release year) — a real date is available here, unlike the Hindi CSVs — and every
            // row's language is explicitly marked "tamil". Never creates new columns.
            new TamilCsvImportService().runOnce();
        } else if ("--kannada-budget-import".equals(args[0])) {
            // One-shot backfill of budget/revenue (USD) for Kannada movies from a single
            // mtwikiblog.com article covering 2022/2023/2026 Karnataka-territory box office.
            // Existing rows already in a non-Kannada language are left untouched; 'unknown'
            // rows are relabelled to 'kannada'; movies with no DB entry at all are inserted as
            // new 'kannada' rows (release_date via AuraLLM, falling back to Jan 1 of the
            // article's section year) — including any pan-India film on the article not yet
            // otherwise in the DB, per explicit instruction.
            new KannadaBudgetBackfillService().runOnce();
        } else if ("--language-id-scan".equals(args[0])) {
            // Identify language for movies_data_collection rows currently language='unknown'
            // via AuraLLM, in bounded batches (languageid.batch.size), repeating every 24 h.
            new LanguageIdService().run();
        } else if ("--language-id-scan-once".equals(args[0])) {
            // Run one language-identification batch and exit. Call repeatedly (or use
            // --language-id-scan / --watch) to work through the full unknown-language backlog.
            new LanguageIdService().runOnce();
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
        } else if ("--release-event-scan".equals(args[0])) {
            // Classify each Indian-language row's release_event_type/name/detail (Holiday/
            // Festival/Election/Cricket World Cup/Football World Cup/Normal) via AuraLLM, based
            // on release_date +/- 7 days, country resolved from language, and (for languages
            // with one) that language's home state's elections, most recently released first,
            // then repeat.
            new ReleaseEventService().run();
        } else if ("--release-event-scan-once".equals(args[0])) {
            // Run one release-event classification cycle and exit. An optional table argument
            // (e.g. india_movies_data_collection) targets that table instead, treating every row
            // in it as an Indian movie, and repeats cycles until its backlog is drained.
            new ReleaseEventService().runOnce(args.length > 1 ? args[1] : null);
        } else if ("--release-event-recheck-normal".equals(args[0])) {
            // Re-examine every row currently classified "Normal" or still unclassified against
            // the full taxonomy (added after some rows were first classified: Cricket/Football
            // World Cup, state-election awareness), overwriting only rows no longer "Normal",
            // then exit.
            new ReleaseEventService().runRecheckNormalOnce();
        } else if ("--movie-details-scan".equals(args[0])) {
            // Fill rating_10/runtime/genre/country/production_companies/directors/cbfc_rating
            // from the real TMDB API, most recently released first, then repeat (see
            // moviedetails.interval.hours).
            new MovieDetailsService().run();
        } else if ("--movie-details-scan-once".equals(args[0])) {
            // Run one movie-details enrichment cycle and exit.
            new MovieDetailsService().runOnce();
        } else if ("--india-movies-import-once".equals(args[0])) {
            // One-shot: import every India-origin movie TMDB has on file (indiamovies.year.from
            // to indiamovies.year.to) with both a non-zero budget AND revenue into a brand-new
            // india_movies_data_collection table (exact schema mirror of movies_data_collection),
            // keyed by (movie_name, release_date, language). Never touches movies_data_collection.
            new IndiaMoviesImportService().runOnce();
        } else if ("--india-movies-copy-once".equals(args[0])) {
            // One-shot: copies every Indian-language row already sitting in
            // movies_data_collection (filled by this app's other backfill services) into
            // india_movies_data_collection, for every movie whose movie_name isn't already
            // there and whose budget AND revenue are both non-zero. Plain SQL copy — no TMDB
            // calls, no rate limit. Safe to re-run.
            new IndiaMoviesCopyService().runOnce();
        } else if ("--actor-tmdb-import-once".equals(args[0])) {
            // One-shot: fill actors_data_collection with the top-billed cast (plus director and
            // music director) of every India-origin TMDB movie released 2006 (or [fromYear]) to
            // today (or [toYear]), updating matching existing rows with TMDB's values. Resumable;
            // shares TMDB's rate limiter.
            new TmdbActorImportService().runOnce(
                args.length > 1 ? Integer.valueOf(args[1]) : null,
                args.length > 2 ? Integer.valueOf(args[2]) : null);
        } else if ("--director-fill-once".equals(args[0])) {
            // One-shot: fill empty india_movies_data_collection.directors (by IMDb id) and
            // actors_data_collection.director (by tmdb_movie_id) from Wikidata (exact id), then
            // Wikipedia's yearly per-language film lists, never overwriting a director already on
            // file. Safe to re-run.
            new DirectorFillService().runOnce();
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
            startDaemonReleaseEventService();
            startDaemonLanguageIdService();
            startDaemonMovieDetailsService();
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

    private static void startDaemonReleaseEventService() {
        Thread t = new Thread(new ReleaseEventService(), "release-event-classifier");
        t.setDaemon(true);
        t.start();
    }

    private static void startDaemonLanguageIdService() {
        Thread t = new Thread(new LanguageIdService(), "language-id-classifier");
        t.setDaemon(true);
        t.start();
    }

    private static void startDaemonMovieDetailsService() {
        Thread t = new Thread(new MovieDetailsService(), "movie-details-enrichment");
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
        System.err.println("  java -jar AuraDataFiller.jar --econ-scan-once [table]                # backfill GDP/inflation for Indian movies released after 2000 (optionally in [table], all rows treated as Indian), then exit");
        System.err.println("  java -jar AuraDataFiller.jar --credits-scan                         # fill directors/production_companies columns (sacnilk.com), repeat every 24 h");
        System.err.println("  java -jar AuraDataFiller.jar --credits-scan-once                    # run one directors/production_companies enrichment cycle and exit");
        System.err.println("  java -jar AuraDataFiller.jar --runtime-budget-scan                  # fill runtime/budget columns (sacnilk.com), repeat every 24 h");
        System.err.println("  java -jar AuraDataFiller.jar --runtime-budget-scan-once             # run one runtime/budget enrichment cycle and exit");
        System.err.println("  java -jar AuraDataFiller.jar --legacy-csv-import                     # backfill genre/release_event_type/revenue/budget/screens from legacycsv.folder, then exit");
        System.err.println("  java -jar AuraDataFiller.jar --tamil-csv-import                      # import genre/runtime/budget/revenue/rating/directors from tamilcsv.files (language='tamil'), then exit");
        System.err.println("  java -jar AuraDataFiller.jar --kannada-budget-import                 # backfill budget/revenue (USD) for Kannada movies from mtwikiblog.com, then exit");
        System.err.println("  java -jar AuraDataFiller.jar --language-id-scan                      # identify language for language='unknown' rows via AuraLLM, in batches, repeat every 24 h");
        System.err.println("  java -jar AuraDataFiller.jar --language-id-scan-once                 # run one language-identification batch and exit");
        System.err.println("  java -jar AuraDataFiller.jar --marketing-tactics-scan                # classify movies' marketing tactics via AuraLLM, repeat every 24 h");
        System.err.println("  java -jar AuraDataFiller.jar --marketing-tactics-scan-once           # run one marketing-tactics classification cycle and exit");
        System.err.println("  java -jar AuraDataFiller.jar --marketing-tactics-lookup \"Movie\" Language YYYY # print a movie's marketing tactics as JSON and exit");
        System.err.println("  java -jar AuraDataFiller.jar --release-event-scan                    # classify release_event_type/name/detail (Indian-language movies) via AuraLLM, repeat");
        System.err.println("  java -jar AuraDataFiller.jar --release-event-scan-once [table]       # run one release-event classification cycle and exit (with [table]: classify every row in it, all treated as Indian)");
        System.err.println("  java -jar AuraDataFiller.jar --release-event-recheck-normal          # re-examine rows classified 'Normal' or unclassified against the current taxonomy, then exit");
        System.err.println("  java -jar AuraDataFiller.jar --movie-details-scan                    # fill rating_10/runtime/genre/country/production_companies/directors/cbfc_rating from TMDB, repeat");
        System.err.println("  java -jar AuraDataFiller.jar --movie-details-scan-once               # run one movie-details enrichment cycle and exit");
        System.err.println("  java -jar AuraDataFiller.jar --india-movies-import-once              # import India-origin TMDB movies with budget+revenue into india_movies_data_collection, then exit");
        System.err.println("  java -jar AuraDataFiller.jar --india-movies-copy-once                # copy Indian-language movies_data_collection rows (budget+revenue non-zero, movie_name not already present) into india_movies_data_collection, then exit");
        System.err.println("  java -jar AuraDataFiller.jar --actor-tmdb-import-once [from] [to]    # fill actors_data_collection with lead cast + director/music director of India-origin TMDB movies 2006-today (or [from]-[to] years; resumable), then exit");
        System.err.println("  java -jar AuraDataFiller.jar --director-fill-once                     # fill empty directors in india_movies_data_collection (IMDb id) and actors_data_collection (TMDB id) from Wikidata, then Wikipedia film lists, then exit");
    }
}
