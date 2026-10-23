package com.lit.fire.flame.marketing;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class LanguageNamesTest {

    @Test
    public void isoCodesBecomeFullNames() {
        assertEquals("latvian", LanguageNames.normalize("lv"));
        assertEquals("danish", LanguageNames.normalize("da"));
        assertEquals("tamil", LanguageNames.normalize("ta"));
    }

    @Test
    public void tmdbSpecialCodes() {
        assertEquals("cantonese", LanguageNames.normalize("cn"));
        assertEquals("no language", LanguageNames.normalize("xx"));
    }

    @Test
    public void fullNamesAreLowercasedAndKept() {
        assertEquals("tamil", LanguageNames.normalize(" Tamil "));
        assertEquals("bhojpuri", LanguageNames.normalize("bhojpuri"));
        assertEquals("chinese", LanguageNames.normalize("Mandarin Chinese"));
    }

    @Test
    public void legacySpellingsAreMerged() {
        assertEquals("odia", LanguageNames.normalize("Oriya"));
        assertEquals("rajasthani", LanguageNames.normalize("rajastani"));
    }

    @Test
    public void commaListsAreNormalisedPartByPart() {
        assertEquals("hindi, tamil", LanguageNames.normalize("Hindi, ta"));
        assertEquals("telugu", LanguageNames.normalize(" ,te, "));
    }

    @Test
    public void blankIsUnknown() {
        assertEquals("unknown", LanguageNames.normalize(null));
        assertEquals("unknown", LanguageNames.normalize("  "));
        assertEquals("unknown", LanguageNames.normalize(" , "));
    }
}
