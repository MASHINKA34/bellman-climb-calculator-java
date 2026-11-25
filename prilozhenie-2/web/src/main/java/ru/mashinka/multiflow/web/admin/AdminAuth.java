package ru.mashinka.multiflow.web.admin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import ru.mashinka.multiflow.web.access.AttemptLimiter;
import ru.mashinka.multiflow.web.access.DatabaseConfig.DataDir;
import ru.mashinka.multiflow.web.access.KeyStore;
import ru.mashinka.multiflow.web.access.Secrets;

// вход в админку. Пароль придумывает владелец при первом открытии страницы: для этого нужен
// одноразовый код из файла на сервере (он же - в ссылке), иначе админку занял бы первый, кто её открыл.
// Забыт пароль - строка таблицы admin удаляется на сервере, после перезапуска появляется новый код
@Service
public class AdminAuth {

    private static final Logger log = LoggerFactory.getLogger(AdminAuth.class);

    static final String SETUP_FILE = "admin-setup.txt";
    static final int MIN_PASSWORD = 8;
    static final int MAX_PASSWORD = 200;
    static final Duration SESSION = Duration.ofDays(7);
    private static final int MAX_FAILURES = 5;
    private static final long FAILURE_WINDOW_MS = Duration.ofMinutes(15).toMillis();

    private final KeyStore store;
    private final Clock clock;
    private final Path setupFile;
    private final AttemptLimiter limiter = new AttemptLimiter(MAX_FAILURES, FAILURE_WINDOW_MS);

    public AdminAuth(KeyStore store, Clock clock, DataDir dataDir) {
        this.store = store;
        this.clock = clock;
        this.setupFile = dataDir.path().resolve(SETUP_FILE);
    }

    // пароля ещё нет - готовим одноразовый код; сам код в журнал не пишется
    @EventListener(ApplicationReadyEvent.class)
    public void prepareSetup() {
        try {
            if (!needsSetup()) {
                if (Files.deleteIfExists(setupFile)) {
                    log.warn("stale admin setup code removed: the password is already set");
                }
                log.info("admin password is set");
                return;
            }
            if (Files.exists(setupFile)) {
                log.info("admin password is not set yet, the one-time code is still in {}", setupFile);
                return;
            }
            Files.writeString(setupFile, Secrets.randomToken());
            if (Files.getFileStore(setupFile).supportsFileAttributeView("posix")) {
                Files.setPosixFilePermissions(setupFile, PosixFilePermissions.fromString("rw-------"));
            }
            log.info("admin password is not set: the one-time code for the first visit is written to {}", setupFile);
        } catch (IOException failure) {
            // сайт работает и без админки; задать пароль не получится, пока код не записан
            log.error("cannot write the admin setup code to {}", setupFile, failure);
        }
    }

    public boolean needsSetup() {
        return store.adminPasswordHash().isEmpty();
    }

    // первый вход: код из ссылки и новый пароль
    String setup(String code, String password, String client) {
        long now = checkAttempts(client);
        if (!needsSetup()) {
            log.warn("admin setup refused: the password is already set, client={}", client);
            throw new AdminDeniedException(409, "Пароль уже задан. Войдите с ним.");
        }
        Optional<String> expected = readSetupCode();
        if (expected.isEmpty() || !Secrets.same(expected.get(), code)) {
            int failures = limiter.failure(client, now);
            log.warn("admin setup refused: wrong one-time code (file {}), failure {} of {}, client={}",
                    expected.isPresent() ? "present" : "missing", failures, MAX_FAILURES, client);
            throw new AdminDeniedException(403, "Ссылка не подходит. Откройте одноразовую ссылку целиком.");
        }
        if (password.length() < MIN_PASSWORD || password.length() > MAX_PASSWORD) {
            throw new IllegalArgumentException("Пароль должен быть от " + MIN_PASSWORD + " до " + MAX_PASSWORD + " знаков");
        }
        if (!store.insertAdminPassword(Passwords.hash(password), now)) {
            log.warn("admin setup refused: the password was set at the same moment, client={}", client);
            throw new AdminDeniedException(409, "Пароль уже задан. Войдите с ним.");
        }
        try {
            Files.deleteIfExists(setupFile);
        } catch (IOException failure) {
            // код больше не действует: пароль уже есть, setup второй раз не пройдёт
            log.error("cannot delete the used admin setup code {}", setupFile, failure);
        }
        limiter.success(client);
        log.info("admin password is set, client={}", client);
        return openSession(now);
    }

    String login(String password, String client) {
        long now = checkAttempts(client);
        long startedAt = System.nanoTime();
        Optional<String> hash = store.adminPasswordHash();
        boolean right = hash.isPresent() && password.length() <= MAX_PASSWORD && Passwords.matches(password, hash.get());
        long ms = (System.nanoTime() - startedAt) / 1_000_000;
        if (!right) {
            int failures = limiter.failure(client, now);
            log.warn("admin login refused: {}, failure {} of {}, {} ms, client={}",
                    hash.isPresent() ? "wrong password" : "the password is not set", failures, MAX_FAILURES, ms, client);
            throw new AdminDeniedException(403, "Неверный пароль.");
        }
        limiter.success(client);
        log.info("admin logged in, {} ms, client={}", ms, client);
        return openSession(now);
    }

    void logout(String token, String client) {
        if (token != null) {
            store.deleteSession(Secrets.hash(token));
        }
        log.info("admin logged out, client={}", client);
    }

    boolean authenticated(String token) {
        return token != null && store.sessionAlive(Secrets.hash(token), clock.millis());
    }

    private String openSession(long now) {
        String token = Secrets.randomToken();
        store.insertSession(Secrets.hash(token), now, now + SESSION.toMillis());
        return token;
    }

    private long checkAttempts(String client) {
        long now = clock.millis();
        if (limiter.blocked(client, now)) {
            log.warn("admin login refused: too many failures, client={}", client);
            throw new AdminDeniedException(429, "Слишком много неверных попыток. Подождите 15 минут.");
        }
        return now;
    }

    private Optional<String> readSetupCode() {
        try {
            return Files.exists(setupFile) ? Optional.of(Files.readString(setupFile).trim()) : Optional.empty();
        } catch (IOException failure) {
            log.error("cannot read the admin setup code {}", setupFile, failure);
            return Optional.empty();
        }
    }

    // админка - только по https: nginx сообщает протокол заголовком X-Forwarded-Proto, а снаружи
    // сайт слушает только 127.0.0.1. Запрос с самого компьютера мимо nginx (проверка при
    // разработке) тоже годится - по сети он не идёт
    static boolean overSecureChannel(HttpServletRequest request) {
        if (request.isSecure()) {
            return true;
        }
        String remote = request.getRemoteAddr();
        boolean local = "127.0.0.1".equals(remote) || "0:0:0:0:0:0:0:1".equals(remote) || "::1".equals(remote);
        String proto = request.getHeader("X-Forwarded-Proto");
        return local && (proto == null || "https".equals(proto));
    }
}
