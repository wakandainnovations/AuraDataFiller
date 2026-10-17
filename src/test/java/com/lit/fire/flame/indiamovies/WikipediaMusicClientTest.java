package com.lit.fire.flame.indiamovies;

import org.junit.Test;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class WikipediaMusicClientTest {

    private static WikipediaMusicClient.Music music(String infoboxMusic) {
        String page = "{{Infobox film\n| name = X\n| music = " + infoboxMusic + "\n| cinematography = Y\n}}\nBody text.";
        return new WikipediaMusicClient.Music(2018, WikipediaMusicClient.parseCredits(WikipediaMusicClient.infoboxParam(page, "music")));
    }

    @Test
    public void parsesSongsAndScoreSections() {
        // Andhadhun: score listed first, label on the same line as the previous name
        WikipediaMusicClient.Music m = music("'''Score:'''<br>Daniel B. George '''Songs:'''<br>[[Amit Trivedi]]<br>'''Additional:'''<br>[[Raftaar]]");
        assertEquals(List.of(new WikipediaMusicClient.Credit("score", "Daniel B. George", "Daniel B. George"),
                new WikipediaMusicClient.Credit("songs", "Amit Trivedi", "Amit Trivedi"),
                new WikipediaMusicClient.Credit("other", "Raftaar", "Raftaar")),
            m.credits());
        assertEquals("Amit Trivedi", m.primary().get(0).name());
    }

    @Test
    public void keepsLinkTargetAndHandlesTemplates() {
        WikipediaMusicClient.Music m = music("{{plainlist|\n* '''Songs:'''\n* [[Pritam Chakraborty|Pritam]]\n* '''Score:'''\n* Salim{{endash}}Sulaiman<ref>{{cite web|url=x}}</ref>\n}}");
        assertEquals(new WikipediaMusicClient.Credit("songs", "Pritam", "Pritam Chakraborty"), m.credits().get(0));
        assertEquals(new WikipediaMusicClient.Credit("score", "Salim–Sulaiman", "Salim–Sulaiman"), m.credits().get(1));
    }

    @Test
    public void noInfoboxMeansNoParam() {
        assertNull(WikipediaMusicClient.infoboxParam("Just text {{Infobox person|name=A}}", "music"));
    }

    @Test
    public void replacesScoreComposerWithSongsComposerInTmdbSpelling() {
        WikipediaMusicClient.Music m = music("'''Songs:'''<br />[[Sachin–Jigar]]<br />'''Score:'''<br />[[Justin Varghese]]");
        List<String> tmdb = List.of("Justin Varghese", "Sachin Sanghvi", "Jigar Saraiya");
        assertEquals("Sachin Sanghvi", WikipediaMusicClient.chooseMusicDirector("Justin Varghese", m, tmdb, Map.of()));
        // already a songs composer: kept
        assertEquals("Jigar Saraiya", WikipediaMusicClient.chooseMusicDirector("Jigar Saraiya", m, tmdb, Map.of()));
    }

    @Test
    public void keepsAliasesAndUsesKnownSpellings() {
        assertEquals("Saluri Koteswara Rao",
            WikipediaMusicClient.chooseMusicDirector("Saluri Koteswara Rao", music("[[Koti]]"), List.of(), Map.of()));
        assertEquals("M.M. Keeravaani", WikipediaMusicClient.chooseMusicDirector(null, music("[[M. M. Keeravani|M. M. Kreem]]"), List.of(),
            Map.of("mmkeeravaani", "M.M. Keeravaani")));
        assertEquals("Shankar Ehsan Loy",
            WikipediaMusicClient.chooseMusicDirector("Ram Sampath", music("'''Songs:'''<br>[[Shankar–Ehsaan–Loy]]<br>'''Score:'''<br>[[Ram Sampath]]"),
                List.of(), Map.of()));
    }

    @Test
    public void ignoresUnsplittableNameRuns() {
        WikipediaMusicClient.Music m = music("Bapi-Tutul Ishq Bector Punnu Brar Daboo Malik");
        assertNull(WikipediaMusicClient.chooseMusicDirector(null, m, List.of(), Map.of()));
        assertEquals("Anand Raj Anand", WikipediaMusicClient.chooseMusicDirector("Anand Raj Anand", music("See soundtrack"), List.of(), Map.of()));
    }

    @Test
    public void sameMusicianRules() {
        assertTrue(WikipediaMusicClient.sameMusician("Thaman S", "S. Thaman"));
        assertTrue(WikipediaMusicClient.sameMusician("Vishal–Shekhar", "Shekhar Ravjiani"));
        assertTrue(WikipediaMusicClient.sameMusician("A. R. Rahman", "A.R. Rahman"));
        assertTrue(!WikipediaMusicClient.sameMusician("Amit Trivedi", "Girish Nakod"));
    }
}
