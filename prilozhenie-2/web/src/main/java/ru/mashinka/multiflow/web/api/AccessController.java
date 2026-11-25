package ru.mashinka.multiflow.web.api;

import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import ru.mashinka.multiflow.web.ClientAddress;
import ru.mashinka.multiflow.web.StartupCheck;
import ru.mashinka.multiflow.web.access.AccessCookies;
import ru.mashinka.multiflow.web.access.AccessKey;
import ru.mashinka.multiflow.web.access.DeviceInfo;
import ru.mashinka.multiflow.web.access.KeyService;
import ru.mashinka.multiflow.web.access.KeyService.Activation;
import ru.mashinka.multiflow.web.access.Secrets;
import ru.mashinka.multiflow.web.access.Tab;

// ввод и проверка ключа. Сюда же ходит приложение Android: оно называет своё устройство в теле
// запроса и само хранит токен, а браузер опознаётся по cookie
@RestController
@RequestMapping("/api/access")
public class AccessController {

    private static final Logger log = LoggerFactory.getLogger(AccessController.class);

    // сколько часов приложение работает без связи с сервером; на сайте не нужно - там считает сервер
    static final int OFFLINE_HOURS = 72;

    private final KeyService keys;
    private final RequestReader reader;
    private final String siteVersion;

    public AccessController(KeyService keys, ObjectMapper mapper, ObjectProvider<BuildProperties> build) {
        this.keys = keys;
        this.reader = new RequestReader(mapper);
        this.siteVersion = StartupCheck.version(build);
    }

    // {"key": "ABCD-EFGH-JKLM"} из браузера; у приложения ещё "device": {"id", "name", "os", "appVersion"}
    @PostMapping("/activate")
    public ResponseEntity<Map<String, Object>> activate(@RequestBody(required = false) String requestBody,
                                                        HttpServletRequest http, HttpServletResponse response) {
        http.setAttribute(SolveController.OPERATION_ATTRIBUTE, "access activate");
        JsonNode request = reader.parse(requestBody);
        String key = RequestReader.text(request, "key");
        boolean android = request.hasNonNull("device");
        DeviceInfo device = android ? androidDevice(request) : browser(http, browserId(http));
        Activation activation = keys.activate(key, device, ClientAddress.of(http));
        if (!android) {
            AccessCookies.write(response, AccessCookies.DEVICE, device.id());
            AccessCookies.write(response, AccessCookies.TOKEN, activation.token());
        }
        Map<String, Object> body = new LinkedHashMap<>();
        if (android) {
            body.put("token", activation.token());
        }
        body.put("tabs", Tab.ids(activation.tabs()));
        body.put("offlineHours", OFFLINE_HOURS);
        return answer(body);
    }

    // действует ли доступ: {} из браузера, {"token", "device": {...}} из приложения
    @PostMapping("/check")
    public ResponseEntity<Map<String, Object>> check(@RequestBody(required = false) String requestBody,
                                                     HttpServletRequest http, HttpServletResponse response) {
        http.setAttribute(SolveController.OPERATION_ATTRIBUTE, "access check");
        JsonNode request = reader.parse(requestBody);
        boolean android = request.hasNonNull("device");
        DeviceInfo device = android ? androidDevice(request)
                : browser(http, AccessCookies.read(http, AccessCookies.DEVICE));
        String token = android ? RequestReader.text(request, "token") : AccessCookies.read(http, AccessCookies.TOKEN);
        AccessKey key = keys.check(token, device);
        if (!android) {
            // срок cookie продлевается, пока человек пользуется сайтом
            AccessCookies.write(response, AccessCookies.DEVICE, device.id());
            AccessCookies.write(response, AccessCookies.TOKEN, token);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("tabs", Tab.ids(key.tabs()));
        body.put("offlineHours", OFFLINE_HOURS);
        return answer(body);
    }

    private DeviceInfo browser(HttpServletRequest http, String id) {
        return DeviceInfo.browser(id, http.getHeader(HttpHeaders.USER_AGENT), siteVersion);
    }

    // номер браузера из cookie; первый раз - новый
    private static String browserId(HttpServletRequest http) {
        String id = AccessCookies.read(http, AccessCookies.DEVICE);
        if (id != null) {
            return id;
        }
        log.info("browser without a device cookie, a new device id is issued");
        return Secrets.randomToken();
    }

    private static DeviceInfo androidDevice(JsonNode request) {
        JsonNode device = request.get("device");
        String id = RequestReader.text(device, "id");
        // приложение присылает SHA-256 от ANDROID_ID, сам номер телефона сюда не попадает
        if (!id.matches("[0-9a-f]{64}")) {
            throw new MalformedRequestException("поле device.id: ожидался SHA-256");
        }
        return new DeviceInfo(DeviceInfo.ANDROID, id, RequestReader.text(device, "name"),
                RequestReader.text(device, "os"), RequestReader.text(device, "appVersion"));
    }

    // ответ с доступом кэшировать нельзя: старый ответ пустил бы отключённого
    private static ResponseEntity<Map<String, Object>> answer(Map<String, Object> body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }
}
