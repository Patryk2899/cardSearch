package com.example.cardSearch;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;

import static org.junit.jupiter.api.Assertions.*;

class CardDatabaseInitializerTests {
    @TempDir
    Path temporary;

    private CardDatabaseInitializer initializer(Path source, boolean enabled) {
        return new CardDatabaseInitializer(new ObjectMapper(),
                temporary.resolve("nested/cards.db").toString(), source.toString(), enabled);
    }

    private long count(String table) throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + temporary.resolve("nested/cards.db"));
             var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            return rows.getLong(1);
        }
    }

    @Test
    void importsRealFieldsPreservesJsonAndSkipsCompletedSeedEvenIfSourceIsMissing() throws Exception {
        Path source = temporary.resolve("cards.jsonl");
        String card = """
                {"id":"one","oracle_id":"oracle-one","name":"Forest","lang":"en","set":"blb",
                "set_name":"Bloomburrow","collector_number":"280","type_line":"Basic Land — Forest",
                "rarity":"common","mana_cost":"","cmc":0,"oracle_text":"({T}: Add {G}.)",
                "image_uris":{"normal":"https://example.com/forest.jpg"},"prices":{"eur":"0.34"}}
                """.replace("\n", "").trim();
        String doubleFaced = """
                {"id":"two","name":"Front // Back","card_faces":[{"image_uris":{"normal":"https://example.com/front.jpg"}}]}
                """.trim();
        Files.writeString(source, "\uFEFF" + card + "\n\n" + doubleFaced + "\n" + card + "\n");
        initializer(source, true).run(null);
        assertEquals(2, count("cards"));
        assertEquals(1, count("seed_imports"));
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + temporary.resolve("nested/cards.db"));
             var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT * FROM cards ORDER BY id")) {
            assertTrue(rows.next());
            assertEquals("Bloomburrow", rows.getString("set_name"));
            assertEquals("Basic Land — Forest", rows.getString("type_line"));
            assertEquals(card, rows.getString("raw_json"));
            assertEquals(0.0, rows.getDouble("mana_value"));
            assertTrue(rows.next());
            assertEquals("https://example.com/front.jpg", rows.getString("image_uri"));
            assertNull(rows.getObject("mana_value"));
        }
        Files.delete(source);
        initializer(source, true).run(null);
        assertEquals(2, count("cards"));
    }

    @Test
    void rollsBackExecutedBatchesOnMalformedInputAndCanRetry() throws Exception {
        Path source = temporary.resolve("broken.jsonl");
        var contents = new StringBuilder();
        for (int i = 0; i < 501; i++) {
            contents.append("{\"id\":\"").append(i).append("\",\"name\":\"Card ").append(i).append("\"}\n");
        }
        Files.writeString(source, contents + "{broken\n");
        IOException failure = assertThrows(IOException.class, () -> initializer(source, true).run(null));
        assertTrue(failure.getMessage().contains("line 502"));
        assertEquals(0, count("cards"));
        assertEquals(0, count("seed_imports"));
        Files.writeString(source, contents);
        initializer(source, true).run(null);
        assertEquals(501, count("cards"));
    }

    @Test
    void rejectsMissingRequiredFieldsAndReportsMissingFiles() throws Exception {
        Path source = temporary.resolve("invalid.jsonl");
        assertThrows(IOException.class, () -> initializer(source, true).run(null));
        Files.writeString(source, "{\"name\":\"Missing id\"}\n");
        assertThrows(IOException.class, () -> initializer(source, true).run(null));
        assertEquals(0, count("cards"));
        assertEquals(0, count("seed_imports"));
    }

    @Test
    void canCreateEmptyDatabaseWithoutSeedFileWhenImportIsDisabled() throws Exception {
        initializer(temporary.resolve("missing.jsonl"), false).run(null);
        assertEquals(0, count("cards"));
    }
}
