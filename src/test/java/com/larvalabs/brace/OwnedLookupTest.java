package com.larvalabs.brace;

import com.larvalabs.brace.testmodels.Note;
import org.junit.jupiter.api.*;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * H4 (2026-10 security review): {@code findOr404} returns any user's row, so the documented
 * lookup was an IDOR. {@code findOwnedOr404} and the predicate {@code findOr404} make the owner
 * check part of the lookup, and answer 404 for someone else's row.
 */
class OwnedLookupTest {

    static DatabaseFactory factory;
    static long alicesNote;
    static long bobsSharedNote;

    @BeforeAll
    static void setup() {
        factory = new DatabaseFactory("jdbc:h2:mem:owned-lookup;DB_CLOSE_DELAY=-1", null, null, List.of(Note.class));
        withDb(db -> {
            alicesNote = insert(db, 1L, "alice's", false);
            bobsSharedNote = insert(db, 2L, "bob's, shared", true);
        });
    }

    @AfterAll
    static void teardown() {
        factory.close();
    }

    @Test
    void ownerGetsTheRow() {
        withDb(db -> assertEquals("alice's", db.findOwnedOr404(Note.class, alicesNote, "ownerId", 1L).text));
    }

    @Test
    void anotherUsersRowIsNotFound() {
        withDb(db -> {
            assertThrows(NotFoundException.class, () -> db.findOwnedOr404(Note.class, alicesNote, "ownerId", 2L));
            assertThrows(NotFoundException.class, () -> db.findOwnedOr404(Note.class, 99999L, "ownerId", 1L));
        });
    }

    @Test
    void nobodySignedInMatchesNothing() {
        withDb(db -> assertThrows(NotFoundException.class, () -> db.findOwnedOr404(Note.class, alicesNote, "ownerId", null)));
    }

    @Test
    void invalidOwnerFieldThrowsAtTheCall() {
        withDb(db -> {
            assertThrows(IllegalArgumentException.class,
                () -> db.findOwnedOr404(Note.class, alicesNote, "ownerId = 1 OR 1=1 OR ownerId", 2L));
            assertThrows(IllegalArgumentException.class, () -> db.findOwnedOr404(Note.class, alicesNote, "", 1L));
        });
    }

    @Test
    void predicateVariantBehavesTheSame() {
        withDb(db -> {
            long user = 1L;
            var visible = db.findOr404(Note.class, bobsSharedNote, n -> n.ownerId.equals(user) || n.shared);
            assertEquals("bob's, shared", visible.text);
            assertEquals("alice's", db.findOr404(Note.class, alicesNote, n -> n.ownerId.equals(user)).text);
            assertThrows(NotFoundException.class, () -> db.findOr404(Note.class, alicesNote, n -> n.ownerId.equals(2L)));
            assertThrows(NotFoundException.class, () -> db.findOr404(Note.class, 99999L, n -> true));
            assertThrows(IllegalArgumentException.class, () -> db.findOr404(Note.class, alicesNote, null));
        });
    }

    private static long insert(Database db, long owner, String text, boolean shared) {
        var note = new Note();
        note.ownerId = owner;
        note.text = text;
        note.shared = shared;
        db.insert(note);
        return note.id;
    }

    private static void withDb(java.util.function.Consumer<Database> body) {
        var db = new Database(factory.openSession());
        try {
            db.beginTransaction();
            body.accept(db);
            db.commitTransaction();
        } finally {
            db.close();
        }
    }
}
