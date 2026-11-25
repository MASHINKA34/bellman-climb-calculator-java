package ru.mashinka.multiflow.web.access;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

// счётчик неудачных попыток с одного адреса: после limit неудач за окно - пауза до конца окна.
// Окно начинается с первой неудачи, удачная попытка его сбрасывает
public class AttemptLimiter {

    private static final int MAX_ADDRESSES = 10_000;

    private final int limit;
    private final long windowMillis;
    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    public AttemptLimiter(int limit, long windowMillis) {
        this.limit = limit;
        this.windowMillis = windowMillis;
    }

    public boolean blocked(String client, long now) {
        Window window = windows.get(client);
        return window != null && now - window.startedAt < windowMillis && window.failures >= limit;
    }

    // возвращает, сколько неудач уже в окне
    public int failure(String client, long now) {
        if (windows.size() > MAX_ADDRESSES) {
            windows.values().removeIf(window -> now - window.startedAt >= windowMillis);
        }
        return windows.compute(client, (key, old) -> {
            boolean fresh = old == null || now - old.startedAt >= windowMillis;
            return fresh ? new Window(now, 1) : new Window(old.startedAt, old.failures + 1);
        }).failures;
    }

    public void success(String client) {
        windows.remove(client);
    }

    private record Window(long startedAt, int failures) {
    }
}
