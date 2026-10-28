package com.example.loancollections.domain;

import com.example.loancollections.domain.CollectionsRules;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Hard gates evaluated before any contact. A priority score never overrides them; missing
 * preference data fails closed.
 */
public final class ContactPolicy {
    private ContactPolicy() {}

    public record Preference(String ref, String channel, LocalDate from, LocalDate to, LocalTime start,
                             LocalTime end, String restrictionType, String timezoneId) {
        public boolean activeOn(LocalDate day) { return CollectionsRules.restrictionActive(from, to, day); }
    }

    public record Decision(boolean allowed, String channel, Instant at, List<String> reasons) {}

    public static Optional<Preference> basePreference(List<Preference> prefs, LocalDate day) {
        Preference found = null;
        for (Preference p : prefs) if ("NONE".equals(p.restrictionType()) && p.activeOn(day)) found = p;
        return Optional.ofNullable(found);
    }

    public static List<Preference> activeRestrictions(List<Preference> prefs, LocalDate day) {
        return prefs.stream().filter(p -> !"NONE".equals(p.restrictionType()) && p.activeOn(day)).toList();
    }

    public static boolean hardship(List<Preference> prefs, LocalDate day) {
        return activeRestrictions(prefs, day).stream().anyMatch(p -> "HARDSHIP".equals(p.restrictionType()));
    }

    /** Preferred channel, switched to EMAIL while a hardship arrangement is active. */
    public static Optional<String> channelFor(List<Preference> prefs, LocalDate day) {
        return basePreference(prefs, day).map(p -> hardship(prefs, day)
                && CollectionsRules.HARDSHIP_BLOCKED_CHANNELS.contains(p.channel()) ? "EMAIL" : p.channel());
    }

    public static Decision evaluate(List<Preference> prefs, String channel, Instant at) {
        Optional<Preference> base = basePreference(prefs, at.atZone(ZoneOffset.UTC).toLocalDate());
        if (base.isEmpty()) return new Decision(false, channel, at, List.of("NO_CONTACT_PREFERENCE"));
        ZoneId zone = ZoneId.of(base.get().timezoneId());
        LocalDate localDay = at.atZone(zone).toLocalDate();
        List<String> reasons = new ArrayList<>();
        for (Preference r : activeRestrictions(prefs, localDay)) {
            if (CollectionsRules.BLOCKING_RESTRICTIONS.contains(r.restrictionType()))
                reasons.add(r.restrictionType() + " (" + r.ref() + ")");
            else if ("HARDSHIP".equals(r.restrictionType()) && CollectionsRules.HARDSHIP_BLOCKED_CHANNELS.contains(channel))
                reasons.add("HARDSHIP_CHANNEL_" + channel + " (" + r.ref() + ")");
        }
        CollectionsRules.contactWindowViolation(at, zone, base.get().start(), base.get().end()).ifPresent(reasons::add);
        return new Decision(reasons.isEmpty(), channel, at, List.copyOf(reasons));
    }

    /** Earliest permissible contact time at or after {@code from}, looking ahead {@code horizonDays} days. */
    public static Optional<Instant> nextPermittedSlot(List<Preference> prefs, Instant from, int horizonDays) {
        Optional<Preference> base = basePreference(prefs, from.atZone(ZoneOffset.UTC).toLocalDate());
        if (base.isEmpty()) return Optional.empty();
        ZoneId zone = ZoneId.of(base.get().timezoneId());
        ZonedDateTime localFrom = from.atZone(zone);
        LocalTime start = max(base.get().start(), CollectionsRules.GLOBAL_WINDOW_START);
        LocalTime end = min(base.get().end(), CollectionsRules.GLOBAL_WINDOW_END);
        for (int i = 0; i <= horizonDays; i++) {
            LocalDate day = localFrom.toLocalDate().plusDays(i);
            if (day.getDayOfWeek() == DayOfWeek.SUNDAY) continue;
            boolean blocked = activeRestrictions(prefs, day).stream()
                    .anyMatch(r -> CollectionsRules.BLOCKING_RESTRICTIONS.contains(r.restrictionType()));
            if (blocked) continue;
            LocalTime candidate = start;
            if (i == 0 && localFrom.toLocalTime().isAfter(start)) {
                LocalTime t = localFrom.toLocalTime().withSecond(0).withNano(0);
                candidate = t.plusMinutes((5 - t.getMinute() % 5) % 5);
                if (candidate.isBefore(t)) continue; // rolled past midnight
            }
            if (candidate.isBefore(end)) return Optional.of(day.atTime(candidate).atZone(zone).toInstant());
        }
        return Optional.empty();
    }

    private static LocalTime max(LocalTime a, LocalTime b) { return a.isAfter(b) ? a : b; }
    private static LocalTime min(LocalTime a, LocalTime b) { return a.isBefore(b) ? a : b; }
}
