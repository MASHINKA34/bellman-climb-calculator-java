package ru.mashinka.multiflow.web.access;

import java.util.Set;

// ключ входа. device == null - ключ ещё не занят устройством
public record AccessKey(long id, String code, String label, Set<Tab> tabs, boolean active, long createdAt,
                        BoundDevice device) {

    // устройство, занявшее ключ. Сам номер устройства и токен в базе только в виде SHA-256
    public record BoundDevice(String kind, String deviceHash, String tokenHash, String name, String os,
                              String appVersion, long boundAt, long lastSeen) {

        @Override
        public String toString() {
            return kind + " " + name;
        }
    }

    // для логов: номер и начало ключа. Весь ключ и хэши в журнал попадать не должны,
    // поэтому и toString - тот же короткий вид
    public String describe() {
        return "#" + id + " " + KeyCodes.mask(code);
    }

    @Override
    public String toString() {
        return describe();
    }
}
