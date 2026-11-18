package ru.mashinka.multiflow.web.api;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import ru.mashinka.multiflow.web.ClientAddress;

// ошибки и предупреждения страницы в журнал сервера. Иначе сбой в Safari на чужом айфоне
// не увидеть: консоль браузера остаётся на телефоне. Страница шлёт только warn и error
@RestController
@RequestMapping("/api")
public class ClientLogController {

    private static final Logger log = LoggerFactory.getLogger("ui.client");

    // строка лога обрезается до ~2 КБ с указанием полного размера
    private static final int MAX_TEXT = 2048;
    // с одного адреса не больше стольких записей в минуту: открытый адрес не должен забивать журнал
    private static final int LIMIT_PER_MINUTE = 30;

    private final RequestReader reader;
    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    public ClientLogController(ObjectMapper mapper) {
        this.reader = new RequestReader(mapper);
    }

    @PostMapping("/client-log")
    public ResponseEntity<Void> report(@RequestBody(required = false) String requestBody, HttpServletRequest http) {
        String client = ClientAddress.of(http);
        if (!allow(client)) {
            return ResponseEntity.status(429).build();
        }
        JsonNode entry = reader.parse(requestBody);
        String level = text(entry, "level");
        String line = String.format("client=%s page=%s module=%s %s %s", client, cut(text(entry, "page")),
                text(entry, "module"), cut(text(entry, "message")), cut(text(entry, "context")));
        if ("error".equals(level)) {
            log.error(line);
        } else {
            log.warn(line);
        }
        return ResponseEntity.noContent().build();
    }

    private boolean allow(String client) {
        long minute = System.currentTimeMillis() / 60_000;
        Window window = windows.compute(client, (key, old) -> old == null || old.minute != minute ? new Window(minute) : old);
        int count = window.count.incrementAndGet();
        if (count == LIMIT_PER_MINUTE + 1) {
            log.warn("client={} sends too many page logs: more than {} per minute, dropping the rest of this minute",
                    client, LIMIT_PER_MINUTE);
        }
        if (windows.size() > 10_000) {
            // старые окна не нужны: чистим, чтобы карта не росла без конца
            windows.entrySet().removeIf(item -> item.getValue().minute != minute);
        }
        return count <= LIMIT_PER_MINUTE;
    }

    private static String text(JsonNode entry, String field) {
        JsonNode value = entry.get(field);
        if (value == null || value.isNull()) {
            return "-";
        }
        return value.isTextual() ? value.asText() : value.toString();
    }

    private static String cut(String value) {
        return value.length() <= MAX_TEXT ? value : value.substring(0, MAX_TEXT) + "…(" + value.length() + " chars)";
    }

    private static final class Window {
        private final long minute;
        private final AtomicInteger count = new AtomicInteger();

        private Window(long minute) {
            this.minute = minute;
        }
    }
}
