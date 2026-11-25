package ru.mashinka.multiflow.web.admin;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.servlet.HandlerInterceptor;

import ru.mashinka.multiflow.web.ClientAddress;

// сама страница админки (static/admin): секретов в ней нет, но по http её лучше не открывать,
// чтобы пароль случайно не ввели туда, где его видно
public class AdminPageInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(AdminPageInterceptor.class);

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {
        if (!AdminAuth.overSecureChannel(request)) {
            log.warn("admin page refused: not https, client={}", ClientAddress.of(request));
            response.setStatus(403);
            response.setContentType("text/plain; charset=utf-8");
            response.getOutputStream().write("Админка открывается только по https."
                    .getBytes(StandardCharsets.UTF_8));
            return false;
        }
        // только свои скрипты и стили, в чужую рамку страницу не вставить
        response.setHeader("Content-Security-Policy", "default-src 'self'; frame-ancestors 'none'; base-uri 'none'");
        response.setHeader("X-Frame-Options", "DENY");
        response.setHeader("Referrer-Policy", "no-referrer");
        return true;
    }
}
