package com.example.cardSearch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;

@Component
public class CardDatabaseInitializer implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(CardDatabaseInitializer.class);
    private static final int BATCH_SIZE = 500;
    private final ObjectMapper mapper;
    private final Path database;
    private final Path source;
    private final boolean importEnabled;

    public CardDatabaseInitializer(ObjectMapper mapper,
            @Value("${cards.database-path}") String database,
            @Value("${cards.import.path}") String source,
            @Value("${cards.import.enabled}") boolean importEnabled) {
        this.mapper = mapper;
        this.database = Path.of(database).toAbsolutePath().normalize();
        this.source = Path.of(source);
        this.importEnabled = importEnabled;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        Files.createDirectories(database.getParent());
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database)) {
            createSchema(connection);
            if (!importEnabled) return;
            String importKey = source.getFileName().toString();
            try (PreparedStatement check = connection.prepareStatement(
                    "SELECT row_count FROM seed_imports WHERE source_name = ?")) {
                check.setString(1, importKey);
                try (var result = check.executeQuery()) {
                    if (result.next()) {
                        log.info("Seed {} already imported ({} records); skipping", importKey, result.getLong(1));
                        return;
                    }
                }
            }
            if (!Files.isRegularFile(source)) {
                throw new IOException("Card seed file not found: " + source.toAbsolutePath()
                        + ". Set CARDS_IMPORT_PATH or disable seeding with CARDS_IMPORT_ENABLED=false.");
            }
            importCards(connection, importKey);
        }
    }

    private void createSchema(Connection connection) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.execute("PRAGMA busy_timeout = 30000");
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS cards (
                        id TEXT PRIMARY KEY NOT NULL,
                        oracle_id TEXT,
                        name TEXT NOT NULL COLLATE NOCASE,
                        lang TEXT,
                        set_code TEXT,
                        set_name TEXT,
                        collector_number TEXT,
                        type_line TEXT,
                        rarity TEXT,
                        mana_cost TEXT,
                        mana_value REAL,
                        oracle_text TEXT,
                        image_uri TEXT,
                        scryfall_uri TEXT,
                        raw_json TEXT NOT NULL
                    )
                    """);
            statement.execute("CREATE INDEX IF NOT EXISTS idx_cards_name ON cards(name)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_cards_set_code ON cards(set_code)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_cards_oracle_id ON cards(oracle_id)");
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS card_lists (
                        id TEXT PRIMARY KEY NOT NULL,
                        name TEXT NOT NULL,
                        cards_text TEXT NOT NULL,
                        line_count INTEGER NOT NULL,
                        created_at TEXT NOT NULL
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS seed_imports (
                        source_name TEXT PRIMARY KEY NOT NULL,
                        row_count INTEGER NOT NULL,
                        completed_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
                    )
                    """);
        }
    }

    private void importCards(Connection connection, String importKey) throws Exception {
        log.info("Importing cards from {} into {}", source.toAbsolutePath(), database);
        connection.setAutoCommit(false);
        long lineNumber = 0;
        long records = 0;
        try (var reader = Files.newBufferedReader(source, StandardCharsets.UTF_8);
             var insert = connection.prepareStatement("""
                     INSERT INTO cards (id, oracle_id, name, lang, set_code, set_name,
                         collector_number, type_line, rarity, mana_cost, mana_value,
                         oracle_text, image_uri, scryfall_uri, raw_json)
                     VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                     ON CONFLICT(id) DO NOTHING
                     """)) {
            String line;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (lineNumber == 1 && line.startsWith("\uFEFF")) line = line.substring(1);
                if (line.isBlank()) continue;
                JsonNode card = mapper.reader().with(
                        com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                        .readTree(line);
                if (card == null || !card.isObject() || !card.path("id").isTextual()
                        || card.path("id").asText().isBlank() || !card.path("name").isTextual()
                        || card.path("name").asText().isBlank()) {
                    throw new IOException("Each card must be an object with non-empty id and name strings");
                }
                String[] fields = {"id", "oracle_id", "name", "lang", "set", "set_name",
                        "collector_number", "type_line", "rarity", "mana_cost"};
                for (int i = 0; i < fields.length; i++) insert.setString(i + 1, text(card, fields[i]));
                if (card.path("cmc").isNumber()) insert.setDouble(11, card.path("cmc").asDouble());
                else insert.setNull(11, java.sql.Types.REAL);
                insert.setString(12, text(card, "oracle_text"));
                JsonNode image = card.path("image_uris");
                if (!image.isObject()) image = card.path("card_faces").path(0).path("image_uris");
                insert.setString(13, text(image, "normal"));
                insert.setString(14, text(card, "scryfall_uri"));
                insert.setString(15, line);
                insert.addBatch();
                records++;
                if (records % BATCH_SIZE == 0) {
                    insert.executeBatch();
                    insert.clearBatch();
                }
                if (records % 10000 == 0) log.info("Read {} card records", records);
            }
            insert.executeBatch();
            if (records == 0) throw new IOException("Card seed file contains no records");
            try (var mark = connection.prepareStatement(
                    "INSERT INTO seed_imports (source_name, row_count) VALUES (?, ?)")) {
                mark.setString(1, importKey);
                mark.setLong(2, records);
                mark.executeUpdate();
            }
            connection.commit();
            log.info("Completed import of {} records into {}", records, database);
        } catch (Exception failure) {
            try {
                connection.rollback();
            } catch (SQLException rollbackFailure) {
                failure.addSuppressed(rollbackFailure);
            }
            throw new IOException("Card import failed at line " + lineNumber + " of " + source
                    + "; no changes from this import were committed", failure);
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }
}
