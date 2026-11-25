package ru.mashinka.multiflow.web.access;

import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import ru.mashinka.multiflow.web.access.AccessDeniedException.Reason;
import ru.mashinka.multiflow.web.access.AccessKey.BoundDevice;

// правила ключей: 1 ключ = 1 устройство, вкладки - по ключу, отключённый ключ не работает.
// Всё, что меняет ключ, пишется в его историю
@Service
public class KeyService {

    private static final Logger log = LoggerFactory.getLogger(KeyService.class);

    public static final int MAX_LABEL = 100;
    // неверных ключей с одного адреса в минуту
    static final int MAX_FAILURES = 10;
    static final long FAILURE_WINDOW_MS = 60_000;
    // «последний вход» чаще не пишем: приложение проверяет ключ раз в минуту и перед расчётами
    static final long TOUCH_EVERY_MS = 30_000;

    private final KeyStore store;
    private final Clock clock;
    private final AttemptLimiter limiter = new AttemptLimiter(MAX_FAILURES, FAILURE_WINDOW_MS);

    public KeyService(KeyStore store, Clock clock) {
        this.store = store;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ устройство

    // ввод ключа. Удача - новый токен для этого устройства; прежний токен того же устройства гаснет.
    // Отказ - тоже исключение, но запись о нём в истории должна остаться, поэтому без отката
    @Transactional(noRollbackFor = AccessDeniedException.class)
    public Activation activate(String input, DeviceInfo device, String client) {
        long now = clock.millis();
        if (limiter.blocked(client, now)) {
            log.warn("key refused: too many attempts, client={}", client);
            throw new AccessDeniedException(Reason.THROTTLED);
        }
        String code = KeyCodes.normalize(input);
        Optional<AccessKey> found = KeyCodes.looksValid(code) ? store.findByCode(code) : Optional.empty();
        if (found.isEmpty()) {
            throw refuse(Reason.UNKNOWN, null, code, device, client, now);
        }
        AccessKey key = found.get();
        if (!key.active()) {
            throw refuse(Reason.DISABLED, key, code, device, client, now);
        }
        String deviceHash = Secrets.hash(device.id());
        boolean again = key.device() != null && Secrets.same(key.device().deviceHash(), deviceHash);
        if (key.device() != null && !again) {
            throw refuse(Reason.BUSY, key, code, device, client, now);
        }
        // номер ключа в токене нужен, чтобы отличить «отвязан» от «удалён»
        String token = key.id() + "." + Secrets.randomToken();
        if (!store.bind(key.id(), device, deviceHash, Secrets.hash(token), now)) {
            // между чтением и записью ключ занял кто-то другой
            throw refuse(Reason.BUSY, key, code, device, client, now);
        }
        limiter.success(client);
        store.addEvent(key.id(), again ? "entered_again" : "bound", device.describe(), now);
        log.info("key {} {} by {} \"{}\", tabs {}, client={}", key.describe(), again ? "entered again" : "bound",
                device.kind(), device.describe(), Tab.ids(key.tabs()), client);
        return new Activation(token, key.tabs());
    }

    private AccessDeniedException refuse(Reason reason, AccessKey key, String code, DeviceInfo device, String client,
                                         long now) {
        int failures = limiter.failure(client, now);
        log.warn("key refused: {}, key {}, device \"{}\", failure {} of {} this minute, client={}", reason.getId(),
                key != null ? key.describe() : KeyCodes.mask(code), device.describe(), failures, MAX_FAILURES, client);
        if (key != null) {
            store.addEvent(key.id(), "refused_" + reason.getId(), device.describe(), now);
        }
        return new AccessDeniedException(reason);
    }

    // действует ли ещё доступ устройства; заодно отмечает последний вход
    @Transactional(noRollbackFor = AccessDeniedException.class)
    public AccessKey check(String token, DeviceInfo device) {
        long now = clock.millis();
        if (token == null || token.isBlank() || device.id() == null || device.id().isBlank()) {
            throw new AccessDeniedException(Reason.NONE);
        }
        AccessKey key = store.findById(keyId(token)).orElseThrow(() -> lost(Reason.DELETED, token, device));
        BoundDevice bound = key.device();
        if (bound == null || !Secrets.same(bound.tokenHash(), Secrets.hash(token))
                || !Secrets.same(bound.deviceHash(), Secrets.hash(device.id()))) {
            throw lost(Reason.UNBOUND, token, device);
        }
        if (!key.active()) {
            throw lost(Reason.DISABLED, token, device);
        }
        boolean changed = !Objects.equals(bound.name(), device.name()) || !Objects.equals(bound.os(), device.os())
                || !Objects.equals(bound.appVersion(), device.appVersion());
        if (changed || now - bound.lastSeen() >= TOUCH_EVERY_MS) {
            store.touch(key.id(), device, now);
        }
        log.debug("key {} is valid for {}, tabs {}", key.describe(), device.describe(), Tab.ids(key.tabs()));
        return key;
    }

    private static AccessDeniedException lost(Reason reason, String token, DeviceInfo device) {
        log.info("access refused: {}, key #{}, device \"{}\"", reason.getId(), keyId(token), device.describe());
        return new AccessDeniedException(reason);
    }

    // номер ключа из токена «12.abc...»; что угодно другое - номер, которого нет
    private static long keyId(String token) {
        int dot = token.indexOf('.');
        String digits = dot > 0 ? token.substring(0, dot) : "";
        return digits.matches("\\d{1,15}") ? Long.parseLong(digits) : -1;
    }

    // ------------------------------------------------------------------ админка

    public List<AccessKey> list() {
        return store.findAll();
    }

    public List<KeyStore.KeyEvent> events(long id) {
        existing(id);
        return store.events(id);
    }

    @Transactional
    public AccessKey create(String label, Set<Tab> tabs) {
        String text = checkedLabel(label);
        Set<Tab> allowed = checkedTabs(tabs);
        long now = clock.millis();
        String code = KeyCodes.generate();
        while (store.findByCode(code).isPresent()) {
            // совпадение из 60 бит практически невозможно, но два одинаковых ключа недопустимы
            log.warn("generated key already exists, generating another");
            code = KeyCodes.generate();
        }
        AccessKey key = store.insert(code, text, allowed, now);
        store.addEvent(key.id(), "created", String.join(", ", Tab.ids(allowed)), now);
        log.info("key {} created, tabs {}", key.describe(), Tab.ids(allowed));
        return key;
    }

    @Transactional
    public AccessKey update(long id, String label, Set<Tab> tabs) {
        AccessKey key = existing(id);
        String text = checkedLabel(label);
        Set<Tab> allowed = checkedTabs(tabs);
        long now = clock.millis();
        store.update(id, text, allowed);
        if (!key.tabs().equals(allowed)) {
            store.addEvent(id, "tabs", String.join(", ", Tab.ids(allowed)), now);
        }
        if (!key.label().equals(text)) {
            store.addEvent(id, "label", text, now);
        }
        log.info("key {} updated, tabs {} -> {}, label {}", key.describe(), Tab.ids(key.tabs()), Tab.ids(allowed),
                key.label().equals(text) ? "same" : "changed");
        return existing(id);
    }

    @Transactional
    public AccessKey setActive(long id, boolean active) {
        AccessKey key = existing(id);
        if (key.active() != active) {
            store.setActive(id, active);
            store.addEvent(id, active ? "enabled" : "disabled", "", clock.millis());
        }
        log.info("key {} {}", key.describe(), active ? "enabled" : "disabled");
        return existing(id);
    }

    @Transactional
    public AccessKey unbind(long id) {
        AccessKey key = existing(id);
        if (key.device() != null) {
            store.unbind(id);
            store.addEvent(id, "unbound", key.device().name(), clock.millis());
        }
        log.info("key {} unbound from {}", key.describe(), key.device() != null ? key.device() : "nothing");
        return existing(id);
    }

    @Transactional
    public void delete(long id) {
        AccessKey key = existing(id);
        store.delete(id);
        log.info("key {} deleted, it was {} and {}", key.describe(), key.active() ? "active" : "disabled",
                key.device() != null ? "bound to " + key.device() : "free");
    }

    private AccessKey existing(long id) {
        return store.findById(id).orElseThrow(() -> new IllegalArgumentException("Ключа №" + id + " нет"));
    }

    private static String checkedLabel(String label) {
        String text = label == null ? "" : label.replaceAll("\\p{Cntrl}", " ").trim();
        if (text.length() > MAX_LABEL) {
            throw new IllegalArgumentException("Пометка длиннее " + MAX_LABEL + " знаков");
        }
        return text;
    }

    private static Set<Tab> checkedTabs(Set<Tab> tabs) {
        if (tabs.isEmpty()) {
            throw new IllegalArgumentException("Выберите хотя бы одну вкладку");
        }
        return tabs;
    }

    // токен отдаётся устройству один раз, в базе остаётся только его хэш
    public record Activation(String token, Set<Tab> tabs) {

        @Override
        public String toString() {
            return "Activation[tabs=" + Tab.ids(tabs) + "]";
        }
    }
}
