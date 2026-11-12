/*
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package org.pocketworkstation.pckeyboard;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import android.content.Context;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;

/**
 * Exports and imports all settings and the learned-word databases.
 *
 * The backup is a zip file holding settings.json (every entry of the default
 * SharedPreferences, with its type) and the auto dictionary and user bigram
 * databases under databases/. A bare settings.json is also accepted on import.
 * Importing replaces the current settings, and any database present in the
 * backup; the caller must restart the process afterwards, since the open
 * database connections and the in-memory settings still hold the old state.
 */
final class SettingsBackup {
    static final String FORMAT = "hackerskeyboard-settings";
    static final int VERSION = 1;

    static final String SETTINGS_ENTRY = "settings.json";
    static final String DATABASE_DIR = "databases/";
    // AutoDictionary and UserBigramDictionary. The user dictionary is the
    // system-wide provider and is not ours to back up.
    static final String[] DATABASES = { "auto_dict.db", "userbigram_dict.db" };
    private static final String WAL_SUFFIX = "-wal";
    private static final String[] STALE_SUFFIXES = { "-wal", "-shm", "-journal" };

    private static final int MAX_ENTRY_SIZE = 64 * 1024 * 1024;
    private static final byte[] SQLITE_HEADER = "SQLite format 3\0".getBytes();

    private static final String TYPE_STRING = "string";
    private static final String TYPE_BOOLEAN = "boolean";
    private static final String TYPE_INT = "int";
    private static final String TYPE_LONG = "long";
    private static final String TYPE_FLOAT = "float";
    private static final String TYPE_STRING_SET = "string-set";

    /** Parsed backup contents: typed preference values and database files. */
    static final class Backup {
        final Map<String, Object> prefs;
        final Map<String, byte[]> files;

        Backup(Map<String, Object> prefs, Map<String, byte[]> files) {
            this.prefs = prefs;
            this.files = files;
        }
    }

    private SettingsBackup() {
    }

    static void exportTo(Context context, OutputStream out) throws IOException, JSONException {
        Map<String, byte[]> files = new TreeMap<String, byte[]>();
        for (String name : DATABASES) {
            File db = context.getDatabasePath(name);
            if (!db.isFile()) continue;
            files.put(DATABASE_DIR + name, readFile(db));
            // Uncheckpointed WAL frames hold recent writes; SQLite replays them on open.
            File wal = new File(db.getPath() + WAL_SUFFIX);
            if (wal.isFile()) files.put(DATABASE_DIR + name + WAL_SUFFIX, readFile(wal));
        }
        write(out, PreferenceManager.getDefaultSharedPreferences(context).getAll(), files);
    }

    static void importFrom(Context context, InputStream in) throws IOException, JSONException {
        Backup backup = read(in);
        writePrefs(PreferenceManager.getDefaultSharedPreferences(context), backup.prefs);
        for (String name : DATABASES) {
            byte[] db = backup.files.get(DATABASE_DIR + name);
            if (db != null) {
                restoreDatabase(context.getDatabasePath(name), db,
                        backup.files.get(DATABASE_DIR + name + WAL_SUFFIX));
            }
        }
    }

    static void write(OutputStream out, Map<String, ?> prefs, Map<String, byte[]> files)
            throws IOException, JSONException {
        ZipOutputStream zip = new ZipOutputStream(out);
        zip.putNextEntry(new ZipEntry(SETTINGS_ENTRY));
        zip.write(prefsToJson(prefs).toString(2).getBytes("UTF-8"));
        zip.closeEntry();
        for (Map.Entry<String, byte[]> file : files.entrySet()) {
            zip.putNextEntry(new ZipEntry(file.getKey()));
            zip.write(file.getValue());
            zip.closeEntry();
        }
        zip.finish();
    }

