/*
 * Copyright (C) 2008 The Android Open Source Project
 *
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

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import org.json.JSONException;

import android.app.Dialog;
import android.app.backup.BackupManager;
import android.content.ActivityNotFoundException;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.content.res.Resources;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Process;
import android.preference.CheckBoxPreference;
import android.preference.ListPreference;
import android.preference.Preference;
import android.preference.PreferenceActivity;
import android.preference.PreferenceGroup;
import android.preference.PreferenceScreen;
import android.text.AutoText;
import android.text.InputType;
import android.util.Log;
import android.widget.Toast;

public class LatinIMESettings extends PreferenceActivity
        implements SharedPreferences.OnSharedPreferenceChangeListener,
        DialogInterface.OnDismissListener {

    private static final String QUICK_FIXES_KEY = "quick_fixes";
    private static final String PREDICTION_SETTINGS_KEY = "prediction_settings";
    private static final String VOICE_SETTINGS_KEY = "voice_mode";
    /* package */ static final String PREF_SETTINGS_KEY = "settings_key";
    static final String INPUT_CONNECTION_INFO = "input_connection_info";    
    private static final String BACKUP_SETTINGS_KEY = "backup_settings";
    private static final String EXPORT_SETTINGS_KEY = "export_settings";
    private static final String IMPORT_SETTINGS_KEY = "import_settings";

    private static final String TAG = "LatinIMESettings";

    // Dialog ids
    private static final int VOICE_INPUT_CONFIRM_DIALOG = 0;

    // Activity request codes
    private static final int EXPORT_SETTINGS_REQUEST = 1;
    private static final int IMPORT_SETTINGS_REQUEST = 2;

    // Leaves time for the import result toast before the process restarts.
    private static final int IMPORT_RESTART_DELAY_MS = 2000;

    private CheckBoxPreference mQuickFixes;
    private ListPreference mVoicePreference;
    private ListPreference mSettingsKeyPreference;
    private ListPreference mKeyboardModePortraitPreference;
    private ListPreference mKeyboardModeLandscapePreference;
    private Preference mInputConnectionInfo;
    private Preference mLabelVersion;

    private boolean mVoiceOn;

    private boolean mOkClicked = false;
    private String mVoiceModeOff;

    @Override
    protected void onCreate(Bundle icicle) {
        super.onCreate(icicle);
        addPreferencesFromResource(R.xml.prefs);
        mQuickFixes = (CheckBoxPreference) findPreference(QUICK_FIXES_KEY);
        mVoicePreference = (ListPreference) findPreference(VOICE_SETTINGS_KEY);
        mSettingsKeyPreference = (ListPreference) findPreference(PREF_SETTINGS_KEY);
        mInputConnectionInfo = (Preference) findPreference(INPUT_CONNECTION_INFO);
        mLabelVersion = (Preference) findPreference("label_version");
        // Export and import use the Storage Access Framework.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.KITKAT) {
            getPreferenceScreen().removePreference(findPreference(BACKUP_SETTINGS_KEY));
        }

        // TODO(klausw): remove these when no longer needed
        mKeyboardModePortraitPreference = (ListPreference) findPreference("pref_keyboard_mode_portrait");
        mKeyboardModeLandscapePreference = (ListPreference) findPreference("pref_keyboard_mode_landscape");
        
        SharedPreferences prefs = getPreferenceManager().getSharedPreferences();
        prefs.registerOnSharedPreferenceChangeListener(this);

        mVoiceModeOff = getString(R.string.voice_mode_off);
        mVoiceOn = !(prefs.getString(VOICE_SETTINGS_KEY, mVoiceModeOff).equals(mVoiceModeOff));
    }

    @Override
    protected void onResume() {
        super.onResume();
        int autoTextSize = AutoText.getSize(getListView());
        if (autoTextSize < 1) {
            ((PreferenceGroup) findPreference(PREDICTION_SETTINGS_KEY))
                    .removePreference(mQuickFixes);
        }
        
        Log.i(TAG, "compactModeEnabled=" + LatinIME.sKeyboardSettings.compactModeEnabled);
        if (!LatinIME.sKeyboardSettings.compactModeEnabled) {
            CharSequence[] oldEntries = mKeyboardModePortraitPreference.getEntries();
            CharSequence[] oldValues = mKeyboardModePortraitPreference.getEntryValues();
            
            if (oldEntries.length > 2) {
                CharSequence[] newEntries = new CharSequence[] { oldEntries[0], oldEntries[2] };
                CharSequence[] newValues = new CharSequence[] { oldValues[0], oldValues[2] };
                mKeyboardModePortraitPreference.setEntries(newEntries);
                mKeyboardModePortraitPreference.setEntryValues(newValues);
                mKeyboardModeLandscapePreference.setEntries(newEntries);
                mKeyboardModeLandscapePreference.setEntryValues(newValues);
            }
        }
        
        updateSummaries();

        String version = "";
        try {
            PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), PackageManager.GET_SIGNATURES);
            version = info.versionName;
            boolean isOfficial = false;
            for (Signature sig : info.signatures) {
                byte[] b = sig.toByteArray();
                int out = 0;
                for (int i = 0; i < b.length; ++i) {
                    int pos = i % 4;
                    out ^= b[i] << (pos * 4);
                }
                if (out == -466825) {
                    isOfficial = true;
                }
                //version += " [" + Integer.toHexString(out) + "]";
            }
            version += isOfficial ? " official" : " custom";
        } catch (PackageManager.NameNotFoundException e) {
            Log.e(TAG, "Could not find version info.");
        }

        mLabelVersion.setSummary(version);
    }

    @Override
    protected void onDestroy() {
        getPreferenceManager().getSharedPreferences().unregisterOnSharedPreferenceChangeListener(
                this);
        super.onDestroy();
    }

    public void onSharedPreferenceChanged(SharedPreferences prefs, String key) {
        (new BackupManager(this)).dataChanged();
        // If turning on voice input, show dialog
        if (key.equals(VOICE_SETTINGS_KEY) && !mVoiceOn) {
            if (!prefs.getString(VOICE_SETTINGS_KEY, mVoiceModeOff)
                    .equals(mVoiceModeOff)) {
                showVoiceConfirmation();
            }
        }
        mVoiceOn = !(prefs.getString(VOICE_SETTINGS_KEY, mVoiceModeOff).equals(mVoiceModeOff));
        updateVoiceModeSummary();
        updateSummaries();
    }

    @Override
    public boolean onPreferenceTreeClick(PreferenceScreen screen, Preference preference) {
        String key = preference.getKey();
        Intent intent;
        int requestCode;
        if (EXPORT_SETTINGS_KEY.equals(key)) {
            String date = new SimpleDateFormat("yyyyMMdd", Locale.US).format(new Date());
            intent = new Intent(Intent.ACTION_CREATE_DOCUMENT)
                    .addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("application/zip")
                    .putExtra(Intent.EXTRA_TITLE, "hackerskeyboard-settings-" + date + ".zip");
            requestCode = EXPORT_SETTINGS_REQUEST;
        } else if (IMPORT_SETTINGS_KEY.equals(key)) {
            intent = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                    .addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("*/*");
            requestCode = IMPORT_SETTINGS_REQUEST;
        } else {
            return super.onPreferenceTreeClick(screen, preference);
        }
        try {
            startActivityForResult(intent, requestCode);
        } catch (ActivityNotFoundException e) {
            Log.e(TAG, "No document picker", e);
            Toast.makeText(this, getString(requestCode == EXPORT_SETTINGS_REQUEST
                    ? R.string.export_settings_failed : R.string.import_settings_failed,
                    e.getMessage()), Toast.LENGTH_LONG).show();
        }
        return true;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        if (requestCode == EXPORT_SETTINGS_REQUEST) {
            exportSettings(data.getData());
        } else if (requestCode == IMPORT_SETTINGS_REQUEST) {
            importSettings(data.getData());
        }
    }

    private void exportSettings(Uri uri) {
        try {
            OutputStream out = getContentResolver().openOutputStream(uri);
            try {
                SettingsBackup.exportTo(this, out);
            } finally {
                out.close();
            }
        } catch (IOException | JSONException | SecurityException e) {
            Log.e(TAG, "Settings export failed", e);
            Toast.makeText(this, getString(R.string.export_settings_failed, e.getMessage()),
                    Toast.LENGTH_LONG).show();
            return;
        }
        Toast.makeText(this, R.string.export_settings_done, Toast.LENGTH_SHORT).show();
    }

    private void importSettings(Uri uri) {
        SharedPreferences prefs = getPreferenceManager().getSharedPreferences();
        // This screen would otherwise react to every replaced value.
        prefs.unregisterOnSharedPreferenceChangeListener(this);
        try {
            InputStream in = getContentResolver().openInputStream(uri);
            try {
                SettingsBackup.importFrom(this, in);
            } finally {
                in.close();
            }
        } catch (IOException | JSONException | SecurityException e) {
            Log.e(TAG, "Settings import failed", e);
            prefs.registerOnSharedPreferenceChangeListener(this);
            Toast.makeText(this, getString(R.string.import_settings_failed, e.getMessage()),
                    Toast.LENGTH_LONG).show();
            return;
        }
        Toast.makeText(this, R.string.import_settings_done, Toast.LENGTH_LONG).show();
        finish();
        // The keyboard caches settings in memory and keeps the dictionary
        // databases open, so restart the process to load the imported state.
        // The system rebinds the input method service on its own.
        new Handler().postDelayed(new Runnable() {
            public void run() {
                Process.killProcess(Process.myPid());
            }
        }, IMPORT_RESTART_DELAY_MS);
    }

    static Map<Integer, String> INPUT_CLASSES = new HashMap<Integer, String>();
    static Map<Integer, String> DATETIME_VARIATIONS = new HashMap<Integer, String>();
    static Map<Integer, String> TEXT_VARIATIONS = new HashMap<Integer, String>();
    static Map<Integer, String> NUMBER_VARIATIONS = new HashMap<Integer, String>();
    static {
        INPUT_CLASSES.put(0x00000004, "DATETIME");
        INPUT_CLASSES.put(0x00000002, "NUMBER");
        INPUT_CLASSES.put(0x00000003, "PHONE");
        INPUT_CLASSES.put(0x00000001, "TEXT"); 
        INPUT_CLASSES.put(0x00000000, "NULL");
        
        DATETIME_VARIATIONS.put(0x00000010, "DATE");
        DATETIME_VARIATIONS.put(0x00000020, "TIME");

        NUMBER_VARIATIONS.put(0x00000010, "PASSWORD");

        TEXT_VARIATIONS.put(0x00000020, "EMAIL_ADDRESS");
        TEXT_VARIATIONS.put(0x00000030, "EMAIL_SUBJECT");
        TEXT_VARIATIONS.put(0x000000b0, "FILTER");
        TEXT_VARIATIONS.put(0x00000050, "LONG_MESSAGE");
        TEXT_VARIATIONS.put(0x00000080, "PASSWORD");
        TEXT_VARIATIONS.put(0x00000060, "PERSON_NAME");
        TEXT_VARIATIONS.put(0x000000c0, "PHONETIC");
        TEXT_VARIATIONS.put(0x00000070, "POSTAL_ADDRESS");
        TEXT_VARIATIONS.put(0x00000040, "SHORT_MESSAGE");
        TEXT_VARIATIONS.put(0x00000010, "URI");
        TEXT_VARIATIONS.put(0x00000090, "VISIBLE_PASSWORD");
        TEXT_VARIATIONS.put(0x000000a0, "WEB_EDIT_TEXT");
        TEXT_VARIATIONS.put(0x000000d0, "WEB_EMAIL_ADDRESS");
        TEXT_VARIATIONS.put(0x000000e0, "WEB_PASSWORD");

    }
    
    private static void addBit(StringBuffer buf, int bit, String str) {
        if (bit != 0) {
            buf.append("|");
            buf.append(str);
        }
    }

    private static String inputTypeDesc(int type) {
        int cls = type & 0x0000000f; // MASK_CLASS
        int flags = type & 0x00fff000; // MASK_FLAGS
        int var = type &  0x00000ff0; // MASK_VARIATION

        StringBuffer out = new StringBuffer();
        String clsName = INPUT_CLASSES.get(cls);
        out.append(clsName != null ? clsName : "?");
        
        if (cls == InputType.TYPE_CLASS_TEXT) {
            String varName = TEXT_VARIATIONS.get(var);
            if (varName != null) {
                out.append(".");
                out.append(varName);
            }
            addBit(out, flags & 0x00010000, "AUTO_COMPLETE");
            addBit(out, flags & 0x00008000, "AUTO_CORRECT");
            addBit(out, flags & 0x00001000, "CAP_CHARACTERS");
            addBit(out, flags & 0x00004000, "CAP_SENTENCES");
            addBit(out, flags & 0x00002000, "CAP_WORDS");
            addBit(out, flags & 0x00040000, "IME_MULTI_LINE");
            addBit(out, flags & 0x00020000, "MULTI_LINE");
            addBit(out, flags & 0x00080000, "NO_SUGGESTIONS");
        } else if (cls == InputType.TYPE_CLASS_NUMBER) {
            String varName = NUMBER_VARIATIONS.get(var);
            if (varName != null) {
                out.append(".");
                out.append(varName);
            }
            addBit(out, flags & 0x00002000, "DECIMAL");
            addBit(out, flags & 0x00001000, "SIGNED");        
        } else if (cls == InputType.TYPE_CLASS_DATETIME) {
            String varName = DATETIME_VARIATIONS.get(var);
            if (varName != null) {
                out.append(".");
                out.append(varName);
            }
        }
        return out.toString();
    }

    private void updateSummaries() {
        Resources res = getResources();
        mSettingsKeyPreference.setSummary(
                res.getStringArray(R.array.settings_key_modes)
                [mSettingsKeyPreference.findIndexOfValue(mSettingsKeyPreference.getValue())]);

        mInputConnectionInfo.setSummary(String.format("%s type=%s",
                LatinIME.sKeyboardSettings.editorPackageName,
                inputTypeDesc(LatinIME.sKeyboardSettings.editorInputType)
                ));
    }

    private void showVoiceConfirmation() {
        mOkClicked = false;
        showDialog(VOICE_INPUT_CONFIRM_DIALOG);
    }

    private void updateVoiceModeSummary() {
        mVoicePreference.setSummary(
                getResources().getStringArray(R.array.voice_input_modes_summary)
                [mVoicePreference.findIndexOfValue(mVoicePreference.getValue())]);
    }

    @Override
    protected Dialog onCreateDialog(int id) {
        switch (id) {
            default:
                Log.e(TAG, "unknown dialog " + id);
                return null;
        }
    }

    public void onDismiss(DialogInterface dialog) {
        if (!mOkClicked) {
            // This assumes that onPreferenceClick gets called first, and this if the user
            // agreed after the warning, we set the mOkClicked value to true.
            mVoicePreference.setValue(mVoiceModeOff);
        }
    }

    private void updateVoicePreference() {
    }
}
