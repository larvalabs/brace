package com.larvalabs.brace.testmodels;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "posts")
public class Post {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    public String title;
    public String body;
    @Column(name = "created_at")
    public Instant createdAt;
}
