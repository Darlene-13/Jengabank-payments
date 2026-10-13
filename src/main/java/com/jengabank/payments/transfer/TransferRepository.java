package com.jengabank.payments.transfer;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jengabank.payments.transfer.TransferDtos.FraudView;
import com.jengabank.payments.transfer.TransferDtos.TransferDto;

@Repository
public class TransferRepository {

    public record AccountRef(UUID id, String accountNumber, String status) {}

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {};

    private static final String SELECT_DTO = """
            SELECT t.id, t.from_account_id, fa.account_number AS from_number, ta.account_number AS to_number,
                   t.amount_minor, t.currency, t.reference, t.status, t.fraud_decision,
                   t.fraud_reasons::text AS fraud_reasons, t.journal_id, cu.username AS created_by, t.created_at,
                   du.username AS decided_by, t.decided_at, t.rejection_reason, t.reversal_journal_id
              FROM transfers t
              JOIN accounts fa ON fa.id = t.from_account_id
              JOIN accounts ta ON ta.id = t.to_account_id
              JOIN users cu ON cu.id = t.created_by
              LEFT JOIN users du ON du.id = t.decided_by
            """;

    /**
     * Customer visibility rule (#5): transfers you created, plus incoming money that actually arrived.
     * Never someone else's pending or blocked attempt. Staff (MAKER, CHECKER) skip this clause.
     */
    private static final String CUSTOMER_SCOPE =
            " (t.created_by = ? OR (ta.owner_id = ? AND t.status IN ('COMPLETED', 'REVERSED'))) ";

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final RowMapper<TransferDto> dto;

    public TransferRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.dto = (rs, n) -> {
            String status = rs.getString("status");
            String decision = rs.getString("fraud_decision");
            List<String> reasons = parseReasons(rs.getString("fraud_reasons"));
            boolean approved = "COMPLETED".equals(status) || "REVERSED".equals(status);
            return new TransferDto(
                    rs.getObject("id", UUID.class),
                    rs.getObject("from_account_id", UUID.class),
                    rs.getString("from_number"),
                    rs.getString("to_number"),
                    rs.getLong("amount_minor"),
                    rs.getString("currency"),
                    rs.getString("reference"),
                    status,
                    decision == null && reasons.isEmpty() ? null : new FraudView(decision == null ? "BLOCK" : decision, reasons),
                    rs.getObject("journal_id", UUID.class),
                    rs.getString("created_by"),
                    rs.getObject("created_at", OffsetDateTime.class),
                    approved ? rs.getString("decided_by") : null,
                    approved ? rs.getObject("decided_at", OffsetDateTime.class) : null,
                    rs.getString("rejection_reason"),
                    rs.getObject("reversal_journal_id", UUID.class));
        };
    }

    /** Serialises every transfer by the same customer: daily totals and velocity can't be raced. */
    public void lockUser(UUID userId) {
        jdbc.query("SELECT id FROM users WHERE id = ? FOR NO KEY UPDATE", (RowCallbackHandler) rs -> { }, userId);
    }

    public Optional<AccountRef> findOwnedAccount(UUID accountId, UUID ownerId) {
        return jdbc.query("""
                SELECT id, account_number, status FROM accounts
                 WHERE id = ? AND owner_id = ? AND type = 'CUSTOMER'""",
                (rs, n) -> new AccountRef(rs.getObject("id", UUID.class), rs.getString("account_number"), rs.getString("status")),
                accountId, ownerId).stream().findFirst();
    }

    public Optional<AccountRef> findActiveCustomerAccount(String accountNumber) {
        return jdbc.query("""
                SELECT id, account_number, status FROM accounts
                 WHERE account_number = ? AND type = 'CUSTOMER' AND status = 'ACTIVE'""",
                (rs, n) -> new AccountRef(rs.getObject("id", UUID.class), rs.getString("account_number"), rs.getString("status")),
                accountNumber).stream().findFirst();
    }

    public void insert(UUID id, UUID fromAccountId, UUID toAccountId, long amountMinor, String reference,
                       String status, String fraudDecision, List<String> fraudReasons, UUID journalId, UUID createdBy) {
        jdbc.update("""
                INSERT INTO transfers (id, from_account_id, to_account_id, amount_minor, currency, reference,
                                       status, fraud_decision, fraud_reasons, journal_id, created_by)
                VALUES (?, ?, ?, ?, 'KES', ?, ?, ?, ?::jsonb, ?, ?)""",
                id, fromAccountId, toAccountId, amountMinor, reference, status, fraudDecision,
                json(fraudReasons), journalId, createdBy);
    }

    public Optional<TransferDto> find(UUID id) {
        return jdbc.query(SELECT_DTO + " WHERE t.id = ?", dto, id).stream().findFirst();
    }

    public Optional<TransferDto> findVisible(UUID id, UUID userId, boolean staff) {
        if (staff) {
            return find(id);
        }
        return jdbc.query(SELECT_DTO + " WHERE t.id = ? AND" + CUSTOMER_SCOPE, dto, id, userId, userId)
                .stream().findFirst();
    }

    public List<TransferDto> list(UUID userId, boolean staff, String status,
                                  OffsetDateTime beforeTs, UUID beforeId, int limit) {
        StringBuilder sql = new StringBuilder(SELECT_DTO).append(" WHERE TRUE");
        List<Object> args = new ArrayList<>();
        if (!staff) {
            sql.append(" AND").append(CUSTOMER_SCOPE);
            args.add(userId);
            args.add(userId);
        }
        if (status != null) {
            sql.append(" AND t.status = ?");
            args.add(status);
        }
        if (beforeTs != null) {
            sql.append(" AND (t.created_at, t.id) < (?, ?)");
            args.add(beforeTs);
            args.add(beforeId);
        }
        sql.append(" ORDER BY t.created_at DESC, t.id DESC LIMIT ?");
        args.add(limit);
        return jdbc.query(sql.toString(), dto, args.toArray());
    }

    private List<String> parseReasons(String text) {
        if (text == null) {
            return List.of();
        }
        try {
            return mapper.readValue(text, STRING_LIST);
        } catch (JsonProcessingException e) {
            return List.of();
        }
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
