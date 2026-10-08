package com.example.cardSearch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.sql.SQLException;
import java.util.List;

@RestController
@RequestMapping("/api/card-lists")
public class CardListController {
    private static final Logger log = LoggerFactory.getLogger(CardListController.class);
    private final CardListRepository repository;
    private final CardImageService images;

    public CardListController(CardListRepository repository, CardImageService images) {
        this.repository = repository;
        this.images = images;
    }

    public record CreateListRequest(String name, String cardsText) {}

    @PostMapping
    public ResponseEntity<CardListRepository.SavedList> create(@RequestBody CreateListRequest request)
            throws SQLException {
        var saved = repository.save(request.name(), request.cardsText());
        return ResponseEntity.created(URI.create("/api/card-lists/" + saved.id())).body(saved);
    }

    @PutMapping("/{id}")
    public CardListRepository.SavedList update(@PathVariable String id, @RequestBody CreateListRequest request)
            throws SQLException {
        return repository.update(id, request.name(), request.cardsText()).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Card list not found."));
    }

    @GetMapping
    public List<CardListRepository.ListSummary> list() throws SQLException {
        return repository.findAll();
    }

    @GetMapping("/{id}")
    public CardListRepository.SavedList get(@PathVariable String id) throws SQLException {
        return repository.findById(id).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Card list not found."));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) throws SQLException {
        if (!repository.delete(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Card list not found.");
        }
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{id}/image-options")
    public CardImageService.ImageState imageOptions(@PathVariable String id) throws SQLException {
        return images.load(id);
    }

    @PutMapping("/{id}/image-selections")
    public CardImageService.SaveImagesResult saveImages(@PathVariable String id,
            @RequestBody CardImageService.SaveImagesRequest request) throws SQLException {
        return images.save(id, request);
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ProblemDetail requestFailure(ResponseStatusException failure) {
        return ProblemDetail.forStatusAndDetail(failure.getStatusCode(), failure.getReason());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail invalidList(IllegalArgumentException failure) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, failure.getMessage());
    }

    @ExceptionHandler(SQLException.class)
    public ProblemDetail databaseFailure(SQLException failure) {
        log.error("Card list database operation failed", failure);
        return ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR,
                "Could not access saved lists. Please try again.");
    }
}
