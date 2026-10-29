package com.vistay.property.application;

import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PropertyPartitionRecordService implements PropertyPartitionRecordReader {
    private final PropertyBranchMembershipRepository memberships;
    private final Clock clock;

    public PropertyPartitionRecordService(PropertyBranchMembershipRepository memberships) {
        this(memberships, Clock.systemUTC());
    }

    PropertyPartitionRecordService(PropertyBranchMembershipRepository memberships, Clock clock) {
        this.memberships = memberships;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Record> find(UUID propertyId) {
        if (!memberships.propertyExists(propertyId)) {
            return Optional.empty();
        }
        return Optional.of(memberships.findCurrent(propertyId, clock.instant())
            .filter(value -> memberships.activeBranchExists(value.branchId()))
            .map(value -> new Record(propertyId, value.branchId(), value.membershipVersion()))
            .orElseGet(() -> new Record(propertyId, null, 0)));
    }
}
