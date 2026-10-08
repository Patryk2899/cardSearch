package com.example.cardSearch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.*;

@Service
public class CardImageService {
    // Scan only the compact covering index for contains matching, then fetch matching rows by ID.
    // MATERIALIZED keeps the name search shared between direct and related-language matches.
    static final String IMAGE_OPTIONS_SQL = """
            WITH matches AS MATERIALIZED (
                SELECT id, oracle_id FROM cards INDEXED BY idx_cards_image_lookup
                WHERE name LIKE ? ESCAPE '!'
            )
            SELECT id, name, set_code, set_name, collector_number, lang, raw_json FROM cards
            WHERE id IN (
                SELECT id FROM matches
                UNION
                SELECT id FROM cards WHERE oracle_id IN (SELECT oracle_id FROM matches)
            )
            ORDER BY set_code, collector_number, lang, id
            """;
    private final String databaseUrl;
    private final ObjectMapper mapper;

    public CardImageService(@Value("${cards.database-path}") String databasePath, ObjectMapper mapper) {
        databaseUrl = "jdbc:sqlite:" + Path.of(databasePath).toAbsolutePath().normalize();
        this.mapper = mapper;
    }

    public record ImageOption(String id, String cardId, int faceIndex, String imageUrl,
                              String name, String setCode, String setName, String collectorNumber, String lang) {}
    public record EntryOptions(int entryIndex, String cardName, String quantity, List<ImageOption> options) {}
    public record Selection(int entryIndex, String copyIndex, String optionId) {}
    public record ImageState(String listId, String name, String cardsText,
                             List<EntryOptions> entries, List<Selection> selections) {}
    public record SaveImagesRequest(String cardsText, List<Selection> selections) {}
    public record SaveImagesResult(String listId, String savedCopies) {}

    public ImageState load(String listId) throws SQLException {
        try (var connection = connect()) {
            connection.setAutoCommit(false);
            var state = load(connection, listId);
            connection.commit();
            return state;
        }
    }

