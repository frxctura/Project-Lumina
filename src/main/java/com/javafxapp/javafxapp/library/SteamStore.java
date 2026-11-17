package com.javafxapp.javafxapp.library;

import com.javafxapp.javafxapp.common.Http;
import com.javafxapp.javafxapp.common.Log;
import com.javafxapp.javafxapp.common.Progress;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class SteamStore {

    public record StoreInfo(String name, String headerImageUrl) {}

    private static final Pattern NAME = Pattern.compile("\"name\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");
    private static final Pattern HEADER = Pattern.compile("\"header_image\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");
    private static final Pattern UNICODE = Pattern.compile("\\\\u([0-9a-fA-F]{4})");

    private SteamStore() {}

    public static boolean isValidAppId(String id) {
        return id != null && id.matches("\\d{1,10}");
    }

    public static Optional<StoreInfo> lookup(String appId) {
        if (!isValidAppId(appId)) return Optional.empty();
        try {
            String json = Http.getString("https://store.steampowered.com/api/appdetails?filters=basic&appids=" + appId);
            if (!json.contains("\"success\":true")) return Optional.empty();
            Matcher name = NAME.matcher(json);
            Matcher header = HEADER.matcher(json);
            if (!name.find()) return Optional.empty();
            return Optional.of(new StoreInfo(unescape(name.group(1)), header.find() ? unescape(header.group(1)) : null));
        } catch (Exception e) {
            Log.error("Store lookup failed for " + appId, e);
            return Optional.empty();
        }
    }

    public record Dlc(String appId, String name) {}

    private static final Pattern DLC_LIST = Pattern.compile("\"dlc\"\\s*:\\s*\\[([0-9,\\s]*)]");
    private static final int MAX_DLC_LOOKUPS = 150;

    public static List<Dlc> dlcs(String appId, Progress progress) throws IOException, InterruptedException {
        if (!isValidAppId(appId)) return List.of();
        String json = Http.getString("https://store.steampowered.com/api/appdetails?filters=basic&appids=" + appId);
        Matcher m = DLC_LIST.matcher(json);
        if (!m.find()) return List.of();

        List<String> ids = Arrays.stream(m.group(1).split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).limit(MAX_DLC_LOOKUPS).toList();
        List<Dlc> result = new ArrayList<>();
        for (int i = 0; i < ids.size(); i++) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            String id = ids.get(i);
            result.add(new Dlc(id, lookup(id).map(StoreInfo::name).orElse("DLC " + id)));
            progress.update(i + 1, ids.size());
        }
        return result;
    }

    static String unescape(String s) {
        Matcher m = UNICODE.matcher(s);
        StringBuilder sb = new StringBuilder();
        while (m.find()) m.appendReplacement(sb, Matcher.quoteReplacement(String.valueOf((char) Integer.parseInt(m.group(1), 16))));
        m.appendTail(sb);
        return sb.toString().replace("\\/", "/").replace("\\\"", "\"").replace("\\\\", "\\");
    }
}
