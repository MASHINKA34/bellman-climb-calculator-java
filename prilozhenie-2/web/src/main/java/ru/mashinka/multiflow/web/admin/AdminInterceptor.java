package ru.mashinka.multiflow.web.admin;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import ru.mashinka.multiflow.web.ClientAddress;
import ru.mashinka.multiflow.web.access.AccessCookies;

// проверки админки: только https, для изменений - заголовок со своей страницы (чужая страница
// его не поставит), для ключей - вход по паролю
public class AdminInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(AdminInterceptor.class);

    public static final String COOKIE = "mf_admin";
    static final String HEADER = "X-Admin";

    private final AdminAuth auth;

    public AdminInterceptor(AdminAuth auth) {
        this.auth = auth;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod method) || !AdminController.class.equals(method.getBeanType())) {
            return true;
        }
        if (!AdminAuth.overSecureChannel(request)) {
            log.warn("admin request refused: not https, client={}", ClientAddress.of(request));
            throw new AdminDeniedException(403, "Админка открывается только по https.");
        }
        if (!HttpMethod.GET.matches(request.getMethod()) && !"1".equals(request.getHeader(HEADER))) {
            log.warn("admin request refused: no {} header, client={}", HEADER, ClientAddress.of(request));
            throw new AdminDeniedException(403, "Запрос не со страницы админки.");
        }
        if (method.hasMethodAnnotation(AdminOnly.class)
                && !auth.authenticated(AccessCookies.read(request, COOKIE))) {
            log.info("admin request refused: not logged in, client={}", ClientAddress.of(request));
            throw new AdminDeniedException(401, "Войдите в админку.");
        }
        return true;
    }
}
