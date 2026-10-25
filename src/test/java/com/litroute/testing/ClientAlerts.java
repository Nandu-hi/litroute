package com.litroute.testing;

import com.litroute.ingestion.enrich.ArticlePageReader;
import com.litroute.ingestion.enrich.CompositeMetadataLookup;
import com.litroute.ingestion.enrich.CrossrefSource;
import com.litroute.ingestion.enrich.OnlinePaperEnricher;
import com.litroute.ingestion.enrich.OpenAlexSource;
import com.litroute.ingestion.enrich.PdfDownloader;
import com.litroute.ingestion.enrich.RecordedHttp;
import com.litroute.ingestion.extract.AlertBatchProcessor;
import com.litroute.ingestion.extract.PaperExtractor;
import com.litroute.model.PaperCandidate;
import com.litroute.store.EmailCacheStore;
import com.litroute.store.EnrichmentCacheStore;
import com.litroute.testing.SampleMails.Sample;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * The papers M4 actually hands to M6: the client's six real alert emails ({@link SampleMails}), extracted,
 * enriched and deduplicated offline from the recorded HTTP traffic ({@link RecordedHttp}).
 */
public final class ClientAlerts {

    private ClientAlerts() {
    }

    /** @param data an empty folder for the run's stores and downloaded PDFs */
    public static List<PaperCandidate> deduplicated(Path data) throws IOException {
        RecordedHttp http = new RecordedHttp();
        OnlinePaperEnricher enrichment = new OnlinePaperEnricher(new ArticlePageReader(http),
                new CompositeMetadataLookup(List.of(new CrossrefSource(http), new OpenAlexSource(http))),
                new PdfDownloader(http, data.resolve("pdfs")));
        AlertBatchProcessor pipeline = new AlertBatchProcessor(new PaperExtractor(enrichment),
                new EnrichmentCacheStore(data.resolve("enrichment_cache.json")),
                new EmailCacheStore(data.resolve("email_cache.json")));
        return pipeline.process(SampleMails.ALL.stream().map(Sample::email).toList());
    }
}
