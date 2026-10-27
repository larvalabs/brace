package com.larvalabs.brace.testmodels;

import jakarta.persistence.*;

/** A user-owned row, for the owned-lookup tests (H4). */
@Entity
@Table(name = "notes")
public class Note {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    @Column(name = "owner_id")
    public Long ownerId;
    public String text;
    public boolean shared;
}
