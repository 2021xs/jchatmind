package com.kama.jchatmind.agent.tools;

import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.expression.LongValue;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.Statements;
import net.sf.jsqlparser.statement.select.Limit;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SetOperationList;
import org.springframework.stereotype.Component;

import java.util.Locale;

@Component
public class SqlSafetyValidator {

    public SqlValidationResult validate(String rawSql, int maxRows) {
        if (rawSql == null || rawSql.trim().isEmpty()) {
            throw new IllegalArgumentException("sql is empty");
        }
        if (maxRows <= 0) {
            throw new IllegalArgumentException("maxRows must be greater than 0");
        }

        Statement statement = parseSingleStatement(rawSql);
        if (!(statement instanceof Select select)) {
            throw new IllegalArgumentException("only single read-only SELECT statements are allowed");
        }
        rejectHighRiskSelectExport(select);
        rejectLockingSelect(select);

        String executableSql = withSafeLimit(select, maxRows);
        return new SqlValidationResult(executableSql);
    }

    private Statement parseSingleStatement(String rawSql) {
        try {
            Statements statements = CCJSqlParserUtil.parseStatements(rawSql);
            if (statements == null || statements.getStatements() == null || statements.getStatements().size() != 1) {
                throw new IllegalArgumentException("multiple SQL statements are not allowed");
            }
            return statements.getStatements().get(0);
        } catch (JSQLParserException e) {
            throw new IllegalArgumentException("sql parse failed: " + e.getMessage(), e);
        }
    }

    private void rejectHighRiskSelectExport(Select select) {
        if (select instanceof PlainSelect plainSelect
                && ((plainSelect.getIntoTables() != null && !plainSelect.getIntoTables().isEmpty())
                || plainSelect.getIntoTempTable() != null)) {
            throw new IllegalArgumentException("SELECT INTO is not allowed");
        }
        String parsedSql = select.toString().toLowerCase(Locale.ROOT);
        if (parsedSql.matches("(?s).*\\binto\\s+(out|dump)file\\b.*")
                || parsedSql.matches("(?s).*\\binto\\s+outfile\\b.*")
                || parsedSql.matches("(?s).*\\binto\\s+dumpfile\\b.*")) {
            throw new IllegalArgumentException("SELECT INTO OUTFILE or DUMPFILE is not allowed");
        }
    }

    private void rejectLockingSelect(Select select) {
        if (select instanceof PlainSelect plainSelect
                && (plainSelect.getForMode() != null
                || plainSelect.getForUpdateTable() != null
                || plainSelect.isNoWait()
                || plainSelect.isSkipLocked())) {
            throw new IllegalArgumentException("SELECT locking clauses such as FOR UPDATE are not allowed");
        }
    }

    private String withSafeLimit(Select select, int maxRows) {
        if (select instanceof PlainSelect plainSelect) {
            capPlainSelectLimit(plainSelect, maxRows);
            return select.toString();
        }
        if (select instanceof SetOperationList setOperationList) {
            Limit limit = setOperationList.getLimit();
            if (limit != null && literalLimitValue(limit) <= maxRows) {
                return select.toString();
            }
        }
        return wrapWithLimit(select.toString(), maxRows);
    }

    private void capPlainSelectLimit(PlainSelect plainSelect, int maxRows) {
        Limit limit = plainSelect.getLimit();
        if (limit == null) {
            limit = new Limit();
            limit.setRowCount(new LongValue(maxRows));
            plainSelect.setLimit(limit);
            return;
        }
        if (literalLimitValue(limit) > maxRows) {
            limit.setRowCount(new LongValue(maxRows));
        }
    }

    private long literalLimitValue(Limit limit) {
        if (limit == null || limit.getRowCount() == null) {
            return Long.MAX_VALUE;
        }
        if (limit.getRowCount() instanceof LongValue value) {
            return value.getValue();
        }
        return Long.MAX_VALUE;
    }

    private String wrapWithLimit(String parsedSelectSql, int maxRows) {
        return "SELECT * FROM (" + parsedSelectSql + ") agent_limited_query LIMIT " + maxRows;
    }

    public record SqlValidationResult(String executableSql) {
    }
}
