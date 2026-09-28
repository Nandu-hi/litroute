package com.litroute.ingestion.extract;

import static org.junit.Assert.assertEquals;

import com.litroute.ingestion.extract.LinkUnwrapper.Link;
import java.util.Optional;
import org.junit.Test;

public class LinkUnwrapperTest {

    private static Link resolve(String href) {
        return LinkUnwrapper.resolve(href);
    }

    @Test
    public void aPlainPublisherUrlIsReadableAsItIs() {
        Link link = resolve("https://jipr.springeropen.com/articles/10.9999/x?utm_source=alert");

        assertEquals(LinkKind.READABLE, link.kind());
        assertEquals(Optional.of("https://jipr.springeropen.com/articles/10.9999/x?utm_source=alert"), link.url());
    }

    @Test
    public void aRedirectWrapperCarryingItsTargetAsAQueryValueIsUnwrapped() {
        Link link = resolve("https://scholar.google.com/scholar_url?url=https%3A%2F%2Fonlinelibrary.wiley.com%2Fdoi%2Fpdf%2F10.9999%2Feqe"
                + "&hl=en&sa=X&scisig=AAZF9b_SIG&oi=scholaralrt");

        assertEquals(LinkKind.READABLE, link.kind());
        assertEquals(Optional.of("https://onlinelibrary.wiley.com/doi/pdf/10.9999/eqe"), link.url());
    }

    @Test
    public void aClickTrackerCarryingItsTargetAsAPathSegmentIsUnwrapped() {
        Link link = resolve("https://click.notification.elsevier.com/CL0/https:%2F%2Fwww.sciencedirect.com%2Fscience%2Farticle%2Fpii%2FS01"
                + "%3Fdgcid=raven/1/0100019-000000/SIG=/");

        assertEquals(LinkKind.READABLE, link.kind());
        assertEquals(Optional.of("https://www.sciencedirect.com/science/article/pii/S01?dgcid=raven"), link.url());
    }

    @Test
    public void wrappersNestedInsideWrappersAreUnwrappedAllTheWayDown() {
        String inner = "https%3A%2F%2Fpub.test%2Fdoi%2F10.9999%2Fdeep";
        String middle = "https://redirect.test/r?u=" + java.net.URLEncoder.encode("https://scholar.google.com/scholar_url?url=" + inner,
                java.nio.charset.StandardCharsets.UTF_8);

        assertEquals(Optional.of("https://pub.test/doi/10.9999/deep"), resolve(middle).url());
    }

    @Test
    public void aTrackerWithNoRecoverableTargetIsOpaqueAndItsUrlIsDropped() {
        for (String tracker : new String[] {
                "https://email.springeropen.com/ls/click?upn=u001.Zk3vQ9pXwM1aT7nR2yLd8eHc5b",
                "https://click.notification.elsevier.com/e/Q0w0L2h0dHBzOi8vc3ludGhldGljLTQ",
                "https://abc123.list-manage.com/track/click?u=1&id=2",
                "https://links.example.org/e/ctt?m=OPAQUE" }) {
            Link link = resolve(tracker);
            assertEquals(tracker, LinkKind.OPAQUE, link.kind());
            assertEquals(tracker, Optional.empty(), link.url());
        }
    }

    @Test
    public void publisherHostsThatMerelyStartWithLinkOrEmailWordsAreNotMistakenForTrackers() {
        assertEquals(LinkKind.READABLE, resolve("https://link.springer.com/article/10.1007/s00000-000-0000-0").kind());
        assertEquals(LinkKind.READABLE, resolve("https://www.tandfonline.com/doi/full/10.9999/x?src=alert").kind());
    }

    @Test
    public void nonHttpOrMissingLinksAreNone() {
        assertEquals(LinkKind.NONE, resolve("mailto:info@springeropen.com").kind());
        assertEquals(LinkKind.NONE, resolve("javascript:void(0)").kind());
        assertEquals(LinkKind.NONE, resolve("cid:logo").kind());
        assertEquals(LinkKind.NONE, resolve("").kind());
        assertEquals(LinkKind.NONE, resolve(null).kind());
    }
}
