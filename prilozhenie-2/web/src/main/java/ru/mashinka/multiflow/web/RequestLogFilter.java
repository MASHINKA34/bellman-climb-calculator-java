package ru.mashinka.multiflow.web;

import java.io.IOException;
import java.util.UUID;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

// каждый запрос - строка в журнале: метод, адрес, статус, время, кто пришёл.
// rid (номер запроса) стоит во всех строках этого запроса, чтобы цепочка находилась grep'ом
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestLogFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RequestLogFilter.class);

    // матрица 10 x 10 весит меньше килобайта; всё, что больше, - не от страницы
    static final long MAX_API_BODY = 64 * 1024;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long startedAt = System.nanoTime();
        String rid = requestId(request);
        MDC.put("rid", rid);
        response.setHeader("X-Request-Id", rid);
        String path = request.getRequestURI();
        String client = ClientAddress.of(request);
        boolean api = path.startsWith("/api/");
        try {
            if (api && request.getContentLengthLong() > MAX_API_BODY) {
                log.warn("{} {} rejected: body {} bytes is larger than {} bytes, client={}", request.getMethod(), path,
                        request.getContentLengthLong(), MAX_API_BODY, client);
                response.sendError(413);
                return;
            }
            chain.doFilter(request, response);
        } catch (IOException | ServletException | RuntimeException failure) {
            log.error("{} {} failed after {} ms, client={}", request.getMethod(), path, millis(startedAt), client, failure);
            throw failure;
        } finally {
            logDone(request, response.getStatus(), path, api, client, millis(startedAt));
            MDC.remove("rid");
        }
    }

    private static void logDone(HttpServletRequest request, int status, String path, boolean api, String client,
                                long ms) {
        boolean page = "/".equals(path) || "/index.html".equals(path);
        if (status >= 500) {
            log.warn("{} {} -> {} in {} ms, client={}", request.getMethod(), path, status, ms, client);
        } else if (api || page || status >= 400) {
            // открытие страницы - вместе с браузером: по нему видно, айфон это или нет
            log.info("{} {} -> {} in {} ms, client={}{}", request.getMethod(), path, status, ms, client,
                    page ? ", agent=" + agent(request) : "");
        } else {
            log.debug("{} {} -> {} in {} ms, client={}", request.getMethod(), path, status, ms, client);
        }
    }

    // nginx передаёт свой $request_id; без nginx (запуск на компьютере) номер придумываем сами
    private static String requestId(HttpServletRequest request) {
        String fromProxy = request.getHeader("X-Request-Id");
        if (fromProxy != null && fromProxy.matches("[A-Za-z0-9-]{8,64}")) {
            return fromProxy.substring(0, Math.min(fromProxy.length(), 12));
        }
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private static String agent(HttpServletRequest request) {
        String agent = request.getHeader("User-Agent");
        if (agent == null) {
            return "-";
        }
        return agent.length() <= 200 ? agent : agent.substring(0, 200) + "…(" + agent.length() + " chars)";
    }

    private static long millis(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000;
    }
}
