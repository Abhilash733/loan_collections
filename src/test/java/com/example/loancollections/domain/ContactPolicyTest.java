package com.example.loancollections.domain;

import com.example.loancollections.domain.ContactPolicy.Preference;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ContactPolicyTest {
    private static final Preference BASE = new Preference("CPF-ACC-2026-000001-01", "PHONE", LocalDate.parse("2025-01-01"), null,
            LocalTime.of(9, 0), LocalTime.of(18, 0), "NONE", "Asia/Kolkata");

    @Test
    void contactInsideLocalWindowIsAllowed() {
        // 2026-10-28T05:00Z = 10:30 IST on a Wednesday
        var decision = ContactPolicy.evaluate(List.of(BASE), "PHONE", Instant.parse("2026-10-28T05:00:00Z"));
        assertTrue(decision.allowed(), decision.reasons().toString());
    }

    @Test
    void contactOutsideCustomerWindowIsBlocked() {
        // 13:30Z = 19:00 IST: inside the 08-20 global window but after the customer's 18:00 end
        var decision = ContactPolicy.evaluate(List.of(BASE), "PHONE", Instant.parse("2026-10-28T13:30:00Z"));
        assertFalse(decision.allowed());
        assertEquals(List.of("OUTSIDE_CUSTOMER_WINDOW"), decision.reasons());
    }

    @Test
    void doNotContactBlocksEvenInsideWindow() {
        var dnc = new Preference("CPF-ACC-2026-000001-02", null, LocalDate.parse("2026-10-01"), null, null, null, "DO_NOT_CONTACT", "Asia/Kolkata");
        var decision = ContactPolicy.evaluate(List.of(BASE, dnc), "PHONE", Instant.parse("2026-10-28T05:00:00Z"));
        assertFalse(decision.allowed());
        assertTrue(decision.reasons().get(0).startsWith("DO_NOT_CONTACT"));
    }

    @Test
    void hardshipSwitchesChannelAndBlocksPhone() {
        var hardship = new Preference("CPF-ACC-2026-000001-02", null, LocalDate.parse("2026-10-01"), LocalDate.parse("2026-12-30"),
                null, null, "HARDSHIP", "Asia/Kolkata");
        var prefs = List.of(BASE, hardship);
        assertEquals("EMAIL", ContactPolicy.channelFor(prefs, LocalDate.parse("2026-10-28")).orElseThrow());
        assertFalse(ContactPolicy.evaluate(prefs, "PHONE", Instant.parse("2026-10-28T05:00:00Z")).allowed());
        assertTrue(ContactPolicy.evaluate(prefs, "EMAIL", Instant.parse("2026-10-28T05:00:00Z")).allowed());
    }

    @Test
    void missingPreferenceFailsClosed() {
        var decision = ContactPolicy.evaluate(List.of(), "PHONE", Instant.parse("2026-10-28T05:00:00Z"));
        assertFalse(decision.allowed());
        assertEquals(List.of("NO_CONTACT_PREFERENCE"), decision.reasons());
    }

    @Test
    void nextSlotSkipsSundayAndStartsAtWindow() {
        // Saturday 2026-10-31 19:00 IST is after the window; Sunday is excluded; Monday 09:00 IST = 03:30Z
        var slot = ContactPolicy.nextPermittedSlot(List.of(BASE), Instant.parse("2026-10-31T13:30:00Z"), 14).orElseThrow();
        assertEquals(Instant.parse("2026-11-02T03:30:00Z"), slot);
    }
}
