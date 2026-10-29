package com.vistay.property.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.vistay.property.application.PropertyBranchMembershipRepository.Membership;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PropertyPartitionRecordServiceTest {
    private static final Instant NOW = Instant.parse("2026-10-29T05:00:00Z");
    private static final UUID PROPERTY_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID BRANCH_ID = UUID.fromString("20000000-0000-0000-0000-000000000001");

    @Test
    void resolvesAnActiveEffectiveMembership() {
        var repository = new StubRepository(true, true,
            Optional.of(new Membership(UUID.randomUUID(), PROPERTY_ID, BRANCH_ID, 7,
                NOW.minusSeconds(60), null)));

        var record = service(repository).find(PROPERTY_ID).orElseThrow();

        assertThat(record.mapped()).isTrue();
        assertThat(record.branchId()).isEqualTo(BRANCH_ID);
        assertThat(record.membershipVersion()).isEqualTo(7);
    }

    @Test
    void distinguishesKnownButUnmappedPropertyFromUnknownProperty() {
        var unmapped = service(new StubRepository(true, true, Optional.empty())).find(PROPERTY_ID);
        var unknown = service(new StubRepository(false, true, Optional.empty())).find(PROPERTY_ID);

        assertThat(unmapped).isPresent();
        assertThat(unmapped.orElseThrow().mapped()).isFalse();
        assertThat(unmapped.orElseThrow().membershipVersion()).isZero();
        assertThat(unknown).isEmpty();
    }

    @Test
    void treatsMembershipToAnInactiveBranchAsMissing() {
        var membership = new Membership(UUID.randomUUID(), PROPERTY_ID, BRANCH_ID, 3,
            NOW.minusSeconds(60), null);

        var record = service(new StubRepository(true, false, Optional.of(membership)))
            .find(PROPERTY_ID).orElseThrow();

        assertThat(record.mapped()).isFalse();
    }

    private static PropertyPartitionRecordService service(PropertyBranchMembershipRepository repository) {
        return new PropertyPartitionRecordService(repository, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private record StubRepository(boolean propertyExists, boolean branchExists,
                                  Optional<Membership> membership)
        implements PropertyBranchMembershipRepository {
        @Override public boolean propertyExists(UUID propertyId) { return propertyExists; }
        @Override public boolean activeBranchExists(UUID branchId) { return branchExists; }
        @Override public Optional<Membership> findCurrent(UUID propertyId, Instant at) { return membership; }
        @Override public void close(UUID membershipId, Instant effectiveTo) { throw new UnsupportedOperationException(); }
        @Override public Membership insert(UUID propertyId, UUID branchId, long membershipVersion,
                                           Instant effectiveFrom, UUID assignedByAdminUserId, String reason) {
            throw new UnsupportedOperationException();
        }
    }
}
