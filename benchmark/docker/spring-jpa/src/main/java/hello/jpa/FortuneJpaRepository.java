package hello.jpa;

import org.springframework.data.jpa.repository.JpaRepository;

public interface FortuneJpaRepository extends JpaRepository<FortuneEntity, Integer> {
}
