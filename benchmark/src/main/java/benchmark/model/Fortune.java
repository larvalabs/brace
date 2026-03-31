package benchmark.model;

import jakarta.persistence.*;

@Entity
@Table(name = "fortune")
public class Fortune {
    @Id
    public int id;
    public String message;
}
