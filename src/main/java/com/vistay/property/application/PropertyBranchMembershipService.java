package com.vistay.property.application;

import com.vistay.property.application.PropertyBranchMembershipRepository.Membership;
import java.time.Clock;
import java.time.Instant;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PropertyBranchMembershipService {
    private final PropertyBranchMembershipRepository memberships;
    private final Clock clock;

    public PropertyBranchMembershipService(PropertyBranchMembershipRepository memberships) {
        this(memberships, Clock.systemUTC());
    }

    PropertyBranchMembershipService(PropertyBranchMembershipRepository memberships, Clock clock) {
        this.memberships = memberships;
        this.clock = clock;
    }

    @Transactional
    public Membership assign(
        UUID propertyId,
        UUID branchId,
        UUID assignedByAdminUserId,
        String reason
    ) {
        if (!memberships.propertyExists(propertyId)) {
            throw new NoSuchElementException("Property was not found: " + propertyId);
        }
        if (!memberships.activeBranchExists(branchId)) {
            throw new NoSuchElementException("Active branch was not found: " + branchId);
        }
        if (reason == null || reason.trim().length() < 8) {
            throw new IllegalArgumentException("Branch membership reason must contain at least 8 characters");
        }

        Instant effectiveAt = clock.instant();
        var current = memberships.findCurrent(propertyId, effectiveAt);
        if (current.isPresent() && current.get().branchId().equals(branchId)) {
            return current.get();
        }

        long nextVersion = current.map(value -> value.membershipVersion() + 1).orElse(1L);
        current.ifPresent(value -> memberships.close(value.id(), effectiveAt));
        return memberships.insert(propertyId, branchId, nextVersion, effectiveAt,
            assignedByAdminUserId, reason.trim());
    }
}
