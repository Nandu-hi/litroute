package com.litroute.ingestion.extract;

import com.litroute.ingestion.enrich.HttpReply;
import com.litroute.model.RawEmail;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.time.Duration;

/**
 * Records the Ollama answers the title tests replay, by sending the client's real alerts to the real model:
 *
 * <pre>
 * mvn -q test-compile exec:java -Dexec.classpathScope=test -Dexec.mainClass=com.litroute.ingestion.extract.TitleRecorder
 * </pre>
 *
 * Run it whenever prompts.txt, the slicing or the default model changes; a few minutes on a CPU. It saves
 * each successful reply under src/test/resources/ollama/, named by a hash of its request (delete the
 * folder's old files first), then prints the titles that passed the checks.
 */
public final class TitleRecorder {

    private TitleRecorder() {
    }

    public static void main(String[] args) throws Exception {
        Files.createDirectories(RecordedOllama.RECORDINGS);
        HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
        OllamaClient client = RecordedOllama.client((uri, body) -> record(http, uri, body));
        AiTitleExtractor extractor = new AiTitleExtractor(PromptGenerator.fromResource(), client);
        for (RawEmail email : RecordedOllama.recordedEmails()) {
            System.out.println(email.messageId());
            extractor.verifiedTitles(email).forEach(title -> System.out.println("  " + title.text()));
        }
    }

    private static HttpReply record(HttpClient http, URI uri, byte[] body) throws java.io.IOException {
        try {
            HttpResponse<byte[]> reply = http.send(HttpRequest.newBuilder(uri).timeout(Duration.ofMinutes(10))
                    .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofByteArray(body)).build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            if (reply.statusCode() == 200) {
                Files.write(RecordedOllama.recordingOf(body), reply.body());
            }
            return new HttpReply(reply.statusCode(), "application/json", reply.body(), uri, false);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new java.io.IOException(e);
        }
    }
}
