/* SPDX-License-Identifier: GPL-2.0-or-later */
package org.telegram.messenger.amegram;

import android.content.SharedPreferences;
import org.junit.Test;

import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.*;

/** Storage-boundary tests with a preference double; actual Android disk tests are separate. */
public class AmeGramSettingsBackupTest {
    @Test
    public void exportUsesAnAllowlistInsteadOfEnumeratingPreferences() throws Exception {
        Store store = new Store();
        store.memory.put("oled_dark_background", true);
        store.memory.put("unrelated", "private-future-preference-fixture");
        String backup = new String(AmeGramBackup.encode(new AmeGramSettings(store.preferences).snapshot()), StandardCharsets.UTF_8);
        assertFalse(backup.contains("unrelated"));
        assertFalse(backup.contains("private-future-preference-fixture"));
        assertEquals(0, store.commits);
    }

    @Test
    public void restoringAndResettingPreserveUnknownPreferences() {
        Store store = new Store();
        store.memory.put("unrelated", "keep");
        AmeGramSettings settings = new AmeGramSettings(store.preferences);
        assertTrue(settings.restore(new AmeGramBackup.Snapshot(true)));
        assertTrue(new AmeGramSettings(store.preferences).usesOledDarkBackground());
        assertEquals(true, store.disk.get("oled_dark_background"));
        assertEquals("keep", store.memory.get("unrelated"));
        assertTrue(settings.restore(new AmeGramBackup.Snapshot(false)));
        assertFalse(settings.usesOledDarkBackground());
        assertEquals("keep", store.memory.get("unrelated"));
        assertEquals(2, store.commits);
    }

    @Test
    public void failedDiskWriteIsReportedInsteadOfClaimingSuccess() {
        Store store = new Store();
        store.commitSucceeds = false;
        assertFalse(new AmeGramSettings(store.preferences).restore(new AmeGramBackup.Snapshot(true)));
        assertTrue(store.disk.isEmpty());
        assertEquals(1, store.commits);
    }

    private static final class Store {
        final Map<String, Object> memory = new HashMap<>();
        final Map<String, Object> disk = new HashMap<>();
        boolean commitSucceeds = true;
        int commits;
        final SharedPreferences preferences = (SharedPreferences) Proxy.newProxyInstance(
                SharedPreferences.class.getClassLoader(), new Class<?>[]{SharedPreferences.class}, (proxy, method, arguments) -> {
                    if (method.getName().equals("getBoolean")) return memory.getOrDefault(arguments[0], arguments[1]);
                    if (method.getName().equals("edit")) return editor();
                    throw new AssertionError("Unexpected preference access: " + method.getName());
                });

        private SharedPreferences.Editor editor() {
            Map<String, Object> pending = new HashMap<>();
            return (SharedPreferences.Editor) Proxy.newProxyInstance(SharedPreferences.Editor.class.getClassLoader(),
                    new Class<?>[]{SharedPreferences.Editor.class}, (proxy, method, arguments) -> {
                        if (method.getName().equals("putBoolean")) {
                            pending.put((String) arguments[0], arguments[1]);
                            return proxy;
                        }
                        if (method.getName().equals("commit")) {
                            commits++;
                            memory.putAll(pending);
                            if (commitSucceeds) disk.putAll(memory);
                            return commitSucceeds;
                        }
                        throw new AssertionError("Unexpected editor operation: " + method.getName());
                    });
        }
    }
}
