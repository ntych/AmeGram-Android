/* SPDX-License-Identifier: GPL-2.0-or-later */
package org.telegram.messenger.amegram;

import android.content.Context;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class AmeGramSettingsTest {
    private Context context;

    @Before
    public void setUp() {
        // Use the instrumentation APK sandbox, never the target app's user data.
        context = InstrumentationRegistry.getInstrumentation().getContext();
        clear();
    }

    @After
    public void clear() {
        assertTrue(context.getSharedPreferences("amegram_settings", Context.MODE_PRIVATE).edit().clear().commit());
    }

    @Test
    public void oledPreferenceSurvivesReloadAndCanBeDisabled() {
        assertFalse(new AmeGramSettings(context).usesOledDarkBackground());
        new AmeGramSettings(context).setOledDarkBackground(true);
        // A synchronous empty commit waits for prior apply writes to reach disk.
        assertTrue(context.getSharedPreferences("amegram_settings", Context.MODE_PRIVATE).edit().commit());
        context.getSharedPreferences("amegram_settings", Context.MODE_PRIVATE).edit().putBoolean("unrelated", true).commit();
        assertTrue(new AmeGramSettings(context).usesOledDarkBackground());
        new AmeGramSettings(context).setOledDarkBackground(false);
        assertFalse(new AmeGramSettings(context).usesOledDarkBackground());
        assertTrue(context.getSharedPreferences("amegram_settings", Context.MODE_PRIVATE).getBoolean("unrelated", false));
    }

    @Test
    public void upstreamPreferencesAreUntouched() {
        android.content.SharedPreferences upstream = context.getSharedPreferences("mainconfig", Context.MODE_PRIVATE);
        java.util.Map<String, ?> before = upstream.getAll();
        new AmeGramSettings(context).setOledDarkBackground(true);
        assertEquals(before, upstream.getAll());
    }

    @Test
    public void backupRoundTripCommitsOnlyKnownAmeGramSettings() throws Exception {
        android.content.SharedPreferences local = context.getSharedPreferences("amegram_settings", Context.MODE_PRIVATE);
        assertTrue(local.edit().putString("unrelated", "keep").commit());
        android.content.SharedPreferences upstream = context.getSharedPreferences("mainconfig", Context.MODE_PRIVATE);
        java.util.Map<String, ?> before = upstream.getAll();
        AmeGramSettings settings = new AmeGramSettings(context);
        settings.setOledDarkBackground(true);
        byte[] bytes = AmeGramBackup.encode(settings.snapshot());
        assertTrue(settings.restore(new AmeGramBackup.Snapshot(false)));
        assertTrue(settings.restore(AmeGramBackup.decode(new java.io.ByteArrayInputStream(bytes))));
        assertTrue(new AmeGramSettings(context).usesOledDarkBackground());
        assertTrue(local.getBoolean("oled_dark_background", false));
        assertEquals("keep", local.getString("unrelated", null));
        assertEquals(before, upstream.getAll());
    }
}
