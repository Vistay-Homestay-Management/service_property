package com.vistay.property.application;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PropertyBranchMembershipRepository {
    boolean propertyExists(UUID propertyId);

    boolean activeBranchExists(UUID branchId);

    Optional<Membership> findCurrent(UUID propertyId, Instant at);

    default List<PartitionRecord> findPartitionRecords(Collection<UUID> propertyIds, Instant at) {
        var result = new java.util.ArrayList<PartitionRecord>();
        for (var propertyId : propertyIds) {
            if (!propertyExists(propertyId)) continue;
            var current = findCurrent(propertyId, at)
                .filter(value -> activeBranchExists(value.branchId()));
            result.add(current.map(value -> new PartitionRecord(propertyId, value.branchId(),
                value.membershipVersion())).orElseGet(() -> new PartitionRecord(propertyId, null, 0)));
        }
        return List.copyOf(result);
    }

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

    record PartitionRecord(UUID propertyId, UUID branchId, long membershipVersion) {}
}
