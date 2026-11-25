package ru.mashinka.multiflow.web.access;

import java.security.SecureRandom;
import java.util.Locale;

// вид ключа входа: ABCD-EFGH-JKLM. В алфавите нет похожих знаков (0 и O, 1 и I),
// 32 знака в 12 позициях - 60 бит, подобрать перебором нельзя.
// В базе ключ хранится без дефисов
public final class KeyCodes {

    static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    static final int LENGTH = 12;
    private static final int GROUP = 4;

    private static final SecureRandom RANDOM = new SecureRandom();

    private KeyCodes() {
    }

    static String generate() {
        StringBuilder code = new StringBuilder(LENGTH);
        for (int k = 0; k < LENGTH; k++) {
            code.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return code.toString();
    }

    // что ввёл человек -> вид в базе: регистр, пробелы и дефисы не важны
    static String normalize(String input) {
        if (input == null) {
            return "";
        }
        StringBuilder code = new StringBuilder(LENGTH);
        for (char symbol : input.toUpperCase(Locale.ROOT).toCharArray()) {
            if (Character.isLetterOrDigit(symbol)) {
                code.append(symbol);
            }
        }
        return code.toString();
    }

    static boolean looksValid(String code) {
        return code.length() == LENGTH && code.chars().allMatch(symbol -> ALPHABET.indexOf(symbol) >= 0);
    }

    public static String display(String code) {
        StringBuilder text = new StringBuilder(LENGTH + 2);
        for (int k = 0; k < code.length(); k++) {
            if (k > 0 && k % GROUP == 0) {
                text.append('-');
            }
            text.append(code.charAt(k));
        }
        return text.toString();
    }

    // для логов: по первым знакам ключ можно узнать в списке админки, но нельзя им воспользоваться
    public static String mask(String code) {
        return code.length() < LENGTH ? "****" : code.substring(0, GROUP) + "-****-****";
    }
}
