package io.brace;

import org.hibernate.StatelessSession;
import org.hibernate.query.MutationQuery;
import org.hibernate.query.Query;

import java.util.List;

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

    public <T> List<T> queryIn(Class<T> type, String field, List<?> values) {
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

    private String convertPositionalParams(String hql) {
        var sb = new StringBuilder();
        int paramIndex = 1;
        for (char c : hql.toCharArray()) {
            if (c == '?') {
                sb.append('?').append(paramIndex++);
            } else {
                sb.append(c);
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
