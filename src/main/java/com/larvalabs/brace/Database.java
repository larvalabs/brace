package com.larvalabs.brace;

import org.hibernate.StatelessSession;
import org.hibernate.query.MutationQuery;
import org.hibernate.query.Query;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Thin wrapper over Hibernate StatelessSession providing a simple query API.
 * Uses ? placeholders (converted to ?1, ?2, etc. for Hibernate 7).
 */
public class Database {

    private final StatelessSession session;
    private int queryCount = 0;
    private long queryDurationUs = 0;
    // Statements that wrote: insert/update/delete/deleteBy/sql. Not jdbc(...), which is opaque
    // (it runs reads as often as writes). Only read by the dev-mode "GET wrote" warning.
    private int writeCount = 0;
    private java.util.List<Runnable> afterCommit;
    // H3: the request this Database serves, whose values must not appear in a query string.
    // Null outside a request handler (jobs, startup code, tests using a Database directly).
    private QueryTaint taint;

    public Database(StatelessSession session) {
        this.session = session;
    }

    /** Check every query string this Database runs against {@code req}'s values (H3). */
    void checkQueriesAgainst(Request req, boolean strict) {
        this.taint = QueryTaint.of(req, strict);
    }

    private void checkQuery(String query, String method) {
        if (taint != null) taint.check(query, method);
    }

