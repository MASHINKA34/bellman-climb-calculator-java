package ru.mashinka.multiflow.core.json;

import ru.mashinka.multiflow.core.photo.PhotoResult;

// ответ для экрана после распознавания фото: что подставить и как подсветить клетки.
// Снимок для предпросмотра приходит готовой строкой data:image/jpeg;base64,... -
// его уменьшает и кодирует приложение, ядро картинок не касается
public final class PhotoJson {

    private PhotoJson() {
    }

    public static String write(PhotoResult result, String scanId, String imageDataUrl, int imageWidth,
                               int imageHeight, int tokens, int ignored, long recognitionMillis, long totalMillis) {
        JsonWriter json = new JsonWriter();
        json.beginObject();
        json.name("scanId").value(scanId);
        json.name("target").value(result.getTarget().getId());
        json.name("ok").value(result.isOk());
        json.name("error").value(result.getError());
        json.name("rows").value(result.rows());
        json.name("columns").value(result.columns());

        json.name("values").beginArray();
        for (String[] row : result.getValues()) {
            json.beginArray();
            for (String text : row) {
                json.value(text);
            }
            json.endArray();
        }
        json.endArray();
        // 0 - распознано, 1 - проверить, 2 - не распознано, 3 - взято из зеркальной клетки,
        // 4 - зеркальные клетки распознаны разными числами
        json.name("flags").matrix(result.getFlags());

        json.name("recognized").value(result.getRecognized());
        json.name("expected").value(result.getExpected());
        json.name("check").value(result.count(PhotoResult.CHECK));
        json.name("missing").value(result.count(PhotoResult.MISSING));
        json.name("mirror").value(result.count(PhotoResult.MIRROR));
        json.name("conflict").value(result.count(PhotoResult.CONFLICT));

        json.name("notes").beginArray();
        for (String note : result.getNotes()) {
            json.value(note);
        }
        json.endArray();

        json.name("tokens").value(tokens);
        json.name("ignored").value(ignored);
        json.name("image").value(imageDataUrl);
        json.name("imageWidth").value(imageWidth);
        json.name("imageHeight").value(imageHeight);
        json.name("recognitionMs").value(recognitionMillis);
        json.name("totalMs").value(totalMillis);
        json.endObject();
        return json.toString();
    }

    // распознавание не дошло до таблицы: фото не открылось, сбой распознавания
    public static String failure(String scanId, String target, String error) {
        JsonWriter json = new JsonWriter();
        json.beginObject();
        json.name("scanId").value(scanId);
        json.name("target").value(target);
        json.name("ok").value(false);
        json.name("cancelled").value(false);
        json.name("error").value(error);
        json.endObject();
        return json.toString();
    }

    // человек закрыл камеру или галерею, ничего не выбрав, - это не ошибка
    public static String cancelled(String scanId, String target) {
        JsonWriter json = new JsonWriter();
        json.beginObject();
        json.name("scanId").value(scanId);
        json.name("target").value(target);
        json.name("ok").value(false);
        json.name("cancelled").value(true);
        json.name("error").value((String) null);
        json.endObject();
        return json.toString();
    }
}
