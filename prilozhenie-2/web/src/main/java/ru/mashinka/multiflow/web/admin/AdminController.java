package ru.mashinka.multiflow.web.admin;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import ru.mashinka.multiflow.web.ClientAddress;
import ru.mashinka.multiflow.web.StartupCheck;
import ru.mashinka.multiflow.web.access.AccessCookies;
import ru.mashinka.multiflow.web.access.AccessKey;
import ru.mashinka.multiflow.web.access.KeyCodes;
import ru.mashinka.multiflow.web.access.KeyService;
import ru.mashinka.multiflow.web.access.Tab;
import ru.mashinka.multiflow.web.api.MalformedRequestException;
import ru.mashinka.multiflow.web.api.RequestReader;
import ru.mashinka.multiflow.web.api.SolveController;

// админка: вход владельца и ключи. https и вход проверяет AdminInterceptor
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final AdminAuth admin;
    private final KeyService keys;
    private final RequestReader reader;
    private final String siteVersion;

    public AdminController(AdminAuth admin, KeyService keys, ObjectMapper mapper,
                           ObjectProvider<BuildProperties> build) {
        this.admin = admin;
        this.keys = keys;
        this.reader = new RequestReader(mapper);
        this.siteVersion = StartupCheck.version(build);
    }

    // что показать: первый вход (придумать пароль), вход или список ключей
    @GetMapping("/state")
    public ResponseEntity<Map<String, Object>> state(HttpServletRequest http) {
        operation(http, "admin state");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("setup", admin.needsSetup());
        body.put("authenticated", admin.authenticated(session(http)));
        body.put("version", siteVersion);
        return answer(body);
    }

    @PostMapping("/setup")
    public ResponseEntity<Map<String, Object>> setup(@RequestBody(required = false) String requestBody,
                                                     HttpServletRequest http, HttpServletResponse response) {
        operation(http, "admin setup");
        JsonNode request = reader.parse(requestBody);
        String token = admin.setup(RequestReader.text(request, "code"), RequestReader.text(request, "password"),
                ClientAddress.of(http));
        writeSession(http, response, token, AdminAuth.SESSION.toSeconds());
        return answer(Map.of("authenticated", true));
    }

    @PostMapping("/login")
    public ResponseEntity<Map<String, Object>> login(@RequestBody(required = false) String requestBody,
                                                     HttpServletRequest http, HttpServletResponse response) {
        operation(http, "admin login");
        JsonNode request = reader.parse(requestBody);
        String token = admin.login(RequestReader.text(request, "password"), ClientAddress.of(http));
        writeSession(http, response, token, AdminAuth.SESSION.toSeconds());
        return answer(Map.of("authenticated", true));
    }

    @PostMapping("/logout")
    public ResponseEntity<Map<String, Object>> logout(HttpServletRequest http, HttpServletResponse response) {
        operation(http, "admin logout");
        admin.logout(session(http), ClientAddress.of(http));
        writeSession(http, response, "", 0);
        return answer(Map.of("authenticated", false));
    }

    // ------------------------------------------------------------------ ключи

    @AdminOnly
    @GetMapping("/keys")
    public ResponseEntity<Map<String, Object>> list(HttpServletRequest http) {
        operation(http, "admin list");
        List<Map<String, Object>> rows = new ArrayList<>();
        for (AccessKey key : keys.list()) {
            rows.add(describe(key));
        }
        return answer(Map.of("keys", rows));
    }

    @AdminOnly
    @PostMapping("/keys")
    public ResponseEntity<Map<String, Object>> create(@RequestBody(required = false) String requestBody,
                                                      HttpServletRequest http) {
        operation(http, "admin create");
        JsonNode request = reader.parse(requestBody);
        return answer(describe(keys.create(RequestReader.text(request, "label"), tabs(request))));
    }

    @AdminOnly
    @PutMapping("/keys/{id}")
    public ResponseEntity<Map<String, Object>> update(@PathVariable("id") long id,
                                                      @RequestBody(required = false) String requestBody,
                                                      HttpServletRequest http) {
        operation(http, "admin update");
        JsonNode request = reader.parse(requestBody);
        return answer(describe(keys.update(id, RequestReader.text(request, "label"), tabs(request))));
    }

    @AdminOnly
    @PostMapping("/keys/{id}/disable")
    public ResponseEntity<Map<String, Object>> disable(@PathVariable("id") long id, HttpServletRequest http) {
        operation(http, "admin disable");
        return answer(describe(keys.setActive(id, false)));
    }

    @AdminOnly
    @PostMapping("/keys/{id}/enable")
    public ResponseEntity<Map<String, Object>> enable(@PathVariable("id") long id, HttpServletRequest http) {
        operation(http, "admin enable");
        return answer(describe(keys.setActive(id, true)));
    }

    @AdminOnly
    @PostMapping("/keys/{id}/unbind")
    public ResponseEntity<Map<String, Object>> unbind(@PathVariable("id") long id, HttpServletRequest http) {
        operation(http, "admin unbind");
        return answer(describe(keys.unbind(id)));
    }

    @AdminOnly
    @DeleteMapping("/keys/{id}")
    public ResponseEntity<Map<String, Object>> delete(@PathVariable("id") long id, HttpServletRequest http) {
        operation(http, "admin delete");
        keys.delete(id);
        return answer(Map.of("deleted", id));
    }

    @AdminOnly
    @GetMapping("/keys/{id}/events")
    public ResponseEntity<Map<String, Object>> events(@PathVariable("id") long id, HttpServletRequest http) {
        operation(http, "admin events");
        return answer(Map.of("events", keys.events(id)));
    }

    // ------------------------------------------------------------------ мелочи

    private static Map<String, Object> describe(AccessKey key) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", key.id());
        row.put("key", KeyCodes.display(key.code()));
        row.put("label", key.label());
        row.put("tabs", Tab.ids(key.tabs()));
        row.put("active", key.active());
        row.put("createdAt", key.createdAt());
        Map<String, Object> device = null;
        if (key.device() != null) {
            device = new LinkedHashMap<>();
            device.put("kind", key.device().kind());
            device.put("name", key.device().name());
            device.put("os", key.device().os());
            device.put("appVersion", key.device().appVersion());
            device.put("boundAt", key.device().boundAt());
            device.put("lastSeen", key.device().lastSeen());
        }
        row.put("device", device);
        return row;
    }

    private static Set<Tab> tabs(JsonNode request) {
        JsonNode list = request.get("tabs");
        if (list == null || !list.isArray()) {
            throw new MalformedRequestException("нет поля tabs с массивом");
        }
        List<String> ids = new ArrayList<>();
        list.forEach(item -> ids.add(item.asText()));
        return Tab.fromIds(ids);
    }

    private static void operation(HttpServletRequest http, String name) {
        http.setAttribute(SolveController.OPERATION_ATTRIBUTE, name);
    }

    private static String session(HttpServletRequest http) {
        return AccessCookies.read(http, AdminInterceptor.COOKIE);
    }

    // cookie входа: по https - с Secure, браузер не отправит её по http
    private static void writeSession(HttpServletRequest http, HttpServletResponse response, String token,
                                     long seconds) {
        ResponseCookie cookie = ResponseCookie.from(AdminInterceptor.COOKIE, token)
                .httpOnly(true).sameSite("Strict").path("/api/admin").maxAge(seconds)
                .secure(http.isSecure() || "https".equals(http.getHeader("X-Forwarded-Proto"))).build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    private static ResponseEntity<Map<String, Object>> answer(Map<String, Object> body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }
}
