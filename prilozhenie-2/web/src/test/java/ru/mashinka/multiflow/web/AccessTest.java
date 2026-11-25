package ru.mashinka.multiflow.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

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
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import ru.mashinka.multiflow.web.access.AccessKey;
import ru.mashinka.multiflow.web.access.KeyCodes;
import ru.mashinka.multiflow.web.access.KeyService;
import ru.mashinka.multiflow.web.access.KeyStore;
import ru.mashinka.multiflow.web.access.RequiresTab;
import ru.mashinka.multiflow.web.access.Tab;
import ru.mashinka.multiflow.web.api.SolveController;

// ключи входа: 1 ключ = 1 устройство, вкладки по ключу, отключённого выкидывает
// журнал самый подробный: секретов не должно быть и на debug
@SpringBootTest(properties = {"multiflow.data-dir=target/test-data/${random.uuid}", "LOG_LEVEL=debug"})
// print = NONE: иначе MockMvc сам печатает тела запросов, и проверка журнала на секреты была бы неверной
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@ExtendWith(OutputCaptureExtension.class)
class AccessTest {

    private static final String SAFARI = "Mozilla/5.0 (iPhone; CPU iPhone OS 18_1 like Mac OS X) AppleWebKit/605.1.15 "
            + "(KHTML, like Gecko) Version/18.1 Mobile/15E148 Safari/604.1";
    private static final String PHONE_A = "a".repeat(64);
    private static final String PHONE_B = "b".repeat(64);

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
    private KeyService keys;
    @Autowired
    private TestClock clock;
    @Autowired
    private RequestMappingHandlerMapping mapping;

    private AccessKey key;

    @BeforeEach
    void newKey() {
        // счётчик неудачных попыток прошлого теста остаётся в прошлой минуте
        clock.advance(Duration.ofMinutes(2));
        key = keys.create("Иван", EnumSet.of(Tab.FLOW, Tab.TRANSPORT));
    }

    // ------------------------------------------------------------------ без ключа

