package com.larvalabs.brace;

import org.hibernate.StatelessSession;
import org.hibernate.query.MutationQuery;
import org.hibernate.query.Query;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Thin wrapper over Hibernate StatelessSession providing a simple query API.
 * Uses ? placeholders (converted to ?1, ?2, etc. for Hibernate 7).
 */
public class Database {

    private final StatelessSession session;
    private int queryCount = 0;
    private long queryDurationUs = 0;

    public Database(StatelessSession session) {
        this.session = session;
    }

    public int queryCount() { return queryCount; }
    public long queryDurationUs() { return queryDurationUs; }

    // --- CRUD ---

    public <T> T find(Class<T> type, Object id) {
        long start = System.nanoTime();
        T result = session.get(type, id);
        queryDurationUs += (System.nanoTime() - start) / 1000;
        queryCount++;
        return result;
    }

    public void insert(Object entity) {
        long start = System.nanoTime();
        session.insert(entity);
        queryDurationUs += (System.nanoTime() - start) / 1000;
        queryCount++;
    }

    public void update(Object entity) {
        long start = System.nanoTime();
        session.update(entity);
        queryDurationUs += (System.nanoTime() - start) / 1000;
        queryCount++;
    }

    public void delete(Object entity) {
        long start = System.nanoTime();
        session.delete(entity);
        queryDurationUs += (System.nanoTime() - start) / 1000;
        queryCount++;
    }

    // --- Queries ---

    public <T> List<T> findAll(Class<T> type) {
        long start = System.nanoTime();
        String hql = "FROM " + type.getSimpleName();
        List<T> result = session.createQuery(hql, type).getResultList();
        queryDurationUs += (System.nanoTime() - start) / 1000;
        queryCount++;
        return result;
    }

    public <T> List<T> query(Class<T> type, String hqlWhere, Object... params) {
        long start = System.nanoTime();
        String hql = "FROM " + type.getSimpleName() + " WHERE " + convertPositionalParams(hqlWhere);
        Query<T> query = session.createQuery(hql, type);
        bindParams(query, params);
        List<T> result = query.getResultList();
        queryDurationUs += (System.nanoTime() - start) / 1000;
        queryCount++;
        return result;
    }

