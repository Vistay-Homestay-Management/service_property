package com.vistay.property.adapter.out.persistence;

import com.vistay.property.application.PropertyBranchMembershipRepository;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcPropertyBranchMembershipRepository implements PropertyBranchMembershipRepository {
    private final JdbcClient jdbc;

    public JdbcPropertyBranchMembershipRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean propertyExists(UUID propertyId) {
        return jdbc.sql("SELECT EXISTS(SELECT 1 FROM properties WHERE id = :id)")
            .param("id", propertyId)
            .query(Boolean.class)
            .single();
    }

    @Override
    public boolean activeBranchExists(UUID branchId) {
        return jdbc.sql("SELECT EXISTS(SELECT 1 FROM operating_branches WHERE id = :id AND active)")
            .param("id", branchId)
            .query(Boolean.class)
            .single();
    }

    @Override
    public Optional<Membership> findCurrent(UUID propertyId, Instant at) {
        return jdbc.sql("""
                SELECT id, property_id, branch_id, membership_version, effective_from, effective_to
                FROM property_branch_memberships
                WHERE property_id = :propertyId
                  AND effective_from <= :at
                  AND (effective_to IS NULL OR effective_to > :at)
                ORDER BY membership_version DESC
                LIMIT 1
                """)
            .param("propertyId", propertyId)
            .param("at", at.atOffset(ZoneOffset.UTC))
            .query(Membership.class)
            .optional();
    }

    @Override
    public void close(UUID membershipId, Instant effectiveTo) {
        int updated = jdbc.sql("""
                UPDATE property_branch_memberships
                SET effective_to = :effectiveTo
                WHERE id = :id AND effective_to IS NULL
                """)
            .param("id", membershipId)
            .param("effectiveTo", effectiveTo.atOffset(ZoneOffset.UTC))
            .update();
        if (updated != 1) {
            throw new IllegalStateException("Current Property-to-Branch membership changed concurrently");
        }
    }

    @Override
    public Membership insert(
        UUID propertyId,
        UUID branchId,
        long membershipVersion,
        Instant effectiveFrom,
        UUID assignedByAdminUserId,
        String reason
    ) {
        return jdbc.sql("""
                INSERT INTO property_branch_memberships(
                    property_id, branch_id, membership_version, effective_from,
                    assigned_by_admin_user_id, reason
                ) VALUES (
                    :propertyId, :branchId, :membershipVersion, :effectiveFrom,
                    :assignedByAdminUserId, :reason
                )
                RETURNING id, property_id, branch_id, membership_version, effective_from, effective_to
                """)
            .param("propertyId", propertyId)
            .param("branchId", branchId)
            .param("membershipVersion", membershipVersion)
            .param("effectiveFrom", effectiveFrom.atOffset(ZoneOffset.UTC))
            .param("assignedByAdminUserId", assignedByAdminUserId)
            .param("reason", reason)
            .query(Membership.class)
            .single();
    }
}
