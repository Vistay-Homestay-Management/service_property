package com.vistay.property.application;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface PropertyBranchMembershipRepository {
    boolean propertyExists(UUID propertyId);

    boolean activeBranchExists(UUID branchId);

    Optional<Membership> findCurrent(UUID propertyId, Instant at);

    void close(UUID membershipId, Instant effectiveTo);

    Membership insert(
        UUID propertyId,
        UUID branchId,
        long membershipVersion,
        Instant effectiveFrom,
        UUID assignedByAdminUserId,
        String reason
    );

    record Membership(
        UUID id,
        UUID propertyId,
        UUID branchId,
        long membershipVersion,
        Instant effectiveFrom,
        Instant effectiveTo
    ) {}
}
