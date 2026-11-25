package hello.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

// JPA entities need a no-arg constructor and non-final fields, so the shared hello.model
// classes (final, constructor-only) can't be reused.
@Entity
@Table(name = "world")
public class WorldEntity {
	@Id
	public Integer id;

	@Column(name = "randomnumber")
	public int randomNumber;
}
