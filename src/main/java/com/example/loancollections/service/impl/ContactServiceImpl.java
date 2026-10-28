package com.example.loancollections.service.impl;

import com.example.loancollections.domain.CollectionsData.CaseRow;
import com.example.loancollections.domain.ContactPolicy;
import com.example.loancollections.exception.BusinessException;
import com.example.loancollections.exception.ResourceNotFoundException;
import com.example.loancollections.model.dto.response.ContactResult;
import com.example.loancollections.repository.jdbc.CollectionsDataRepository;
import com.example.loancollections.repository.jdbc.ContactAttemptRepository;
import com.example.loancollections.repository.jdbc.ContactPreferenceRepository;
import com.example.loancollections.service.AuditService;
import com.example.loancollections.service.CaseLifecycleService;
import com.example.loancollections.service.ContactService;
import com.example.loancollections.util.DateUtils;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ContactServiceImpl implements ContactService {
    private static final Logger log = LoggerFactory.getLogger(ContactServiceImpl.class);
    private static final Set<String> RECORDABLE_OUTCOMES = Set.of("NO_RESPONSE", "CONTACTED", "DISPUTE_RAISED");
    private static final Set<String> SCHEDULABLE_STATUSES = Set.of("OPEN", "IN_PROGRESS");
    private static final int SLOT_HORIZON_DAYS = 14;

    private final CollectionsDataRepository collectionsData;
    private final ContactAttemptRepository attempts;
    private final ContactPreferenceRepository preferences;
    private final CaseLifecycleService lifecycle;
    private final AuditService audit;

    public ContactServiceImpl(CollectionsDataRepository collectionsData, ContactAttemptRepository attempts,
                              ContactPreferenceRepository preferences, CaseLifecycleService lifecycle, AuditService audit) {
        this.collectionsData = collectionsData;
        this.attempts = attempts;
        this.preferences = preferences;
        this.lifecycle = lifecycle;
        this.audit = audit;
    }

    @Override
    @Transactional
    public ContactResult schedule(String caseRef, Instant requestedAt, String actor) {
        CaseRow c = findCase(caseRef);
        if (!SCHEDULABLE_STATUSES.contains(c.status()))
            throw new BusinessException("Case " + caseRef + " is " + c.status() + "; contact can only be scheduled for OPEN or IN_PROGRESS cases");
        List<ContactPolicy.Preference> prefs = collectionsData.loadForCase(c).preferences(c.accountId());
        Instant now = Instant.now();
        Instant at = requestedAt != null ? requestedAt : ContactPolicy.nextPermittedSlot(prefs, now, SLOT_HORIZON_DAYS).orElse(now);
        String channel = ContactPolicy.channelFor(prefs, DateUtils.utcDate(at)).orElse("LETTER");
        ContactPolicy.Decision decision = ContactPolicy.evaluate(prefs, channel, at);
        String outcome = decision.allowed() ? "SCHEDULED" : "BLOCKED";
        String ref = "CTP-%s-%03d".formatted(c.caseRef(), attempts.nextSequence(c.id()));
        attempts.insert(ref, c.id(), channel, DateUtils.toUtcDateTime(at), outcome,
                decision.allowed() ? null : String.join("; ", decision.reasons()), decision.allowed() ? actor : "POLICY-ENGINE");
        audit.record("CONTACT_ATTEMPT", ref, "CONTACT_" + outcome, actor, String.join("; ", decision.reasons()));
        log.info("Contact {} for case {}: {} via {} at {} {}", ref, caseRef, outcome, channel, at, decision.reasons());
        return new ContactResult(ref, outcome, channel, at, decision.reasons());
    }

    @Override
    @Transactional
    public ContactResult recordOutcome(String contactRef, String outcome, Instant attemptedAt, String actor) {
        if (!RECORDABLE_OUTCOMES.contains(outcome))
            throw new BusinessException("Outcome must be one of " + RECORDABLE_OUTCOMES + "; record promises via the promise endpoint");
        ContactAttemptRepository.Attempt attempt = attempts.findByRef(contactRef)
                .orElseThrow(() -> new ResourceNotFoundException("Contact attempt not found: " + contactRef));
        if (!"SCHEDULED".equals(attempt.outcome()))
            throw new BusinessException("Contact " + contactRef + " is already " + attempt.outcome());
        CaseRow c = findCase(attempt.caseRef());
        Instant at = attemptedAt != null ? attemptedAt : DateUtils.fromUtcDateTime(attempt.scheduledAt());
        List<ContactPolicy.Preference> prefs = collectionsData.loadForCase(c).preferences(c.accountId());
        ContactPolicy.Decision decision = ContactPolicy.evaluate(prefs, attempt.channel(), at);
        if (!decision.allowed()) {
            String reason = String.join("; ", decision.reasons());
            attempts.markBlocked(contactRef, reason);
            audit.record("CONTACT_ATTEMPT", contactRef, "CONTACT_BLOCKED_AT_EXECUTION", actor, reason);
            return new ContactResult(contactRef, "BLOCKED", attempt.channel(), at, decision.reasons());
        }
        attempts.recordOutcome(contactRef, DateUtils.toUtcDateTime(at), outcome, actor);
        audit.record("CONTACT_ATTEMPT", contactRef, "CONTACT_" + outcome, actor, null);
        String status = c.status();
        if ("OPEN".equals(status)) {
            lifecycle.transition(c.id(), c.caseRef(), "OPEN", "IN_PROGRESS", "First contact attempt", actor);
            status = "IN_PROGRESS";
        }
        if ("DISPUTE_RAISED".equals(outcome)) raiseDispute(c, status, prefs, at, contactRef, actor);
        return new ContactResult(contactRef, outcome, attempt.channel(), at, List.of());
    }

    /** Moves the case to DISPUTED and restricts all contact on the account from the next local day. */
    private void raiseDispute(CaseRow c, String status, List<ContactPolicy.Preference> prefs, Instant at, String contactRef, String actor) {
        lifecycle.transition(c.id(), c.caseRef(), status, "DISPUTED", "Customer disputed the debt", actor);
        ContactPolicy.Preference base = ContactPolicy.basePreference(prefs, DateUtils.utcDate(at)).orElseThrow();
        LocalDate from = at.atZone(ZoneId.of(base.timezoneId())).toLocalDate().plusDays(1);
        String prefRef = preferences.nextRef(c.accountId());
        preferences.insertRestriction(prefRef, c.accountId(), from, "DISPUTE", base.timezoneId());
        audit.record("CONTACT_PREFERENCE", prefRef, "DISPUTE_RESTRICTION_ADDED", actor, "Raised on " + contactRef);
    }

    private CaseRow findCase(String caseRef) {
        return collectionsData.findCase(caseRef).orElseThrow(() -> new ResourceNotFoundException("Collection case not found: " + caseRef));
    }
}
