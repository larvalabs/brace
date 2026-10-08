package com.larvalabs.brace;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Coarse User-Agent classification for {@link Analytics}: is it a bot, and which device class,
 * browser family and OS. Deliberately small and dependency-free. Analytics only needs the handful
 * of families a person would recognise on a dashboard, not versions or exact devices.
 *
 * <p>Chromium's low-entropy client hints ({@code Sec-CH-UA-Mobile}, {@code Sec-CH-UA-Platform})
 * are sent by default on HTTPS and are preferred when present, since Chrome has frozen most of the
 * UA string.
 */
final class UserAgents {

    private UserAgents() {}

    // Substrings that mark scripted clients, crawlers, monitors and link-preview fetchers. Matched
    // case-insensitively. A UA that doesn't start with "Mozilla/" is already treated as a bot (every
    // mainstream browser still sends that prefix), so this list mostly catches crawlers that dress
    // up as browsers.
    private static final Pattern BOT = Pattern.compile(
        "bot|crawl|spider|slurp|scrap|fetch|curl|wget|python|java/|okhttp|go-http|httpclient|axios"
        + "|node-fetch|undici|headless|phantom|puppeteer|playwright|selenium|lighthouse|pagespeed"
        + "|gtmetrix|pingdom|uptime|monitor|statuscake|preview|facebookexternalhit|embedly|whatsapp"
        + "|telegram|discord|slack|skype|vkshare|feed|rss|libwww|httpie|postman|insomnia|semrush"
        + "|ahrefs|mj12|petal|bytespider|gptbot|ccbot|perplexity|dataprovider|zgrab|masscan|nmap"
        + "|nikto|sqlmap|censys|expanse|qualys|nessus");

    static boolean isBot(String ua) {
        if (ua == null || ua.isBlank()) return true;
        if (!ua.startsWith("Mozilla/") && !ua.startsWith("Opera/")) return true;
        return BOT.matcher(ua.toLowerCase(Locale.ROOT)).find();
    }

    /** {@code desktop}, {@code mobile} or {@code tablet}. */
    static String device(String ua, String chMobile) {
        if (ua == null) ua = "";
        if (ua.contains("iPad") || ua.contains("Tablet")
            || (ua.contains("Android") && !ua.contains("Mobile"))) {
            return "tablet";
        }
        if ("?1".equals(chMobile)) return "mobile";
        if (ua.contains("Mobi") || ua.contains("iPhone") || ua.contains("iPod")) return "mobile";
        return "desktop";
    }

    /** Browser family. Order matters: most Chromium browsers also say "Chrome" and "Safari". */
    static String browser(String ua) {
        if (ua == null) return "Other";
        if (ua.contains("Edg/") || ua.contains("EdgA/") || ua.contains("EdgiOS")) return "Edge";
        if (ua.contains("OPR/") || ua.contains("Opera")) return "Opera";
        if (ua.contains("SamsungBrowser")) return "Samsung Internet";
        if (ua.contains("Vivaldi")) return "Vivaldi";
        if (ua.contains("DuckDuckGo/")) return "DuckDuckGo";
        if (ua.contains("Firefox/") || ua.contains("FxiOS")) return "Firefox";
        if (ua.contains("CriOS") || ua.contains("Chrome/") || ua.contains("Chromium")) return "Chrome";
        if (ua.contains("Safari/")) return "Safari";
        return "Other";
    }

    /** Operating system family, from {@code Sec-CH-UA-Platform} when sent. */
    static String os(String ua, String chPlatform) {
        if (chPlatform != null) {
            String p = chPlatform.replace("\"", "").strip();
            switch (p) {
                case "Windows", "macOS", "Android", "Linux", "iOS" -> { return p; }
                case "Chrome OS", "ChromeOS" -> { return "ChromeOS"; }
                default -> { /* fall through to the UA string */ }
            }
        }
        if (ua == null) return "Other";
        if (ua.contains("iPhone") || ua.contains("iPad") || ua.contains("iPod")) return "iOS";
        if (ua.contains("Android")) return "Android";
        if (ua.contains("Windows")) return "Windows";
        if (ua.contains("CrOS")) return "ChromeOS";
        if (ua.contains("Macintosh") || ua.contains("Mac OS X")) return "macOS";
        if (ua.contains("Linux")) return "Linux";
        return "Other";
    }
}
