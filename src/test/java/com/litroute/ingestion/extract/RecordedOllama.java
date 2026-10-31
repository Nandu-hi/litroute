package com.litroute.ingestion.extract;

import com.litroute.ingestion.enrich.HttpReply;
import com.litroute.model.RawEmail;
import com.litroute.testing.SampleMails;
import com.litroute.testing.SampleMails.Sample;
import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Replays what Ollama really answered ({@link TitleRecorder}), byte for byte, by the exact request: a changed
 * prompts.txt, slicing or model finds no recording, and fails instead of testing against a stale answer.
 */
public final class RecordedOllama implements OllamaClient.Poster {

    static final Path RECORDINGS = Path.of("src/test/resources/ollama");

    /**
     * The client's alerts the recordings cover: as they reached the inbox, their tidied copies, and two
     * tidied copies whose author and citation lines are removed, which the HTML parser can't read.
     */
    static List<RawEmail> recordedEmails() throws IOException {
        List<RawEmail> emails = new ArrayList<>();
        for (Sample sample : SampleMails.ALL) {
            emails.add(received(sample));
            emails.add(sample.email());
        }
        emails.add(unparseable(SampleMails.SPRINGER_RELEVANT));
        emails.add(unparseable(SampleMails.SPRINGER_IRRELEVANT));
        return emails;
    }

    /** The real alert exactly as it reached the inbox. */
    static RawEmail received(Sample sample) throws IOException {
        String html = Files.readString(Path.of("src/test/resources/samples/received", sample.file()));
        return new RawEmail("<received-" + sample.file() + ">", sample.subject(), SampleMails.FORWARDER,
                sample.receivedAt(), Optional.of(html), Optional.empty());
    }

    /**
     * A SpringerOpen alert without the lines under its title, so the parser finds no paper and only the model can.
     * The title, its real link and the rest of the mail are untouched.
     */
    public static RawEmail unparseable(Sample sample) {
        RawEmail email = sample.email();
        String html = email.htmlBody().orElseThrow()
                .replaceAll("<p><b>[^\\n]*</b></p>", "")
                .replaceAll("<p style=\"color:#666\">Journal of Infrastructure[^\\n]*</p>", "");
        return new RawEmail(email.messageId(), email.subject(), email.fromAddress(), email.receivedAt(),
                Optional.of(html), email.plainTextBody());
    }

    /** The real title reader over the recordings. */
    public static AiTitleExtractor titleExtractor() {
        return new AiTitleExtractor(PromptGenerator.fromResource(), client(new RecordedOllama()));
    }

    /** A title reader whose Ollama isn't running, counting how often it was tried. */
    public static AiTitleExtractor unreachable(AtomicInteger attempts) {
        return new AiTitleExtractor(PromptGenerator.fromResource(), client((uri, body) -> {
            attempts.incrementAndGet();
            throw new ConnectException();
        }));
    }

    static OllamaClient client(OllamaClient.Poster poster) {
        return new OllamaClient(poster, OllamaClient.DEFAULT_URL, OllamaClient.DEFAULT_MODEL);
    }

    /** The recording of the request with this body. */
    static Path recordingOf(byte[] body) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(body);
            return RECORDINGS.resolve(HexFormat.of().formatHex(digest, 0, 10) + ".json");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public HttpReply post(URI uri, byte[] body) throws IOException {
        Path recording = recordingOf(body);
        if (!Files.exists(recording)) {
            throw new AssertionError("No recorded Ollama answer for this request: prompts.txt, the slicing or the "
                    + "model changed. Record again (see src/main/java/com/litroute/ingestion/extract/readme.md).");
        }
        return new HttpReply(200, "application/json", Files.readAllBytes(recording), uri, false);
    }
}
