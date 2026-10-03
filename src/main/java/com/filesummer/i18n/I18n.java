package com.filesummer.i18n;

import java.text.MessageFormat;
import java.util.Locale;
import java.util.MissingResourceException;
import java.util.ResourceBundle;

/**
 * Tiny ResourceBundle wrapper. Bundle files live in src/main/resources
 * (messages.properties = 简体中文 base, messages_ja_JP.properties = 日本語,
 * messages_en_US.properties = English); since JEP 226 they are read as UTF-8 directly.
 */
public final class I18n {

    public static final String TAG_ZH = "zh_CN";
    public static final String TAG_JA = "ja_JP";
    public static final String TAG_EN = "en_US";

    private static volatile Locale locale = Locale.SIMPLIFIED_CHINESE;

    /**
     * The stock control inserts the *JVM default* locale into the candidate chain, so on an
     * English Windows box a zh_CN lookup (which has no messages_zh_CN.properties, the base file
     * is Chinese) would silently land on messages_en_US. No-fallback keeps the chain
     * zh_CN -> zh -> base bundle only.
     */
    private static final ResourceBundle.Control NO_DEFAULT_FALLBACK =
            ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_PROPERTIES);

    private I18n() {
    }

    public static void setLocale(Locale l) {
        locale = l == null ? Locale.SIMPLIFIED_CHINESE : l;
    }

    public static Locale getLocale() {
        return locale;
    }

    /** Maps a persisted language tag ("zh_CN"/"ja_JP"/"en_US") to a Locale; unknown -> Chinese.
     *  Note: full tags are required (Locale.JAPAN / Locale.US) - bare "ja"/"en" would miss
     *  messages_ja_JP.properties / messages_en_US.properties depending on the JVM default locale. */
    public static Locale parse(String tag) {
        if (tag != null) {
            String lower = tag.toLowerCase(Locale.ROOT);
            if (lower.startsWith("ja")) {
                return Locale.JAPAN;
            }
            if (lower.startsWith("en")) {
                return Locale.US;
            }
        }
        return Locale.SIMPLIFIED_CHINESE;
    }

    public static String t(String key, Object... args) {
        String pattern;
        try {
            pattern = ResourceBundle.getBundle("messages", locale, NO_DEFAULT_FALLBACK).getString(key);
        } catch (MissingResourceException e) {
            return "!" + key;
        }
        return args.length == 0 ? pattern : MessageFormat.format(pattern, args);
    }
}
