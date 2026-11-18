package ru.mashinka.multiflow.web.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

// разбор тела запроса страницы. Правила те же, что в мосте Android-приложения
// (MultiFlowBridge.readMatrix / readVector): каждая клетка - целое число, дробное не обрезается молча
final class RequestReader {

    private final ObjectMapper mapper;

    RequestReader(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    JsonNode parse(String body) {
        if (body == null || body.isBlank()) {
            throw new MalformedRequestException("пустое тело запроса");
        }
        try {
            JsonNode root = mapper.readTree(body);
            if (root == null || !root.isObject()) {
                throw new MalformedRequestException("ожидался объект JSON");
            }
            return root;
        } catch (JsonProcessingException notJson) {
            throw new MalformedRequestException("тело запроса - не JSON: " + notJson.getOriginalMessage(), notJson);
        }
    }

    static String runId(JsonNode root) {
        JsonNode value = root.get("runId");
        return value != null && value.isTextual() && !value.asText().isBlank() ? value.asText() : "unknown";
    }

    static int[][] matrix(JsonNode root, String field) {
        JsonNode rows = array(root, field);
        int[][] matrix = new int[rows.size()][];
        for (int i = 0; i < rows.size(); i++) {
            JsonNode row = rows.get(i);
            if (!row.isArray()) {
                throw new MalformedRequestException("поле " + field + ", строка " + (i + 1) + ": ожидался массив");
            }
            matrix[i] = new int[row.size()];
            for (int j = 0; j < row.size(); j++) {
                matrix[i][j] = integer(row.get(j), "Клетка (" + (i + 1) + "," + (j + 1) + ")");
            }
        }
        return matrix;
    }

    static int[] vector(JsonNode root, String field, String title) {
        JsonNode values = array(root, field);
        int[] vector = new int[values.size()];
        for (int i = 0; i < values.size(); i++) {
            vector[i] = integer(values.get(i), title + ", вид " + (i + 1));
        }
        return vector;
    }

    private static JsonNode array(JsonNode root, String field) {
        JsonNode node = root.get(field);
        if (node == null || !node.isArray()) {
            throw new MalformedRequestException("нет поля " + field + " с массивом");
        }
        return node;
    }

    // ошибки здесь - про данные, а не про вид запроса: текст для человека, ответ 400
    private static int integer(JsonNode value, String where) {
        if (value == null || !value.isIntegralNumber()) {
            throw new IllegalArgumentException(where + ": нужно целое число, а пришло " + value);
        }
        if (!value.canConvertToInt()) {
            throw new IllegalArgumentException(where + ": слишком большое число " + value.asText());
        }
        return value.intValue();
    }
}
