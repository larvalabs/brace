package hello.repository;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ParameterizedPreparedStatementSetter;
import org.springframework.stereotype.Repository;

import hello.jpa.FortuneEntity;
import hello.jpa.FortuneJpaRepository;
import hello.jpa.WorldJpaRepository;
import hello.model.Fortune;
import hello.model.World;

/**
 * Reads through Spring Data JPA (Hibernate): {@code findById} per world, {@code findAll} for
 * fortunes, each in Spring Data's default read-only transaction.
 *
 * The Updates write is one JDBC batch, exactly as in JdbcDbRepository and as in Brace's
 * benchmark app ({@code db.jdbc(...)} + {@code executeBatch}), so all three issue the same
 * statements: N selects plus one batched update. Doing it through JPA instead (merge or
 * re-find the detached entities, then flush) would add N more selects, because TechEmpower's
 * DbHandler reads and writes in separate calls.
 */
@Repository
@Profile("jpa")
public class JpaDbRepository implements DbRepository {

	private final WorldJpaRepository worldRepository;
	private final FortuneJpaRepository fortuneRepository;
	private final JdbcTemplate jdbcTemplate;

	public JpaDbRepository(WorldJpaRepository worldRepository, FortuneJpaRepository fortuneRepository,
			JdbcTemplate jdbcTemplate) {
		this.worldRepository = worldRepository;
		this.fortuneRepository = fortuneRepository;
		this.jdbcTemplate = jdbcTemplate;
	}

	@Override
	public World getWorld(int id) {
		return worldRepository.findById(id).map(w -> new World(w.id, w.randomNumber)).orElse(null);
	}

	@Override
	public void updateWorlds(List<World> worlds) {
		jdbcTemplate.batchUpdate("UPDATE world SET randomnumber = ? WHERE id = ?", worlds, worlds.size(),
				new ParameterizedPreparedStatementSetter<World>() {
					@Override
					public void setValues(PreparedStatement ps, World world) throws SQLException {
						ps.setInt(1, world.randomNumber);
						ps.setInt(2, world.id);
					}
				});
	}

	@Override
	public List<Fortune> fortunes() {
		List<Fortune> result = new ArrayList<>();
		for (FortuneEntity f : fortuneRepository.findAll()) {
			result.add(new Fortune(f.id, f.message));
		}
		return result;
	}
}
