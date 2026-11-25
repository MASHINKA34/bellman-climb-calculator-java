package ru.mashinka.multiflow.web;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import ru.mashinka.multiflow.web.access.DatabaseConfig.DataDir;
import ru.mashinka.multiflow.web.admin.AdminAuth;

// админка: первый вход по одноразовому коду, вход по паролю, ключи, только https
// журнал самый подробный: секретов не должно быть и на debug
@SpringBootTest(properties = {"multiflow.data-dir=target/test-data/${random.uuid}", "LOG_LEVEL=debug"})
// print = NONE: иначе MockMvc сам печатает тела запросов, и проверка журнала на секреты была бы неверной
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@ExtendWith(OutputCaptureExtension.class)
class AdminTest {

    private static final String PASSWORD = "очень длинный пароль 1";

    @TestConfiguration
    static class Config {
        @Bean
        @Primary
        TestClock testClock() {
            return new TestClock();
        }
    }

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper mapper;
    @Autowired
    private TestClock clock;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private AdminAuth auth;
    @Autowired
    private DataDir dataDir;

    // каждый тест начинает с чистой базы: пароль не задан, ключей нет
    @BeforeEach
    void clean() {
        clock.advance(Duration.ofHours(1));
        jdbc.update("DELETE FROM admin");
        jdbc.update("DELETE FROM admin_session");
        jdbc.update("DELETE FROM access_key");
        auth.prepareSetup();
    }

    // ------------------------------------------------------------------ вход

