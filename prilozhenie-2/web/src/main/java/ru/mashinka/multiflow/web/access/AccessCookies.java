package ru.mashinka.multiflow.web.access;

import java.time.Duration;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;

// cookie сайта: номер браузера (по нему браузер - «устройство») и токен введённого ключа.
// Скрипту страницы они не видны (HttpOnly) и с чужих страниц не отправляются (SameSite=Strict).
// Secure не ставится: сайт открывают по http
public final class AccessCookies {

    public static final String DEVICE = "mf_device";
    public static final String TOKEN = "mf_access";

    // дольше 400 дней браузеры cookie не хранят; срок продлевается при каждой проверке
    private static final Duration MAX_AGE = Duration.ofDays(400);

    private AccessCookies() {
    }

    public static String read(HttpServletRequest request, String name) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (name.equals(cookie.getName()) && !cookie.getValue().isBlank()) {
                return cookie.getValue();
            }
        }
        return null;
    }

    public static void write(HttpServletResponse response, String name, String value) {
        ResponseCookie cookie = ResponseCookie.from(name, value)
                .httpOnly(true).sameSite("Strict").path("/").maxAge(MAX_AGE).build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }
}
