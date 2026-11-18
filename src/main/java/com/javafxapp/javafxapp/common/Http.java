package com.javafxapp.javafxapp.common;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

public final class Http {

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    private Http() {}

    private static HttpRequest request(String url) {
        return HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", "Lumina")
                .timeout(Duration.ofSeconds(30))
                .GET().build();
    }

    public static String getString(String url) throws IOException, InterruptedException {
        HttpResponse<String> response = CLIENT.send(request(url), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) throw failure(response.statusCode(), response.headers().firstValue("x-ratelimit-remaining").orElse(""), url);
        return response.body();
    }

    static IOException failure(int status, String rateLimitRemaining, String url) {
        // GitHub allows 60 requests an hour without a login, and answers 403 once they're used up
        if ((status == 403 || status == 429) && (rateLimitRemaining.equals("0") || status == 429))
            return new IOException("The server says too many requests were made from this connection. Wait a while and try again.");
        return new IOException("HTTP " + status + " for " + url);
    }

    public static void download(String url, Path target, Progress progress) throws IOException, InterruptedException {
        HttpResponse<InputStream> response = CLIENT.send(request(url), HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 200) {
            response.body().close();
            throw new IOException("Download failed: HTTP " + response.statusCode());
        }
        long total = response.headers().firstValueAsLong("Content-Length").orElse(-1);
        try (InputStream in = response.body(); OutputStream out = Files.newOutputStream(target)) {
            byte[] buffer = new byte[64 * 1024];
            long read = 0;
            int n;
            while ((n = in.read(buffer)) != -1) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                out.write(buffer, 0, n);
                read += n;
                progress.update(read, total);
            }
        }
    }
}
