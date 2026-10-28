package ru.mashinka.multiflow.core.log;

import java.util.Locale;
import java.util.logging.Level;
import java.util.logging.Logger;

// общие настройки логов ядра. Сами логи пишет java.util.logging: он есть и в обычной
// Java, и на Android, поэтому ядро обходится без внешних библиотек.
// Куда и в каком виде выводить строки, решает приложение: тесты, Android, сайт.
//#статика - все методы static, final и приватный конструктор: создавать объект незачем
public final class CoreLog {

    // корневой логгер всех классов ядра и приложения
    public static final String ROOT = "ru.mashinka.multiflow";

    // java.util.logging держит логгеры по слабым ссылкам: без этого поля
    // выставленный уровень мог бы пропасть вместе с логгером при сборке мусора
    private static final Logger ROOT_LOGGER = Logger.getLogger(ROOT);

    private CoreLog() {
    }

    public static Logger root() {
        return ROOT_LOGGER;
    }

    // уровень из переменной LOG_LEVEL: debug / info / warn / error
    // пустое или незнакомое значение - info, чтобы опечатка не выключила логи
    public static Level parseLevel(String name) {
        if (name == null) {
            return Level.INFO;
        }
        switch (name.trim().toLowerCase(Locale.ROOT)) {
            case "trace":
            case "all":
                return Level.ALL;
            case "debug":
            case "fine":
                return Level.FINE;
            case "warn":
            case "warning":
                return Level.WARNING;
            case "error":
            case "severe":
                return Level.SEVERE;
            default:
                return Level.INFO;
        }
    }

    public static void setLevel(Level level) {
        ROOT_LOGGER.setLevel(level);
    }

    // запись множества вершин так, как её видит пользователь: {1, 3, 5}
    // внутри ядра вершины нумеруются с нуля, на экране и в логах - с единицы
    public static String vertices(int[] zeroBased) {
        StringBuilder text = new StringBuilder("{");
        for (int i = 0; i < zeroBased.length; i++) {
            if (i > 0) {
                text.append(", ");
            }
            text.append(zeroBased[i] + 1);
        }
        return text.append('}').toString();
    }

    public static String millis(long nanos) {
        return String.format(Locale.ROOT, "%.2f ms", nanos / 1_000_000.0);
    }
}
