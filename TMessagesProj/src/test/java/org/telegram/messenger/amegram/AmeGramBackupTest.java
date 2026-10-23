/* SPDX-License-Identifier: GPL-2.0-or-later */
package org.telegram.messenger.amegram;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.Assert.*;

public class AmeGramBackupTest {
    private static final String VALID = "{\"format\":\"amegram-settings\",\"version\":1,\"settings\":{\"oled_dark_background\":true}}";

    @Test
    public void bothPreferenceValuesRoundTrip() throws Exception {
        for (boolean oled : new boolean[]{false, true}) {
            byte[] bytes = AmeGramBackup.encode(new AmeGramBackup.Snapshot(oled));
            assertTrue(bytes.length < AmeGramBackup.MAX_BYTES);
            assertEquals(oled, AmeGramBackup.decode(new ByteArrayInputStream(bytes)).oledDarkBackground);
        }
    }

    @Test
    public void fieldOrderAndWhitespaceAreNotSignificant() throws Exception {
        String text = " { \"settings\":{\"oled_dark_background\":false},\"version\":1,\"format\":\"amegram-settings\" }\n";
        assertFalse(AmeGramBackup.decode(stream(text)).oledDarkBackground);
    }

    @Test
    public void unknownFieldsCannotImportAccountOrOtherPreferenceData() {
        reject(VALID.replace("\"version\":1", "\"private_fixture\":\"not-an-account-key\",\"version\":1"));
        reject(VALID.replace("\"oled_dark_background\":true", "\"oled_dark_background\":true,\"unknown_setting\":false"));
        reject(VALID.replace("amegram-settings", "telegram-session"));
    }

    @Test
    public void duplicateFieldsAreRejectedAtBothLevels() {
        reject(VALID.replace("\"version\":1", "\"version\":1,\"version\":1"));
        reject(VALID.replace("\"format\":\"amegram-settings\"", "\"format\":\"amegram-settings\",\"format\":\"amegram-settings\""));
        reject(VALID.replace("\"oled_dark_background\":true", "\"oled_dark_background\":false,\"oled_dark_background\":true"));
        reject(VALID.replace("\"settings\":", "\"settings\":{\"oled_dark_background\":false},\"settings\":"));
    }

    @Test
    public void unsupportedOrCoercedVersionsAreRejected() {
        for (String version : new String[]{"0", "2", "-1", "1.0", "\"1\"", "null", "true"}) {
            reject(VALID.replace("\"version\":1", "\"version\":" + version));
        }
    }

    @Test
    public void missingOrIncorrectTypesAreRejected() {
        for (String value : new String[]{"null", "1", "\"true\"", "[]", "{}"}) {
            reject(VALID.replace("\"oled_dark_background\":true", "\"oled_dark_background\":" + value));
        }
        for (String text : new String[]{"", "null", "[]", "{}", "{\"version\":1}",
                VALID.replace("\"format\":\"amegram-settings\",", ""),
                VALID.replace("\"version\":1,", ""),
                VALID.replace("\"oled_dark_background\":true", "")}) reject(text);
    }

    @Test
    public void trailingDocumentsAndNonJsonSyntaxAreRejected() {
        for (String text : new String[]{VALID + "{}", VALID + "private-fixture", "/*comment*/" + VALID,
                VALID.replace('"', '\''), VALID.replace("\"version\"", "version"), VALID.substring(0, VALID.length() - 1)}) {
            reject(text);
        }
    }

    @Test
    public void byteLimitAcceptsBoundaryAndRejectsOverflow() throws Exception {
        byte[] valid = VALID.getBytes(StandardCharsets.UTF_8);
        byte[] boundary = Arrays.copyOf(valid, AmeGramBackup.MAX_BYTES);
        Arrays.fill(boundary, valid.length, boundary.length, (byte) ' ');
        assertTrue(AmeGramBackup.decode(new ByteArrayInputStream(boundary)).oledDarkBackground);
        byte[] overflow = Arrays.copyOf(boundary, boundary.length + 1);
        overflow[overflow.length - 1] = ' ';
        rejectBytes(overflow);
    }

    @Test
    public void malformedUtf8IsRejectedWithoutReplacementCharacters() {
        rejectBytes(new byte[]{(byte) 0xc3, 0x28});
    }

    @Test
    public void ioFailureIsRedactedWithoutRetainingItsCause() {
        try {
            AmeGramBackup.decode(new ByteArrayInputStream(new byte[0]) {
                @Override
                public int read(byte[] bytes) throws IOException {
                    throw new IOException("private-provider-fixture");
                }
            });
            fail("Expected failure");
        } catch (IOException error) {
            assertEquals("Invalid or unsupported AmeGram settings backup.", error.getMessage());
            assertNull(error.getCause());
        }
    }

    private static ByteArrayInputStream stream(String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
    }

    private static void reject(String text) {
        rejectBytes(text.getBytes(StandardCharsets.UTF_8));
    }

    private static void rejectBytes(byte[] bytes) {
        try {
            AmeGramBackup.decode(new ByteArrayInputStream(bytes));
            fail("Expected malformed/unsupported backup to fail");
        } catch (IOException error) {
            assertEquals("Invalid or unsupported AmeGram settings backup.", error.getMessage());
            assertNull(error.getCause());
        }
    }
}
