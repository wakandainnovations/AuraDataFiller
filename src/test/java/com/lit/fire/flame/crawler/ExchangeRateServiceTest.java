package com.lit.fire.flame.crawler;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;

import org.junit.Test;

/** Offline tests for {@link ExchangeRateService#parseUsdRate}, using trimmed xe.com page shapes. */
public class ExchangeRateServiceTest {

    // Shape of a normal page (2024-01-12): historicRates first, cbrData later.
    private static final String VALID_PAGE =
        "{\"pageProps\":{\"historicRates\":[" +
        "{\"currency\":\"USD\",\"rate\":0.012073870003077906,\"inverse\":82.82348573780213," +
        "\"encyclopediaSlug\":\"usd-us-dollar\"}," +
        "{\"currency\":\"EUR\",\"rate\":0.011016321378728934,\"inverse\":90.77}]," +
        "\"timestamp\":1705017600000,\"isError\":false," +
        "\"cbrData\":[{\"currency\":\"USD\",\"rate\":5.5,\"profileSlug\":\"usd-us-dollar\"}]}}";

    // Shape of a no-data page (1995-12-20): the only USD entry is a central-bank rate.
    private static final String ERROR_PAGE =
        "{\"pageProps\":{\"historicRates\":null,\"timestamp\":null,\"isError\":true," +
        "\"cbrData\":[{\"currency\":\"JPY\",\"rate\":1.25},{\"currency\":\"USD\",\"rate\":3.75," +
        "\"profileSlug\":\"usd-us-dollar\"}]}}";

    @Test
    public void readsUsdFromHistoricRates() throws IOException {
        assertEquals(0.012073870003077906, ExchangeRateService.parseUsdRate(VALID_PAGE, "2024-01-12"), 0.0);
    }

    @Test
    public void rejectsErrorPageInsteadOfReadingInterestRate() {
        assertThrowsMentioning(ERROR_PAGE, "isError");
    }

    @Test
    public void rejectsNullHistoricRatesEvenWithoutErrorFlag() {
        assertThrowsMentioning(ERROR_PAGE.replace("\"isError\":true", "\"isError\":false"), "historicRates");
    }

    @Test
    public void ignoresCbrUsdWhenHistoricRatesLacksUsd() {
        String page = VALID_PAGE.replace("\"currency\":\"USD\",\"rate\":0.0120", "\"currency\":\"GBP\",\"rate\":0.0120");
        assertThrowsMentioning(page, "No USD entry");
    }

    @Test
    public void rejectsImplausibleRate() {
        String page = VALID_PAGE.replace("0.012073870003077906", "3.75");
        assertThrowsMentioning(page, "Implausible");
    }

    private static void assertThrowsMentioning(String body, String text) {
        try {
            double rate = ExchangeRateService.parseUsdRate(body, "1995-12-20");
            fail("expected IOException, got rate " + rate);
        } catch (IOException e) {
            assertTrue("message was: " + e.getMessage(), e.getMessage().contains(text));
        }
    }
}
