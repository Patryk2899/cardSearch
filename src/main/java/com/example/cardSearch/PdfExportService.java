package com.example.cardSearch;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Service
public class PdfExportService {
    private static final Logger log = LoggerFactory.getLogger(PdfExportService.class);
    public record ExportStatus(String id, String listId, String status, String message, int cardCount, int fileCount) {}
    private final Map<String, ExportStatus> jobs = new ConcurrentHashMap<>();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon().factory());
    private final CardListRepository repository;
    private final CardImageDownloader downloader;
    private final ScribusPdfRenderer renderer;
    private final ObjectMapper mapper;
    private final Path root;

    public PdfExportService(CardListRepository repository, CardImageDownloader downloader, ScribusPdfRenderer renderer,
                            ObjectMapper mapper, @Value("${cards.database-path}") String database) {
        this.repository = repository;
        this.downloader = downloader;
        this.renderer = renderer;
        this.mapper = mapper;
        root = Path.of(database).toAbsolutePath().normalize().getParent().resolve("pdf-exports");
    }

    public synchronized ExportStatus start(String listId) throws SQLException, IOException {
        for (var job : jobs.values()) {
            if (job.listId().equals(listId) && !Set.of("READY", "FAILED").contains(job.status())) return job;
        }
        var source = repository.printSource(listId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Card list not found."));
        if (source.cardCount().signum() == 0 || !source.cardCount().equals(BigInteger.valueOf(source.imageUrls().size()))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Assign and save an image for every card copy before generating PDFs.");
        }
        var job = new ExportStatus(UUID.randomUUID().toString(), listId, "QUEUED", "Waiting to generate PDFs…",
                source.imageUrls().size(), (source.imageUrls().size() + 8) / 9);
        Files.createDirectories(directory(job));
        publish(job);
        worker.submit(() -> generate(job, source));
        return job;
    }

    public ExportStatus status(String listId, String jobId) throws IOException {
        UUID.fromString(jobId);
        var job = jobs.get(jobId);
        if (job == null) {
            Path saved = root.resolve(jobId).resolve("status.json");
            if (!Files.isRegularFile(saved)) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "PDF export not found.");
            job = mapper.readValue(saved.toFile(), ExportStatus.class);
            if (!Set.of("READY", "FAILED").contains(job.status())) {
                job = new ExportStatus(job.id(), job.listId(), "FAILED", "The server restarted. Generate the PDFs again.", job.cardCount(), job.fileCount());
            }
        }
        if (!job.listId().equals(listId)) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "PDF export not found.");
        return job;
    }

    public Path download(String listId, String jobId) throws IOException {
        var job = status(listId, jobId);
        Path zip = directory(job).resolve("cards.zip");
        if (!job.status().equals("READY") || !Files.isRegularFile(zip)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The PDFs are not ready to download yet.");
        }
        return zip;
    }

    private Path directory(ExportStatus job) { return root.resolve(job.id()); }

    private void publish(ExportStatus job) throws IOException {
        Path target = directory(job).resolve("status.json");
        Path temporary = directory(job).resolve("status.tmp");
        mapper.writeValue(temporary.toFile(), job);
        Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        jobs.put(job.id(), job);
    }

    private void progress(ExportStatus job, String status, String message) throws IOException {
        publish(new ExportStatus(job.id(), job.listId(), status, message, job.cardCount(), job.fileCount()));
    }

    private void generate(ExportStatus job, CardListRepository.PrintSource source) {
        try {
            Path directory = directory(job);
            var files = new HashMap<String, Path>();
            var unique = new LinkedHashSet<>(source.imageUrls());
            // Download each distinct image once; repeated card copies reuse the cached file.
            for (String url : unique) {
                progress(job, "DOWNLOADING", "Loading card images " + (files.size() + 1) + " / " + unique.size() + "…");
                files.put(url, downloader.download(url, root.resolve("image-cache")));
            }
            copyResource("nine-cards.sla", directory);
            copyResource("render.py", directory);
            var images = source.imageUrls().stream().map(url -> files.get(url).toString()).toList();
            Path manifest = directory.resolve("manifest.json");
            mapper.writeValue(manifest.toFile(), Map.of("template", directory.resolve("nine-cards.sla").toString(),
                    "output", directory.toString(), "images", images));
            progress(job, "RENDERING", "Generating " + job.fileCount() + " PDFs using your Scribus template…");
            renderer.render(manifest, directory);
            try (var zip = new ZipOutputStream(Files.newOutputStream(directory.resolve("cards.zip")))) {
                for (int sheet = 1; sheet <= job.fileCount(); sheet++) {
                    String filename = "sheet-%03d.pdf".formatted(sheet);
                    Path pdf = directory.resolve(filename);
                    if (!Files.isRegularFile(pdf) || Files.size(pdf) == 0) throw new IOException("A generated PDF is missing. Please try again.");
                    zip.putNextEntry(new ZipEntry(filename));
                    Files.copy(pdf, zip);
                    zip.closeEntry();
                }
            }
            progress(job, "READY", job.fileCount() + " PDFs ready to download (" + job.cardCount() + " cards).");
        } catch (Exception failure) {
            log.error("PDF export {} failed", job.id(), failure);
            try {
                progress(job, "FAILED", failure instanceof IOException ? failure.getMessage() : "Could not generate PDFs. Please try again.");
            } catch (IOException persistFailure) {
                jobs.put(job.id(), new ExportStatus(job.id(), job.listId(), "FAILED", "Could not save the PDF export.", job.cardCount(), job.fileCount()));
                log.error("Could not save PDF export failure", persistFailure);
            }
        }
    }

    private static void copyResource(String name, Path directory) throws IOException {
        try (var resource = PdfExportService.class.getResourceAsStream("/pdf/" + name)) {
            if (resource == null) throw new IOException("PDF template or renderer is missing.");
            Files.copy(resource, directory.resolve(name), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    @PreDestroy
    public void shutdown() { worker.shutdownNow(); }
}