    public int queryCount() { return queryCount; }
    public long queryDurationUs() { return queryDurationUs; }
    int writeCount() { return writeCount; }

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
        writeCount++;
        queryDurationUs += (System.nanoTime() - start) / 1000;
        queryCount++;
    }

    public void update(Object entity) {
        long start = System.nanoTime();
        session.update(entity);
        writeCount++;
        queryDurationUs += (System.nanoTime() - start) / 1000;
        queryCount++;
    }

    public void delete(Object entity) {
        long start = System.nanoTime();
        session.delete(entity);
        writeCount++;
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
        return query(type, hqlWhere, (Sort) null, params);
    }

    /**
     * Like {@link #query(Class, String, Object...)}, ordered by {@code sort}: the safe way to
     * sort by a request's {@code ?sort=} (H3). The where-fragment must not have its own
     * {@code ORDER BY}; it may be blank to list every row.
     *
     * <pre>{@code
     * var sort = Sort.from(req, "sort", Map.of("title", "title", "date", "createdAt"), "createdAt DESC");
     * var posts = db.query(Post.class, "published = true", sort);
     * }</pre>
     */
    public <T> List<T> query(Class<T> type, String hqlWhere, Sort sort, Object... params) {
        checkQuery(hqlWhere, "query");
        long start = System.nanoTime();
        String hql = "FROM " + type.getSimpleName() + orderedWhere(hqlWhere, sort);
        Query<T> query = session.createQuery(hql, type);
        bindParams(query, params);
        List<T> result = query.getResultList();
        queryDurationUs += (System.nanoTime() - start) / 1000;
        queryCount++;
        return result;
    }

    /**
     * Like {@link #query(Class, String, Object...)}, but returns one page of results.
     * {@code limit}/{@code offset} are applied via Hibernate's
     * {@code setMaxResults}/{@code setFirstResult} — no string surgery on the HQL — so the
     * database emits the dialect-correct LIMIT/OFFSET clause.
     *
     * <p>{@code ORDER BY} belongs inside the where-fragment string (it is concatenated into the
     * generated HQL, same as {@link #query}); always order when paginating, or page boundaries
     * are unstable. Pair with {@link #count(Class, String, Object...)} for the total:
     *
     * <pre>{@code
     * // page 2, 20 per page
     * var page = db.queryPage(Post.class, "published = true ORDER BY createdAt DESC", 20, 20);
     * var total = db.count(Post.class, "published = true");
     * }</pre>
     *
     * @param limit  maximum rows to return; must be {@code > 0}
     * @param offset rows to skip before the first returned row; must be {@code >= 0}
     * @throws IllegalArgumentException if {@code limit <= 0} or {@code offset < 0}
     */
    public <T> List<T> queryPage(Class<T> type, String hqlWhere, int limit, int offset, Object... params) {
        return queryPage(type, hqlWhere, null, limit, offset, params);
    }

    /** Like {@link #queryPage(Class, String, int, int, Object...)}, ordered by {@code sort} (H3). */
    public <T> List<T> queryPage(Class<T> type, String hqlWhere, Sort sort, int limit, int offset, Object... params) {
        if (limit <= 0) throw new IllegalArgumentException("limit must be > 0 (was " + limit + ")");
        if (offset < 0) throw new IllegalArgumentException("offset must be >= 0 (was " + offset + ")");
        checkQuery(hqlWhere, "queryPage");
        long start = System.nanoTime();
        String hql = "FROM " + type.getSimpleName() + orderedWhere(hqlWhere, sort);
        Query<T> query = session.createQuery(hql, type);
        bindParams(query, params);
        query.setMaxResults(limit);
        query.setFirstResult(offset);
        List<T> result = query.getResultList();
        queryDurationUs += (System.nanoTime() - start) / 1000;
        queryCount++;
        return result;
    }

    /**
     * One page of {@code type} plus the totals and links a pager needs. The page number is read
     * from the request's {@code ?page=} (missing or unparseable is page 1) and clamped to the
     * last page; the returned {@link Paged} builds its links from the request's URL, so filters
     * in the query string carry over. Runs a count query and, unless it is zero, one page fetch.
     *
     * <pre>{@code
     * var posts = db.paginate(Post.class, "published = true ORDER BY createdAt DESC", req, 20);
     * }</pre>
     *
     * {@code ORDER BY} belongs in the where-fragment, as for {@link #queryPage}; always order, or
     * page boundaries are unstable. The count ignores it.
     */
    public <T> Paged<T> paginate(Class<T> type, String hqlWhere, Request req, int perPage, Object... params) {
        return paginate(type, hqlWhere, null, Paged.requestedPage(req), perPage, params).linkedTo(req);
    }

    /**
     * Like {@link #paginate(Class, String, Request, int, Object...)}, ordered by {@code sort} (H3).
     * The page links keep the request's {@code ?sort=}:
     *
     * <pre>{@code
     * var sort = Sort.from(req, "sort", Map.of("title", "title", "date", "createdAt"), "createdAt DESC");
     * var posts = db.paginate(Post.class, "published = true", sort, req, 20);
     * }</pre>
     */
    public <T> Paged<T> paginate(Class<T> type, String hqlWhere, Sort sort, Request req, int perPage, Object... params) {
        return paginate(type, hqlWhere, sort, Paged.requestedPage(req), perPage, params).linkedTo(req);
    }

    /**
     * Like {@link #paginate(Class, String, Request, int, Object...)} with an explicit page
     * number, for JSON APIs and jobs. The result has no links unless you call
     * {@link Paged#linkedTo(Request)}.
     */
    public <T> Paged<T> paginate(Class<T> type, String hqlWhere, int page, int perPage, Object... params) {
        return paginate(type, hqlWhere, null, page, perPage, params);
    }

    /** Like {@link #paginate(Class, String, int, int, Object...)}, ordered by {@code sort} (H3). */
    public <T> Paged<T> paginate(Class<T> type, String hqlWhere, Sort sort, int page, int perPage, Object... params) {
        if (perPage <= 0) throw new IllegalArgumentException("perPage must be > 0 (was " + perPage + ")");
        checkQuery(hqlWhere, "paginate");
        long start = System.nanoTime();
        String hql = "FROM " + type.getSimpleName() + orderedWhere(hqlWhere, sort);
        Query<T> query = session.createQuery(hql, type);
        bindParams(query, params);
        // Hibernate derives the count from the same query (ORDER BY dropped), so the where
        // clause is written once.
        long total = query.getResultCount();
        queryCount++;
        int p = Paged.clamp(page, Paged.totalPages(total, perPage));
        List<T> items = List.of();
        if (total > 0) {
            query.setFirstResult((p - 1) * perPage);
            query.setMaxResults(perPage);
            items = query.getResultList();
            queryCount++;
        }
        queryDurationUs += (System.nanoTime() - start) / 1000;
        return Paged.of(items, p, perPage, total);
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
        // Mirrors query() but caps the result set at one row: without setMaxResults(1),
        // a non-unique predicate fetched and hydrated every matching row to return the first.
        checkQuery(hqlWhere, "queryOne");
        long start = System.nanoTime();
        String hql = "FROM " + type.getSimpleName() + whereClause(hqlWhere, true);
        Query<T> query = session.createQuery(hql, type);
        bindParams(query, params);
        query.setMaxResults(1);
        List<T> result = query.getResultList();
        queryDurationUs += (System.nanoTime() - start) / 1000;
        queryCount++;
        return result.isEmpty() ? null : result.get(0);
    }

    /**
     * Like {@link #find(Class, Object)}, but throws {@link NotFoundException} (rendered as a
     * 404 response) when no row exists.
     *
     * <p><strong>It returns any user's row.</strong> For data that belongs to a user, an id taken
     * from the URL must be checked against the signed-in user, or changing the id reads or edits
     * someone else's (an IDOR). Use {@link #findOwnedOr404} or
     * {@link #findOr404(Class, Object, java.util.function.Predicate)} there; this one is for
     * rows every visitor may see.
     */
    public <T> T findOr404(Class<T> type, Object id) {
        T result = find(type, id);
        if (result == null) throw new NotFoundException();
        return result;
    }

    /**
     * The row with this id whose {@code ownerField} equals {@code ownerId}, or
     * {@link NotFoundException} (a 404) when there is none: the lookup for user-owned data (H4).
     * A row that exists but belongs to someone else is a 404 too, so ids of other users' rows
     * can't be probed.
     *
     * <pre>{@code
     * var post = db.findOwnedOr404(Post.class, req.longPathParam("id"), "ownerId", session.get("userId"));
     * }</pre>
     *
     * {@code ownerField} is an entity attribute path ({@code "ownerId"}, {@code "owner.id"}),
     * validated like {@link #findBy}'s field; it must be hard-coded, never request input. A null
     * {@code ownerId} (nobody signed in) matches nothing.
     */
    public <T> T findOwnedOr404(Class<T> type, Object id, String ownerField, Object ownerId) {
        requireValidFieldIdentifier(ownerField);
        if (id == null || ownerId == null) throw new NotFoundException();
        long start = System.nanoTime();
        String hql = "FROM " + type.getSimpleName() + " e WHERE id(e) = ?1 AND e." + ownerField + " = ?2";
        Query<T> query = session.createQuery(hql, type);
        query.setParameter(1, id);
        query.setParameter(2, ownerId);
        List<T> result = query.getResultList();
        queryDurationUs += (System.nanoTime() - start) / 1000;
        queryCount++;
        if (result.isEmpty()) throw new NotFoundException();
        return result.get(0);
    }

    /**
     * Like {@link #findOr404(Class, Object)}, but also a 404 when {@code allowed} rejects the
     * row: for access rules a single owner column doesn't express (shared with a team, public
     * once published). Rejected rows are 404, not 403, so their ids can't be probed (H4).
     *
     * <pre>{@code
     * var doc = db.findOr404(Doc.class, id, d -> d.ownerId.equals(userId) || d.isPublic);
     * }</pre>
     */
    public <T> T findOr404(Class<T> type, Object id, java.util.function.Predicate<? super T> allowed) {
        if (allowed == null) throw new IllegalArgumentException("findOr404 needs an access predicate");
        T result = find(type, id);
        if (result == null || !allowed.test(result)) throw new NotFoundException();
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
        checkQuery(hqlWhere, "count");
        long start = System.nanoTime();
        String hql = "SELECT count(*) FROM " + type.getSimpleName() + whereClause(hqlWhere, false);
        Query<Long> query = session.createQuery(hql, Long.class);
        bindParams(query, params);
        long result = query.getSingleResult();
        queryDurationUs += (System.nanoTime() - start) / 1000;
        queryCount++;
        return result;
    }

    /**
     * Return {@code true} if any row of {@code type} matches the HQL where-fragment — the
     * multi-field counterpart of {@link #existsBy(Class, String, Object)}. Equivalent to
     * {@code count(type, hqlWhere, params) > 0} in one call:
     * {@code db.exists(Rating.class, "talkId = ? AND attendeeId = ?", talkId, attendeeId)}.
     *
     * <p><strong>Security:</strong> {@code hqlWhere} is concatenated into the generated HQL,
     * same as {@link #query(Class, String, Object...)} — it must be a trusted, hard-coded
     * fragment. User-controlled values belong in {@code ?} bind params, never in the
     * fragment itself.
     */
    public <T> boolean exists(Class<T> type, String hqlWhere, Object... params) {
        // count() already instruments
        return count(type, hqlWhere, params) > 0;
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
        requireValidFieldIdentifier(field);
        // SELECT 1 with a row cap: an index probe that stops at the first hit,
        // instead of COUNT(*) walking every matching row.
        long start = System.nanoTime();
        String hql = "SELECT 1 FROM " + type.getSimpleName() + " WHERE " + field + " = ?1";
        Query<Integer> query = session.createQuery(hql, Integer.class);
        query.setParameter(1, value);
        query.setMaxResults(1);
        boolean result = !query.getResultList().isEmpty();
        queryDurationUs += (System.nanoTime() - start) / 1000;
        queryCount++;
        return result;
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
        writeCount++;
        queryDurationUs += (System.nanoTime() - start) / 1000;
        queryCount++;
        return result;
    }

    // --- Raw queries ---

    public List<Object[]> hql(String hql, Object... params) {
        checkQuery(hql, "hql");
        long start = System.nanoTime();
        Query<?> query = session.createQuery(convertPositionalParams(hql));
        bindParams(query, params);
        List<Object[]> result = asRows(query.getResultList());
        queryDurationUs += (System.nanoTime() - start) / 1000;
        queryCount++;
        return result;
    }

    public void sql(String sql, Object... params) {
        checkQuery(sql, "sql");
        long start = System.nanoTime();
        MutationQuery query = session.createNativeMutationQuery(convertPositionalParams(sql));
        bindParams(query, params);
        query.executeUpdate();
        writeCount++;
        queryDurationUs += (System.nanoTime() - start) / 1000;
        queryCount++;
    }

    public List<Object[]> sqlQuery(String sql, Object... params) {
        checkQuery(sql, "sqlQuery");
        long start = System.nanoTime();
        var query = session.createNativeQuery(convertPositionalParams(sql));
        bindParams(query, params);
        List<Object[]> results = asRows(query.getResultList());
        queryDurationUs += (System.nanoTime() - start) / 1000;
        queryCount++;
        return results;
    }

    @SuppressWarnings("unchecked")
    public Long sqlQueryLong(String sql, Object... params) {
        checkQuery(sql, "sqlQueryLong");
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

    /**
     * Run {@code action} after this session's next successful commit, or drop it if that
     * transaction rolls back. For side effects that must not fire until the data they describe is
     * actually visible to other sessions — {@link Jobs#schedule} uses it to nudge the job poller,
     * which under READ COMMITTED would otherwise look before the row exists and find nothing.
     *
     * <p>Callbacks run after the commit returns, on the committing thread, and their exceptions are
     * logged rather than propagated: the transaction has already succeeded, so a failing hook must
     * not turn that into an error for the caller. Keep them short and non-blocking.
     *
     * <p>A {@link Database} is confined to one request or job, so registration is not synchronized.
     */
    public void afterCommit(Runnable action) {
        if (afterCommit == null) {
            afterCommit = new java.util.ArrayList<>(2);
        }
        afterCommit.add(action);
    }

    public void commitTransaction() {
        session.getTransaction().commit();
        if (afterCommit != null) {
            var actions = afterCommit;
            afterCommit = null;
            for (var action : actions) {
                try {
                    action.run();
                } catch (Exception e) {
                    Log.error("after-commit-hook-failed", e);
                }
            }
        }
    }

    public void rollbackTransaction() {
        // Drop the hooks first, so a throw from rollback still can't leak them into a later commit
        // on this session — the work they describe never landed.
        afterCommit = null;
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
    private static final Pattern ORDER_BY_ONLY = Pattern.compile("(?is)\\s*order\\s+by\\b.*");

    /**
     * The clause that follows {@code FROM Type} for a where-fragment: {@code " WHERE ..."}, or no
     * {@code WHERE} when the fragment has no condition — blank, or only an {@code ORDER BY}
     * ({@code db.paginate(Post.class, "ORDER BY createdAt DESC", req, 20)} lists every row).
     * {@code keepOrderBy=false} drops an ORDER BY-only fragment entirely, for counts.
     */
    private static final Pattern HAS_ORDER_BY = Pattern.compile("(?i)\\border\\s+by\\b");

    /** The where clause plus {@code sort}'s ORDER BY; just the where clause when there's no sort. */
    private String orderedWhere(String hqlWhere, Sort sort) {
        if (sort == null) return whereClause(hqlWhere, true);
        if (hqlWhere != null && HAS_ORDER_BY.matcher(hqlWhere).find()) {
            throw new IllegalArgumentException("The where-fragment has its own ORDER BY and a Sort was passed: "
                + "put the whole order in the Sort (Sort.of(\"createdAt DESC, id\") for a fixed one)");
        }
        return whereClause(hqlWhere, true) + sort.hql();
    }

    String whereClause(String hqlWhere, boolean keepOrderBy) {
        if (hqlWhere == null || hqlWhere.isBlank()) return "";
        if (ORDER_BY_ONLY.matcher(hqlWhere).matches()) {
            return keepOrderBy ? " " + convertPositionalParams(hqlWhere.strip()) : "";
        }
        return " WHERE " + convertPositionalParams(hqlWhere);
    }

    // L6: the rewrite is a pure function of the input string and the inputs are almost always
    // code literals (a naturally small, bounded set), so memoize it instead of re-scanning on
    // every execution. Bounded — once the cache is full we stop adding entries and fall back to a
    // fresh scan, so a caller that builds dynamic query strings (e.g. queryIn's per-IN-size HQL,
    // L7) can't grow it without limit.
    private static final int PARAM_CACHE_MAX = 1000;
    private static final ConcurrentHashMap<String, String> PARAM_CACHE = new ConcurrentHashMap<>();

    String convertPositionalParams(String hql) {   // package-private for direct unit testing
        String cached = PARAM_CACHE.get(hql);
        if (cached != null) return cached;
        String converted = scanPositionalParams(hql);
        if (PARAM_CACHE.size() < PARAM_CACHE_MAX) {
            PARAM_CACHE.putIfAbsent(hql, converted);
        }
        return converted;
    }

    private static String scanPositionalParams(String hql) {
        var sb = new StringBuilder(hql.length() + 8);
        int paramIndex = 1;
        int n = hql.length();
        int i = 0;
        while (i < n) {
            char c = hql.charAt(i);

            // ── E-string: E'...' or e'...' — backslash escapes active ──────────────────────
            // L11: the E must START a token. Without that check the trailing 'E' of any identifier
            // or keyword immediately followed by a quote — "... LIKE'%x%'" — opened backslash-escape
            // mode, where a literal backslash before the closing quote swallows the terminator and
            // every following '?' is mis-numbered.
            if ((c == 'E' || c == 'e') && i + 1 < n && hql.charAt(i + 1) == '\''
                    && !isIdentifierChar(i > 0 ? hql.charAt(i - 1) : ' ')) {
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

    /**
     * Normalize a result list to {@code List<Object[]>} — one array per row (M7).
     *
     * <p>Hibernate returns a list of <em>scalars</em>, not arrays, when the select has a single
     * item, so {@code hql}/{@code sqlQuery} used to reach their declared type through an unchecked
     * cast that was simply false for that shape. The failure landed as a
     * {@code ClassCastException} inside the CALLER's {@code for (Object[] row : ...)} loop, with a
     * stack trace pointing nowhere near the query. Wrapping here makes the declared type true for
     * every query, so a one-column select behaves like any other:
     * {@code row[0]} is the value.
     *
     * <p>{@code sqlQueryLong} already normalized both shapes; this brings the list accessors in
     * line with it.
     */
    private static List<Object[]> asRows(List<?> results) {
        var rows = new java.util.ArrayList<Object[]>(results.size());
        for (Object row : results) {
            rows.add(row instanceof Object[] array ? array : new Object[]{row});
        }
        return rows;
    }

    /** True for a character that can appear inside a SQL identifier or keyword (see L11). */
    private static boolean isIdentifierChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$';
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
