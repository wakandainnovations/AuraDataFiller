package com.lit.fire.flame.marketing;

import java.util.List;

/**
 * Fixed catalogue of movie marketing tactic classifications used by MarketingTacticsService.
 *
 * Sub-classifications 1-38 are the taxonomy supplied by the product owner. 39-44 are additions
 * covering gaps that matter specifically for the Indian-cinema catalogue this app tracks
 * (audio launch events, pre-release/success-meet functions, and traditional TV/print/radio
 * buys are all standard parts of an Indian theatrical campaign but weren't covered by the
 * original list). Sub-classification numbers are stable identifiers — they double as the JSON
 * keys AuraLLM is asked to respond with and as movie_marketing_tactics.sub_classification_number
 * — so existing numbers must never be renumbered; only append.
 */
public final class MarketingTacticTaxonomy {

    public record Tactic(int subNumber, String subName, int mainNumber, String mainName, String description) {}

    private static final List<Tactic> TACTICS = List.of(
        // 1. Trailer & Video Marketing
        new Tactic(1,  "Teaser Trailers",               1, "Trailer & Video Marketing",
            "30-to-60-second mood pieces designed purely to announce the film and set the tone."),
        new Tactic(2,  "Theatrical Trailers",            1, "Trailer & Video Marketing",
            "The standard 2.5-minute, story-driven cut."),
        new Tactic(3,  "Character Promos / Vignettes",   1, "Trailer & Video Marketing",
            "15-second spots focusing on a single character, highly optimized for mobile viewing."),
        new Tactic(4,  "Behind-the-Scenes (BTS) Featurettes", 1, "Trailer & Video Marketing",
            "\"Making-of\" content that highlights practical effects, intense actor training, or the director's vision."),

        // 2. Public Relations & Earned Media
        new Tactic(5,  "Influencer Seeding / PR Gifting", 2, "Public Relations & Earned Media",
            "Sending exclusive, photogenic merchandise to celebrities and creators to manufacture organic \"unboxing\" moments."),
        new Tactic(6,  "Stunt Marketing",                 2, "Public Relations & Earned Media",
            "High-visibility public spectacles (like building the Kalki car) engineered for news coverage."),
        new Tactic(7,  "Manufactured \"Leaks\"",          2, "Public Relations & Earned Media",
            "Releasing intentional, unpolished footage (like the Marty Supreme video call) to bypass traditional advertising filters."),
        new Tactic(8,  "Press Junkets",                   2, "Public Relations & Earned Media",
            "Controlled events where cast members speak to dozens of journalists in rapid succession."),
        new Tactic(9,  "Late-Night & Talk Show Circuits",  2, "Public Relations & Earned Media",
            "Traditional couch interviews tailored to specific host demographics."),

        // 3. Digital & Social Media
        new Tactic(10, "Meme Marketing & Shitposting",    3, "Digital & Social Media",
            "Deliberately absurd or humorous brand accounts engaging with internet culture (highly utilized by horror films and comedies)."),
        new Tactic(11, "AR Filters & Lenses",              3, "Digital & Social Media",
            "Custom interactive filters on Snapchat, TikTok, and Instagram."),
        new Tactic(12, "Short-Form Challenges",            3, "Digital & Social Media",
            "Creating TikTok audio trends or dance challenges to drive user-generated content."),
        new Tactic(13, "Interactive Story Countdowns",     3, "Digital & Social Media",
            "Using native platform features to keep the release date top-of-mind."),
        new Tactic(14, "Platform Takeovers",               3, "Digital & Social Media",
            "Buying the masthead on YouTube or the trending tab on X (formerly Twitter) for a day."),

        // 4. Experiential & Event Marketing
        new Tactic(15, "Immersive Pop-Ups & Activations",  4, "Experiential & Event Marketing",
            "Recreating sets or themes in major cities for fans to walk through and photograph."),
        new Tactic(16, "Themed Real-World Events",         4, "Experiential & Event Marketing",
            "Executing events that mirror the movie's plot (like the Marty Supreme tennis tournament or GD Naidu science competition)."),
        new Tactic(17, "Convention Panels",                4, "Experiential & Event Marketing",
            "Massive reveals and exclusive footage drops at San Diego Comic-Con, D23, or CinemaCon."),
        new Tactic(18, "Red Carpet Premieres",             4, "Experiential & Event Marketing",
            "Global, live-streamed events featuring the cast, crew, and invited influencers."),

        // 5. Out-of-Home (OOH)
        new Tactic(19, "Billboard Teasers",                5, "Out-of-Home (OOH)",
            "Minimalist designs (like the all-pink Barbie date billboards) that rely on high brand recognition."),
        new Tactic(20, "Transit Takeovers",                5, "Out-of-Home (OOH)",
            "Wrapping city buses, subway cars, or station tunnels in immersive key art."),
        new Tactic(21, "Building Projections / 3D Mapping", 5, "Out-of-Home (OOH)",
            "Projecting motion graphics onto famous landmarks or skyscrapers at night."),

        // 6. In-Theater (Exhibitor Relations)
        new Tactic(22, "Viral Concession Items",           6, "In-Theater (Exhibitor Relations)",
            "Highly elaborate, collectible popcorn buckets or beverage cups that spark social media debate."),
        new Tactic(23, "Custom Pre-Show Content",          6, "In-Theater (Exhibitor Relations)",
            "Actors breaking the fourth wall to tell the specific theater audience to silence their phones."),
        new Tactic(24, "Theatrical Standees & Lobby Takeovers", 6, "In-Theater (Exhibitor Relations)",
            "Massive, physical, often 3D cardboard displays."),
        new Tactic(25, "In-Theater Slideshows",            6, "In-Theater (Exhibitor Relations)",
            "Trivia and stills played on screen before the trailers begin."),

        // 7. Grassroots & Community
        new Tactic(26, "Crowdfunding Campaigns",           7, "Grassroots & Community",
            "Using platforms like Kickstarter (as with Lucia) to turn backers into vocal brand ambassadors."),
        new Tactic(27, "College & University Tours",       7, "Grassroots & Community",
            "Bringing the cast or the film directly to campuses."),
        new Tactic(28, "Word-of-Mouth (WOM) Screenings",   7, "Grassroots & Community",
            "Free, early screenings for specific niche groups (e.g., showing a horror movie to fraternities a month before release)."),

        // 8. Brand Partnerships & Tie-Ins
        new Tactic(29, "Co-Branded Commercials",           8, "Brand Partnerships & Tie-Ins",
            "A car manufacturer or insurance company creating an ad that exists within the movie's cinematic universe."),
        new Tactic(30, "Fast Food Tie-Ins",                8, "Brand Partnerships & Tie-Ins",
            "Happy Meal toys or custom menu items."),
        new Tactic(31, "Product Placement Integration",    8, "Brand Partnerships & Tie-Ins",
            "Brands paying to be featured prominently within the film, and subsequently promoting the film's release in their own marketing."),

        // 9. Merchandising & Consumer Products
        new Tactic(32, "Retail Licensing",                 9, "Merchandising & Consumer Products",
            "Action figures, apparel lines, and home goods sold at mass retailers."),
        new Tactic(33, "Video Game Crossovers",             9, "Merchandising & Consumer Products",
            "Integrating character skins or movie locations into massive games like Fortnite or Call of Duty."),

        // 10. Data, Lifecycle & SEM
        new Tactic(34, "Programmatic Retargeting",         10, "Data, Lifecycle & SEM",
            "Serving display ads to users who watched the trailer on YouTube but haven't clicked a ticketing link yet."),
        new Tactic(35, "Search Engine Marketing (SEM)",    10, "Data, Lifecycle & SEM",
            "Bidding on high-intent keywords like \"sci-fi movies playing near me\"."),
        new Tactic(36, "CRM & Loyalty Emails",             10, "Data, Lifecycle & SEM",
            "Partnering with theater chains to email discounts to users who have previously purchased tickets for similar genres."),

        // 11. Festival & Awards (FYC)
        new Tactic(37, "Festival Premieres",               11, "Festival & Awards (FYC)",
            "Using Cannes, TIFF, or Sundance strictly to build critical buzz and secure a Rotten Tomatoes score prior to wide release."),
        new Tactic(38, "\"For Your Consideration\" (FYC) Campaigns", 11, "Festival & Awards (FYC)",
            "Highly targeted trade ads and private tastemaker screenings aimed exclusively at voting guilds and academies."),

        // 12. Music & Audio Launch Marketing (added — standard part of an Indian theatrical campaign)
        new Tactic(39, "Audio Launch Events",              12, "Music & Audio Launch Marketing",
            "A dedicated public event (often televised) where the film's soundtrack is unveiled to press, fans, and industry guests ahead of release."),
        new Tactic(40, "Single/Song Promotional Releases",  12, "Music & Audio Launch Marketing",
            "Releasing individual songs or lyric/music videos on a staggered schedule ahead of the film as standalone promotional content."),

        // 13. Regional Pre-Release & Success Events (added — common in South Indian cinema)
        new Tactic(41, "Pre-Release Function",             13, "Regional Pre-Release & Success Events",
            "A large public event just before release, featuring cast/crew speeches and distributor appearances, meant to energize fans and trade ahead of opening day."),
        new Tactic(42, "Success Meet",                     13, "Regional Pre-Release & Success Events",
            "A public celebration event held after a strong opening or box-office milestone, sustaining word-of-mouth momentum through the run."),

        // 14. Traditional Media Advertising (added)
        new Tactic(43, "TV Spot Advertising",              14, "Traditional Media Advertising",
            "Paid commercial spots aired on television channels."),
        new Tactic(44, "Print & Radio Advertising",         14, "Traditional Media Advertising",
            "Newspaper ads, magazine spreads, and radio spot campaigns.")
    );

    private MarketingTacticTaxonomy() {}

    public static List<Tactic> all() {
        return TACTICS;
    }

    public static Tactic bySubNumber(int subNumber) {
        for (Tactic t : TACTICS) {
            if (t.subNumber() == subNumber) return t;
        }
        return null;
    }

    /** Renders the full taxonomy as a numbered listing for embedding in the AuraLLM prompt. */
    public static String promptListing() {
        StringBuilder sb = new StringBuilder();
        for (Tactic t : TACTICS) {
            sb.append(t.subNumber()).append(". ").append(t.subName())
              .append(" [category: ").append(t.mainName()).append("] - ")
              .append(t.description()).append('\n');
        }
        return sb.toString();
    }
}
