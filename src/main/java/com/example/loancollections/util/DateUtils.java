package com.example.loancollections.util;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/** Date/time helpers. The database stores timestamps in UTC. */
public final class DateUtils {
    private DateUtils() {}

    public static LocalDate todayUtc() {
        return LocalDate.now(ZoneOffset.UTC);
    }

    public static LocalDate orToday(LocalDate asOf) {
        return asOf != null ? asOf : todayUtc();
    }

    public static LocalDate utcDate(Instant instant) {
        return instant.atZone(ZoneOffset.UTC).toLocalDate();
    }

    public static LocalDateTime toUtcDateTime(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    public static Instant fromUtcDateTime(LocalDateTime value) {
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }
}
