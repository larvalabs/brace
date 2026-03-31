package benchmark.model;

import jakarta.persistence.*;

@Entity
@Table(name = "world")
public class World {
    @Id
    public int id;

    @Column(name = "randomnumber")
    public int randomNumber;
}
