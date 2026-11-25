package ru.mashinka.multiflow.web.access;

// устройство, с которого пришёл запрос: id - то, чем оно опознаётся (у Android - хэш ANDROID_ID,
// у браузера - cookie, которую поставил сервер), остальное - для списка в админке
public record DeviceInfo(String kind, String id, String name, String os, String appVersion) {

    public static final String ANDROID = "android";
    public static final String BROWSER = "browser";

    // подписи показываются в админке: длинные и странные обрезаются
    static final int MAX_TEXT = 80;

    public DeviceInfo {
        name = clean(name);
        os = clean(os);
        appVersion = clean(appVersion);
    }

    private static String clean(String text) {
        if (text == null) {
            return "";
        }
        String line = text.replaceAll("\\p{Cntrl}", " ").trim();
        return line.length() <= MAX_TEXT ? line : line.substring(0, MAX_TEXT) + "…";
    }

    // «Samsung SM-A525F, Android 13» - для истории ключа и логов; номера устройства тут нет
    public String describe() {
        String system = os.isEmpty() ? "" : ", " + os;
        // у браузера версия сайта одна на всех, в подписи она лишняя
        String version = appVersion.isEmpty() || BROWSER.equals(kind) ? "" : ", версия " + appVersion;
        return (name.isEmpty() ? kind : name) + system + version;
    }

    @Override
    public String toString() {
        return describe();
    }

    // «Safari, iPhone» по заголовку User-Agent. Точность тут не нужна: это подпись для админки
    public static DeviceInfo browser(String id, String userAgent, String siteVersion) {
        String agent = userAgent == null ? "" : userAgent;
        return new DeviceInfo(BROWSER, id, browserName(agent), systemName(agent), siteVersion);
    }

    private static String browserName(String agent) {
        if (agent.contains("YaBrowser/")) {
            return "Яндекс Браузер";
        }
        if (agent.contains("EdgA/") || agent.contains("Edg/")) {
            return "Edge";
        }
        if (agent.contains("OPR/")) {
            return "Opera";
        }
        if (agent.contains("SamsungBrowser/")) {
            return "Samsung Internet";
        }
        if (agent.contains("FxiOS/") || agent.contains("Firefox/")) {
            return "Firefox";
        }
        if (agent.contains("CriOS/") || agent.contains("Chrome/")) {
            return "Chrome";
        }
        if (agent.contains("Safari/") || agent.contains("iPhone") || agent.contains("iPad")) {
            return "Safari";
        }
        return "Браузер";
    }

    private static String systemName(String agent) {
        if (agent.contains("iPhone")) {
            return "iPhone";
        }
        if (agent.contains("iPad")) {
            return "iPad";
        }
        if (agent.contains("Android")) {
            return "Android";
        }
        if (agent.contains("Windows")) {
            return "Windows";
        }
        if (agent.contains("Mac OS X")) {
            return "macOS";
        }
        return agent.contains("Linux") ? "Linux" : "";
    }
}
