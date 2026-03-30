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

    public Database(StatelessSession session) {
        this.session = session;
    }

    // --- CRUD ---

    public <T> T find(Class<T> type, Object id) {
        return session.get(type, id);
    }

    public void insert(Object entity) {
        session.insert(entity);
    }

    public void update(Object entity) {
        session.update(entity);
    }

    public void delete(Object entity) {
        session.delete(entity);
    }

    // --- Queries ---

    public <T> List<T> findAll(Class<T> type) {
        String hql = "FROM " + type.getSimpleName();
        return session.createQuery(hql, type).getResultList();
    }

    public <T> List<T> query(Class<T> type, String hqlWhere, Object... params) {
        String hql = "FROM " + type.getSimpleName() + " WHERE " + convertPositionalParams(hqlWhere);
        Query<T> query = session.createQuery(hql, type);
        bindParams(query, params);
        return query.getResultList();
    }

    public <T> T queryOne(Class<T> type, String hqlWhere, Object... params) {
        List<T> results = query(type, hqlWhere, params);
        return results.isEmpty() ? null : results.get(0);
    }

    public <T> long count(Class<T> type) {
        String hql = "SELECT count(*) FROM " + type.getSimpleName();
        return session.createQuery(hql, Long.class).getSingleResult();
    }

    public <T> long count(Class<T> type, String hqlWhere, Object... params) {
        String hql = "SELECT count(*) FROM " + type.getSimpleName() + " WHERE " + convertPositionalParams(hqlWhere);
        Query<Long> query = session.createQuery(hql, Long.class);
        bindParams(query, params);
        return query.getSingleResult();
    }

    // --- Raw queries ---

    @SuppressWarnings("unchecked")
    public List<Object[]> hql(String hql, Object... params) {
        Query<?> query = session.createQuery(convertPositionalParams(hql));
        bindParams(query, params);
        return (List<Object[]>) query.getResultList();
    }

    public void sql(String sql, Object... params) {
        MutationQuery query = session.createNativeMutationQuery(convertPositionalParams(sql));
        bindParams(query, params);
        query.executeUpdate();
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
