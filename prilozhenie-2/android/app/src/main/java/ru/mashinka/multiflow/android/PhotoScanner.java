package ru.mashinka.multiflow.android;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.util.Base64;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import ru.mashinka.multiflow.core.json.PhotoJson;
import ru.mashinka.multiflow.core.photo.PhotoFit;
import ru.mashinka.multiflow.core.photo.PhotoResult;
import ru.mashinka.multiflow.core.photo.PhotoTarget;
import ru.mashinka.multiflow.core.photo.PhotoToken;
import ru.mashinka.multiflow.core.photo.ScannedTable;
import ru.mashinka.multiflow.core.photo.TableScanner;

// таблица по фото: камера или галерея -> снимок -> ML Kit -> таблица из ядра -> страница
// Распознавание идёт на телефоне: модель ML Kit лежит внутри приложения, сеть не нужна.
// Здесь только работа с Android; как из слов собрать таблицу, решает ядро (пакет photo)
final class PhotoScanner {

    static final int REQUEST_CAMERA = 4101;
    static final int REQUEST_GALLERY = 4102;

    // снимок для распознавания: длинная сторона не больше, иначе память и время растут зря
    private static final int RECOGNITION_SIDE = 2560;
    // и не меньше: на мелком снимке (скриншот, фото издалека) ML Kit теряет одиночные цифры.
    // Проверено на эмуляторе: таблица 1032x468 - 13 клеток из 20, она же втрое крупнее - 15 из 15
    private static final int MIN_RECOGNITION_SIDE = 2400;
    private static final double MAX_UPSCALE = 3.0;
    // снимок для предпросмотра на странице
    private static final int PREVIEW_SIDE = 1280;
    private static final int PREVIEW_QUALITY = 75;

    private static final String STATE_SCAN = "photo.scanId";
    private static final String STATE_TARGET = "photo.target";
    private static final String STATE_SOURCE = "photo.source";

    // куда отдать готовый JSON: MainActivity передаёт его странице
    interface Delivery {
        void deliver(String json);
    }

