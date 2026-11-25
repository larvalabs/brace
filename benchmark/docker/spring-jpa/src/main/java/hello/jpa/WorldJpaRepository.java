package hello.jpa;

import org.springframework.data.jpa.repository.JpaRepository;

public interface WorldJpaRepository extends JpaRepository<WorldEntity, Integer> {
}
