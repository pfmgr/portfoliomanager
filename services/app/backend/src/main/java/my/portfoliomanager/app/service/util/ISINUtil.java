package my.portfoliomanager.app.service.util;

import java.util.*;
import java.util.regex.Pattern;

public class ISINUtil {
    private static final Pattern ISIN_RE = Pattern.compile("^[A-Z]{2}[A-Z0-9]{9}[0-9]$");
    private ISINUtil(){
        //Statuc class
    }
    public static  List<String> normalizeIsins(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        Set<String> seen = new HashSet<>();
        List<String> normalized = new ArrayList<>();
        for (String raw : values) {
            String isin = normalizeIsin(raw);
            if (seen.add(isin)) {
                normalized.add(isin);
            }
        }
        return normalized;
    }
    public static String normalizeIsin(String value) {
        String trimmed = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (!ISIN_RE.matcher(trimmed).matches()) {
            throw new IllegalArgumentException("Invalid ISIN: " + trimmed);
        }
        return trimmed;
    }
}