    @Test
    void firstVisitAsksToInventPassword() throws Exception {
        mvc.perform(https(get("/api/admin/state"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.setup").value(true))
                .andExpect(jsonPath("$.authenticated").value(false));

        // без кода или с чужим кодом пароль не задать
        setup("wrong", PASSWORD).andExpect(status().isForbidden());
        setup("", PASSWORD).andExpect(status().isForbidden());
        setup(code(), "1234567").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("от 8")));

        MockHttpServletResponse done = setup(code(), PASSWORD).andExpect(status().isOk()).andReturn().getResponse();
        String cookie = done.getHeader("Set-Cookie");
        assertTrue(cookie.contains("HttpOnly") && cookie.contains("SameSite=Strict") && cookie.contains("Secure")
                && cookie.contains("Path=/api/admin"), cookie);
        mvc.perform(https(get("/api/admin/state")).cookie(done.getCookies()))
                .andExpect(jsonPath("$.setup").value(false))
                .andExpect(jsonPath("$.authenticated").value(true));
        assertFalse(Files.exists(setupFile()), "одноразовый код удалён");

        // второй раз пароль не задать, даже если файл с кодом кто-то вернёт
        Files.writeString(setupFile(), "again");
        setup("again", "другой пароль 2").andExpect(status().isConflict());
        Files.delete(setupFile());
        login("другой пароль 2").andExpect(status().isForbidden());
        login(PASSWORD).andExpect(status().isOk());
    }

    @Test
    void wrongPasswordsArePaused() throws Exception {
        setup(code(), PASSWORD).andExpect(status().isOk());
        for (int k = 0; k < 5; k++) {
            login("не тот пароль").andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.detail").value("Неверный пароль."));
        }
        login(PASSWORD).andExpect(status().isTooManyRequests());
        clock.advance(Duration.ofMinutes(16));
        login(PASSWORD).andExpect(status().isOk());
    }

    @Test
    void sessionEndsAfterAWeekOrLogout() throws Exception {
        Cookie[] session = enter();
        mvc.perform(https(get("/api/admin/keys")).cookie(session)).andExpect(status().isOk());
        clock.advance(Duration.ofDays(6));
        mvc.perform(https(get("/api/admin/keys")).cookie(session)).andExpect(status().isOk());
        clock.advance(Duration.ofDays(2));
        mvc.perform(https(get("/api/admin/keys")).cookie(session)).andExpect(status().isUnauthorized());

        Cookie[] second = login(PASSWORD).andReturn().getResponse().getCookies();
        mvc.perform(https(post("/api/admin/logout")).header("X-Admin", "1").cookie(second)).andExpect(status().isOk());
        mvc.perform(https(get("/api/admin/keys")).cookie(second)).andExpect(status().isUnauthorized());
    }

    @Test
    void keysNeedLogin() throws Exception {
        setup(code(), PASSWORD);
        mvc.perform(https(get("/api/admin/keys"))).andExpect(status().isUnauthorized());
        mvc.perform(https(get("/api/admin/keys")).cookie(new Cookie("mf_admin", "guess"))).andExpect(status().isUnauthorized());
        mvc.perform(https(get("/api/admin/keys/1/events"))).andExpect(status().isUnauthorized());
        mvc.perform(json(https(post("/api/admin/keys")).header("X-Admin", "1"), "{\"label\":\"x\",\"tabs\":[\"flow\"]}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(https(delete("/api/admin/keys/1")).header("X-Admin", "1")).andExpect(status().isUnauthorized());
        for (String action : new String[]{"disable", "enable", "unbind"}) {
            mvc.perform(https(post("/api/admin/keys/1/" + action)).header("X-Admin", "1"))
                    .andExpect(status().isUnauthorized());
        }
        mvc.perform(json(https(put("/api/admin/keys/1")).header("X-Admin", "1"), "{\"label\":\"x\",\"tabs\":[\"flow\"]}"))
                .andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------------ только https и только со своей страницы

    @Test
    void plainHttpIsRefused() throws Exception {
        Cookie[] session = enter();
        mvc.perform(get("/api/admin/state").header("X-Forwarded-Proto", "http")).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value("Админка открывается только по https."));
        mvc.perform(get("/api/admin/keys").header("X-Forwarded-Proto", "http").cookie(session))
                .andExpect(status().isForbidden());
        mvc.perform(json(post("/api/admin/login").header("X-Forwarded-Proto", "http").header("X-Admin", "1"),
                "{\"password\":\"" + PASSWORD + "\"}")).andExpect(status().isForbidden());
        mvc.perform(get("/admin/index.html").header("X-Forwarded-Proto", "http")).andExpect(status().isForbidden());
        mvc.perform(get("/admin/").header("X-Forwarded-Proto", "http")).andExpect(status().isForbidden());
        // запрос не с этого компьютера и мимо nginx: заголовку протокола верить нельзя
        mvc.perform(https(get("/api/admin/state")).with(request -> {
            request.setRemoteAddr("10.0.0.5");
            return request;
        })).andExpect(status().isForbidden());
    }

    @Test
    void changesFromAnotherPageAreRefused() throws Exception {
        Cookie[] session = enter();
        mvc.perform(json(https(post("/api/admin/keys")).cookie(session), "{\"label\":\"x\",\"tabs\":[\"flow\"]}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value("Запрос не со страницы админки."));
    }

    @Test
    void pageIsServedWithStrictPolicy() throws Exception {
        mvc.perform(https(get("/admin/"))).andExpect(status().isOk()).andExpect(forwardedUrl("/admin/index.html"));
        mvc.perform(https(get("/admin/index.html"))).andExpect(status().isOk())
                .andExpect(header().string("Content-Security-Policy", containsString("default-src 'self'")))
                .andExpect(header().string("X-Frame-Options", "DENY"));
        mvc.perform(https(get("/admin/admin.js"))).andExpect(status().isOk());
        mvc.perform(https(get("/admin/admin.css"))).andExpect(status().isOk());
        mvc.perform(https(get("/admin"))).andExpect(status().is3xxRedirection());
    }

    // ------------------------------------------------------------------ ключи

    @Test
    void ownerManagesKeys() throws Exception {
        Cookie[] session = enter();
        JsonNode created = body(mvc.perform(json(https(post("/api/admin/keys")).header("X-Admin", "1").cookie(session),
                        "{\"label\":\"  Иван  \",\"tabs\":[\"transport\",\"flow\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.label").value("Иван"))
                .andExpect(jsonPath("$.tabs[0]").value("flow"))
                .andExpect(jsonPath("$.tabs[1]").value("transport"))
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.device").isEmpty()));
        String key = created.get("key").asText();
        long id = created.get("id").asLong();
        assertTrue(key.matches("[A-HJ-NP-Z2-9]{4}-[A-HJ-NP-Z2-9]{4}-[A-HJ-NP-Z2-9]{4}"), key);

        mvc.perform(json(post("/api/access/activate"), "{\"key\":\"" + key + "\"}")).andExpect(status().isOk());
        mvc.perform(https(get("/api/admin/keys")).cookie(session)).andExpect(status().isOk())
                .andExpect(jsonPath("$.keys.length()").value(1))
                .andExpect(jsonPath("$.keys[0].key").value(key))
                .andExpect(jsonPath("$.keys[0].device.kind").value("browser"))
                .andExpect(jsonPath("$.keys[0].device.deviceHash").doesNotExist())
                .andExpect(jsonPath("$.keys[0].device.tokenHash").doesNotExist());

        mvc.perform(json(https(put("/api/admin/keys/" + id)).header("X-Admin", "1").cookie(session),
                        "{\"label\":\"Иван И.\",\"tabs\":[\"placement\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.label").value("Иван И."))
                .andExpect(jsonPath("$.tabs.length()").value(1));
        act(session, id, "disable").andExpect(jsonPath("$.active").value(false));
        act(session, id, "enable").andExpect(jsonPath("$.active").value(true));
        act(session, id, "unbind").andExpect(jsonPath("$.device").isEmpty());
        mvc.perform(https(get("/api/admin/keys/" + id + "/events")).cookie(session)).andExpect(status().isOk())
                .andExpect(jsonPath("$.events[0].type").value("unbound"))
                .andExpect(jsonPath("$.events[6].type").value("created"));
        mvc.perform(https(delete("/api/admin/keys/" + id)).header("X-Admin", "1").cookie(session))
                .andExpect(status().isOk());
        mvc.perform(https(get("/api/admin/keys")).cookie(session)).andExpect(jsonPath("$.keys.length()").value(0));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM key_event WHERE key_id = ?", Integer.class, id),
                "история удаляется вместе с ключом");
    }

    @Test
    void badKeyDataIsRejected() throws Exception {
        Cookie[] session = enter();
        create(session, "{\"label\":\"x\",\"tabs\":[]}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Выберите хотя бы одну вкладку"));
        create(session, "{\"label\":\"x\",\"tabs\":[\"admin\"]}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Неизвестная вкладка: admin"));
        create(session, "{\"label\":\"" + "я".repeat(101) + "\",\"tabs\":[\"flow\"]}").andExpect(status().isBadRequest());
        create(session, "{\"label\":\"x\"}").andExpect(status().isBadRequest());
        create(session, "{\"label\":\"\",\"tabs\":[\"flow\"]}").andExpect(status().isOk());
        act(session, 999_999, "disable").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Ключа №999999 нет"));
    }

    // ------------------------------------------------------------------ логи

    @Test
    void logsDoNotLeakPasswordOrSession(CapturedOutput output) throws Exception {
        String code = code();
        setup("wrong-code-value", PASSWORD);
        setup(code, PASSWORD);
        login("неверный пароль 77");
        Cookie[] session = login(PASSWORD).andReturn().getResponse().getCookies();
        String key = body(create(session, "{\"label\":\"x\",\"tabs\":[\"flow\"]}")).get("key").asText();
        String log = output.getAll();
        assertTrue(log.contains("admin login refused: wrong password"));
        assertTrue(log.contains("admin logged in"));
        for (String secret : new String[]{PASSWORD, "неверный пароль 77", code, "wrong-code-value",
                session[0].getValue(), key, key.replace("-", "")}) {
            assertFalse(log.contains(secret), "в журнале есть секрет");
        }
        String stored = jdbc.queryForObject("SELECT password_hash FROM admin", String.class);
        assertTrue(stored.startsWith("pbkdf2$600000$"), "в базе хэш, а не пароль");
    }

    // ------------------------------------------------------------------ помощники

    private Path setupFile() {
        return dataDir.path().resolve("admin-setup.txt");
    }

    private String code() throws Exception {
        return Files.readString(setupFile());
    }

    private Cookie[] enter() throws Exception {
        return setup(code(), PASSWORD).andExpect(status().isOk()).andReturn().getResponse().getCookies();
    }

    private ResultActions setup(String code, String password) throws Exception {
        return mvc.perform(json(https(post("/api/admin/setup")).header("X-Admin", "1"),
                mapper.writeValueAsString(Map.of("code", code, "password", password))));
    }

    private ResultActions login(String password) throws Exception {
        return mvc.perform(json(https(post("/api/admin/login")).header("X-Admin", "1"),
                mapper.writeValueAsString(Map.of("password", password))));
    }

    private ResultActions create(Cookie[] session, String body) throws Exception {
        return mvc.perform(json(https(post("/api/admin/keys")).header("X-Admin", "1").cookie(session), body));
    }

    private ResultActions act(Cookie[] session, long id, String action) throws Exception {
        return mvc.perform(https(post("/api/admin/keys/" + id + "/" + action)).header("X-Admin", "1").cookie(session));
    }

    private JsonNode body(ResultActions result) throws Exception {
        return mapper.readTree(result.andReturn().getResponse().getContentAsString());
    }

    private static MockHttpServletRequestBuilder https(MockHttpServletRequestBuilder request) {
        return request.header("X-Forwarded-Proto", "https");
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String body) {
        return request.contentType(MediaType.APPLICATION_JSON).content(body);
    }
}