    static Backup read(InputStream in) throws IOException, JSONException {
        byte[] data = readLimited(in);
        if (data.length < 2 || data[0] != 'P' || data[1] != 'K') {
            return new Backup(prefsFromJson(new JSONObject(new String(data, "UTF-8"))),
                    new HashMap<String, byte[]>());
        }
        Set<String> allowed = new HashSet<String>();
        for (String name : DATABASES) {
            allowed.add(DATABASE_DIR + name);
            allowed.add(DATABASE_DIR + name + WAL_SUFFIX);
        }
        JSONObject settings = null;
        Map<String, byte[]> files = new HashMap<String, byte[]>();
        ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(data));
        for (ZipEntry entry; (entry = zip.getNextEntry()) != null; ) {
            String name = entry.getName();
            if (name.equals(SETTINGS_ENTRY)) {
                settings = new JSONObject(new String(readLimited(zip), "UTF-8"));
            } else if (allowed.contains(name)) {
                files.put(name, readLimited(zip));
            }
        }
        if (settings == null) {
            throw new IOException("Not a Hacker's Keyboard settings file.");
        }
        for (String name : DATABASES) {
            byte[] db = files.get(DATABASE_DIR + name);
            if (db == null) {
                files.remove(DATABASE_DIR + name + WAL_SUFFIX);
            } else if (db.length < SQLITE_HEADER.length || !Arrays.equals(SQLITE_HEADER,
                    Arrays.copyOf(db, SQLITE_HEADER.length))) {
                throw new IOException(name + " is not an SQLite database.");
            }
        }
        return new Backup(prefsFromJson(settings), files);
    }

    static JSONObject prefsToJson(Map<String, ?> prefs) throws JSONException {
        JSONObject entries = new JSONObject();
        for (Map.Entry<String, ?> pref : new TreeMap<String, Object>(prefs).entrySet()) {
            Object value = pref.getValue();
            JSONObject entry = new JSONObject();
            if (value instanceof String) {
                entry.put("type", TYPE_STRING).put("value", value);
            } else if (value instanceof Boolean) {
                entry.put("type", TYPE_BOOLEAN).put("value", value);
            } else if (value instanceof Integer) {
                entry.put("type", TYPE_INT).put("value", value);
            } else if (value instanceof Long) {
                entry.put("type", TYPE_LONG).put("value", value);
            } else if (value instanceof Float) {
                entry.put("type", TYPE_FLOAT).put("value", ((Float) value).doubleValue());
            } else if (value instanceof Set) {
                JSONArray items = new JSONArray();
                for (Object item : (Set<?>) value) items.put(item);
                entry.put("type", TYPE_STRING_SET).put("value", items);
            } else {
                throw new JSONException("Unsupported type for setting " + pref.getKey());
            }
            entries.put(pref.getKey(), entry);
        }
        return new JSONObject()
                .put("format", FORMAT)
                .put("version", VERSION)
                .put("prefs", entries);
    }

    static Map<String, Object> prefsFromJson(JSONObject root) throws IOException, JSONException {
        if (!FORMAT.equals(root.optString("format"))) {
            throw new IOException("Not a Hacker's Keyboard settings file.");
        }
        int version = root.optInt("version", -1);
        if (version < 1 || version > VERSION) {
            throw new IOException("Unsupported settings file version " + version + ".");
        }
        JSONObject entries = root.getJSONObject("prefs");
        Map<String, Object> prefs = new HashMap<String, Object>();
        for (Iterator<String> keys = entries.keys(); keys.hasNext(); ) {
            String key = keys.next();
            JSONObject entry = entries.getJSONObject(key);
            String type = entry.getString("type");
            if (TYPE_STRING.equals(type)) {
                prefs.put(key, entry.getString("value"));
            } else if (TYPE_BOOLEAN.equals(type)) {
                prefs.put(key, entry.getBoolean("value"));
            } else if (TYPE_INT.equals(type)) {
                prefs.put(key, entry.getInt("value"));
            } else if (TYPE_LONG.equals(type)) {
                prefs.put(key, entry.getLong("value"));
            } else if (TYPE_FLOAT.equals(type)) {
                prefs.put(key, (float) entry.getDouble("value"));
            } else if (TYPE_STRING_SET.equals(type)) {
                JSONArray items = entry.getJSONArray("value");
                Set<String> set = new HashSet<String>();
                for (int i = 0; i < items.length(); i++) set.add(items.getString(i));
                prefs.put(key, set);
            } else {
                throw new IOException("Unknown type \"" + type + "\" for setting " + key + ".");
            }
        }
        return prefs;
    }

    /** Replaces all preferences with the given ones. */
    @SuppressWarnings("unchecked")
    static void writePrefs(SharedPreferences sharedPrefs, Map<String, Object> prefs)
            throws IOException {
        SharedPreferences.Editor editor = sharedPrefs.edit();
        // Removing keys one by one rather than clear(), which reports a null key
        // to change listeners on newer Android versions.
        for (String key : sharedPrefs.getAll().keySet()) {
            if (!prefs.containsKey(key)) editor.remove(key);
        }
        for (Map.Entry<String, Object> pref : prefs.entrySet()) {
            String key = pref.getKey();
            Object value = pref.getValue();
            if (value instanceof String) {
                editor.putString(key, (String) value);
            } else if (value instanceof Boolean) {
                editor.putBoolean(key, (Boolean) value);
            } else if (value instanceof Integer) {
                editor.putInt(key, (Integer) value);
            } else if (value instanceof Long) {
                editor.putLong(key, (Long) value);
            } else if (value instanceof Float) {
                editor.putFloat(key, (Float) value);
            } else {
                editor.putStringSet(key, (Set<String>) value);
            }
        }
        if (!editor.commit()) throw new IOException("Could not save the settings.");
    }

    private static void restoreDatabase(File db, byte[] data, byte[] wal) throws IOException {
        File dir = db.getParentFile();
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("Could not create " + dir + ".");
        }
        File tmp = new File(db.getPath() + ".tmp");
        writeFile(tmp, data);
        // A leftover WAL or journal from the old database would be replayed
        // into the restored one.
        for (String suffix : STALE_SUFFIXES) {
            new File(db.getPath() + suffix).delete();
        }
        if (!tmp.renameTo(db)) throw new IOException("Could not replace " + db.getName() + ".");
        if (wal != null) writeFile(new File(db.getPath() + WAL_SUFFIX), wal);
    }

    private static byte[] readFile(File file) throws IOException {
        InputStream in = new FileInputStream(file);
        try {
            return readLimited(in);
        } finally {
            in.close();
        }
    }

    private static void writeFile(File file, byte[] data) throws IOException {
        FileOutputStream out = new FileOutputStream(file);
        try {
            out.write(data);
            out.getFD().sync();
        } finally {
            out.close();
        }
    }

    private static byte[] readLimited(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        for (int n; (n = in.read(buf)) != -1; ) {
            if (out.size() + n > MAX_ENTRY_SIZE) throw new IOException("File is too large.");
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }
}