    @Test
    void everyCalculationNeedsKey() throws Exception {
        for (String address : List.of("/api/example", "/api/placement/example", "/api/transport/example")) {
            mvc.perform(get(address)).andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.reason").value("none"))
                    .andExpect(jsonPath("$.detail").value("Введите ключ доступа."));
        }
        for (String address : List.of("/api/solve", "/api/placement/solve", "/api/transport/solve")) {
            mvc.perform(json(post(address), "{}")).andExpect(status().isUnauthorized());
        }
        mvc.perform(json(post("/api/access/check"), "{}")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.reason").value("none"));
        mvc.perform(get("/api/health")).andExpect(status().isOk());
    }

    // новый метод расчёта без отметки @RequiresTab - ошибка, а не открытая дверь
    @Test
    void everyMethodOfTheSolveControllerIsProtected() {
        int checked = 0;
        for (Map.Entry<RequestMappingInfo, HandlerMethod> entry : mapping.getHandlerMethods().entrySet()) {
            HandlerMethod method = entry.getValue();
            if (!SolveController.class.equals(method.getBeanType()) || method.getMethod().getName().equals("health")) {
                continue;
            }
            assertTrue(method.hasMethodAnnotation(RequiresTab.class), method + " без @RequiresTab");
            checked++;
        }
        assertEquals(6, checked);
    }

    @Test
    void oddSpellingsOfTheAddressDoNotOpenIt() throws Exception {
        for (String address : List.of("/api/%73olve", "/api//solve", "/api/solve/", "/api/solve;a=b", "/API/solve",
                "/api/./solve", "/api/transport/../solve")) {
            MockHttpServletResponse response = mvc.perform(json(post(URI.create("http://localhost" + address)),
                    "{\"capacities\":[[0,1,1],[1,0,1],[1,1,0]]}")).andReturn().getResponse();
            assertNotEquals(200, response.getStatus(), address);
        }
    }

    // ------------------------------------------------------------------ браузер

    @Test
    void browserEntersKeyAndSolvesOnlyItsTabs() throws Exception {
        MockHttpServletResponse entered = activate(KeyCodes.display(key.code()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tabs[0]").value("flow"))
                .andExpect(jsonPath("$.tabs[1]").value("transport"))
                .andExpect(jsonPath("$.tabs.length()").value(2))
                .andExpect(jsonPath("$.token").doesNotExist())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andReturn().getResponse();
        for (String cookie : entered.getHeaders("Set-Cookie")) {
            assertTrue(cookie.contains("HttpOnly") && cookie.contains("SameSite=Strict"), cookie);
        }
        Cookie[] cookies = entered.getCookies();
        assertEquals(2, cookies.length);
        AccessKey.BoundDevice device = keys.list().get(0).device();
        assertEquals("browser", device.kind());
        assertEquals("Safari", device.name());
        assertEquals("iPhone", device.os());

        mvc.perform(get("/api/example").cookie(cookies)).andExpect(status().isOk());
        mvc.perform(get("/api/transport/example").cookie(cookies)).andExpect(status().isOk());
        mvc.perform(get("/api/placement/example").cookie(cookies)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.reason").value("tab"));

        // вкладку добавили в админке - доступ появляется без нового ввода ключа
        keys.update(key.id(), "Иван", EnumSet.of(Tab.PLACEMENT));
        mvc.perform(get("/api/placement/example").cookie(cookies)).andExpect(status().isOk());
        mvc.perform(get("/api/example").cookie(cookies)).andExpect(status().isForbidden());
        mvc.perform(json(post("/api/access/check").cookie(cookies), "{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tabs.length()").value(1))
                .andExpect(jsonPath("$.tabs[0]").value("placement"));
    }

    @Test
    void keyIsAcceptedInAnySpelling() throws Exception {
        String sloppy = "  " + key.code().substring(0, 5).toLowerCase() + " - " + key.code().substring(5) + " ";
        activate(sloppy).andExpect(status().isOk());
    }

    @Test
    void secondBrowserIsRefusedUntilUnbound() throws Exception {
        Cookie[] first = activate(key.code()).andExpect(status().isOk()).andReturn().getResponse().getCookies();
        activate(key.code()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.reason").value("busy"))
                .andExpect(jsonPath("$.detail").value("Ключ уже используется на другом устройстве."));
        mvc.perform(get("/api/example").cookie(first)).andExpect(status().isOk());

        keys.unbind(key.id());
        mvc.perform(get("/api/example").cookie(first)).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.reason").value("unbound"));
        activate(key.code()).andExpect(status().isOk());
        mvc.perform(get("/api/example").cookie(first)).andExpect(status().isUnauthorized());
    }

    @Test
    void sameBrowserMayEnterTheKeyAgain() throws Exception {
        Cookie[] first = activate(key.code()).andReturn().getResponse().getCookies();
        Cookie device = first[0].getName().equals("mf_device") ? first[0] : first[1];
        Cookie[] second = mvc.perform(json(post("/api/access/activate").cookie(device), "{\"key\":\"" + key.code() + "\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getCookies();
        mvc.perform(get("/api/example").cookie(second)).andExpect(status().isOk());
        // прежний токен этого же браузера больше не годится
        mvc.perform(get("/api/example").cookie(first)).andExpect(status().isUnauthorized());
    }

    // токен без cookie браузера (или с чужой) не работает: скопировать один токен мало
    @Test
    void tokenAloneIsNotEnough() throws Exception {
        Cookie[] cookies = activate(key.code()).andReturn().getResponse().getCookies();
        Cookie token = cookies[0].getName().equals("mf_access") ? cookies[0] : cookies[1];
        mvc.perform(get("/api/example").cookie(token)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/example").cookie(token, new Cookie("mf_device", "someone-else")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void disabledKeyThrowsOutAndComesBackWhenEnabled() throws Exception {
        Cookie[] cookies = activate(key.code()).andReturn().getResponse().getCookies();
        keys.setActive(key.id(), false);
        mvc.perform(get("/api/example").cookie(cookies)).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.reason").value("disabled"))
                .andExpect(jsonPath("$.detail").value("Ключ отключён."));
        mvc.perform(json(post("/api/access/check").cookie(cookies), "{}")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.reason").value("disabled"));
        mvc.perform(json(post("/api/access/activate").cookie(cookies), "{\"key\":\"" + key.code() + "\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.reason").value("disabled"));

        keys.setActive(key.id(), true);
        mvc.perform(get("/api/example").cookie(cookies)).andExpect(status().isOk());
    }

    @Test
    void deletedKeyThrowsOut() throws Exception {
        Cookie[] cookies = activate(key.code()).andReturn().getResponse().getCookies();
        keys.delete(key.id());
        mvc.perform(get("/api/example").cookie(cookies)).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.reason").value("deleted"));
        activate(key.code()).andExpect(status().isNotFound()).andExpect(jsonPath("$.reason").value("unknown"));
    }

    @Test
    void wrongKeysArePaused() throws Exception {
        for (int k = 0; k < 10; k++) {
            activate("ABCD-EFGH-JKL" + "ABCDEFGHJK".charAt(k)).andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.reason").value("unknown"));
        }
        activate(key.code()).andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.reason").value("throttled"));
        clock.advance(Duration.ofSeconds(61));
        activate(key.code()).andExpect(status().isOk());
    }

    @Test
    void garbageInsteadOfTheKeyIsRefused() throws Exception {
        activate("").andExpect(status().isNotFound());
        activate("' OR 1=1 --").andExpect(status().isNotFound());
        mvc.perform(json(post("/api/access/activate"), "{\"key\":5}")).andExpect(status().isBadRequest());
        mvc.perform(json(post("/api/access/activate"), "капуста")).andExpect(status().isBadRequest());
        for (String token : List.of("1", "1.", ".x", "999999999999999.x", "99999999999999999999.x", "-1.x")) {
            mvc.perform(get("/api/example").cookie(new Cookie("mf_access", token), new Cookie("mf_device", "d")))
                    .andExpect(status().isUnauthorized());
        }
    }

    // ------------------------------------------------------------------ приложение

    @Test
    void phoneEntersKeyAndChecksIt() throws Exception {
        String token = phone(PHONE_A);
        check(token, PHONE_A).andExpect(status().isOk())
                .andExpect(jsonPath("$.tabs.length()").value(2))
                .andExpect(jsonPath("$.offlineHours").value(72));
        // данные приложения скопировали на другой телефон
        check(token, PHONE_B).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.reason").value("unbound"));
        // ключ на втором телефоне
        activatePhone(PHONE_B).andExpect(status().isConflict()).andExpect(jsonPath("$.reason").value("busy"));
        // и в браузере: занят телефоном
        activate(key.code()).andExpect(status().isConflict());

        // переустановка: тот же телефон вводит тот же ключ, прежний токен гаснет
        String again = phone(PHONE_A);
        check(again, PHONE_A).andExpect(status().isOk());
        check(token, PHONE_A).andExpect(status().isUnauthorized());

        keys.setActive(key.id(), false);
        check(again, PHONE_A).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.reason").value("disabled"));
        keys.setActive(key.id(), true);
        check(again, PHONE_A).andExpect(status().isOk());
        keys.unbind(key.id());
        check(again, PHONE_A).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.reason").value("unbound"));
    }

    @Test
    void phoneIsShownInTheListWithLastSeen() throws Exception {
        String token = phone(PHONE_A);
        long entered = keys.list().get(0).device().lastSeen();
        clock.advance(Duration.ofSeconds(20));
        check(token, PHONE_A);
        assertEquals(entered, keys.list().get(0).device().lastSeen(), "чаще раза в 30 секунд не пишем");
        clock.advance(Duration.ofSeconds(20));
        check(token, PHONE_A);
        AccessKey.BoundDevice device = keys.list().get(0).device();
        assertEquals(entered + 40_000, device.lastSeen());
        assertEquals("Samsung SM-A525F", device.name());
        assertEquals("Android 13", device.os());
        assertEquals("1.5.0", device.appVersion());
        assertEquals("android", device.kind());
    }

    @Test
    void badDeviceIdIsMalformed() throws Exception {
        activatePhone("short").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Некорректный запрос"));
    }

    // два устройства вводят один ключ одновременно - проходит ровно одно
    @Test
    void simultaneousEntryBindsOneDevice() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Callable<Integer>> tasks = new ArrayList<>();
            for (int k = 0; k < 8; k++) {
                String id = Integer.toHexString(k).repeat(64);
                tasks.add(() -> activatePhone(id).andReturn().getResponse().getStatus());
            }
            int accepted = 0;
            for (Future<Integer> answer : pool.invokeAll(tasks)) {
                accepted += answer.get() == 200 ? 1 : 0;
            }
            assertEquals(1, accepted);
        } finally {
            pool.shutdown();
        }
    }

    // ------------------------------------------------------------------ история и логи

    @Test
    void historyTellsWhatHappened() throws Exception {
        phone(PHONE_A);
        activatePhone(PHONE_B);
        keys.setActive(key.id(), false);
        keys.setActive(key.id(), true);
        keys.update(key.id(), "Иван И.", Set.of(Tab.FLOW));
        keys.unbind(key.id());
        List<String> types = keys.events(key.id()).stream().map(KeyStore.KeyEvent::type).toList();
        assertEquals(List.of("unbound", "label", "tabs", "enabled", "disabled", "refused_busy", "bound", "created"), types);
    }

    @Test
    void historyIsLimited() {
        for (int k = 0; k < 260; k++) {
            keys.setActive(key.id(), k % 2 == 1);
        }
        assertEquals(200, keys.events(key.id()).size());
    }

    // ни ключа, ни токена, ни номера устройства в журнале
    @Test
    void logsDoNotLeakSecrets(CapturedOutput output) throws Exception {
        String token = phone(PHONE_A);
        check(token, PHONE_A);
        check(token, PHONE_B);
        activatePhone(PHONE_B);
        activate("ZZZZ-YYYY-XXXX");
        keys.setActive(key.id(), false);
        check(token, PHONE_A);
        String log = output.getAll();
        assertTrue(log.contains("key #" + key.id() + " " + key.code().substring(0, 4) + "-****-****"), "ключ узнаётся по началу");
        assertTrue(log.contains("key refused: busy"));
        assertFalse(log.contains(key.code()) || log.contains(KeyCodes.display(key.code())), "ключ целиком");
        assertFalse(log.contains("ZZZZYYYYXXXX") || log.contains("ZZZZ-YYYY-XXXX"), "неверный ключ целиком");
        assertFalse(log.contains(token) || log.contains(token.substring(token.indexOf('.') + 1)), "токен");
        assertFalse(log.contains(PHONE_A) || log.contains(PHONE_B), "номер устройства");
    }

    // ------------------------------------------------------------------ помощники

    private ResultActions activate(String code) throws Exception {
        return mvc.perform(json(post("/api/access/activate").header("User-Agent", SAFARI),
                mapper.writeValueAsString(Map.of("key", code))));
    }

    private ResultActions activatePhone(String id) throws Exception {
        return mvc.perform(json(post("/api/access/activate"),
                "{\"key\":\"" + key.code() + "\",\"device\":" + device(id) + "}"));
    }

    private String phone(String id) throws Exception {
        JsonNode answer = mapper.readTree(activatePhone(id).andExpect(status().isOk())
                .andExpect(header().doesNotExist("Set-Cookie"))
                .andReturn().getResponse().getContentAsString());
        return answer.get("token").asText();
    }

    private ResultActions check(String token, String id) throws Exception {
        return mvc.perform(json(post("/api/access/check"),
                "{\"token\":\"" + token + "\",\"device\":" + device(id) + "}"));
    }

    private static String device(String id) {
        return "{\"id\":\"" + id + "\",\"name\":\"Samsung SM-A525F\",\"os\":\"Android 13\",\"appVersion\":\"1.5.0\"}";
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String body) {
        return request.contentType(MediaType.APPLICATION_JSON).content(body);
    }
}
