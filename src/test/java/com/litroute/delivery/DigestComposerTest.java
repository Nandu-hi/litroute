package com.litroute.delivery;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.litroute.model.PaperCandidate;
import com.litroute.model.ReviewStatus;
import com.litroute.model.ScoredPaper;
import com.litroute.store.Candidates;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.Before;
import org.junit.Test;

/**
 * How M10 lays out a student's approved papers: two real papers from the client's SpringerOpen alert with
 * their recorded PDFs (8 KB each), and one the alert said almost nothing about.
 */
public class DigestComposerTest {

    private static final String STUDENT = "RP1-mh-risk";
    private static final List<String> TO = List.of("rp1.student@example.com");
    private static final String PROFESSOR = "professor@example.com";

    private Path facadesPdf;
    private Path ssiPdf;

    @Before
    public void copyRecordedPdfs() throws IOException {
        Path dir = Files.createTempDirectory("litroute-digest");
        facadesPdf = Files.copy(Path.of("src/test/resources/http/e502fdcc276fe9dbf272.pdf"),
                dir.resolve("10.1186_s43065-024-00115-x.pdf"));
        ssiPdf = Files.copy(Path.of("src/test/resources/http/8d0d08f9b152568722f5.pdf"),
                dir.resolve("10.1186_s43065-025-00118-2.pdf"));
    }

    private static ScoredPaper approved(PaperCandidate candidate, double score, boolean recommended) {
        return new ScoredPaper(candidate, STUDENT, score, "Closest in " + STUDENT + ": ...", ReviewStatus.APPROVED,
                recommended);
    }

    private ScoredPaper facades() {
        return approved(Candidates.full().withPdfPath(Optional.of(facadesPdf)), 0.7, false);
    }

    private ScoredPaper ssi() {
        return approved(Candidates.ssiReview(ssiPdf), 0.5, true);
    }

    @Test
    public void papersThatFitGoInOneEmailWithTheirPdfsAttached() {
        List<DigestEmail> emails = DigestComposer.compose(STUDENT, TO, PROFESSOR, List.of(facades(), ssi()),
                DigestComposer.GMAIL_ATTACHMENT_BUDGET);

        assertEquals(1, emails.size());
        DigestEmail email = emails.get(0);
        assertEquals("LitRoute: 2 new papers for RP1-mh-risk", email.subject());
        assertEquals(TO, email.recipients());
        assertEquals(List.of(ssiPdf, facadesPdf), email.attachments());
        assertTrue(email.body().contains(PROFESSOR + " has approved these papers"));
        assertTrue(email.body().contains("https://doi.org/10.1186/s43065-024-00115-x"));
        assertTrue(email.body().contains("Gilberto Gomes, Luis Silva, Elton Bauer"));
        assertTrue(email.body().contains("Journal of Infrastructure Preservation and Resilience · 2025 · Research article"));
        assertTrue(email.body().contains("PDF attached: 10.1186_s43065-024-00115-x.pdf"));
        assertTrue(email.body().contains("Abstract: Building facades are continuously subjected"));
    }

    /** An email is composed only for approved papers: none at all when the student has none approved. */
    @Test
    public void onlyApprovedPapersAreComposedAndNoneApprovedMeansNoEmail() {
        ScoredPaper pending = facades().withStatus(ReviewStatus.PENDING);
        ScoredPaper dropped = ssi().withStatus(ReviewStatus.DROPPED);

        assertTrue(DigestComposer.compose(STUDENT, TO, PROFESSOR, List.of(), DigestComposer.GMAIL_ATTACHMENT_BUDGET)
                .isEmpty());
        assertTrue(DigestComposer.compose(STUDENT, TO, PROFESSOR, List.of(pending, dropped),
                DigestComposer.GMAIL_ATTACHMENT_BUDGET).isEmpty());
        List<DigestEmail> emails = DigestComposer.compose(STUDENT, TO, PROFESSOR, List.of(pending, ssi()),
                DigestComposer.GMAIL_ATTACHMENT_BUDGET);
        assertEquals(1, emails.size());
        assertEquals(List.of(ssi().queueKey()), emails.get(0).papers().stream().map(ScoredPaper::queueKey).toList());
    }

    /** M6's recommendation leads, as it does in the review table, even over a higher score. */
    @Test
    public void recommendedPapersAreListedFirst() {
        DigestEmail email = DigestComposer.compose(STUDENT, TO, PROFESSOR, List.of(facades(), ssi()),
                DigestComposer.GMAIL_ATTACHMENT_BUDGET).get(0);

        assertEquals(List.of(ssi(), facades()), email.papers());
        assertTrue(email.body().indexOf("1. Advancing soil-structure interaction") < email.body().indexOf("2. Hygrothermal"));
    }

    @Test
    public void pdfsBeyondTheBudgetContinueInAnotherEmail() throws IOException {
        long onePdf = Files.size(facadesPdf);

        List<DigestEmail> emails = DigestComposer.compose(STUDENT, TO, PROFESSOR, List.of(facades(), ssi()), onePdf + 100);

        assertEquals(2, emails.size());
        assertEquals("LitRoute: 1 new paper for RP1-mh-risk (part 1 of 2)", emails.get(0).subject());
        assertEquals("LitRoute: 1 new paper for RP1-mh-risk (part 2 of 2)", emails.get(1).subject());
        assertEquals(List.of(ssi()), emails.get(0).papers());
        assertEquals(List.of(ssiPdf), emails.get(0).attachments());
        assertEquals(List.of(facades()), emails.get(1).papers());
        assertEquals(List.of(facadesPdf), emails.get(1).attachments());
    }

    /** No PDF, a PDF since deleted, or one too large for any email: the paper still goes, with its link. */
    @Test
    public void papersWhosePdfCannotBeAttachedAreListedWithTheirLink() throws IOException {
        ScoredPaper paywalled = approved(Candidates.full().withPdfPath(Optional.empty()), 0.7, false);
        ScoredPaper gone = approved(Candidates.full(), 0.7, false); // its pdfPath does not exist here
        ScoredPaper tooLarge = ssi();
        long smallerThanAnyPdf = Files.size(ssiPdf) - 1;

        for (ScoredPaper paper : List.of(paywalled, gone, tooLarge)) {
            List<DigestEmail> emails = DigestComposer.compose(STUDENT, TO, PROFESSOR, List.of(paper), smallerThanAnyPdf);

            assertEquals(1, emails.size());
            DigestEmail email = emails.get(0);
            assertEquals("LitRoute: 1 new paper for RP1-mh-risk", email.subject());
            assertEquals(List.of(), email.attachments());
            assertEquals(List.of(paper), email.papers());
            assertTrue(email.body().contains(PROFESSOR + " has approved this paper"));
            assertTrue(email.body().contains("https://doi.org/"));
            assertTrue(email.body().contains("use the link above."));
        }
    }

    @Test
    public void aPaperWithNothingButATitleIsStillListed() {
        ScoredPaper sparse = approved(Candidates.sparse(), 0.4, false);

        DigestEmail email = DigestComposer.compose(STUDENT, TO, PROFESSOR, List.of(sparse),
                DigestComposer.GMAIL_ATTACHMENT_BUDGET).get(0);

        assertTrue(email.body().contains("1. A paper the email told us almost nothing about\n"
                + "   No PDF was freely available"));
    }
}
