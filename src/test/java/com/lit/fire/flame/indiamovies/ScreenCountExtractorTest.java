package com.lit.fire.flame.indiamovies;

import org.junit.Test;

import java.time.LocalDate;
import java.util.Optional;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ScreenCountExtractorTest {

    private static Optional<ScreenCountExtractor.Pick> pick(String language, String text) {
        return ScreenCountExtractor.pick(language, ScreenCountExtractor.clean(text));
    }

    private static void assertPick(String scope, int screens, String language, String text) {
        ScreenCountExtractor.Pick p = pick(language, text).orElseThrow();
        assertEquals(scope, p.scope());
        assertEquals(screens, p.screens());
    }

    @Test
    public void prefersTheIndiaFigureOverWorldwideAndOverseas() {
        assertPick("india", 3350, "hindi",
            "It was released in India on 14 February 2019 on 3,350 screens and in overseas markets on 751 screens, with a worldwide count of 4,101 screens.");
        assertPick("india", 4000, "tamil", "Darbar opened in 7,000 screens worldwide, including 4,000 screens in India.");
        assertPick("india", 225, "tamil",
            "It was released worldwide on 18 June 2010, in 375 Screens (225 Screens in India and 150 Screens Overseas).");
    }

    @Test
    public void sumsHomeStateAndRestOfIndia() {
        assertPick("india", 247, "malayalam",
            "The film was released on 4 February 2016 in 131 screens in Kerala and in 116 screens in rest of India and in more than 10 screens in the United States on 5 February.");
        assertPick("india", 90, "malayalam",
            "The film released on 9 December 2010, in 78 theatres in Kerala and in another 12 theatres outside Kerala.");
    }

    @Test
    public void subtractsOverseasFromWorldwide() {
        assertPick("india", 1000, "tamil", "It was also showcased in 1400 screens worldwide, with the film releasing over 330 screens in "
            + "Andhra Pradesh and Telangana; 75 screens in Kerala, 100 screens across Karnataka, 125 screens across other parts of India, "
            + "and 400 screens in the overseas centres.");
    }

    @Test
    public void prefersScreensOverTheatres() {
        assertPick("india", 3550, "hindi", "The film was released in 2,550 cinemas across 3,550 screens in India, and across 700 screens internationally.");
        assertPick("india", 1300, "hindi", "Barfi! was released on 14 September 2012 on 1300 screens in 700 theatres in India.");
    }

    @Test
    public void keepsTheOpeningCountNotTheExpansion() {
        assertPick("india", 630, "hindi",
            "It was initially released in over 630 screens in India on 11 March 2022 and was later increased to 4,000 screens.");
    }

    @Test
    public void labelsHomeStateOnlyFigures() {
        assertPick("state:kerala", 144, "malayalam", "It was released on 3 February 2023 across 144 theatres in Kerala.");
        assertPick("state:tamil nadu", 400, "tamil", "In Tamil Nadu, the film was distributed in more than 400 theatres.");
        assertPick("state:karnataka", 350, "kannada", "It was released in over 350 theatres across the state.");
    }

    @Test
    public void ignoresOtherStatesCitiesForeignMarketsAndOtherFilms() {
        // non-home state: the Kerala release of a Tamil film
        assertFalse(pick("tamil", "In Kerala the film opened in 200 theatres.").isPresent());
        assertFalse(pick("tamil", "The Kerala rights were acquired by Shibu Thameens and was released in over 80 screens across the state.").isPresent());
        assertFalse(pick("hindi", "In the United Kingdom, it released across 23 theatres and earned £31,576 in its first week.").isPresent());
        assertFalse(pick("hindi", "It was released in 156 screens across the country by local distributor Eveready Pictures, "
            + "which is said to be the highest number of screens for any film in Pakistan.").isPresent());
        assertFalse(pick("telugu", "In the Nizam area of Andhra Pradesh, 200 theatres, including 76 in Hyderabad, screened the film.").isPresent());
        assertFalse(pick("hindi", "Domestically, the film had the third-widest release of all Hindi films released in 2018, "
            + "behind only Thugs of Hindostan (5000 screens) and Race 3 (4400 screens).").isPresent());
        assertFalse(pick("tamil", "The Hindi version Vishwaroop was released on over 1,035 screens on 1 February 2013.").isPresent());
        assertFalse(pick("kannada", "By the end of 75 days, it collected 35 crore and still was being screened in over 100 cinemas.").isPresent());
    }

    @Test
    public void reportsWorldwideOnlyFiguresAsSuch() {
        assertPick("worldwide", 4000, "kannada", "Kabzaa was released theatrically on 17 March 2023 across 4,000 screens worldwide.");
    }

    @Test
    public void unqualifiedCountsAreUnspecified() {
        assertPick("unspecified", 4000, "telugu", "The film was released on 4,000 screens, during the first day of the Navratri season on 21 September 2017.");
    }

    @Test
    public void cleanStripsRefsAndLinks() {
        String text = ScreenCountExtractor.clean("Released in [[Kerala|Kerala state]] on {{nowrap|131 screens}}.<ref>{{cite web|url=x}}</ref>");
        assertEquals("Released in Kerala state on 131 screens.", text);
    }

    @Test
    public void parsesBoxOfficeIndiaMoviePage() {
        String html = "<html><head><title>Pathaan - Movie - Box Office India</title></head><body><table><tr><td>"
            + "Release Date:<span class=\"redtext\"> 25 Jan 2023 </span>&nbsp;|&nbsp;Runtime: 146 min</td></tr>"
            + "<tr><td class=\"td_cst_wd\"><a href=\"screens.php\">Screens:</a></td><td>&nbsp;</td><td class=\"td_cst_wd\">5000</td></tr>"
            + "</table></body></html>";
        BoxOfficeIndiaClient.Movie m = BoxOfficeIndiaClient.parse(6214, html).orElseThrow();
        assertEquals("Pathaan", m.title());
        assertEquals(LocalDate.of(2023, 1, 25), m.releaseDate());
        assertEquals(Integer.valueOf(5000), m.screens());

        BoxOfficeIndiaClient.Movie unrecorded = BoxOfficeIndiaClient.parse(6243, html.replace(">5000<", ">0<")).orElseThrow();
        assertEquals(null, unrecorded.screens());
        assertFalse(BoxOfficeIndiaClient.parse(4000, "<html><head><title> - Movie - Box Office India</title></head></html>").isPresent());
    }

    @Test
    public void matchesBoxOfficeIndiaTitles() {
        assertEquals(1.0, IndiaScreensFillService.titleSimilarity("Happy Bhaag Jayegi, Happy Bhag Jayegi", "Happy Bhag Jayegi"), 0.0);
        assertTrue(IndiaScreensFillService.titleSimilarity("Mrs. Chatterjee vs. Norway", "Mrs. Chatterjee Vs Norway ") >= 0.99);
        assertTrue(IndiaScreensFillService.titleSimilarity("Dhoom 3", "Dhoom 2") < 1.0);
    }
}
