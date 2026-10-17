package tk.glucodata;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import org.json.JSONObject;
import java.io.File;

/** Small transactional identity store excluded from cloud/device-transfer backups. */
final class PersonalConnectionStore {
    private final File file;
    PersonalConnectionStore(Context context) {
        file=new File(context.getNoBackupFilesDir(),"personal-connection.db");
    }

    JSONObject load() throws Exception {
        if(!file.isFile()) return null;
        try(SQLiteDatabase database=SQLiteDatabase.openDatabase(file.getAbsolutePath(),null,SQLiteDatabase.OPEN_READWRITE)) {
            database.execSQL("CREATE TABLE IF NOT EXISTS connection_identity (id INTEGER PRIMARY KEY, payload TEXT NOT NULL)");
            try(Cursor cursor=database.rawQuery("SELECT payload FROM connection_identity WHERE id=1",null)) {
                return cursor.moveToFirst()?new JSONObject(cursor.getString(0)):null;
            }
        }
    }

    void save(JSONObject value) {
        try(SQLiteDatabase database=SQLiteDatabase.openOrCreateDatabase(file,null)) {
            // DDL outside the transaction leaves a recoverable empty store if the
            // first identity transaction is interrupted. No request precedes commit.
            database.execSQL("CREATE TABLE IF NOT EXISTS connection_identity (id INTEGER PRIMARY KEY, payload TEXT NOT NULL)");
            database.beginTransaction();
            try {
                ContentValues row=new ContentValues();
                row.put("id",1);
                row.put("payload",value.toString());
                if(database.insertWithOnConflict("connection_identity",null,row,SQLiteDatabase.CONFLICT_REPLACE)<0)
                    throw new IllegalStateException("Cannot persist personal connection");
                database.setTransactionSuccessful();
            } finally { database.endTransaction(); }
        }
    }
}
