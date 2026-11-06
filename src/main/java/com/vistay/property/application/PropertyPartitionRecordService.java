package com.vistay.property.application;

import java.time.Clock;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PropertyPartitionRecordService implements PropertyPartitionRecordReader {
    private final PropertyBranchMembershipRepository memberships;
    private final Clock clock;

    @Autowired
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
        return findAll(List.of(propertyId)).stream().findFirst();
    }

    @Override
    @Transactional(readOnly = true)
    public List<Record> findAll(Collection<UUID> propertyIds) {
        return memberships.findPartitionRecords(propertyIds, clock.instant()).stream()
            .map(value -> new Record(value.propertyId(), value.branchId(), value.membershipVersion()))
            .toList();
    }
}
