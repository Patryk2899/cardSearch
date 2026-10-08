package com.example.cardSearch;

import java.math.BigInteger;
import java.util.List;
import java.util.regex.Pattern;

/** Parses deck-list notation without changing or rejecting the original text. */
public final class CardListParser {
    private static final Pattern QUANTITY = Pattern.compile("^(\\d+)[xX]?\\s+(.+)$");
    private static final Pattern PRINTING = Pattern.compile("^\\(([A-Za-z0-9]+)\\)(?:\\s+(\\S+))?$");
    private static final Pattern FACE_SEPARATOR = Pattern.compile("\\s*(?<!/)/{1,2}(?!/)\\s*");

    private CardListParser() {}

    public record Entry(BigInteger quantity, String cardName, String setCode, String collectorNumber) {}

    public static List<Entry> parse(String text) {
        return text.lines().filter(line -> !line.isBlank()).map(CardListParser::parseLine).toList();
    }

    public static BigInteger cardCount(String text) {
        return text.lines().filter(line -> !line.isBlank()).map(CardListParser::parseLine)
                .map(Entry::quantity).reduce(BigInteger.ZERO, BigInteger::add);
    }

    private static Entry parseLine(String line) {
        String name = line.strip();
        BigInteger quantity = BigInteger.ONE;
        var quantityMatch = QUANTITY.matcher(name);
        if (quantityMatch.matches()) {
            quantity = new BigInteger(quantityMatch.group(1));
            name = quantityMatch.group(2).strip();
        }
        int printingStart = name.indexOf('(');
        if (printingStart >= 0) {
            var printingMatch = PRINTING.matcher(name.substring(printingStart));
            name = name.substring(0, printingStart).strip();
            if (printingMatch.matches()) {
                return new Entry(quantity, normalizeName(name), printingMatch.group(1), printingMatch.group(2));
            }
        }
        return new Entry(quantity, normalizeName(name), null, null);
    }

    private static String normalizeName(String name) {
        return FACE_SEPARATOR.matcher(name).replaceAll(" // ");
    }
}