    private final Activity activity;
    private final Delivery delivery;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "photo-scan");
        thread.setDaemon(true);
        return thread;
    });
    private final TableScanner tableScanner = new TableScanner();
    private final PhotoFit photoFit = new PhotoFit();
    private TextRecognizer recognizer;

    // съёмка, которая сейчас идёт; переживает пересоздание экрана, пока открыта камера
    private String scanId;
    private String target;
    private String source;
    private long startedAt;
    // снимок уже получен и распознаётся - новую съёмку в это время не начинаем
    private boolean recognizing;

    PhotoScanner(Activity activity, Delivery delivery, Bundle saved) {
        this.activity = activity;
        this.delivery = delivery;
        if (saved != null && saved.getString(STATE_SCAN) != null) {
            scanId = saved.getString(STATE_SCAN);
            target = saved.getString(STATE_TARGET);
            source = saved.getString(STATE_SOURCE);
            startedAt = SystemClock.elapsedRealtime();
            AppLog.i("photo", "scan=" + scanId + " restored after the screen was recreated, waiting for " + source);
        }
    }

    // ------------------------------------------------------------------ начало

    // вызывается на главном потоке по просьбе страницы
    void start(String newScanId, String newTarget, String newSource) {
        if (scanId != null && recognizing) {
            AppLog.w("photo", "scan=" + newScanId + " refused: scan " + scanId + " is still being recognized");
            delivery.deliver(PhotoJson.failure(newScanId, newTarget, "Предыдущее фото ещё распознаётся - "
                    + "подождите пару секунд."));
            return;
        }
        if (scanId != null) {
            // страница снова просит фото, значит камера или галерея уже закрыты, а их ответ потерялся
            AppLog.w("photo", "scan=" + scanId + " never got an answer from " + source + " after "
                    + (SystemClock.elapsedRealtime() - startedAt) + " ms, dropping it for scan " + newScanId);
            scanId = null;
        }
        boolean camera = "camera".equals(newSource);
        Intent intent;
        if (camera) {
            File file = PhotoProvider.file(activity);
            if (file.exists() && !file.delete()) {
                AppLog.w("photo", "scan=" + newScanId + " old camera photo was not deleted: " + file);
            }
            Uri output = PhotoProvider.uri(activity);
            intent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
            intent.putExtra(MediaStore.EXTRA_OUTPUT, output);
            // право записи нужно камере на сам адрес; ClipData - чтобы его получили и камеры,
            // которые открывают адрес не из EXTRA_OUTPUT
            intent.setClipData(ClipData.newRawUri("photo", output));
            intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } else {
            intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.setType("image/*");
            intent.addCategory(Intent.CATEGORY_OPENABLE);
        }

        scanId = newScanId;
        target = newTarget;
        source = camera ? "camera" : "gallery";
        startedAt = SystemClock.elapsedRealtime();
        try {
            activity.startActivityForResult(intent, camera ? REQUEST_CAMERA : REQUEST_GALLERY);
            AppLog.i("photo", "scan=" + scanId + " target=" + target + ": opened " + source);
        } catch (ActivityNotFoundException missing) {
            AppLog.w("photo", "scan=" + scanId + " no app for " + source, missing);
            finish(PhotoJson.failure(scanId, target, camera
                    ? "На телефоне не нашлось приложения камеры. Выберите фото из галереи."
                    : "На телефоне не нашлось галереи или файлового менеджера."));
        }
    }

    // ------------------------------------------------------------------ результат

    boolean onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode != REQUEST_CAMERA && requestCode != REQUEST_GALLERY) {
            return false;
        }
        if (scanId == null) {
            AppLog.w("photo", "result for request " + requestCode + " arrived, but no scan is waiting - ignored");
            return true;
        }
        long waited = SystemClock.elapsedRealtime() - startedAt;
        if (resultCode != Activity.RESULT_OK) {
            AppLog.i("photo", "scan=" + scanId + " " + source + " closed without a photo after " + waited + " ms");
            finish(PhotoJson.cancelled(scanId, target));
            return true;
        }

        Uri uri = requestCode == REQUEST_CAMERA ? PhotoProvider.uri(activity) : (data == null ? null : data.getData());
        if (uri == null) {
            AppLog.w("photo", "scan=" + scanId + " " + source + " returned OK but no picture address");
            finish(PhotoJson.failure(scanId, target, "Фото не получено. Попробуйте ещё раз."));
            return true;
        }
        AppLog.i("photo", "scan=" + scanId + " got picture from " + source + " after " + waited + " ms: "
                + (requestCode == REQUEST_CAMERA ? "camera file" : uri.getScheme() + "://" + uri.getAuthority()));

        String id = scanId;
        String goal = target;
        recognizing = true;
        worker.execute(() -> recognize(id, goal, uri));
        return true;
    }

    // фоновый поток: снимок -> уменьшенная копия с правильным поворотом -> ML Kit
    private void recognize(String id, String goal, Uri uri) {
        long started = SystemClock.elapsedRealtime();
        Bitmap bitmap;
        try {
            bitmap = loadBitmap(uri, id);
        } catch (IOException | RuntimeException | OutOfMemoryError failure) {
            AppLog.e("photo", "scan=" + id + " cannot read the picture " + uri, failure);
            post(PhotoJson.failure(id, goal, "Фото не открылось: " + describe(failure)));
            return;
        }
        if (bitmap == null) {
            AppLog.w("photo", "scan=" + id + " picture is not an image or is empty: " + uri);
            post(PhotoJson.failure(id, goal, "Это не картинка или файл пустой. Выберите фото таблицы."));
            return;
        }
        String preview = previewOf(bitmap, id);
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        long decoded = SystemClock.elapsedRealtime() - started;
        AppLog.i("photo", "scan=" + id + " picture ready " + width + "x" + height + " in " + decoded
                + " ms, preview " + (preview == null ? "none" : preview.length() / 1024 + " KB") + ", recognizing");

        long recognitionStarted = SystemClock.elapsedRealtime();
        recognizer().process(InputImage.fromBitmap(bitmap, 0))
                .addOnSuccessListener(worker, text -> {
                    long recognized = SystemClock.elapsedRealtime() - recognitionStarted;
                    try {
                        List<PhotoToken> tokens = tokensOf(text);
                        AppLog.i("photo", "scan=" + id + " ML Kit found " + text.getTextBlocks().size() + " blocks, "
                                + tokens.size() + " words in " + recognized + " ms");
                        if (AppLog.debugEnabled()) {
                            AppLog.d("photo", "scan=" + id + " words: " + tokens);
                        }
                        ScannedTable table = tableScanner.scan(tokens, id);
                        PhotoResult result = photoFit.fit(table, PhotoTarget.fromId(goal), id);
                        long total = SystemClock.elapsedRealtime() - started;
                        AppLog.i("photo", "scan=" + id + " target=" + goal + " " + (result.isOk()
                                ? "table " + result.rows() + "x" + result.columns() + ", recognized "
                                + result.getRecognized() + " of " + result.getExpected()
                                : "rejected: " + result.getError()) + ", total " + total + " ms");
                        post(PhotoJson.write(result, id, preview, width, height, table.getTokens(), table.getIgnored(),
                                recognized, total));
                    } catch (RuntimeException failure) {
                        AppLog.e("photo", "scan=" + id + " table assembly failed", failure);
                        post(PhotoJson.failure(id, goal, "Сбой при разборе таблицы: " + describe(failure)));
                    } finally {
                        bitmap.recycle();
                    }
                })
                .addOnFailureListener(worker, failure -> {
                    AppLog.e("photo", "scan=" + id + " ML Kit recognition failed after "
                            + (SystemClock.elapsedRealtime() - recognitionStarted) + " ms", failure);
                    bitmap.recycle();
                    post(PhotoJson.failure(id, goal, "Распознавание не удалось: " + describe(failure)));
                });
    }

    private TextRecognizer recognizer() {
        synchronized (this) {
            if (recognizer == null) {
                long started = SystemClock.elapsedRealtime();
                recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
                AppLog.i("photo", "ML Kit text recognizer created in " + (SystemClock.elapsedRealtime() - started) + " ms");
            }
            return recognizer;
        }
    }

    // слова ML Kit -> слова для ядра: текст и прямоугольник
    private static List<PhotoToken> tokensOf(Text text) {
        List<PhotoToken> tokens = new ArrayList<>();
        for (Text.TextBlock block : text.getTextBlocks()) {
            for (Text.Line line : block.getLines()) {
                for (Text.Element element : line.getElements()) {
                    Rect box = element.getBoundingBox();
                    if (box == null) {
                        continue;
                    }
                    tokens.add(new PhotoToken(element.getText(), box.left, box.top, box.right, box.bottom));
                }
            }
        }
        return tokens;
    }

    // ------------------------------------------------------------------ картинка

    private Bitmap loadBitmap(Uri uri, String id) throws IOException {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream in = activity.getContentResolver().openInputStream(uri)) {
            if (in == null) {
                return null;
            }
            BitmapFactory.decodeStream(in, null, bounds);
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return null;
        }
        int side = Math.max(bounds.outWidth, bounds.outHeight);
        int sample = 1;
        while (side / (sample * 2) >= RECOGNITION_SIDE) {
            sample *= 2;
        }
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sample;
        Bitmap bitmap;
        try (InputStream in = activity.getContentResolver().openInputStream(uri)) {
            bitmap = BitmapFactory.decodeStream(in, null, options);
        }
        if (bitmap == null) {
            return null;
        }
        AppLog.i("photo", "scan=" + id + " source " + bounds.outWidth + "x" + bounds.outHeight + " ("
                + bounds.outMimeType + "), decoded with sample " + sample + " to " + bitmap.getWidth() + "x"
                + bitmap.getHeight());

        bitmap = scaleDown(bitmap, RECOGNITION_SIDE);
        int small = Math.max(bitmap.getWidth(), bitmap.getHeight());
        if (small < MIN_RECOGNITION_SIDE) {
            double factor = Math.min(MAX_UPSCALE, (double) MIN_RECOGNITION_SIDE / small);
            Bitmap larger = Bitmap.createScaledBitmap(bitmap, (int) Math.round(bitmap.getWidth() * factor),
                    (int) Math.round(bitmap.getHeight() * factor), true);
            if (larger != bitmap) {
                bitmap.recycle();
            }
            bitmap = larger;
            AppLog.i("photo", "scan=" + id + " small picture enlarged x" + String.format(java.util.Locale.ROOT, "%.2f",
                    factor) + " to " + bitmap.getWidth() + "x" + bitmap.getHeight() + " - ML Kit misses lone digits on small ones");
        }
        int rotation = rotationOf(uri, id);
        if (rotation != 0) {
            Matrix matrix = new Matrix();
            matrix.postRotate(rotation);
            Bitmap rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
            if (rotated != bitmap) {
                bitmap.recycle();
            }
            bitmap = rotated;
            AppLog.i("photo", "scan=" + id + " rotated by " + rotation + "° from EXIF");
        }
        return bitmap;
    }

    // камера часто пишет снимок «лёжа» и только помечает в EXIF, как его повернуть
    private int rotationOf(Uri uri, String id) {
        try (InputStream in = activity.getContentResolver().openInputStream(uri)) {
            if (in == null) {
                return 0;
            }
            int orientation = new ExifInterface(in).getAttributeInt(ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL);
            switch (orientation) {
                case ExifInterface.ORIENTATION_ROTATE_90:
                    return 90;
                case ExifInterface.ORIENTATION_ROTATE_180:
                    return 180;
                case ExifInterface.ORIENTATION_ROTATE_270:
                    return 270;
                default:
                    return 0;
            }
        } catch (IOException | RuntimeException failure) {
            AppLog.w("photo", "scan=" + id + " EXIF is unreadable, keeping the picture as is", failure);
            return 0;
        }
    }

    private static Bitmap scaleDown(Bitmap bitmap, int maxSide) {
        int side = Math.max(bitmap.getWidth(), bitmap.getHeight());
        if (side <= maxSide) {
            return bitmap;
        }
        double factor = (double) maxSide / side;
        Bitmap scaled = Bitmap.createScaledBitmap(bitmap, (int) Math.round(bitmap.getWidth() * factor),
                (int) Math.round(bitmap.getHeight() * factor), true);
        if (scaled != bitmap) {
            bitmap.recycle();
        }
        return scaled;
    }

    // снимок для предпросмотра на странице: уменьшенный JPEG строкой data:...
    private static String previewOf(Bitmap bitmap, String id) {
        try {
            int side = Math.max(bitmap.getWidth(), bitmap.getHeight());
            Bitmap small = bitmap;
            if (side > PREVIEW_SIDE) {
                double factor = (double) PREVIEW_SIDE / side;
                small = Bitmap.createScaledBitmap(bitmap, (int) Math.round(bitmap.getWidth() * factor),
                        (int) Math.round(bitmap.getHeight() * factor), true);
            }
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            small.compress(Bitmap.CompressFormat.JPEG, PREVIEW_QUALITY, bytes);
            if (small != bitmap) {
                small.recycle();
            }
            return "data:image/jpeg;base64," + Base64.encodeToString(bytes.toByteArray(), Base64.NO_WRAP);
        } catch (RuntimeException | OutOfMemoryError failure) {
            AppLog.w("photo", "scan=" + id + " preview was not made, the page will show the table only", failure);
            return null;
        }
    }

    // ------------------------------------------------------------------ завершение

    private void post(String json) {
        activity.runOnUiThread(() -> finish(json));
    }

    // главный поток: съёмка закончена, ответ - странице
    private void finish(String json) {
        AppLog.i("photo", "scan=" + scanId + " finished after " + (SystemClock.elapsedRealtime() - startedAt)
                + " ms, answer " + json.length() + " chars");
        scanId = null;
        target = null;
        source = null;
        recognizing = false;
        delivery.deliver(json);
    }

    void save(Bundle state) {
        if (scanId != null) {
            state.putString(STATE_SCAN, scanId);
            state.putString(STATE_TARGET, target);
            state.putString(STATE_SOURCE, source);
        }
    }

    void close() {
        worker.shutdownNow();
        synchronized (this) {
            if (recognizer != null) {
                recognizer.close();
                recognizer = null;
            }
        }
    }

    private static String describe(Throwable failure) {
        String message = failure.getMessage();
        return message == null || message.isEmpty() ? failure.getClass().getSimpleName() : message;
    }
}
