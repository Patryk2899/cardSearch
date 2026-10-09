package com.example.cardSearch;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipFile;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class PdfExportServiceTests {
    @TempDir Path temporary;
    final ObjectMapper mapper = new ObjectMapper();
    CardListRepository repository;
    CardImageService images;
    CardImageDownloader downloader;
    ScribusPdfRenderer renderer;
    PdfExportService exports;

    @BeforeEach
    void setup() throws Exception {
        Path database = temporary.resolve("cards.db");
        Path seed = temporary.resolve("seed.jsonl");
        Files.writeString(seed, """
                {"id":"a","name":"Mountain","image_uris":{"normal":"https://example.com/a.png"}}
                {"id":"b","name":"Sol Ring","image_uris":{"normal":"https://example.com/b.png"}}
                """);
        new CardDatabaseInitializer(mapper, database.toString(), seed.toString(), true).run(null);
        repository = new CardListRepository(database.toString());
        images = new CardImageService(database.toString(), mapper);
        downloader = mock(CardImageDownloader.class);
        renderer = mock(ScribusPdfRenderer.class);
        Path image = temporary.resolve("image.png");
        Files.write(image, new byte[]{1});
        when(downloader.download(anyString(), any())).thenReturn(image);
        doAnswer(call -> {
            var manifest = mapper.readTree(((Path) call.getArgument(0)).toFile());
            Path directory = call.getArgument(1);
            int count = (manifest.path("images").size() + 8) / 9;
            for (int sheet = 1; sheet <= count; sheet++) Files.writeString(directory.resolve("sheet-%03d.pdf".formatted(sheet)), "%PDF-1.4 test");
            return null;
        }).when(renderer).render(any(), any());
        exports = new PdfExportService(repository, downloader, renderer, mapper, database.toString());
    }

    @AfterEach void close() { exports.shutdown(); }

    private PdfExportService.ExportStatus await(String listId, String jobId) throws Exception {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            var status = exports.status(listId, jobId);
            if (List.of("READY", "FAILED").contains(status.status())) return status;
            Thread.sleep(10);
        }
        throw new AssertionError("Export did not finish");
    }

    @Test void rejectsMissingOrIncompleteLists() throws Exception {
        var missing = assertThrows(ResponseStatusException.class, () -> exports.start("missing"));
        assertEquals(HttpStatus.NOT_FOUND, missing.getStatusCode());
        var list = repository.save("Incomplete", "2 Mountain");
        images.save(list.id(), new CardImageService.SaveImagesRequest(list.cardsText(), List.of(new CardImageService.Selection(0, "0", "a:-1"))));
        var incomplete = assertThrows(ResponseStatusException.class, () -> exports.start(list.id()));
        assertEquals(HttpStatus.CONFLICT, incomplete.getStatusCode());
        verifyNoInteractions(downloader, renderer);
    }

    @Test void exportsEveryCopyInOrderIntoNineCardFilesAndCanDownloadAfterRestart() throws Exception {
        var list = repository.save("100 copies", "99 Mountain\nSol Ring");
        var selections = new ArrayList<CardImageService.Selection>();
        for (int copy = 0; copy < 99; copy++) selections.add(new CardImageService.Selection(0, String.valueOf(copy), "a:-1"));
        selections.add(new CardImageService.Selection(1, "0", "b:-1"));
        images.save(list.id(), new CardImageService.SaveImagesRequest(list.cardsText(), selections));
        var job = exports.start(list.id());
        var ready = await(list.id(), job.id());
        assertEquals("READY", ready.status());
        assertEquals(12, ready.fileCount());
        assertEquals(100, ready.cardCount());
        verify(downloader, times(2)).download(anyString(), any());
        try (var zip = new ZipFile(exports.download(list.id(), job.id()).toFile())) {
            assertEquals(12, zip.size());
            assertNotNull(zip.getEntry("sheet-001.pdf"));
            assertNotNull(zip.getEntry("sheet-012.pdf"));
        }
        var other = repository.save("Other", "Mountain");
        assertEquals(HttpStatus.NOT_FOUND, assertThrows(ResponseStatusException.class, () -> exports.status(other.id(), job.id())).getStatusCode());
        var reopened = new PdfExportService(repository, downloader, renderer, mapper, temporary.resolve("cards.db").toString());
        try { assertEquals(exports.download(list.id(), job.id()), reopened.download(list.id(), job.id())); }
        finally { reopened.shutdown(); }
    }

    @Test void failedDownloadReportsFailureWithoutProducingAPartialArchive() throws Exception {
        var list = repository.save("Failure", "Mountain");
        images.save(list.id(), new CardImageService.SaveImagesRequest(list.cardsText(), List.of(new CardImageService.Selection(0, "0", "a:-1"))));
        when(downloader.download(anyString(), any())).thenThrow(new IOException("Image unavailable"));
        var job = exports.start(list.id());
        assertEquals("FAILED", await(list.id(), job.id()).status());
        assertEquals(HttpStatus.CONFLICT, assertThrows(ResponseStatusException.class, () -> exports.download(list.id(), job.id())).getStatusCode());
        verifyNoInteractions(renderer);
    }
}
