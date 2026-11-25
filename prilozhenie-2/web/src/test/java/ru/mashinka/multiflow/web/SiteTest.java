package ru.mashinka.multiflow.web;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.EnumSet;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import ru.mashinka.multiflow.web.access.AccessKey;
import ru.mashinka.multiflow.web.access.KeyService;
import ru.mashinka.multiflow.web.access.Tab;

// сайт целиком: страница отдаётся, адреса api/* отвечают тем же JSON, что мост в APK,
// ошибки ввода - 400 с заголовком и подробностью
// Запросы идут от браузера с ключом на все вкладки: без ключа сайт не считает (см. AccessTest)
@SpringBootTest(properties = "multiflow.data-dir=target/test-data/${random.uuid}")
@AutoConfigureMockMvc
class SiteTest {

    // итог встроенного примера - тот же, что в тестах ядра (сверен с перебором всех разрезов)
    private static final int[][] EXAMPLE_TABLE = {
            {0, 9, 18, 9, 18, 9, 9},
            {9, 0, 9, 18, 9, 18, 18},
            {18, 9, 0, 9, 19, 9, 9},
            {9, 18, 9, 0, 9, 24, 21},
            {18, 9, 19, 9, 0, 9, 9},
            {9, 18, 9, 24, 9, 0, 21},
            {9, 18, 9, 21, 9, 21, 0},
    };

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper mapper;

    @Autowired
    private KeyService keys;

    // cookie браузера, в котором введён ключ на все вкладки
    private Cookie[] cookies;

