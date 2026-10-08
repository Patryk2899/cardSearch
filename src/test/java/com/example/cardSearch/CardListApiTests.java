package com.example.cardSearch;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "cards.import.enabled=false")
@AutoConfigureMockMvc
class CardListApiTests {
    @TempDir
    static Path temporary;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("cards.database-path", () -> temporary.resolve("cards.db").toString());
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;

    @Test
    void savesMoreThan100LinesPreservesTextAndReadsItFromAnotherRepository() throws Exception {
        String text = "1 Sol Ring\r\n\r\n" + "10 Forest\r\n".repeat(250)
                + "Unknown card <script>alert('x')</script>\r\nO'Brien's card";
        var result = mvc.perform(post("/api/card-lists").contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("name", "  Commander list  ", "cardsText", text))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Commander list"))
                .andExpect(jsonPath("$.lineCount").value(253))
                .andExpect(jsonPath("$.cardCount").value(2503))
                .andExpect(jsonPath("$.cardsText").value(text))
                .andReturn();
        String id = mapper.readTree(result.getResponse().getContentAsString()).path("id").asText();
        assertEquals("/api/card-lists/" + id, result.getResponse().getHeader("Location"));
        mvc.perform(get("/api/card-lists/" + id))
                .andExpect(status().isOk()).andExpect(jsonPath("$.cardsText").value(text));
        mvc.perform(get("/api/card-lists"))
                .andExpect(status().isOk()).andExpect(jsonPath("$[?(@.id == '" + id + "')].name").value("Commander list"))
                .andExpect(jsonPath("$[0].cardsText").doesNotExist());
        var reopened = new CardListRepository(temporary.resolve("cards.db").toString());
        assertEquals(text, reopened.findById(id).orElseThrow().cardsText());
    }

    @Test
    void recognizesQuantitiesAndPrintingDetailsAndDeletesOnlySelectedList() throws Exception {
        String text = "2 Mountain (FRA) 393\n1 Sol Ring\nMountain";
        var result = mvc.perform(post("/api/card-lists").contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("name", "Quantity test", "cardsText", text))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.cardCount").value(4))
                .andExpect(jsonPath("$.entries[0].quantity").value(2))
                .andExpect(jsonPath("$.entries[0].cardName").value("Mountain"))
                .andExpect(jsonPath("$.entries[0].setCode").value("FRA"))
                .andExpect(jsonPath("$.entries[0].collectorNumber").value("393"))
                .andReturn();
        String id = mapper.readTree(result.getResponse().getContentAsString()).path("id").asText();
        var repository = new CardListRepository(temporary.resolve("cards.db").toString());
        var other = repository.save("Keep this list", "5 Forest");
        assertEquals(java.math.BigInteger.valueOf(4), repository.findById(id).orElseThrow().cardCount());
        mvc.perform(get("/api/card-lists"))
                .andExpect(jsonPath("$[?(@.id == '" + id + "')].cardCount").value(4));
        mvc.perform(delete("/api/card-lists/" + id)).andExpect(status().isNoContent());
        mvc.perform(get("/api/card-lists/" + id)).andExpect(status().isNotFound());
        mvc.perform(delete("/api/card-lists/" + id)).andExpect(status().isNotFound());
        assertTrue(repository.findById(id).isEmpty());
        assertTrue(repository.findById(other.id()).isPresent());
    }

    @Test
    void editsExistingListWithoutDuplicatingItAndRecalculatesQuantities() throws Exception {
        var repository = new CardListRepository(temporary.resolve("cards.db").toString());
        var original = repository.save("Original", "2 Mountain (FRA) 393");
        var unrelated = repository.save("Other list", "Forest");
        int before = repository.findAll().size();
        String editedText = "3 Mountain (FRA) 393\r\n\r\n" + "2 Forest\r\n".repeat(300);
        mvc.perform(put("/api/card-lists/" + original.id()).contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("name", "Renamed list", "cardsText", editedText))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(original.id()))
                .andExpect(jsonPath("$.name").value("Renamed list"))
                .andExpect(jsonPath("$.createdAt").value(original.createdAt()))
                .andExpect(jsonPath("$.cardsText").value(editedText))
                .andExpect(jsonPath("$.lineCount").value(301))
                .andExpect(jsonPath("$.cardCount").value(603))
                .andExpect(jsonPath("$.entries[0].quantity").value(3));
        var reopened = new CardListRepository(temporary.resolve("cards.db").toString());
        assertEquals(before, reopened.findAll().size());
        assertEquals(editedText, reopened.findById(original.id()).orElseThrow().cardsText());
        assertEquals("Forest", reopened.findById(unrelated.id()).orElseThrow().cardsText());
        mvc.perform(get("/api/card-lists/" + original.id()))
                .andExpect(jsonPath("$.name").value("Renamed list"))
                .andExpect(jsonPath("$.cardCount").value(603));
    }

    @Test
    void invalidEditPreservesOriginalAndMissingListDoesNotGetCreated() throws Exception {
        var repository = new CardListRepository(temporary.resolve("cards.db").toString());
        var original = repository.save("Keep original", "5 Forest");
        int before = repository.findAll().size();
        for (String body : new String[] {"{}", "{\"cardsText\":null}", "{\"cardsText\":\" \\n\"}"}) {
            mvc.perform(put("/api/card-lists/" + original.id()).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest());
        }
        assertEquals(original, repository.findById(original.id()).orElseThrow());
        mvc.perform(put("/api/card-lists/missing-list").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Missing\",\"cardsText\":\"2 Mountain\"}"))
                .andExpect(status().isNotFound());
        assertEquals(before, repository.findAll().size());
        mvc.perform(put("/api/card-lists/" + original.id()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\" \",\"cardsText\":\"Mountain\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.name").value("Untitled list"));
    }

    @Test
    void supportsUnnamedListsAndNamesThatContainSqlCharacters() throws Exception {
        mvc.perform(post("/api/card-lists").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cardsText\":\"Forest\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.name").value("Untitled list"));
        String title = "'); DROP TABLE card_lists; --";
        mvc.perform(post("/api/card-lists").contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("name", title, "cardsText", "Sol Ring"))))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.name").value(title));
        mvc.perform(get("/api/card-lists")).andExpect(status().isOk());
    }

    @Test
    void rejectsEmptyInputWithoutInsertingRowsAndReturns404ForUnknownIds() throws Exception {
        var repository = new CardListRepository(temporary.resolve("cards.db").toString());
        int before = repository.findAll().size();
        for (String body : new String[] {"{}", "{\"cardsText\":null}", "{\"cardsText\":\" \\r\\n\\t\"}"}) {
            mvc.perform(post("/api/card-lists").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest());
        }
        assertEquals(before, repository.findAll().size());
        mvc.perform(get("/api/card-lists/does-not-exist")).andExpect(status().isNotFound());
    }
}
