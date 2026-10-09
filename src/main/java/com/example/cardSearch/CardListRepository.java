package com.example.cardSearch;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

import java.nio.file.Path;
import java.math.BigInteger;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class CardListRepository {
    private final String databaseUrl;

    public CardListRepository(@Value("${cards.database-path}") String databasePath) {
        databaseUrl = "jdbc:sqlite:" + Path.of(databasePath).toAbsolutePath().normalize();
    }

    public record SavedList(String id, String name, String cardsText, long lineCount, String createdAt,
                            BigInteger cardCount, List<CardListParser.Entry> entries) {}
    public record ListSummary(String id, String name, long lineCount, String createdAt, BigInteger cardCount,
                              long assignedImageCount) {}
    public record PrintSource(String name, BigInteger cardCount, List<String> imageUrls) {}

    public Optional<PrintSource> printSource(String id) throws SQLException {
        try (var connection = connect()) {
            connection.setAutoCommit(false);
            String name;
            BigInteger total;
            try (var query = connection.prepareStatement("SELECT name, cards_text FROM card_lists WHERE id = ?")) {
                query.setString(1, id);
                try (var row = query.executeQuery()) {
                    if (!row.next()) return Optional.empty();
                    name = row.getString("name");
                    total = CardListParser.cardCount(row.getString("cards_text"));
                }
            }
            var urls = new ArrayList<String>();
            try (var query = connection.prepareStatement("""
                    SELECT image_uri FROM list_card_images WHERE list_id = ?
                    ORDER BY entry_index, length(copy_index), copy_index
                    """)) {
                query.setString(1, id);
                try (var rows = query.executeQuery()) {
                    while (rows.next()) urls.add(rows.getString("image_uri"));
                }
            }
            connection.commit();
            return Optional.of(new PrintSource(name, total, List.copyOf(urls)));
        }
    }

    private static SavedList savedList(String id, String name, String text, long lineCount, String createdAt) {
        var entries = CardListParser.parse(text);
        var total = entries.stream().map(CardListParser.Entry::quantity).reduce(BigInteger.ZERO, BigInteger::add);
        return new SavedList(id, name, text, lineCount, createdAt, total, entries);
    }

    public SavedList save(String name, String cardsText) throws SQLException {
        if (cardsText == null || cardsText.isBlank()) {
            throw new IllegalArgumentException("Paste at least one card before saving.");
        }
        String title = name == null || name.isBlank() ? "Untitled list" : name.strip();
        long lineCount = cardsText.lines().filter(line -> !line.isBlank()).count();
        SavedList saved = savedList(UUID.randomUUID().toString(), title, cardsText,
                lineCount, Instant.now().toString());
        try (var connection = connect();
             var insert = connection.prepareStatement("""
                     INSERT INTO card_lists (id, name, cards_text, line_count, created_at)
                     VALUES (?, ?, ?, ?, ?)
                     """)) {
            insert.setString(1, saved.id());
            insert.setString(2, saved.name());
            insert.setString(3, saved.cardsText());
            insert.setLong(4, saved.lineCount());
            insert.setString(5, saved.createdAt());
            insert.executeUpdate();
        }
        return saved;
    }

    public Optional<SavedList> update(String id, String name, String cardsText) throws SQLException {
        if (cardsText == null || cardsText.isBlank()) {
            throw new IllegalArgumentException("Paste at least one card before saving.");
        }
        String title = name == null || name.isBlank() ? "Untitled list" : name.strip();
        long lineCount = cardsText.lines().filter(line -> !line.isBlank()).count();
        try (var connection = connect();
             var update = connection.prepareStatement("""
                     UPDATE card_lists SET name = ?, cards_text = ?, line_count = ?
                     WHERE id = ? RETURNING created_at
                     """)) {
            connection.setAutoCommit(false);
            // A different card list invalidates the old per-copy assignments.
            try (var clear = connection.prepareStatement("""
                    DELETE FROM list_card_images WHERE list_id = ? AND EXISTS (
                        SELECT 1 FROM card_lists WHERE id = ? AND cards_text <> ?)
                    """)) {
                clear.setString(1, id);
                clear.setString(2, id);
                clear.setString(3, cardsText);
                clear.executeUpdate();
            }
            update.setString(1, title);
            update.setString(2, cardsText);
            update.setLong(3, lineCount);
            update.setString(4, id);
            Optional<SavedList> saved;
            try (var row = update.executeQuery()) {
                saved = row.next() ? Optional.of(savedList(id, title, cardsText, lineCount,
                        row.getString("created_at"))) : Optional.empty();
            }
            connection.commit();
            return saved;
        }
    }

    public List<ListSummary> findAll() throws SQLException {
        var lists = new ArrayList<ListSummary>();
        try (var connection = connect();
             var statement = connection.createStatement();
             var rows = statement.executeQuery("""
                     SELECT id, name, cards_text, line_count, created_at,
                         (SELECT COUNT(*) FROM list_card_images WHERE list_id = card_lists.id) AS assigned_image_count
                     FROM card_lists
                     ORDER BY created_at DESC, id DESC
                     """)) {
            while (rows.next()) {
                lists.add(new ListSummary(rows.getString("id"), rows.getString("name"),
                        rows.getLong("line_count"), rows.getString("created_at"),
                        CardListParser.cardCount(rows.getString("cards_text")), rows.getLong("assigned_image_count")));
            }
        }
        return lists;
    }

    public Optional<SavedList> findById(String id) throws SQLException {
        try (var connection = connect();
             var query = connection.prepareStatement("SELECT * FROM card_lists WHERE id = ?")) {
            query.setString(1, id);
            try (var row = query.executeQuery()) {
                if (!row.next()) return Optional.empty();
                return Optional.of(savedList(row.getString("id"), row.getString("name"),
                        row.getString("cards_text"), row.getLong("line_count"), row.getString("created_at")));
            }
        }
    }

    public boolean delete(String id) throws SQLException {
        try (var connection = connect();
             var delete = connection.prepareStatement("DELETE FROM card_lists WHERE id = ?")) {
            delete.setString(1, id);
            return delete.executeUpdate() > 0;
        }
    }

    private Connection connect() throws SQLException {
        Connection connection = DriverManager.getConnection(databaseUrl);
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
