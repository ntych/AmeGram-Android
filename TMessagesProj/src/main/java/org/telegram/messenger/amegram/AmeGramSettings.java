/* SPDX-License-Identifier: GPL-2.0-or-later
 * Copyright (c) 2026 Umi.
 */
package org.telegram.messenger.amegram;

import android.content.Context;
import android.content.SharedPreferences;

/** Device-wide UI preferences only. Never store accounts, auth keys or messages here. */
public final class AmeGramSettings {
    private static final String FILE_NAME = "amegram_settings";
    private static final String OLED_DARK_BACKGROUND = "oled_dark_background";
    private final SharedPreferences preferences;

    public AmeGramSettings(Context context) {
        this(context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE));
    }

    AmeGramSettings(SharedPreferences preferences) {
        this.preferences = preferences;
    }

    public boolean usesOledDarkBackground() {
        return preferences.getBoolean(OLED_DARK_BACKGROUND, false);
    }

    public void setOledDarkBackground(boolean enabled) {
        preferences.edit().putBoolean(OLED_DARK_BACKGROUND, enabled).apply();
    }

    public AmeGramBackup.Snapshot snapshot() {
        return new AmeGramBackup.Snapshot(usesOledDarkBackground());
    }

    /** Call on a worker thread. Preserve unknown keys and report disk-write failures. */
    public boolean restore(AmeGramBackup.Snapshot snapshot) {
        return preferences.edit().putBoolean(OLED_DARK_BACKGROUND, snapshot.oledDarkBackground).commit();
    }
}
