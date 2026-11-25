package ru.mashinka.multiflow.web.access;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import ru.mashinka.multiflow.web.access.AccessKey.BoundDevice;

// таблицы ключей и их истории в SQLite. Правил здесь нет, только чтение и запись
@Repository
public class KeyStore {

    private static final Logger log = LoggerFactory.getLogger(KeyStore.class);

    private static final int SCHEMA_VERSION = 1;
    // история одного ключа: старые события удаляются, чтобы база не росла
    static final int MAX_EVENTS_PER_KEY = 200;

    private static final RowMapper<AccessKey> KEY = KeyStore::readKey;

    private final JdbcTemplate jdbc;

    public KeyStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        createSchema();
    }

    private void createSchema() {
        Integer version = jdbc.queryForObject("PRAGMA user_version", Integer.class);
        log.info("key database schema version {}, needed {}", version, SCHEMA_VERSION);
        if (version != null && version > SCHEMA_VERSION) {
            // базу открыла более новая версия сайта: не портим её, лучше не запускаться
            throw new IllegalStateException("key database has schema " + version + ", this version knows only "
                    + SCHEMA_VERSION);
        }
        jdbc.execute("CREATE TABLE IF NOT EXISTS access_key ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT, code TEXT NOT NULL UNIQUE, label TEXT NOT NULL, "
                + "tabs TEXT NOT NULL, active INTEGER NOT NULL, created_at INTEGER NOT NULL, "
                + "device_kind TEXT, device_hash TEXT, token_hash TEXT, device_name TEXT, device_os TEXT, "
                + "app_version TEXT, bound_at INTEGER, last_seen INTEGER)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS key_event ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT, "
                + "key_id INTEGER NOT NULL REFERENCES access_key(id) ON DELETE CASCADE, "
                + "at INTEGER NOT NULL, type TEXT NOT NULL, detail TEXT NOT NULL)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS key_event_by_key ON key_event(key_id, id)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS admin ("
                + "id INTEGER PRIMARY KEY CHECK (id = 1), password_hash TEXT NOT NULL, created_at INTEGER NOT NULL)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS admin_session ("
                + "token_hash TEXT PRIMARY KEY, created_at INTEGER NOT NULL, expires_at INTEGER NOT NULL)");
        jdbc.execute("PRAGMA user_version = " + SCHEMA_VERSION);
    }

    // ------------------------------------------------------------------ ключи

    public List<AccessKey> findAll() {
        return jdbc.query("SELECT * FROM access_key ORDER BY id DESC", KEY);
    }

    public Optional<AccessKey> findById(long id) {
        return jdbc.query("SELECT * FROM access_key WHERE id = ?", KEY, id).stream().findFirst();
    }

    Optional<AccessKey> findByCode(String code) {
        return jdbc.query("SELECT * FROM access_key WHERE code = ?", KEY, code).stream().findFirst();
    }

    AccessKey insert(String code, String label, Set<Tab> tabs, long now) {
        jdbc.update("INSERT INTO access_key (code, label, tabs, active, created_at) VALUES (?, ?, ?, 1, ?)",
                code, label, join(tabs), now);
        return findByCode(code).orElseThrow();
    }

    void update(long id, String label, Set<Tab> tabs) {
        jdbc.update("UPDATE access_key SET label = ?, tabs = ? WHERE id = ?", label, join(tabs), id);
    }

    void setActive(long id, boolean active) {
        jdbc.update("UPDATE access_key SET active = ? WHERE id = ?", active ? 1 : 0, id);
    }

    // занять ключ устройством. Условие в самом запросе: из двух одновременных вводов пройдёт один
    boolean bind(long id, DeviceInfo device, String deviceHash, String tokenHash, long now) {
        return jdbc.update("UPDATE access_key SET device_kind = ?, device_hash = ?, token_hash = ?, device_name = ?, "
                        + "device_os = ?, app_version = ?, bound_at = ?, last_seen = ? "
                        + "WHERE id = ? AND active = 1 AND (device_hash IS NULL OR device_hash = ?)",
                device.kind(), deviceHash, tokenHash, device.name(), device.os(), device.appVersion(), now, now,
                id, deviceHash) == 1;
    }

    void unbind(long id) {
        jdbc.update("UPDATE access_key SET device_kind = NULL, device_hash = NULL, token_hash = NULL, "
                + "device_name = NULL, device_os = NULL, app_version = NULL, bound_at = NULL, last_seen = NULL "
                + "WHERE id = ?", id);
    }

    void touch(long id, DeviceInfo device, long now) {
        jdbc.update("UPDATE access_key SET device_name = ?, device_os = ?, app_version = ?, last_seen = ? "
                + "WHERE id = ?", device.name(), device.os(), device.appVersion(), now, id);
    }

    void delete(long id) {
        jdbc.update("DELETE FROM access_key WHERE id = ?", id);
    }

    // ------------------------------------------------------------------ история

    void addEvent(long keyId, String type, String detail, long now) {
        jdbc.update("INSERT INTO key_event (key_id, at, type, detail) VALUES (?, ?, ?, ?)", keyId, now, type, detail);
        jdbc.update("DELETE FROM key_event WHERE key_id = ? AND id NOT IN "
                        + "(SELECT id FROM key_event WHERE key_id = ? ORDER BY id DESC LIMIT " + MAX_EVENTS_PER_KEY + ")",
                keyId, keyId);
    }

    public List<KeyEvent> events(long keyId) {
        return jdbc.query("SELECT at, type, detail FROM key_event WHERE key_id = ? ORDER BY id DESC",
                (row, number) -> new KeyEvent(row.getLong("at"), row.getString("type"), row.getString("detail")), keyId);
    }

    // событие в истории ключа: type - код для страницы, detail - подробность для человека
    public record KeyEvent(long at, String type, String detail) {
    }

    // ------------------------------------------------------------------ вход в админку

    public Optional<String> adminPasswordHash() {
        return jdbc.queryForList("SELECT password_hash FROM admin WHERE id = 1", String.class).stream().findFirst();
    }

    // пароль задаётся один раз: второй раз вставка не пройдёт
    public boolean insertAdminPassword(String hash, long now) {
        return jdbc.update("INSERT OR IGNORE INTO admin (id, password_hash, created_at) VALUES (1, ?, ?)", hash, now) == 1;
    }

    public void insertSession(String tokenHash, long now, long expiresAt) {
        jdbc.update("DELETE FROM admin_session WHERE expires_at <= ?", now);
        jdbc.update("INSERT INTO admin_session (token_hash, created_at, expires_at) VALUES (?, ?, ?)",
                tokenHash, now, expiresAt);
    }

    public boolean sessionAlive(String tokenHash, long now) {
        return !jdbc.queryForList("SELECT 1 FROM admin_session WHERE token_hash = ? AND expires_at > ?",
                Integer.class, tokenHash, now).isEmpty();
    }

    public void deleteSession(String tokenHash) {
        jdbc.update("DELETE FROM admin_session WHERE token_hash = ?", tokenHash);
    }

    // ------------------------------------------------------------------ строки таблицы

    private static AccessKey readKey(ResultSet row, int number) throws SQLException {
        BoundDevice device = row.getString("device_hash") == null ? null : new BoundDevice(
                row.getString("device_kind"), row.getString("device_hash"), row.getString("token_hash"),
                row.getString("device_name"), row.getString("device_os"), row.getString("app_version"),
                row.getLong("bound_at"), row.getLong("last_seen"));
        return new AccessKey(row.getLong("id"), row.getString("code"), row.getString("label"),
                split(row.getString("tabs")), row.getInt("active") == 1, row.getLong("created_at"), device);
    }

    private static String join(Set<Tab> tabs) {
        return String.join(",", Tab.ids(tabs));
    }

    private static Set<Tab> split(String tabs) {
        return tabs.isEmpty() ? Set.of() : Tab.fromIds(Arrays.asList(tabs.split(",")));
    }
}
