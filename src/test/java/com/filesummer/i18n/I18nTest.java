package com.filesummer.i18n;

import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class I18nTest {

    @Test
    void chineseIsDefaultAndJapaneseBundleResolves() {
        Locale previous = I18n.getLocale();
        try {
            I18n.setLocale(I18n.parse(null));
            assertEquals("修改日期", I18n.t("detail.date"));

            I18n.setLocale(I18n.parse(I18n.TAG_JA));
            assertEquals(Locale.JAPAN, I18n.getLocale());
            assertEquals("更新日", I18n.t("detail.date"));

            I18n.setLocale(I18n.parse(I18n.TAG_EN));
            assertEquals(Locale.US, I18n.getLocale());
            assertEquals("Modified Date", I18n.t("detail.date"));

            assertTrue(I18n.t("no.such.key").startsWith("!"));
        } finally {
            I18n.setLocale(previous);
        }
    }

    @Test
    void formatsArguments() {
        Locale previous = I18n.getLocale();
        try {
            I18n.setLocale(I18n.parse(I18n.TAG_ZH));
            assertEquals("已加载项目「P」，共 3 个任务。", I18n.t("app.loaded", "P", 3));
        } finally {
            I18n.setLocale(previous);
        }
    }

    @Test
    void chineseResolvesOnEnglishDefaultLocale() {
        // build.gradle pins the test JVM to en-US, i.e. "Chinese UI on an English Windows box".
        // Without a no-fallback Control the zh_CN lookup would land on messages_en_US.
        assertEquals(Locale.US, Locale.getDefault(), "test JVM must run with the CI locale");
        Locale previous = I18n.getLocale();
        try {
            I18n.setLocale(I18n.parse(I18n.TAG_ZH));
            assertEquals("修改日期", I18n.t("detail.date"));
            assertEquals("English", I18n.t("lang.en"));
        } finally {
            I18n.setLocale(previous);
        }
    }

    @Test
    void allBundlesCarryTheSameKeys() throws Exception {
        // raw file comparison: ResourceBundle.keySet() would include parent-bundle keys
        var base = keysOf("messages.properties");
        assertEquals(base, keysOf("messages_ja_JP.properties"), "ja bundle out of sync");
        assertEquals(base, keysOf("messages_en_US.properties"), "en bundle out of sync");
    }

    private java.util.Set<String> keysOf(String file) throws java.io.IOException {
        try (var in = getClass().getResourceAsStream("/" + file)) {
            org.junit.jupiter.api.Assertions.assertNotNull(in, file + " missing");
            return new java.util.PropertyResourceBundle(in).keySet();
        }
    }
}
