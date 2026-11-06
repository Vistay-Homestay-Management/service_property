package com.vistay.property.adapter.in.grpc;

import com.vistay.contracts.property.v1.GetPropertyPartitionRecordRequest;
import com.vistay.contracts.property.v1.GetPropertyPartitionRecordResponse;
import com.vistay.contracts.property.v1.GetPropertyPartitionRecordsRequest;
import com.vistay.contracts.property.v1.GetPropertyPartitionRecordsResponse;
import com.vistay.contracts.property.v1.PropertyPartitionResolutionStatus;
import com.vistay.contracts.property.v1.PropertyPartitionServiceGrpc;
import com.vistay.property.application.PropertyPartitionRecordReader;
import com.vistay.property.config.GrpcTrustBoundaryInterceptor;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.util.UUID;
import java.util.LinkedHashSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.grpc.server.service.GrpcService;

@GrpcService(interceptors = GrpcTrustBoundaryInterceptor.class)
public class PropertyPartitionGrpcService
    extends PropertyPartitionServiceGrpc.PropertyPartitionServiceImplBase {

    private static final Logger log = LoggerFactory.getLogger(PropertyPartitionGrpcService.class);

    private final PropertyPartitionRecordReader records;

    public PropertyPartitionGrpcService(PropertyPartitionRecordReader records) {
        this.records = records;
    }

    @Override
    public void getPropertyPartitionRecord(
        GetPropertyPartitionRecordRequest request,
        StreamObserver<GetPropertyPartitionRecordResponse> responseObserver
    ) {
        final UUID propertyId;
        try {
            propertyId = UUID.fromString(request.getPropertyId());
        } catch (IllegalArgumentException invalid) {
            responseObserver.onError(Status.INVALID_ARGUMENT
                .withDescription("property_id must be a UUID").asRuntimeException());
            return;
        }

        final java.util.Optional<PropertyPartitionRecordReader.Record> record;
        try {
            record = records.find(propertyId);
        } catch (RuntimeException unavailable) {
            log.error("Property partition lookup failed for propertyId={}", propertyId, unavailable);
            responseObserver.onError(Status.UNAVAILABLE
                .withDescription("Property partition repository is unavailable")
                .withCause(unavailable).asRuntimeException());
            return;
        }
        if (record.isEmpty()) {
            responseObserver.onError(Status.NOT_FOUND
                .withDescription("Property was not found").asRuntimeException());
            return;
        }

        var source = record.get();
        var response = GetPropertyPartitionRecordResponse.newBuilder()
            .setPropertyId(source.propertyId().toString())
            .setMembershipVersion(source.membershipVersion());
        if (source.mapped()) {
            response.setResolutionStatus(
                    PropertyPartitionResolutionStatus.PROPERTY_PARTITION_RESOLUTION_STATUS_MAPPED)
                .setBranchId(source.branchId().toString());
        } else {
            response.setResolutionStatus(
                PropertyPartitionResolutionStatus.PROPERTY_PARTITION_RESOLUTION_STATUS_MISSING);
        }
        responseObserver.onNext(response.build());
        responseObserver.onCompleted();
    }

    @Override
    public void getPropertyPartitionRecords(
        GetPropertyPartitionRecordsRequest request,
        StreamObserver<GetPropertyPartitionRecordsResponse> responseObserver
    ) {
        if (request.getPropertyIdsCount() == 0 || request.getPropertyIdsCount() > 100) {
            responseObserver.onError(Status.INVALID_ARGUMENT
                .withDescription("property_ids must contain 1-100 UUIDs").asRuntimeException());
            return;
        }
        var ids = new LinkedHashSet<UUID>();
        try {
            request.getPropertyIdsList().forEach(value -> ids.add(UUID.fromString(value)));
        } catch (IllegalArgumentException invalid) {
            responseObserver.onError(Status.INVALID_ARGUMENT
                .withDescription("property_ids must contain UUIDs").asRuntimeException());
            return;
        }
        try {
            var found = records.findAll(ids);
            var foundIds = found.stream().map(PropertyPartitionRecordReader.Record::propertyId)
                .collect(java.util.stream.Collectors.toSet());
            var builder = GetPropertyPartitionRecordsResponse.newBuilder();
            found.forEach(record -> builder.addRecords(toResponse(record)));
            ids.stream().filter(id -> !foundIds.contains(id)).map(UUID::toString)
                .forEach(builder::addMissingPropertyIds);
            responseObserver.onNext(builder.build());
            responseObserver.onCompleted();
        } catch (RuntimeException unavailable) {
            responseObserver.onError(Status.UNAVAILABLE
                .withDescription("Property partition repository is unavailable")
                .withCause(unavailable).asRuntimeException());
        }
    }

    private static GetPropertyPartitionRecordResponse toResponse(PropertyPartitionRecordReader.Record source) {
        var response = GetPropertyPartitionRecordResponse.newBuilder()
            .setPropertyId(source.propertyId().toString())
            .setMembershipVersion(source.membershipVersion());
        if (source.mapped()) {
            response.setResolutionStatus(
                    PropertyPartitionResolutionStatus.PROPERTY_PARTITION_RESOLUTION_STATUS_MAPPED)
                .setBranchId(source.branchId().toString());
        } else {
            response.setResolutionStatus(
                PropertyPartitionResolutionStatus.PROPERTY_PARTITION_RESOLUTION_STATUS_MISSING);
        }
        return response.build();
    }
}
