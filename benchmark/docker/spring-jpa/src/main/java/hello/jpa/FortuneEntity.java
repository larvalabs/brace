package hello.jpa;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "fortune")
public class FortuneEntity {
	@Id
	public Integer id;

	public String message;
}
