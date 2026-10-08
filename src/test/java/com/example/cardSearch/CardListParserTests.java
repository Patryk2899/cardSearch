package com.example.cardSearch;

import org.junit.jupiter.api.Test;

import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.*;

class CardListParserTests {
    @Test
    void normalizesSingleAndDoubleFaceSeparatorsWithoutChangingNamesOrQuantities() {
        String expected = "Bilbo Baggins, Burglar // Take a Glance";
        for (String separator : new String[] { " / ", "/", "  /   ", " // ", "//" }) {
            String name = "Bilbo Baggins, Burglar" + separator + "Take a Glance";
            assertEquals(expected, CardListParser.parse(name).getFirst().cardName());
            assertEquals(new CardListParser.Entry(BigInteger.TWO, expected, "FRA", "317"),
                    CardListParser.parse("2 " + name + " (FRA) 317").getFirst());
        }
    }

    @Test
    void trimsNameBeforeFirstParenthesisAndKeepsQuantitySeparate() {
        for (String line : new String[] { "1 Tam, the Possibility (FRA) 317",
                "1 Tam, the Possibility    (FRA) 317", "1 Tam, the Possibility(FRA) 317" }) {
            assertEquals(new CardListParser.Entry(BigInteger.ONE, "Tam, the Possibility", "FRA", "317"),
                    CardListParser.parse(line).getFirst());
        }
        assertEquals("Tam, the Possibility", CardListParser.parse("1 Tam, the Possibility (extra notes)")
                .getFirst().cardName());
    }

    @Test
    void parsesQuantitiesPrintingInformationAndUnnumberedNames() {
        var entries = CardListParser.parse(" 2 Mountain (FRA) 393 \r\n\r\n3x Forest\nSol Ring\n1 Fire // Ice (MH2) 290\n");
        assertEquals(4, entries.size());
        assertEquals(new CardListParser.Entry(BigInteger.TWO, "Mountain", "FRA", "393"), entries.getFirst());
        assertEquals(new CardListParser.Entry(BigInteger.valueOf(3), "Forest", null, null), entries.get(1));
        assertEquals(BigInteger.ONE, entries.get(2).quantity());
        assertEquals("Fire // Ice", entries.get(3).cardName());
    }

    @Test
    void doesNotImposeAQuantityLimitAndKeepsRepeatedEntries() {
        String huge = "999999999999999999999999999999999999";
        String text = huge + " Mountain\nMountain\nMountain";
        assertEquals(3, CardListParser.parse(text).size());
        assertEquals(new BigInteger(huge).add(BigInteger.TWO), CardListParser.cardCount(text));
    }
}
