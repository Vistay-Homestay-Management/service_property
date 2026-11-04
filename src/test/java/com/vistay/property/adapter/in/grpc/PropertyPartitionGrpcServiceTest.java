package com.vistay.property.adapter.in.grpc;

import static org.assertj.core.api.Assertions.assertThat;

import com.vistay.contracts.property.v1.GetPropertyPartitionRecordRequest;
import com.vistay.contracts.property.v1.GetPropertyPartitionRecordResponse;
import com.vistay.contracts.property.v1.PropertyPartitionResolutionStatus;
import com.vistay.property.application.PropertyPartitionRecordReader;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PropertyPartitionGrpcServiceTest {
    private static final UUID PROPERTY_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID BRANCH_ID = UUID.fromString("20000000-0000-0000-0000-000000000001");

    @Test
    void returnsMappedRecordWithVersion() {
        var observer = new CapturingObserver();
        var service = new PropertyPartitionGrpcService(id -> Optional.of(
            new PropertyPartitionRecordReader.Record(id, BRANCH_ID, 8)));

        service.getPropertyPartitionRecord(request(PROPERTY_ID.toString()), observer);

        assertThat(observer.error).isNull();
        assertThat(observer.value.getResolutionStatus()).isEqualTo(
            PropertyPartitionResolutionStatus.PROPERTY_PARTITION_RESOLUTION_STATUS_MAPPED);
        assertThat(observer.value.getBranchId()).isEqualTo(BRANCH_ID.toString());
        assertThat(observer.value.getMembershipVersion()).isEqualTo(8);
        assertThat(observer.completed).isTrue();
    }

    @Test
    void returnsMissingForKnownUnmappedProperty() {
        var observer = new CapturingObserver();
        var service = new PropertyPartitionGrpcService(id -> Optional.of(
            new PropertyPartitionRecordReader.Record(id, null, 0)));

        service.getPropertyPartitionRecord(request(PROPERTY_ID.toString()), observer);

        assertThat(observer.value.getResolutionStatus()).isEqualTo(
            PropertyPartitionResolutionStatus.PROPERTY_PARTITION_RESOLUTION_STATUS_MISSING);
        assertThat(observer.value.hasBranchId()).isFalse();
    }

    @Test
    void mapsInvalidUnknownAndRepositoryFailureToExplicitGrpcStatuses() {
        var invalid = new CapturingObserver();
        new PropertyPartitionGrpcService(id -> Optional.empty())
            .getPropertyPartitionRecord(request("not-a-uuid"), invalid);
        assertThat(Status.fromThrowable(invalid.error).getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT);

        var unknown = new CapturingObserver();
        new PropertyPartitionGrpcService(id -> Optional.empty())
            .getPropertyPartitionRecord(request(PROPERTY_ID.toString()), unknown);
        assertThat(Status.fromThrowable(unknown.error).getCode()).isEqualTo(Status.Code.NOT_FOUND);

        var unavailable = new CapturingObserver();
        new PropertyPartitionGrpcService(id -> { throw new IllegalStateException("database down"); })
            .getPropertyPartitionRecord(request(PROPERTY_ID.toString()), unavailable);
        assertThat(Status.fromThrowable(unavailable.error).getCode()).isEqualTo(Status.Code.UNAVAILABLE);
    }

    private static GetPropertyPartitionRecordRequest request(String propertyId) {
        return GetPropertyPartitionRecordRequest.newBuilder().setPropertyId(propertyId).build();
    }

    private static final class CapturingObserver
        implements StreamObserver<GetPropertyPartitionRecordResponse> {
        private GetPropertyPartitionRecordResponse value;
        private Throwable error;
        private boolean completed;

        @Override public void onNext(GetPropertyPartitionRecordResponse value) { this.value = value; }
        @Override public void onError(Throwable error) { this.error = error; }
        @Override public void onCompleted() { completed = true; }
    }
}