    /**
     * Batch-fetch all rows of {@code type} where {@code field} is one of the given {@code values}.
     *
     * <p><strong>Security:</strong> {@code field} must be a trusted, hard-coded entity attribute
     * name — never pass user-controlled input (e.g. {@code req.queryParam("sort")}) as the field
     * argument. Doing so is an HQL injection risk. The value is validated against the safe
     * identifier pattern {@code [A-Za-z_$][A-Za-z0-9_$]*(\.[A-Za-z_$][A-Za-z0-9_$]*)*}
     * and rejected with {@link IllegalArgumentException} if it does not match.
     */
    public <T> List<T> queryIn(Class<T> type, String field, List<?> values) {
        requireValidFieldIdentifier(field);
        if (values.isEmpty()) {
            return List.of();
        }
        long start = System.nanoTime();
        var placeholders = new StringBuilder();
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) placeholders.append(", ");
            placeholders.append('?').append(i + 1);
        }
        String hql = "FROM " + type.getSimpleName() + " WHERE " + field + " IN (" + placeholders + ")";
        Query<T> query = session.createQuery(hql, type);
        for (int i = 0; i < values.size(); i++) {
            query.setParameter(i + 1, values.get(i));
        }
        List<T> result = query.getResultList();
        queryDurationUs += (System.nanoTime() - start) / 1000;
        queryCount++;
        return result;
    }

    public <T> T queryOne(Class<T> type, String hqlWhere, Object... params) {
        // Delegates to query() which already instruments
        List<T> results = query(type, hqlWhere, params);
        return results.isEmpty() ? null : results.get(0);
    }

    /**
     * Like {@link #find(Class, Object)}, but throws {@link NotFoundException} (rendered as a
     * 404 response) when no row exists. The canonical handler lookup:
     * {@code var post = db.findOr404(Post.class, req.longPathParam("id"));}
     */
    public <T> T findOr404(Class<T> type, Object id) {
        T result = find(type, id);
        if (result == null) throw new NotFoundException();
        return result;
    }

    /**
     * Like {@link #queryOne(Class, String, Object...)}, but throws {@link NotFoundException}
     * (rendered as a 404 response) when no row matches.
     */
    public <T> T queryOneOr404(Class<T> type, String hqlWhere, Object... params) {
        T result = queryOne(type, hqlWhere, params);
        if (result == null) throw new NotFoundException();
        return result;
    }

    public <T> long count(Class<T> type) {
        long start = System.nanoTime();
        String hql = "SELECT count(*) FROM " + type.getSimpleName();
        long result = session.createQuery(hql, Long.class).getSingleResult();
        queryDurationUs += (System.nanoTime() - start) / 1000;
        queryCount++;
        return result;
    }

    public <T> long count(Class<T> type, String hqlWhere, Object... params) {
        long start = System.nanoTime();
        String hql = "SELECT count(*) FROM " + type.getSimpleName() + " WHERE " + convertPositionalParams(hqlWhere);
        Query<Long> query = session.createQuery(hql, Long.class);
        bindParams(query, params);
        long result = query.getSingleResult();
        queryDurationUs += (System.nanoTime() - start) / 1000;
        queryCount++;
        return result;
    }

    // --- Constrained helpers (single-field queries) ---

    /**
     * Find the first row of {@code type} where {@code field} equals {@code value}, or {@code null}.
     *
     * <p><strong>Security:</strong> {@code field} must be a trusted, hard-coded entity attribute
     * name — never pass user-controlled input (e.g. {@code req.queryParam("sort")}) as the field
     * argument. Doing so is an HQL injection risk. The value is validated against the safe
     * identifier pattern {@code [A-Za-z_$][A-Za-z0-9_$]*(\.[A-Za-z_$][A-Za-z0-9_$]*)*}
     * and rejected with {@link IllegalArgumentException} if it does not match.
     */
    public <T> T findBy(Class<T> type, String field, Object value) {
        requireValidFieldIdentifier(field);
        return queryOne(type, field + " = ?", value);
    }

    /**
     * Find all rows of {@code type} where {@code field} equals {@code value}.
     *
     * <p><strong>Security:</strong> {@code field} must be a trusted, hard-coded entity attribute
     * name — never pass user-controlled input (e.g. {@code req.queryParam("sort")}) as the field
     * argument. Doing so is an HQL injection risk. The value is validated against the safe
     * identifier pattern {@code [A-Za-z_$][A-Za-z0-9_$]*(\.[A-Za-z_$][A-Za-z0-9_$]*)*}
     * and rejected with {@link IllegalArgumentException} if it does not match.
     */
    public <T> List<T> findAllBy(Class<T> type, String field, Object value) {
        requireValidFieldIdentifier(field);
        return query(type, field + " = ?", value);
    }

    /**
     * Count rows of {@code type} where {@code field} equals {@code value}.
     *
     * <p><strong>Security:</strong> {@code field} must be a trusted, hard-coded entity attribute
     * name — never pass user-controlled input (e.g. {@code req.queryParam("sort")}) as the field
     * argument. Doing so is an HQL injection risk. The value is validated against the safe
     * identifier pattern {@code [A-Za-z_$][A-Za-z0-9_$]*(\.[A-Za-z_$][A-Za-z0-9_$]*)*}
     * and rejected with {@link IllegalArgumentException} if it does not match.
     */
    public <T> long countBy(Class<T> type, String field, Object value) {
        requireValidFieldIdentifier(field);
        return count(type, field + " = ?", value);
    }

    /**
     * Return {@code true} if any row of {@code type} has {@code field} equal to {@code value}.
     *
     * <p><strong>Security:</strong> {@code field} must be a trusted, hard-coded entity attribute
     * name — never pass user-controlled input (e.g. {@code req.queryParam("sort")}) as the field
     * argument. Doing so is an HQL injection risk. The value is validated against the safe
     * identifier pattern {@code [A-Za-z_$][A-Za-z0-9_$]*(\.[A-Za-z_$][A-Za-z0-9_$]*)*}
     * and rejected with {@link IllegalArgumentException} if it does not match.
     */
    public <T> boolean existsBy(Class<T> type, String field, Object value) {
        // requireValidFieldIdentifier delegated to countBy
        return countBy(type, field, value) > 0;
    }

    /**
     * Delete all rows of {@code type} where {@code field} equals {@code value}.
     * Returns the number of rows deleted.
     *
     * <p><strong>Security:</strong> {@code field} must be a trusted, hard-coded entity attribute
     * name — never pass user-controlled input (e.g. {@code req.queryParam("sort")}) as the field
     * argument. Doing so is an HQL injection risk. The value is validated against the safe
     * identifier pattern {@code [A-Za-z_$][A-Za-z0-9_$]*(\.[A-Za-z_$][A-Za-z0-9_$]*)*}
     * and rejected with {@link IllegalArgumentException} if it does not match.
     */
    public <T> int deleteBy(Class<T> type, String field, Object value) {
        requireValidFieldIdentifier(field);
        long start = System.nanoTime();
        String hql = "DELETE FROM " + type.getSimpleName() + " WHERE " + field + " = ?1";
        MutationQuery query = session.createMutationQuery(hql);
        query.setParameter(1, value);
        int result = query.executeUpdate();
        queryDurationUs += (System.nanoTime() - start) / 1000;
        queryCount++;
        return result;
    }

    // --- Raw queries ---

    @SuppressWarnings("unchecked")
    public List<Object[]> hql(String hql, Object... params) {
        long start = System.nanoTime();
        Query<?> query = session.createQuery(convertPositionalParams(hql));
        bindParams(query, params);
        List<Object[]> result = (List<Object[]>) query.getResultList();
        queryDurationUs += (System.nanoTime() - start) / 1000;
        queryCount++;
        return result;
    }

    public void sql(String sql, Object... params) {
        long start = System.nanoTime();
        MutationQuery query = session.createNativeMutationQuery(convertPositionalParams(sql));
        bindParams(query, params);
        query.executeUpdate();
        queryDurationUs += (System.nanoTime() - start) / 1000;
        queryCount++;
    }

    @SuppressWarnings("unchecked")
    public List<Object[]> sqlQuery(String sql, Object... params) {
        long start = System.nanoTime();
        var query = session.createNativeQuery(convertPositionalParams(sql));
        bindParams(query, params);
        var results = query.getResultList();
        queryDurationUs += (System.nanoTime() - start) / 1000;
        queryCount++;
        return (List<Object[]>) (List<?>) results;
    }

    @SuppressWarnings("unchecked")
    public Long sqlQueryLong(String sql, Object... params) {
        long start = System.nanoTime();
        var query = session.createNativeQuery(convertPositionalParams(sql));
        bindParams(query, params);
        var results = query.getResultList();
        queryDurationUs += (System.nanoTime() - start) / 1000;
        queryCount++;
        if (results.isEmpty()) return null;
        Object val = results.get(0);
        if (val instanceof Object[] arr) return ((Number) arr[0]).longValue();
        return ((Number) val).longValue();
    }

    // --- Raw JDBC access ---

    public <T> T jdbc(JdbcFunction<T> function) {
        long start = System.nanoTime();
        Object[] result = new Object[1];
        session.doWork(connection -> {
            result[0] = function.apply(connection);
        });
        queryDurationUs += (System.nanoTime() - start) / 1000;
        queryCount++;
        @SuppressWarnings("unchecked")
        T value = (T) result[0];
        return value;
    }

    public void jdbc(JdbcConsumer consumer) {
        long start = System.nanoTime();
        session.doWork(consumer::accept);
        queryDurationUs += (System.nanoTime() - start) / 1000;
        queryCount++;
    }

    @FunctionalInterface
    public interface JdbcFunction<T> {
        T apply(java.sql.Connection connection) throws java.sql.SQLException;
    }

    @FunctionalInterface
    public interface JdbcConsumer {
        void accept(java.sql.Connection connection) throws java.sql.SQLException;
    }

    // --- Transaction management ---

    public void beginTransaction() {
        session.getTransaction().begin();
    }

    public void commitTransaction() {
        session.getTransaction().commit();
    }

    public void rollbackTransaction() {
        if (session.getTransaction().isActive()) {
            session.getTransaction().rollback();
        }
    }

    // --- Lifecycle ---

    public void close() {
        session.close();
    }

    // --- Internal ---

    /**
     * Identifier pattern for HQL field names: a simple Java-style identifier or a dot-separated
     * embedded path (e.g. {@code id}, {@code address.city}). Only ASCII letters, digits, {@code _},
     * and {@code $} are accepted. Reflecting over entity attributes would be stricter but adds
     * startup machinery — this regex gate kills identifier-injection while keeping helpers zero-config.
     */
    private static final Pattern FIELD_IDENTIFIER =
            Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)*");

    /**
     * Validate that {@code field} is a safe HQL attribute identifier (simple name or dotted
     * embedded path). Throws {@link IllegalArgumentException} if not, naming the offending value.
     */
    static void requireValidFieldIdentifier(String field) {
        if (field == null || !FIELD_IDENTIFIER.matcher(field).matches()) {
            throw new IllegalArgumentException(
                    "Invalid field identifier: \"" + field + "\". " +
                    "Must match [A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)* " +
                    "(e.g. \"id\", \"title\", \"address.city\"). " +
                    "Never pass user-controlled input as a field name.");
        }
    }

    /**
     * Rewrite {@code ?} placeholders to Hibernate-style {@code ?1, ?2, …}, but leave alone any
     * {@code ?} that isn't actually a placeholder. The scanner recognises and skips the following
     * constructs so that a {@code ?} inside them is never renumbered:
     *
     * <ul>
     *   <li><strong>Single-quoted string literals</strong> {@code 'abc'} — {@code ''} is the
     *       only standard escape; standard_conforming_strings is assumed on (Postgres default).</li>
     *   <li><strong>E-strings</strong> {@code E'abc\'def'} / {@code e'...'} — a leading
     *       {@code E}/{@code e} before the opening quote activates backslash-escape mode, so
     *       {@code \'} does not close the string.</li>
     *   <li><strong>Double-quoted identifiers</strong> {@code "od?d"} — {@code ""} escapes an
     *       embedded double-quote; the body is never a placeholder.</li>
     *   <li><strong>Dollar-quoted strings</strong> {@code $$...$$} and {@code $tag$...$tag$}
     *       where the tag is empty or a plain identifier ({@code [A-Za-z0-9_]*}). Only a
     *       {@code $} that opens a syntactically complete dollar-quote token triggers this state;
     *       bare {@code $} in identifiers (e.g. {@code a$b}) or Postgres {@code $1} positional
     *       params in raw SQL are not dollar-quote openers and fall through to the default
     *       character-copy path.</li>
     *   <li><strong>Line comments</strong> {@code -- ...}</li>
     *   <li><strong>Block comments</strong> {@code /* ... *}{@code /}</li>
     * </ul>
     *
     * A literal {@code ?} elsewhere (e.g. a Postgres JSONB {@code ?}/{@code ?|}/{@code ?&}
     * operator) can be escaped as {@code ??}, which emits a single {@code ?}. For fully
     * hand-written SQL, {@link #jdbc(JdbcConsumer)} is the raw escape hatch.
     */
    String convertPositionalParams(String hql) {   // package-private for direct unit testing
        var sb = new StringBuilder(hql.length() + 8);
        int paramIndex = 1;
        int n = hql.length();
        int i = 0;
        while (i < n) {
            char c = hql.charAt(i);

            // ── E-string: E'...' or e'...' — backslash escapes active ──────────────────────
            if ((c == 'E' || c == 'e') && i + 1 < n && hql.charAt(i + 1) == '\'') {
                sb.append(c);               // emit the E/e prefix
                i++;
                sb.append('\'');            // emit the opening quote
                i++;
                while (i < n) {
                    char d = hql.charAt(i);
                    sb.append(d);
                    i++;
                    if (d == '\\' && i < n) {   // backslash-escaped char: skip next char verbatim
                        sb.append(hql.charAt(i));
                        i++;
                    } else if (d == '\'') {
                        if (i < n && hql.charAt(i) == '\'') {  // '' escape inside E-string
                            sb.append('\'');
                            i++;
                        } else {
                            break;                               // end of E-string
                        }
                    }
                }

            // ── Ordinary single-quoted literal — standard SQL ('' escape only) ─────────────
            } else if (c == '\'') {
                sb.append(c);
                i++;
                while (i < n) {
                    char d = hql.charAt(i);
                    sb.append(d);
                    i++;
                    if (d == '\'') {
                        if (i < n && hql.charAt(i) == '\'') {  // '' escapes a quote
                            sb.append('\'');
                            i++;
                        } else {
                            break;
                        }
                    }
                }

            // ── Double-quoted identifier: "..." — "" escapes an embedded double-quote ───────
            } else if (c == '"') {
                sb.append(c);
                i++;
                while (i < n) {
                    char d = hql.charAt(i);
                    sb.append(d);
                    i++;
                    if (d == '"') {
                        if (i < n && hql.charAt(i) == '"') {   // "" escape
                            sb.append('"');
                            i++;
                        } else {
                            break;
                        }
                    }
                }

            // ── Dollar-quoted string: $tag$...$tag$ (tag is empty or [A-Za-z0-9_]+) ────────
            } else if (c == '$') {
                // Try to read a dollar-quote opener: $[tag]$
                // Scan forward to find the matching closing $, collecting the tag.
                int j = i + 1;
                while (j < n && (hql.charAt(j) == '_'
                        || (hql.charAt(j) >= 'A' && hql.charAt(j) <= 'Z')
                        || (hql.charAt(j) >= 'a' && hql.charAt(j) <= 'z')
                        || (hql.charAt(j) >= '0' && hql.charAt(j) <= '9'))) {
                    j++;
                }
                if (j < n && hql.charAt(j) == '$') {
                    // We have a valid $tag$ opener (tag may be empty).
                    String closeTag = hql.substring(i, j + 1);   // e.g. "$$" or "$body$"
                    sb.append(closeTag);
                    i = j + 1;
                    // Scan for the matching closer.
                    while (i < n) {
                        int closeIdx = hql.indexOf(closeTag, i);
                        if (closeIdx < 0) {
                            // Unterminated dollar-quote — copy remainder verbatim.
                            sb.append(hql, i, n);
                            i = n;
                        } else {
                            sb.append(hql, i, closeIdx + closeTag.length());
                            i = closeIdx + closeTag.length();
                        }
                        break;
                    }
                } else {
                    // Not a dollar-quote opener (e.g. $1 positional param in raw SQL, a$b, etc.)
                    // — copy the $ verbatim and continue.
                    sb.append(c);
                    i++;
                }

            // ── Line comment: -- ... ─────────────────────────────────────────────────────────
            } else if (c == '-' && i + 1 < n && hql.charAt(i + 1) == '-') {
                while (i < n && hql.charAt(i) != '\n') {
                    sb.append(hql.charAt(i));
                    i++;
                }

            // ── Block comment: /* ... */ ──────────────────────────────────────────────────────
            } else if (c == '/' && i + 1 < n && hql.charAt(i + 1) == '*') {
                sb.append("/*");
                i += 2;
                while (i < n) {
                    if (hql.charAt(i) == '*' && i + 1 < n && hql.charAt(i + 1) == '/') {
                        sb.append("*/");
                        i += 2;
                        break;
                    }
                    sb.append(hql.charAt(i));
                    i++;
                }

            // ── Placeholder or escaped-? ──────────────────────────────────────────────────────
            } else if (c == '?') {
                if (i + 1 < n && hql.charAt(i + 1) == '?') {   // ?? -> literal ?
                    sb.append('?');
                    i += 2;
                } else {
                    sb.append('?').append(paramIndex++);
                    i++;
                }

            // ── Default: copy character verbatim ─────────────────────────────────────────────
            } else {
                sb.append(c);
                i++;
            }
        }
        return sb.toString();
    }

    private void bindParams(Query<?> query, Object[] params) {
        for (int i = 0; i < params.length; i++) {
            query.setParameter(i + 1, params[i]);
        }
    }

    private void bindParams(MutationQuery query, Object[] params) {
        for (int i = 0; i < params.length; i++) {
            query.setParameter(i + 1, params[i]);
        }
    }
}
