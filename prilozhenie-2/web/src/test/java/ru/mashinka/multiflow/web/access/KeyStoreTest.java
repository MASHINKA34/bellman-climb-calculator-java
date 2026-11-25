package ru.mashinka.multiflow.web.access;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Clock;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

// база ключей переживает перезапуск сайта - значит, и выкладку новой версии
class KeyStoreTest {

    private final DatabaseConfig config = new DatabaseConfig();

    @Test
    void keysSurviveRestart() {
        String folder = "target/test-data/" + UUID.randomUUID();
        AccessKey created;
        String token;
        try (HikariDataSource source = open(folder)) {
            KeyService keys = service(source);
            created = keys.create("Пётр", EnumSet.of(Tab.FLOW, Tab.PLACEMENT));
            token = keys.activate(created.code(), phone("a"), "10.0.0.1").token();
        }
        try (HikariDataSource source = open(folder)) {
            KeyService keys = service(source);
            AccessKey loaded = keys.check(token, phone("a"));
            assertEquals(created.code(), loaded.code());
            assertEquals("Пётр", loaded.label());
            assertEquals(Set.of(Tab.FLOW, Tab.PLACEMENT), loaded.tabs());
            assertEquals(2, keys.events(loaded.id()).size());
            // номер удалённого ключа не достаётся новому: старый токен не оживёт
            keys.delete(loaded.id());
            assertTrue(keys.create("новый", EnumSet.of(Tab.FLOW)).id() > loaded.id());
        }
    }

    @Test
    void newerDatabaseIsNotTouched() {
        String folder = "target/test-data/" + UUID.randomUUID();
        try (HikariDataSource source = open(folder)) {
            service(source);
            new JdbcTemplate(source).execute("PRAGMA user_version = 99");
            IllegalStateException error = assertThrows(IllegalStateException.class, () -> service(source));
            assertTrue(error.getMessage().contains("schema 99"), error.getMessage());
        }
    }

    @Test
    void tablesAreInWalModeWithForeignKeys() {
        try (HikariDataSource source = open("target/test-data/" + UUID.randomUUID())) {
            JdbcTemplate jdbc = new JdbcTemplate(source);
            assertEquals("wal", jdbc.queryForObject("PRAGMA journal_mode", String.class));
            assertEquals(1, jdbc.queryForObject("PRAGMA foreign_keys", Integer.class));
        }
    }

    @Test
    void codesAreReadableAndDifferent() {
        Set<String> codes = new HashSet<>();
        for (int k = 0; k < 2000; k++) {
            String code = KeyCodes.generate();
            assertTrue(KeyCodes.looksValid(code), code);
            assertFalse(code.matches(".*[01IO].*"), code);
            assertTrue(codes.add(code));
        }
        assertEquals("ABCD-EFGH-JKLM", KeyCodes.display("ABCDEFGHJKLM"));
        assertEquals("ABCDEFGHJKLM", KeyCodes.normalize(" abcd-efgh jklm "));
        assertEquals("ABCD-****-****", KeyCodes.mask("ABCDEFGHJKLM"));
        assertEquals("****", KeyCodes.mask("ABC"), "короткий ввод не показывается совсем");
        assertFalse(KeyCodes.looksValid("ABCDEFGHJKL0"));
        assertEquals("", KeyCodes.normalize(null));
    }

    @Test
    void browserNamesAreRecognized() {
        assertBrowser("Safari", "iPhone", "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) Version/17.0 Safari/604.1");
        assertBrowser("Chrome", "iPhone", "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) CriOS/119.0 Safari/604.1");
        assertBrowser("Chrome", "Windows", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/142.0.0.0 Safari/537.36");
        assertBrowser("Edge", "Windows", "Mozilla/5.0 (Windows NT 10.0) Chrome/142.0.0.0 Safari/537.36 Edg/142.0");
        assertBrowser("Яндекс Браузер", "Android", "Mozilla/5.0 (Linux; Android 13) Chrome/120 YaBrowser/24.1 Mobile Safari/537.36");
        assertBrowser("Firefox", "Linux", "Mozilla/5.0 (X11; Linux x86_64; rv:130.0) Gecko/20100101 Firefox/130.0");
        assertBrowser("Браузер", "", null);
    }

    private static void assertBrowser(String name, String os, String agent) {
        DeviceInfo device = DeviceInfo.browser("id", agent, "1.5.0");
        assertEquals(name, device.name());
        assertEquals(os, device.os());
    }

    private static DeviceInfo phone(String id) {
        return new DeviceInfo(DeviceInfo.ANDROID, id.repeat(64), "Pixel", "Android 10", "1.5.0");
    }

    private HikariDataSource open(String folder) {
        return (HikariDataSource) config.dataSource(new DatabaseConfig.DataDir(config.dataDir(folder).path()));
    }

    private static KeyService service(HikariDataSource source) {
        return new KeyService(new KeyStore(new JdbcTemplate(source)), Clock.systemUTC());
    }
}
