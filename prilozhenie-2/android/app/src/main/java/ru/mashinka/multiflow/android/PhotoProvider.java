package ru.mashinka.multiflow.android;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;

// один файл, куда системная камера кладёт снимок таблицы
// Камера - чужое приложение, путь к нашим файлам ей не дать, поэтому она получает адрес
// content://ru.mashinka.multiflow.photos/scan.jpg и временное право записи на него.
// Провайдер не экспортирован: открыть файл можно только по разрешению из нашего запроса
public class PhotoProvider extends ContentProvider {

    private static final String NAME = "scan.jpg";

    static String authority(Context context) {
        return context.getPackageName() + ".photos";
    }

    static Uri uri(Context context) {
        return new Uri.Builder().scheme("content").authority(authority(context)).path(NAME).build();
    }

    static File file(Context context) {
        File folder = new File(context.getCacheDir(), "photos");
        if (!folder.isDirectory() && !folder.mkdirs()) {
            AppLog.w("photo", "cannot create folder for camera photos: " + folder);
        }
        return new File(folder, NAME);
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!("/" + NAME).equals(uri.getPath())) {
            AppLog.w("photo", "provider refused unknown path " + uri);
            throw new FileNotFoundException("Нет такого файла: " + uri);
        }
        File target = file(getContext());
        AppLog.i("photo", "provider opens " + target.getName() + " mode=" + mode + " size=" + target.length() + " bytes");
        return ParcelFileDescriptor.open(target, ParcelFileDescriptor.parseMode(mode));
    }

    @Override
    public String getType(Uri uri) {
        return "image/jpeg";
    }

    // некоторые камеры спрашивают имя и размер файла перед записью
    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        File target = file(getContext());
        MatrixCursor cursor = new MatrixCursor(new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE});
        cursor.addRow(new Object[]{NAME, target.length()});
        return cursor;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
