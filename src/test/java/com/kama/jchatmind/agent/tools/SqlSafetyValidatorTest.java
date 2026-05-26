package com.kama.jchatmind.agent.tools;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqlSafetyValidatorTest {
    private final SqlSafetyValidator validator = new SqlSafetyValidator();

    @Test
    void normalSelectShouldPassAndReceiveLimit() {
        String executableSql = validator.validate("SELECT id, name FROM users", 50).executableSql();

        assertEquals("SELECT id, name FROM users LIMIT 50", executableSql);
    }

    @Test
    void mixedCaseSelectShouldPass() {
        String executableSql = validator.validate("SeLeCt id FrOm users", 50).executableSql();

        assertEquals("SELECT id FROM users LIMIT 50", executableSql);
    }

    @Test
    void writeAndDdlStatementsShouldBeRejectedByAstType() {
        assertRejected("UPDATE users SET name = 'x'");
        assertRejected("DELETE FROM users");
        assertRejected("DROP TABLE users");
        assertRejected("ALTER TABLE users ADD COLUMN age int");
        assertRejected("INSERT INTO users(id) VALUES (1)");
        assertRejected("TRUNCATE TABLE users");
        assertRejected("CREATE TABLE users(id int)");
        assertRejected("REPLACE INTO users(id) VALUES (1)");
        assertRejected("MERGE INTO users u USING tmp t ON u.id = t.id WHEN MATCHED THEN UPDATE SET id = t.id");
    }

    @Test
    void multipleStatementsShouldBeRejected() {
        assertRejected("SELECT 1; DROP TABLE users");
    }

    @Test
    void storedProcedureCallShouldBeRejected() {
        assertRejected("CALL refresh_user_stats()");
    }

    @Test
    void identifiersContainingDangerousWordsShouldNotBeRejected() {
        String executableSql = validator.validate("SELECT update_time FROM drop_shipping_records", 50).executableSql();

        assertEquals("SELECT update_time FROM drop_shipping_records LIMIT 50", executableSql);
    }

    @Test
    void existingLimitBelowMaxShouldPass() {
        String executableSql = validator.validate("SELECT * FROM users LIMIT 10", 50).executableSql();

        assertEquals("SELECT * FROM users LIMIT 10", executableSql);
    }

    @Test
    void existingLimitAboveMaxShouldBeCapped() {
        String executableSql = validator.validate("SELECT * FROM users LIMIT 1000", 50).executableSql();

        assertEquals("SELECT * FROM users LIMIT 50", executableSql);
    }

    @Test
    void selectIntoOutfileShouldBeRejected() {
        assertRejected("SELECT * INTO OUTFILE '/tmp/users.csv' FROM users");
        assertRejected("SELECT * INTO DUMPFILE '/tmp/users.bin' FROM users");
    }

    @Test
    void selectForUpdateShouldBeRejected() {
        assertRejected("SELECT * FROM users FOR UPDATE");
    }

    @Test
    void slowFunctionSelectShouldRelyOnExecutionTimeoutAndStillReceiveLimit() {
        String executableSql = validator.validate("SELECT SLEEP(10)", 50).executableSql();

        assertEquals("SELECT SLEEP(10) LIMIT 50", executableSql);
    }

    @Test
    void invalidSqlShouldBeRejected() {
        IllegalArgumentException error = assertRejected("SELECT FROM WHERE");

        assertTrue(error.getMessage().contains("parse failed"));
    }

    @Test
    void setOperationShouldUseConservativeLimitWrapper() {
        String executableSql = validator.validate("SELECT id FROM a UNION SELECT id FROM b", 50).executableSql();

        assertEquals("SELECT * FROM (SELECT id FROM a UNION SELECT id FROM b) agent_limited_query LIMIT 50", executableSql);
    }

    private IllegalArgumentException assertRejected(String sql) {
        return assertThrows(IllegalArgumentException.class, () -> validator.validate(sql, 50), sql);
    }
}
