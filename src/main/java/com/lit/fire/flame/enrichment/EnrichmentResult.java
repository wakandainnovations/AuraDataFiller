package com.lit.fire.flame.enrichment;

public record EnrichmentResult(
    Double gdpBillionsUsd,
    Double inflationRatePct,
    String releaseEventType,
    String releaseEventName,
    String releaseEventDetail
) {
    public static EnrichmentResult empty() {
        return new EnrichmentResult(null, null, null, null, null);
    }
}
