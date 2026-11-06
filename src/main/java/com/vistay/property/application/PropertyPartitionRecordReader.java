package com.vistay.property.application;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PropertyPartitionRecordReader {
    Optional<Record> find(UUID propertyId);

    default List<Record> findAll(Collection<UUID> propertyIds) {
        return propertyIds.stream().map(this::find).flatMap(Optional::stream).toList();
    }

    record Record(UUID propertyId, UUID branchId, long membershipVersion) {
        public boolean mapped() {
            return branchId != null;
        }
    }
}
