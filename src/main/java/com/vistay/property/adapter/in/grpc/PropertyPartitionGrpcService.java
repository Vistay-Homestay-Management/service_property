package com.vistay.property.adapter.in.grpc;

import com.vistay.contracts.property.v1.GetPropertyPartitionRecordRequest;
import com.vistay.contracts.property.v1.GetPropertyPartitionRecordResponse;
import com.vistay.contracts.property.v1.PropertyPartitionResolutionStatus;
import com.vistay.contracts.property.v1.PropertyPartitionServiceGrpc;
import com.vistay.property.application.PropertyPartitionRecordReader;
import com.vistay.property.config.GrpcTrustBoundaryInterceptor;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.util.UUID;
import org.springframework.grpc.server.service.GrpcService;

@GrpcService(interceptors = GrpcTrustBoundaryInterceptor.class)
public class PropertyPartitionGrpcService
    extends PropertyPartitionServiceGrpc.PropertyPartitionServiceImplBase {

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
}