    @BeforeEach
    void enterKey() throws Exception {
        AccessKey key = keys.create("тест", EnumSet.allOf(Tab.class));
        cookies = mvc.perform(post("/api/access/activate").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"key\":\"" + key.code() + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getCookies();
    }

    @Test
    void pageAndScriptsAreServed() throws Exception {
        // корень сайта Spring отдаёт как переход на index.html; MockMvc переходы не выполняет
        mvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(forwardedUrl("index.html"))
                .andExpect(header().exists("X-Request-Id"));
        String page = mvc.perform(get("/index.html"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertTrue(page.contains("<title>Многополюсный поток</title>"), "заголовок страницы");
        mvc.perform(get("/app.js")).andExpect(status().isOk());
        mvc.perform(get("/placement.js")).andExpect(status().isOk());
        mvc.perform(get("/transport.js")).andExpect(status().isOk());
        mvc.perform(get("/access.js")).andExpect(status().isOk());
        mvc.perform(get("/styles.css")).andExpect(status().isOk());
    }

    @Test
    void healthShowsVersion() throws Exception {
        mvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"))
                .andExpect(jsonPath("$.version").isString());
    }

    @Test
    void exampleThenSolveGivesTheKnownTable() throws Exception {
        String example = mvc.perform(get("/api/example").cookie(cookies))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.n").value(7))
                .andReturn().getResponse().getContentAsString();
        JsonNode capacities = mapper.readTree(example).get("capacities");

        String request = "{\"runId\":\"site-test\",\"capacities\":" + capacities + "}";
        String answer = mvc.perform(post("/api/solve").cookie(cookies).contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runId").value("site-test"))
                .andExpect(jsonPath("$.steps.length()").value(6))
                // шаги без стягивания: шаг 2 - пара 1-3 по всей сети 7 x 7
                .andExpect(jsonPath("$.steps[1].s").value(1))
                .andExpect(jsonPath("$.steps[1].t").value(3))
                .andExpect(jsonPath("$.steps[1].capacities.length()").value(7))
                .andReturn().getResponse().getContentAsString();

        int[][] table = mapper.treeToValue(mapper.readTree(answer).get("table"), int[][].class);
        assertEquals(7, table.length);
        for (int i = 0; i < 7; i++) {
            assertArrayEquals(EXAMPLE_TABLE[i], table[i], "строка " + (i + 1));
        }
    }

    @Test
    void asymmetricMatrixIsRejectedWithReason() throws Exception {
        String request = "{\"runId\":\"bad\",\"capacities\":[[0,5,0],[4,0,1],[0,1,0]]}";
        mvc.perform(post("/api/solve").cookie(cookies).contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Некорректные исходные данные"))
                .andExpect(jsonPath("$.detail").isString());
    }

    @Test
    void fractionalCellIsRejected() throws Exception {
        String request = "{\"runId\":\"frac\",\"capacities\":[[0,2.5],[2.5,0]]}";
        mvc.perform(post("/api/solve").cookie(cookies).contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Некорректные исходные данные"))
                .andExpect(jsonPath("$.detail").value(containsString("нужно целое число")));
    }

    @Test
    void notJsonIsMalformed() throws Exception {
        mvc.perform(post("/api/solve").cookie(cookies).contentType(MediaType.APPLICATION_JSON).content("капуста"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Некорректный запрос"));
        mvc.perform(post("/api/solve").cookie(cookies).contentType(MediaType.APPLICATION_JSON).content("{\"runId\":\"x\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Некорректный запрос"))
                .andExpect(jsonPath("$.detail").value(containsString("capacities")));
    }

    // пример из пособия МЭСИ: ответ 7892 тыс. руб., как в приложении (ядро считает в рублях)
    @Test
    void placementExampleGives7892() throws Exception {
        String example = mvc.perform(get("/api/placement/example").cookie(cookies))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode data = mapper.readTree(example);
        String request = "{\"runId\":\"placement-test\",\"production\":" + data.get("production")
                + ",\"sales\":" + data.get("sales") + ",\"volume\":" + data.get("volume")
                + ",\"price\":" + data.get("price") + "}";
        mvc.perform(post("/api/placement/solve").cookie(cookies).contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalProfit").value(7_892_000));
    }

    // вариант 1 контрольной: те же суммы, что в тестах ядра (сверены с независимой программой)
    @Test
    void transportVariantsAndSolution() throws Exception {
        String examples = mvc.perform(get("/api/transport/example").cookie(cookies))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.variants.length()").value(27))
                .andReturn().getResponse().getContentAsString();
        JsonNode first = mapper.readTree(examples).get("variants").get(0);
        String data = "\"costs\":" + first.get("costs") + ",\"supply\":" + first.get("supply")
                + ",\"demand\":" + first.get("demand");
        mvc.perform(post("/api/transport/solve").cookie(cookies).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"runId\":\"tr\"," + data + ",\"fictitiousLast\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("shortage"))
                .andExpect(jsonPath("$.northWest.cost").value(5190))
                .andExpect(jsonPath("$.minimumCost.cost").value(3280))
                .andExpect(jsonPath("$.optimal.cost").value(2670));
        mvc.perform(post("/api/transport/solve").cookie(cookies).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"runId\":\"tr\"," + data + ",\"fictitiousLast\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.minimumCost.cost").value(3340));
    }

    @Test
    void badTransportDataIsRejected() throws Exception {
        mvc.perform(post("/api/transport/solve").cookie(cookies).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"costs\":[[1,2],[3,4]],\"supply\":[5,0],\"demand\":[2,3]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("Запас, поставщик 2: нужно не меньше 1")));
        mvc.perform(post("/api/transport/solve").cookie(cookies).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"costs\":[[1,2],[3,4]],\"supply\":[5,1],\"demand\":[2,3],\"fictitiousLast\":\"yes\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Некорректный запрос"));
    }

    @Test
    void pageLogIsAcceptedAndLimited() throws Exception {
        String entry = "{\"level\":\"error\",\"module\":\"ui\",\"message\":\"test\",\"context\":\"k=v\",\"page\":\"/\"}";
        mvc.perform(post("/api/client-log").contentType(MediaType.APPLICATION_JSON).content(entry)
                        .with(request -> {
                            request.setRemoteAddr("10.0.0.7");
                            return request;
                        }))
                .andExpect(status().isNoContent());
        int last = 0;
        for (int k = 0; k < 40; k++) {
            last = mvc.perform(post("/api/client-log").contentType(MediaType.APPLICATION_JSON).content(entry)
                            .with(request -> {
                                request.setRemoteAddr("10.0.0.8");
                                return request;
                            }))
                    .andReturn().getResponse().getStatus();
        }
        assertEquals(429, last, "после 30 записей в минуту с одного адреса - отказ");
    }

    @Test
    void oversizedBodyIsRefused() throws Exception {
        byte[] big = new byte[(int) RequestLogFilter.MAX_API_BODY + 10];
        mvc.perform(post("/api/solve").cookie(cookies).contentType(MediaType.APPLICATION_JSON).content(big))
                .andExpect(status().isPayloadTooLarge());
    }
}
