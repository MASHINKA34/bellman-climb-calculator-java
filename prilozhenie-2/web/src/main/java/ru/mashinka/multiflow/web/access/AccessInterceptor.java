package ru.mashinka.multiflow.web.access;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import ru.mashinka.multiflow.web.ClientAddress;
import ru.mashinka.multiflow.web.access.AccessDeniedException.Reason;

// расчёт на сайте - только с ключом: перед методом с отметкой @RequiresTab проверяется cookie
// браузера и вкладка. Отказ уходит исключением, ответ по нему пишет ApiErrorHandler
public class AccessInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(AccessInterceptor.class);

    private final KeyService keys;
    private final String siteVersion;

    public AccessInterceptor(KeyService keys, String siteVersion) {
        this.keys = keys;
        this.siteVersion = siteVersion;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        RequiresTab required = handler instanceof HandlerMethod method
                ? method.getMethodAnnotation(RequiresTab.class) : null;
        if (required == null) {
            return true;
        }
        DeviceInfo device = DeviceInfo.browser(AccessCookies.read(request, AccessCookies.DEVICE),
                request.getHeader(HttpHeaders.USER_AGENT), siteVersion);
        AccessKey key = keys.check(AccessCookies.read(request, AccessCookies.TOKEN), device);
        if (!key.tabs().contains(required.value())) {
            log.warn("access refused: key {} has no tab {}, client={}", key.describe(), required.value().getId(),
                    ClientAddress.of(request));
            throw new AccessDeniedException(Reason.TAB);
        }
        log.debug("access granted: key {}, tab {}", key.describe(), required.value().getId());
        return true;
    }
}