    private ImageState load(Connection connection, String listId) throws SQLException {
        String name;
        String text;
        try (var query = connection.prepareStatement("SELECT name, cards_text FROM card_lists WHERE id = ?")) {
            query.setString(1, listId);
            try (var row = query.executeQuery()) {
                if (!row.next()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Card list not found.");
                name = row.getString("name");
                text = row.getString("cards_text");
            }
        }
        var entries = new ArrayList<EntryOptions>();
        var cache = new HashMap<String, List<ImageOption>>();
        var parsed = CardListParser.parse(text);
        for (int i = 0; i < parsed.size(); i++) {
            var entry = parsed.get(i);
            var options = cache.get(entry.cardName());
            if (options == null) {
                options = findOptions(connection, entry.cardName());
                cache.put(entry.cardName(), options);
            }
            // Suggested printing first, while keeping all other available images.
            var ordered = new ArrayList<>(options);
            ordered.sort(Comparator.comparing(option -> !(
                    entry.setCode() != null && entry.setCode().equalsIgnoreCase(option.setCode())
                    && (entry.collectorNumber() == null || entry.collectorNumber().equals(option.collectorNumber())))));
            entries.add(new EntryOptions(i, entry.cardName(), entry.quantity().toString(), ordered));
        }
        var selections = new ArrayList<Selection>();
        try (var query = connection.prepareStatement("""
                SELECT entry_index, copy_index, card_id, face_index FROM list_card_images WHERE list_id = ?
                ORDER BY entry_index, length(copy_index), copy_index
                """)) {
            query.setString(1, listId);
            try (var rows = query.executeQuery()) {
                while (rows.next()) selections.add(new Selection(rows.getInt("entry_index"),
                        rows.getString("copy_index"), rows.getString("card_id") + ":" + rows.getInt("face_index")));
            }
        }
        return new ImageState(listId, name, text, entries, selections);
    }

    private List<ImageOption> findOptions(Connection connection, String name) throws SQLException {
        var options = new ArrayList<ImageOption>();
        // Match the literal name anywhere, including either face of a multi-face card.
        try (var query = connection.prepareStatement(IMAGE_OPTIONS_SQL)) {
            String contains = "%" + name.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
            query.setString(1, contains);
            try (var rows = query.executeQuery()) {
                while (rows.next()) {
                    JsonNode card;
                    try {
                        card = mapper.readTree(rows.getString("raw_json"));
                    } catch (IOException failure) {
                        throw new SQLException("Could not read card image data", failure);
                    }
                    String id = rows.getString("id");
                    String cardName = rows.getString("name");
                    String set = rows.getString("set_code");
                    String setName = rows.getString("set_name");
                    String number = rows.getString("collector_number");
                    String lang = rows.getString("lang");
                    String image = imageUrl(card.path("image_uris"));
                    if (image != null) options.add(new ImageOption(id + ":-1", id, -1, image,
                            cardName, set, setName, number, lang));
                    var faces = card.path("card_faces");
                    for (int face = 0; face < faces.size(); face++) {
                        image = imageUrl(faces.path(face).path("image_uris"));
                        if (image != null) options.add(new ImageOption(id + ":" + face, id, face, image,
                                faces.path(face).path("name").asText(cardName), set, setName, number, lang));
                    }
                }
            }
        }
        return options;
    }

    private static String imageUrl(JsonNode images) {
        for (String format : List.of("normal", "large", "png", "small")) {
            String url = images.path(format).asText("");
            if (url.startsWith("https://") || url.startsWith("http://")) return url;
        }
        return null;
    }

    public SaveImagesResult save(String listId, SaveImagesRequest request) throws SQLException {
        if (request.selections() == null) throw new IllegalArgumentException("Provide the assigned card images.");
        try (var connection = connect()) {
            connection.setAutoCommit(false);
            try {
                // Obtain the write lock before reading, to prevent a concurrent list edit during validation.
                try (var lock = connection.prepareStatement("UPDATE card_lists SET id = id WHERE id = ?")) {
                    lock.setString(1, listId);
                    lock.executeUpdate();
                }
                var state = load(connection, listId);
                if (!state.cardsText().equals(request.cardsText())) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "This list has changed. Reopen image selection and try again.");
                }
                BigInteger total = state.entries().stream().map(entry -> new BigInteger(entry.quantity()))
                        .reduce(BigInteger.ZERO, BigInteger::add);
                if (BigInteger.valueOf(request.selections().size()).compareTo(total) > 0) {
                    throw new IllegalArgumentException("Cannot assign more images than card copies.");
                }
                var allowed = new ArrayList<Map<String, ImageOption>>();
                for (var entry : state.entries()) {
                    var choices = new HashMap<String, ImageOption>();
                    for (var option : entry.options()) choices.put(option.id(), option);
                    allowed.add(choices);
                }
                var seen = new HashSet<String>();
                try (var clear = connection.prepareStatement("DELETE FROM list_card_images WHERE list_id = ?")) {
                    clear.setString(1, listId);
                    clear.executeUpdate();
                }
                try (var insert = connection.prepareStatement("""
                        INSERT INTO list_card_images (list_id, entry_index, copy_index, card_id, face_index, image_uri)
                        VALUES (?, ?, ?, ?, ?, ?)
                        """)) {
                    int batch = 0;
                    for (var selected : request.selections()) {
                        if (selected == null || selected.entryIndex() < 0 || selected.entryIndex() >= state.entries().size()
                                || selected.copyIndex() == null || !selected.copyIndex().matches("0|[1-9][0-9]*")) {
                            throw new IllegalArgumentException("Invalid card copy selection.");
                        }
                        var copy = new BigInteger(selected.copyIndex());
                        var entry = state.entries().get(selected.entryIndex());
                        var option = allowed.get(selected.entryIndex()).get(selected.optionId());
                        if (copy.compareTo(new BigInteger(entry.quantity())) >= 0 || option == null
                                || !seen.add(selected.entryIndex() + ":" + copy)) {
                            throw new IllegalArgumentException("Invalid or duplicate card image selection.");
                        }
                        insert.setString(1, listId);
                        insert.setInt(2, selected.entryIndex());
                        insert.setString(3, selected.copyIndex());
                        insert.setString(4, option.cardId());
                        insert.setInt(5, option.faceIndex());
                        insert.setString(6, option.imageUrl());
                        insert.addBatch();
                        if (++batch % 500 == 0) { insert.executeBatch(); insert.clearBatch(); }
                    }
                    insert.executeBatch();
                }
                connection.commit();
                return new SaveImagesResult(listId, Integer.toString(request.selections().size()));
            } catch (SQLException | RuntimeException failure) {
                try { connection.rollback(); } catch (SQLException rollback) { failure.addSuppressed(rollback); }
                throw failure;
            }
        }
    }

    private Connection connect() throws SQLException {
        var connection = DriverManager.getConnection(databaseUrl);
        try (var statement = connection.createStatement()) {
            statement.execute("PRAGMA busy_timeout = 30000");
            statement.execute("PRAGMA foreign_keys = ON");
        } catch (SQLException failure) {
            connection.close();
            throw failure;
        }
        return connection;
    }
}
