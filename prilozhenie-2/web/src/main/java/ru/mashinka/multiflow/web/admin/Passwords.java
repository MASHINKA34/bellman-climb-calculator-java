package ru.mashinka.multiflow.web.admin;

import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

// хэш пароля админки: PBKDF2 с солью, средствами самой Java. Считается намеренно долго
// (доли секунды), чтобы пароль по украденной базе нельзя было быстро перебрать
final class Passwords {

    private static final String ALGORITHM = "PBKDF2WithHmacSHA256";
    private static final int ITERATIONS = 600_000;
    private static final int KEY_BITS = 256;
    private static final SecureRandom RANDOM = new SecureRandom();

    private Passwords() {
    }

    // «pbkdf2$600000$соль$хэш»: число повторов хранится рядом, чтобы его можно было потом поднять
    static String hash(String password) {
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        Base64.Encoder encoder = Base64.getEncoder();
        return "pbkdf2$" + ITERATIONS + "$" + encoder.encodeToString(salt) + "$"
                + encoder.encodeToString(derive(password, salt, ITERATIONS));
    }

    static boolean matches(String password, String stored) {
        String[] parts = stored.split("\\$");
        if (parts.length != 4 || !"pbkdf2".equals(parts[0])) {
            throw new IllegalStateException("stored admin password hash has unknown format");
        }
        Base64.Decoder decoder = Base64.getDecoder();
        byte[] expected = decoder.decode(parts[3]);
        byte[] actual = derive(password, decoder.decode(parts[2]), Integer.parseInt(parts[1]));
        return MessageDigest.isEqual(expected, actual);
    }

    private static byte[] derive(String password, byte[] salt, int iterations) {
        PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, iterations, KEY_BITS);
        try {
            return SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).getEncoded();
        } catch (GeneralSecurityException failure) {
            throw new IllegalStateException("cannot compute the password hash: " + ALGORITHM, failure);
        } finally {
            spec.clearPassword();
        }
    }
}
