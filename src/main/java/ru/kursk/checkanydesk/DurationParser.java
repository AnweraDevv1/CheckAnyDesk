package ru.kursk.checkanydesk;

import java.util.Locale;
import java.util.OptionalLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class DurationParser {
    private static final Pattern PATTERN = Pattern.compile("^(\\d+)([smhd]?)$");

    private DurationParser() {
    }

    static OptionalLong parse(String string) {
        Matcher matcher = PATTERN.matcher(string.toLowerCase(Locale.ROOT));
        if (!matcher.matches()) {
            return OptionalLong.empty();
        }
        try {
            long l = Long.parseLong(matcher.group(1));
            if (l <= 0L) {
                return OptionalLong.empty();
            }
            long l2 = switch (matcher.group(2)) {
                case "s" -> 1000L;
                case "h" -> 3600000L;
                case "d" -> 86400000L;
                default -> 60000L;
            };
            return OptionalLong.of(Math.multiplyExact(l, l2));
        }
        catch (ArithmeticException | NumberFormatException runtimeException) {
            return OptionalLong.empty();
        }
    }
}

