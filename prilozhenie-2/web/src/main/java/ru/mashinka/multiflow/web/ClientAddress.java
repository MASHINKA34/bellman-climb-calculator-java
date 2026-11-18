package ru.mashinka.multiflow.web;

import jakarta.servlet.http.HttpServletRequest;

// адрес того, кто открыл сайт. На сервере перед сайтом стоит nginx, и сайт слушает только
// 127.0.0.1, поэтому настоящий адрес приходит в заголовке X-Real-IP, который ставит nginx.
// Заголовку верим только от локального адреса - снаружи его подделать нельзя
public final class ClientAddress {

    private ClientAddress() {
    }

    public static String of(HttpServletRequest request) {
        String remote = request.getRemoteAddr();
        String forwarded = request.getHeader("X-Real-IP");
        boolean fromProxy = "127.0.0.1".equals(remote) || "0:0:0:0:0:0:0:1".equals(remote) || "::1".equals(remote);
        if (fromProxy && forwarded != null && !forwarded.isBlank()) {
            return forwarded.trim();
        }
        return remote;
    }
}
