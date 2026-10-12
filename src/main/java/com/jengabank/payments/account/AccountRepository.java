package com.jengabank.payments.account;

import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class AccountRepository {

    private static final String COLUMNS = "id, account_no, owner_id, owner_name, kind, status";

    private static final RowMapper<Account> MAPPER = (rs, rowNum) -> new Account(
            rs.getLong("id"),
            rs.getString("account_no"),
            rs.getString("owner_id"),
            rs.getString("owner_name"),
            rs.getString("kind"),
            rs.getString("status"));

    private final JdbcTemplate jdbc;

    public AccountRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Account> findByAccountNo(String accountNo) {
        List<Account> rows = jdbc.query(
                "SELECT " + COLUMNS + " FROM accounts WHERE account_no = ?", MAPPER, accountNo);
        return rows.stream().findFirst();
    }

    public List<Account> findAll() {
        return jdbc.query("SELECT " + COLUMNS + " FROM accounts ORDER BY account_no", MAPPER);
    }

    public List<Account> lockPair(String accountNoA, String accountNoB) {
        return jdbc.query(
                "SELECT " + COLUMNS + " FROM accounts WHERE account_no IN (?, ?) "
                        + "ORDER BY account_no FOR UPDATE",
                MAPPER, accountNoA, accountNoB);
    }

    public Optional<Account> lockForUpdate(String accountNo) {
        List<Account> rows = jdbc.query(
                "SELECT " + COLUMNS + " FROM accounts WHERE account_no = ? FOR UPDATE",
                MAPPER, accountNo);
        return rows.stream().findFirst();
    }

    public long balanceOf(String accountNo) {
        Long balance = jdbc.query(
                "SELECT balance_minor FROM account_balances WHERE account_no = ?",
                rs -> rs.next() ? rs.getLong(1) : null,
                accountNo);
        return balance == null ? 0L : balance;
    }

    public Long insert(String accountNo, String ownerId, String ownerName, String kind) {
        return jdbc.queryForObject(
                "INSERT INTO accounts (account_no, owner_id, owner_name, kind) "
                        + "VALUES (?, ?, ?, ?) RETURNING id",
                Long.class, accountNo, ownerId, ownerName, kind);
    }
}