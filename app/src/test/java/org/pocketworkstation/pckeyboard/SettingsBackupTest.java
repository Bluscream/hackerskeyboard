package org.pocketworkstation.pckeyboard;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.fail;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.json.JSONObject;
import org.junit.Test;

public class SettingsBackupTest {
    private static final byte[] DB = "SQLite format 3\0rest of the database".getBytes();
    private static final byte[] WAL = { 1, 2, 3 };

    private static Map<String, Object> samplePrefs() {
        Map<String, Object> prefs = new HashMap<String, Object>();
        prefs.put("settings_height_portrait", "50");
        prefs.put("auto_cap", false);
        prefs.put("pref_slide_keys_int", 3);
        prefs.put("some_long", 1L << 40);
        prefs.put("some_float", 0.3f);
        prefs.put("some_set", new HashSet<String>(Arrays.asList("a", "b")));
        return prefs;
    }

    private static byte[] export(Map<String, ?> prefs, Map<String, byte[]> files)
            throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        SettingsBackup.write(out, prefs, files);
        return out.toByteArray();
    }

    private static SettingsBackup.Backup read(byte[] data) throws Exception {
        return SettingsBackup.read(new ByteArrayInputStream(data));
    }

    private static void assertRejected(byte[] data) throws Exception {
        try {
            read(data);
            fail("backup was accepted");
        } catch (IOException expected) {
        }
    }

    @Test
    public void roundTripKeepsValuesAndTypes() throws Exception {
        Map<String, byte[]> files = new TreeMap<String, byte[]>();
        files.put("databases/auto_dict.db", DB);
        files.put("databases/auto_dict.db-wal", WAL);
        SettingsBackup.Backup backup = read(export(samplePrefs(), files));

        assertEquals(samplePrefs(), backup.prefs);
        assertEquals(Float.class, backup.prefs.get("some_float").getClass());
        assertEquals(Long.class, backup.prefs.get("some_long").getClass());
        assertEquals(2, backup.files.size());
        assertArrayEquals(DB, backup.files.get("databases/auto_dict.db"));
        assertArrayEquals(WAL, backup.files.get("databases/auto_dict.db-wal"));
    }

    @Test
    public void acceptsBareSettingsJson() throws Exception {
        byte[] json = SettingsBackup.prefsToJson(samplePrefs()).toString().getBytes("UTF-8");
        SettingsBackup.Backup backup = read(json);
        assertEquals(samplePrefs(), backup.prefs);
        assertEquals(0, backup.files.size());
    }

    @Test
    public void ignoresUnknownEntriesAndOrphanWal() throws Exception {
        Map<String, byte[]> files = new TreeMap<String, byte[]>();
        files.put("databases/userbigram_dict.db-wal", WAL);
        files.put("../../evil.db", DB);
        files.put("databases/other.db", DB);
        SettingsBackup.Backup backup = read(export(samplePrefs(), files));
        assertEquals(0, backup.files.size());
    }

    @Test
    public void rejectsWrongFormatAndVersion() throws Exception {
        JSONObject json = SettingsBackup.prefsToJson(samplePrefs());
        assertRejected(json.put("format", "something-else").toString().getBytes("UTF-8"));
        json = SettingsBackup.prefsToJson(samplePrefs());
        assertRejected(json.put("version", SettingsBackup.VERSION + 1).toString().getBytes("UTF-8"));
    }

    @Test
    public void rejectsUnknownType() throws Exception {
        JSONObject json = SettingsBackup.prefsToJson(samplePrefs());
        json.getJSONObject("prefs").put("x", new JSONObject().put("type", "double").put("value", 1));
        assertRejected(json.toString().getBytes("UTF-8"));
    }

    @Test
    public void rejectsZipWithoutSettings() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ZipOutputStream zip = new ZipOutputStream(out);
        zip.putNextEntry(new ZipEntry("databases/auto_dict.db"));
        zip.write(DB);
        zip.closeEntry();
        zip.finish();
        assertRejected(out.toByteArray());
    }

    @Test
    public void rejectsDatabaseWithoutSqliteHeader() throws Exception {
        Map<String, byte[]> files = new TreeMap<String, byte[]>();
        files.put("databases/auto_dict.db", "not a database".getBytes());
        assertRejected(export(samplePrefs(), files));
    }

    @Test
    public void rejectsGarbage() throws Exception {
        assertRejected("PK garbage".getBytes());
        assertFalse(read(export(samplePrefs(), new TreeMap<String, byte[]>())).prefs.isEmpty());
    }
}
