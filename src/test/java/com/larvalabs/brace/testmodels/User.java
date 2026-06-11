package com.larvalabs.brace.testmodels;

import jakarta.persistence.*;

@Entity
@Table(name = "users")
public class User {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    public String email;
    public String passwordHash;  // Sensitive field that should not be serialized
}
