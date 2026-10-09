package com.example.cardSearch;

import org.springframework.core.io.FileSystemResource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.io.IOException;
import java.sql.SQLException;

@RestController
@RequestMapping("/api/card-lists/{listId}/pdf-exports")
public class PdfExportController {
    private final PdfExportService exports;
    public PdfExportController(PdfExportService exports) { this.exports = exports; }

    @PostMapping
    public ResponseEntity<PdfExportService.ExportStatus> start(@PathVariable String listId) throws SQLException, IOException {
        return ResponseEntity.accepted().body(exports.start(listId));
    }

    @GetMapping("/{jobId}")
    public PdfExportService.ExportStatus status(@PathVariable String listId, @PathVariable String jobId) throws IOException {
        return exports.status(listId, jobId);
    }

    @GetMapping("/{jobId}/download")
    public ResponseEntity<FileSystemResource> download(@PathVariable String listId, @PathVariable String jobId) throws IOException {
        var file = exports.download(listId, jobId);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/zip"))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename("card-sheets-" + jobId + ".zip").build().toString())
                .cacheControl(CacheControl.noStore()).body(new FileSystemResource(file));
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ProblemDetail requestFailure(ResponseStatusException failure) {
        return ProblemDetail.forStatusAndDetail(failure.getStatusCode(), failure.getReason());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail invalidRequest(IllegalArgumentException failure) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Invalid PDF export request.");
    }

    @ExceptionHandler({SQLException.class, IOException.class})
    public ProblemDetail exportFailure(Exception failure) {
        org.slf4j.LoggerFactory.getLogger(PdfExportController.class).error("PDF export request failed", failure);
        return ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, "Could not access PDF exports. Please try again.");
    }
}
