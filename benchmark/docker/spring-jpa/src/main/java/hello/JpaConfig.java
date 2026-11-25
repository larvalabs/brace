package hello;

import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

// Added by Brace's benchmark (benchmark/docker/spring-jpa): Spring Data JPA over Hibernate, the
// "full ORM" Spring setup most real applications use. TechEmpower's Spring app ships none.
@Profile("jpa")
@Configuration
@EnableJpaRepositories(basePackages = "hello.jpa")
@EntityScan(basePackages = "hello.jpa")
public class JpaConfig {
}
