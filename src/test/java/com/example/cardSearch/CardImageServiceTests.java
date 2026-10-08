package com.example.cardSearch;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class CardImageServiceTests {
    @TempDir Path temporary;
    ObjectMapper mapper = new ObjectMapper();
    CardListRepository repository;
    CardImageService images;
    MockMvc mvc;
    String database;

    @BeforeEach
    void setup() throws Exception {
        database = temporary.resolve("cards.db").toString();
        Path seed = temporary.resolve("seed.jsonl");
        Files.writeString(seed, """
                {"id":"mountain-a","oracle_id":"mountain","name":"Mountain","lang":"en","set":"aaa","set_name":"First set","collector_number":"1","image_uris":{"normal":"https://example.com/mountain-a.jpg","large":"https://example.com/mountain-a-large.jpg"}}
                {"id":"mountain-b","oracle_id":"mountain","name":"Mountain","lang":"en","set":"fra","set_name":"FRA set","collector_number":"393","image_uris":{"normal":"https://example.com/mountain-b.jpg"}}
                {"id":"mountain-c","oracle_id":"mountain","name":"Montagne","lang":"fr","set":"fra","collector_number":"393","image_uris":{"normal":"https://example.com/mountain-c.jpg"}}
                {"id":"ring","oracle_id":"ring-oracle","name":"Sol Ring","image_uris":{"normal":"https://example.com/ring.jpg"}}
                {"id":"delver","oracle_id":"delver-oracle","name":"Delver of Secrets // Insectile Aberration","card_faces":[{"name":"Delver of Secrets","image_uris":{"normal":"https://example.com/front.jpg"}},{"name":"Insectile Aberration","image_uris":{"normal":"https://example.com/back.jpg"}}]}
                {"id":"no-image","name":"Missing Art"}
                {"id":"percent","name":"100% Card","image_uris":{"png":"https://example.com/percent.png"}}
                {"id":"other-percent","name":"100x Card","image_uris":{"normal":"https://example.com/other.jpg"}}
                {"id":"tam","name":"Tam, the Possibility // Another Face","set":"fra","collector_number":"317","image_uris":{"normal":"https://example.com/tam.jpg"}}
                """);
        new CardDatabaseInitializer(mapper, database, seed.toString(), true).run(null);
        repository = new CardListRepository(database);
        images = new CardImageService(database, mapper);
        mvc = MockMvcBuilders.standaloneSetup(new CardListController(repository, images)).build();
    }

    private CardImageService.Selection selection(int entry, String copy, String option) {
        return new CardImageService.Selection(entry, copy, option);
    }

    private List<CardImageService.Selection> twoMountains() {
        return List.of(selection(0, "0", "mountain-a:-1"), selection(0, "1", "mountain-b:-1"));
    }

    @Test
    void listsAllPrintingsPrioritizesSpecifiedSetAndIncludesBothFaces() throws Exception {
        var list = repository.save("Images", "2 mountain (FRA) 393\nDelver of Secrets\nUnknown Card\nMissing Art");
        var state = images.load(list.id());
        assertEquals("2", state.entries().getFirst().quantity());
        assertEquals(3, state.entries().getFirst().options().size());
        assertEquals("fra", state.entries().getFirst().options().getFirst().setCode());
        assertEquals(2, state.entries().get(1).options().size());
        assertEquals("delver:0", state.entries().get(1).options().getFirst().id());
        assertEquals("delver:1", state.entries().get(1).options().get(1).id());
        assertTrue(state.entries().get(2).options().isEmpty());
        assertTrue(state.entries().get(3).options().isEmpty());
        mvc.perform(get("/api/card-lists/" + list.id() + "/image-options"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries[0].quantity").value("2"))
                .andExpect(jsonPath("$.entries[0].options.length()").value(3));
    }

    @Test
    void savesDifferentImagesForEachCopyThroughApiAndReloadsFromSqlite() throws Exception {
        var list = repository.save("Two copies", "2 Mountain (FRA) 393");
        var request = new CardImageService.SaveImagesRequest(list.cardsText(), twoMountains());
        mvc.perform(put("/api/card-lists/" + list.id() + "/image-selections")
                        .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(request)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.savedCopies").value("2"));
        var reopened = new CardImageService(database, mapper).load(list.id());
        assertEquals(twoMountains(), reopened.selections());
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             var query = connection.createStatement();
             var rows = query.executeQuery("SELECT card_id, image_uri FROM list_card_images ORDER BY copy_index")) {
            assertTrue(rows.next());
            assertEquals("mountain-a", rows.getString("card_id"));
            assertEquals("https://example.com/mountain-a.jpg", rows.getString("image_uri"));
            assertTrue(rows.next());
            assertEquals("mountain-b", rows.getString("card_id"));
        }
    }

    @Test
    void rejectsDuplicateOutOfRangeAndWrongCardChoicesWithoutLosingSavedImages() throws Exception {
        var list = repository.save("Validate", "2 Mountain");
        images.save(list.id(), new CardImageService.SaveImagesRequest(list.cardsText(), twoMountains()));
        var invalid = List.of(
                List.of(selection(0, "0", "mountain-a:-1"), selection(0, "0", "mountain-b:-1")),
                List.of(selection(0, "0", "mountain-a:-1"), selection(0, "2", "mountain-b:-1")),
                List.of(selection(0, "0", "mountain-a:-1"), selection(0, "1", "ring:-1")),
                List.of(selection(0, "0", "mountain-a:-1"), selection(0, "01", "mountain-b:-1")),
                List.of(selection(0, "0", "mountain-a:-1"), selection(9, "1", "mountain-b:-1")));
        for (var selections : invalid) {
            mvc.perform(put("/api/card-lists/" + list.id() + "/image-selections")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(mapper.writeValueAsString(new CardImageService.SaveImagesRequest(list.cardsText(), selections))))
                    .andExpect(status().isBadRequest());
            assertEquals(twoMountains(), images.load(list.id()).selections());
        }
    }

    @Test
    void savesPartialAssignmentsThroughApiAndResumesWithoutLosingChoices() throws Exception {
        var list = repository.save("In progress", "2 Mountain\nUnknown Card");
        var partial = List.of(selection(0, "0", "mountain-a:-1"));
        mvc.perform(put("/api/card-lists/" + list.id() + "/image-selections")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new CardImageService.SaveImagesRequest(list.cardsText(), partial))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.savedCopies").value("1"));
        assertEquals(partial, new CardImageService(database, mapper).load(list.id()).selections());
        images.save(list.id(), new CardImageService.SaveImagesRequest(list.cardsText(), twoMountains()));
        assertEquals(twoMountains(), images.load(list.id()).selections());
    }

    @Test
    void editingTextClearsAssignmentsRenamingPreservesThemAndDeletingCascades() throws Exception {
        var list = repository.save("Original", "2 Mountain");
        images.save(list.id(), new CardImageService.SaveImagesRequest(list.cardsText(), twoMountains()));
        repository.update(list.id(), "Renamed", list.cardsText());
        assertEquals(2, images.load(list.id()).selections().size());
        repository.update(list.id(), "Changed", "3 Mountain");
        assertTrue(images.load(list.id()).selections().isEmpty());
        var failure = assertThrows(ResponseStatusException.class, () -> images.save(list.id(),
                new CardImageService.SaveImagesRequest(list.cardsText(), twoMountains())));
        assertEquals(HttpStatus.CONFLICT, failure.getStatusCode());
        repository.update(list.id(), "Original again", "2 Mountain");
        images.save(list.id(), new CardImageService.SaveImagesRequest(list.cardsText(), twoMountains()));
        repository.delete(list.id());
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             var query = connection.createStatement();
             var rows = query.executeQuery("SELECT COUNT(*) FROM list_card_images")) {
            assertTrue(rows.next());
            assertEquals(0, rows.getLong(1));
        }
        mvc.perform(get("/api/card-lists/" + list.id() + "/image-options")).andExpect(status().isNotFound());
    }

    @Test
    void allowsMoreThan100CopiesAndKeepsDuplicateEntriesIndependent() throws Exception {
        var list = repository.save("Many copies", "120 Mountain\nMountain\nSol Ring");
        var selected = new ArrayList<CardImageService.Selection>();
        for (int i = 0; i < 120; i++) selected.add(selection(0, Integer.toString(i), "mountain-a:-1"));
        selected.add(selection(1, "0", "mountain-b:-1"));
        selected.add(selection(2, "0", "ring:-1"));
        assertEquals("122", images.save(list.id(), new CardImageService.SaveImagesRequest(list.cardsText(), selected)).savedCopies());
        assertEquals(122, images.load(list.id()).selections().size());
    }

    @Test
    void matchesNamesContainingParsedNameAtAnyPosition() throws Exception {
        var list = repository.save("Contains", "1 Tam, the Possibility   (FRA) 317\n1 the possibility\n1 Insectile Aberration");
        var entries = images.load(list.id()).entries();
        assertEquals("Tam, the Possibility", entries.getFirst().cardName());
        assertEquals("1", entries.getFirst().quantity());
        assertEquals("tam:-1", entries.getFirst().options().getFirst().id());
        assertEquals("tam:-1", entries.get(1).options().getFirst().id());
        assertEquals(2, entries.get(2).options().size());
    }

    @Test
    void containsLookupScansCoveringIndexAndFetchesFullRowsByPrimaryKey() throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             var query = connection.prepareStatement("EXPLAIN QUERY PLAN " + CardImageService.IMAGE_OPTIONS_SQL)) {
            query.setString(1, "%Mountain%");
            var plan = new StringBuilder();
            try (var rows = query.executeQuery()) {
                while (rows.next()) plan.append(rows.getString("detail")).append('\n');
            }
            assertTrue(plan.toString().contains("COVERING INDEX idx_cards_image_lookup"), plan.toString());
            assertTrue(plan.toString().contains("SEARCH cards USING INDEX sqlite_autoindex_cards_1"), plan.toString());
            assertTrue(plan.toString().contains("SEARCH cards USING INDEX idx_cards_oracle_id"), plan.toString());
        }
    }

    @Test
    void singleSlashFindsCatalogDoubleFaceNameAndPreservesSavedText() throws Exception {
        String text = "2 Delver of Secrets / Insectile Aberration";
        var list = repository.save("Single slash", text);
        var state = images.load(list.id());
        assertEquals(text, state.cardsText());
        assertEquals("Delver of Secrets // Insectile Aberration", state.entries().getFirst().cardName());
        assertEquals(2, state.entries().getFirst().options().size());
        images.save(list.id(), new CardImageService.SaveImagesRequest(text,
                List.of(selection(0, "0", "delver:0"), selection(0, "1", "delver:1"))));
        assertEquals(2, images.load(list.id()).selections().size());
    }

    @Test
    void listSummariesCountSavedCopiesAndUpdateAfterEdits() throws Exception {
        var list = repository.save("Progress", "2 Mountain");
        var other = repository.save("Other", "Sol Ring");
        assertEquals(0, repository.findAll().stream().filter(row -> row.id().equals(list.id())).findFirst().orElseThrow().assignedImageCount());
        images.save(list.id(), new CardImageService.SaveImagesRequest(list.cardsText(), List.of(selection(0, "0", "mountain-a:-1"))));
        mvc.perform(get("/api/card-lists"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == '" + list.id() + "')].assignedImageCount").value(1))
                .andExpect(jsonPath("$[?(@.id == '" + other.id() + "')].assignedImageCount").value(0));
        images.save(list.id(), new CardImageService.SaveImagesRequest(list.cardsText(), twoMountains()));
        assertEquals(2, repository.findAll().stream().filter(row -> row.id().equals(list.id())).findFirst().orElseThrow().assignedImageCount());
        repository.update(list.id(), "Renamed", list.cardsText());
        assertEquals(2, repository.findAll().stream().filter(row -> row.id().equals(list.id())).findFirst().orElseThrow().assignedImageCount());
        repository.update(list.id(), "Changed", "3 Mountain");
        assertEquals(0, repository.findAll().stream().filter(row -> row.id().equals(list.id())).findFirst().orElseThrow().assignedImageCount());
    }

    @Test
    void treatsSqlWildcardsInNamesLiterally() throws Exception {
        var list = repository.save("Percent", "1 100%");
        var options = images.load(list.id()).entries().getFirst().options();
        assertEquals(1, options.size());
        assertEquals("percent:-1", options.getFirst().id());
    }
}
