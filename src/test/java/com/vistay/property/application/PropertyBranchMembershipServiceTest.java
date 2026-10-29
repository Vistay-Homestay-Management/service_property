package com.vistay.property.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.vistay.property.application.PropertyBranchMembershipRepository.Membership;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PropertyBranchMembershipServiceTest {
    private static final Instant NOW = Instant.parse("2026-10-29T05:00:00Z");
    private static final UUID PROPERTY_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID BRANCH_A = UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final UUID BRANCH_B = UUID.fromString("20000000-0000-0000-0000-000000000002");
    private static final UUID ADMIN_ID = UUID.fromString("30000000-0000-0000-0000-000000000001");

    @Test
    void createsFirstVersionForAnUnmappedProperty() {
        var repository = new FakeRepository();
        var service = service(repository);

        var assigned = service.assign(PROPERTY_ID, BRANCH_A, ADMIN_ID, "Initial operating branch");

        assertThat(assigned.branchId()).isEqualTo(BRANCH_A);
        assertThat(assigned.membershipVersion()).isEqualTo(1);
        assertThat(assigned.effectiveFrom()).isEqualTo(NOW);
        assertThat(repository.closedMembershipId).isNull();
    }

    @Test
    void closesCurrentMembershipAndCreatesTheNextVersion() {
        var repository = new FakeRepository();
        repository.current = Optional.of(membership(BRANCH_A, 4));
        var service = service(repository);

        var assigned = service.assign(PROPERTY_ID, BRANCH_B, ADMIN_ID, "Move to the south branch");

        assertThat(repository.closedMembershipId).isEqualTo(repository.originalMembershipId);
        assertThat(repository.closedAt).isEqualTo(NOW);
        assertThat(assigned.branchId()).isEqualTo(BRANCH_B);
        assertThat(assigned.membershipVersion()).isEqualTo(5);
    }

    @Test
    void assigningTheSameBranchIsIdempotent() {
        var repository = new FakeRepository();
        repository.current = Optional.of(membership(BRANCH_A, 2));
        var service = service(repository);

        var assigned = service.assign(PROPERTY_ID, BRANCH_A, ADMIN_ID, "Confirm current assignment");

        assertThat(assigned).isSameAs(repository.current.orElseThrow());
        assertThat(repository.insertCount).isZero();
        assertThat(repository.closedMembershipId).isNull();
    }

    @Test
    void rejectsUnknownPropertyInactiveBranchAndUnexplainedChanges() {
        var repository = new FakeRepository();
        var service = service(repository);

        repository.propertyExists = false;
        assertThatThrownBy(() -> service.assign(PROPERTY_ID, BRANCH_A, ADMIN_ID, "Initial branch"))
            .isInstanceOf(java.util.NoSuchElementException.class);

        repository.propertyExists = true;
        repository.branchExists = false;
        assertThatThrownBy(() -> service.assign(PROPERTY_ID, BRANCH_A, ADMIN_ID, "Initial branch"))
            .isInstanceOf(java.util.NoSuchElementException.class);

        repository.branchExists = true;
        assertThatThrownBy(() -> service.assign(PROPERTY_ID, BRANCH_A, ADMIN_ID, "short"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    private static PropertyBranchMembershipService service(FakeRepository repository) {
        return new PropertyBranchMembershipService(repository, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static Membership membership(UUID branchId, long version) {
        return new Membership(UUID.randomUUID(), PROPERTY_ID, branchId, version,
            NOW.minusSeconds(3600), null);
    }

    private static final class FakeRepository implements PropertyBranchMembershipRepository {
        private boolean propertyExists = true;
        private boolean branchExists = true;
        private Optional<Membership> current = Optional.empty();
        private UUID originalMembershipId;
        private UUID closedMembershipId;
        private Instant closedAt;
        private int insertCount;

        @Override public boolean propertyExists(UUID propertyId) { return propertyExists; }
        @Override public boolean activeBranchExists(UUID branchId) { return branchExists; }
        @Override public Optional<Membership> findCurrent(UUID propertyId, Instant at) {
            current.ifPresent(value -> originalMembershipId = value.id());
            return current;
        }
        @Override public void close(UUID membershipId, Instant effectiveTo) {
            closedMembershipId = membershipId;
            closedAt = effectiveTo;
        }
        @Override public Membership insert(UUID propertyId, UUID branchId, long membershipVersion,
                                           Instant effectiveFrom, UUID assignedByAdminUserId, String reason) {
            insertCount++;
            return new Membership(UUID.randomUUID(), propertyId, branchId, membershipVersion,
                effectiveFrom, null);
        }
    }
}
