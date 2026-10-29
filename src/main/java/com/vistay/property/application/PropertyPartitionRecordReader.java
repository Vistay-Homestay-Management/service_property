package com.vistay.property.application;

import java.util.Optional;
import java.util.UUID;

public interface PropertyPartitionRecordReader {
    Optional<Record> find(UUID propertyId);

    record Record(UUID propertyId, UUID branchId, long membershipVersion) {
        public boolean mapped() {
            return branchId != null;
        }
    }
}
