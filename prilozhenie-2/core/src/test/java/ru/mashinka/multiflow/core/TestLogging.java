package ru.mashinka.multiflow.core;

import java.util.logging.Level;

import ru.mashinka.multiflow.core.log.CoreLog;

// уровень логов в тестах берётся из LOG_LEVEL: по умолчанию info, для разбора - debug
public final class TestLogging {

    private TestLogging() {
    }

    public static void init() {
        CoreLog.setLevel(CoreLog.parseLevel(System.getenv("LOG_LEVEL")));
    }

    // массовые прогоны решают сотни сетей: на время прогона оставляем только ошибки
    public static Level mute() {
        Level previous = CoreLog.root().getLevel();
        CoreLog.setLevel(Level.SEVERE);
        return previous;
    }

    public static void restore(Level previous) {
        CoreLog.setLevel(previous);
    }
}
