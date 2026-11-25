package ru.mashinka.multiflow.web.access;

// отказ в доступе: код причины для страницы и текст для человека
public class AccessDeniedException extends RuntimeException {

    public enum Reason {
        // ключ ещё не вводили
        NONE("none", 401, "Введите ключ доступа."),
        // такого ключа нет
        UNKNOWN("unknown", 404, "Ключ не найден. Проверьте, что ввели его без ошибок."),
        // ключ занят другим устройством
        BUSY("busy", 409, "Ключ уже используется на другом устройстве."),
        DISABLED("disabled", 401, "Ключ отключён."),
        UNBOUND("unbound", 401, "Ключ отвязан от этого устройства."),
        DELETED("deleted", 401, "Ключ удалён."),
        // ключ есть, но эта вкладка в него не входит
        TAB("tab", 403, "Ваш ключ не даёт доступа к этой вкладке."),
        THROTTLED("throttled", 429, "Слишком много попыток. Подождите минуту.");

        private final String id;
        private final int status;
        private final String text;

        Reason(String id, int status, String text) {
            this.id = id;
            this.status = status;
            this.text = text;
        }

        public String getId() {
            return id;
        }

        public int getStatus() {
            return status;
        }

        public String getText() {
            return text;
        }
    }

    private final transient Reason reason;

    public AccessDeniedException(Reason reason) {
        super(reason.getText());
        this.reason = reason;
    }

    public Reason getReason() {
        return reason;
    }
}
