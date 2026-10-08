package com.example.cardSearch;

import org.springframework.stereotype.Component;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;

@Component
public class CardImageDownloader {
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL).build();

    public Path download(String url, Path cache) throws Exception {
        Files.createDirectories(cache);
        String key = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(url.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        Path target = cache.resolve(key + ".img");
        if (Files.isRegularFile(target) && Files.size(target) > 0) return target;
        var uri = URI.create(url);
        if (!"https".equals(uri.getScheme()) && !"http".equals(uri.getScheme())) throw new IOException("Invalid card image URL.");
        Path temporary = Files.createTempFile(cache, "download-", ".tmp");
        try {
            var request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(60))
                    .header("User-Agent", "CardSearch/1.0").GET().build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofFile(temporary));
            if (response.statusCode() != 200 || Files.size(temporary) == 0
                    || !response.headers().firstValue("Content-Type").orElse("").startsWith("image/")) {
                throw new IOException("Could not download a selected card image (HTTP " + response.statusCode() + "). Please try again.");
            }
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            return target;
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
