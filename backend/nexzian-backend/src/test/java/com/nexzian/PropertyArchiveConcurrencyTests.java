package com.nexzian;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.nexzian.dto.PropertyRequest;
import com.nexzian.dto.PropertyResponse;
import com.nexzian.entity.Property;
import com.nexzian.exception.PropertyArchivedException;
import com.nexzian.service.PropertyService;

/**
 * Concurrency safety of the property archive lifecycle (R3 review findings M1 and M2).
 *
 * Archive and restore are single atomic conditional UPDATEs (PropertyRepository.archiveIfActive /
 * restoreIfArchived) and Property uses @DynamicUpdate. These tests protect against the two real
 * races a read-then-save implementation had:
 *  - an EDIT that loaded the property before a concurrent archive/restore writing its stale
 *    deleted_at/deleted_by back and silently undoing the transition (a hidden listing could go
 *    public again);
 *  - two concurrent ARCHIVES both succeeding, the later one overwriting who/when archived it.
 * On top of that, the edit path reads the property row WITH A WRITE LOCK (see the "serialize on
 * the property row" section), so an edit and an archive/restore of the same property run one
 * after the other: an edit can never silently succeed against a property that has just become
 * archived. The two stale-edit tests above emulate an edit that did NOT take the lock and so
 * guard the second line of defence (@DynamicUpdate).
 * Tests drive the service from real threads with committed transactions; every concurrent test
 * asserts an INVARIANT that must hold whichever thread wins, so none of them is timing-dependent.
 */
class PropertyArchiveConcurrencyTests extends AuthorizationTestSupport {

    private static final int ROUNDS = 25;

    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private PropertyService propertyService;
    @Autowired private JdbcTemplate jdbcTemplate;

    // ---- M1: a stale edit must never undo an archive / restore ----------------------------------

    @Test
    void editLoadedBeforeAnArchiveCannotUnArchiveTheProperty() throws Exception {
        Property property = propertyA;
        LocalDateTime[] archivedAt = new LocalDateTime[1];

        // The "edit request": loads the ACTIVE property (as PropertyService.updateProperty does),
        // pauses while another request archives it, then changes the title and commits.
        runEditAroundATransition(property.getId(), tenantA.getId(), "edited during archive",
                () -> {
                    assertTrue(propertyService.archiveProperty(property.getId(), tenantA.getId(), ownerA.getId()));
                    archivedAt[0] = propertyRepository.findById(property.getId()).orElseThrow().getDeletedAt();
                    return null;
                });

        Property stored = propertyRepository.findById(property.getId()).orElseThrow();
        assertNotNull(stored.getDeletedAt(), "the edit's commit must not clear deleted_at");
        assertEquals(ownerA.getId(), stored.getDeletedBy(), "nor deleted_by");
        assertEquals(archivedAt[0], stored.getDeletedAt(), "the archive timestamp must be exactly what the archive wrote");
        assertEquals("edited during archive", stored.getTitle(), "the edit itself still applies");
    }

    @Test
    void editLoadedBeforeARestoreCannotReArchiveTheProperty() throws Exception {
        // Mirror image: the edit's stale snapshot still says "archived"; its commit must not write
        // that back over a restore that happened meanwhile.
        assertTrue(propertyService.archiveProperty(propertyA.getId(), tenantA.getId(), ownerA.getId()));

        runEditAroundATransition(propertyA.getId(), tenantA.getId(), "edited during restore",
                () -> {
                    assertTrue(propertyService.restoreProperty(propertyA.getId(), tenantA.getId()).isPresent());
                    return null;
                });

        Property stored = propertyRepository.findById(propertyA.getId()).orElseThrow();
        assertNull(stored.getDeletedAt(), "the edit's commit must not re-archive a restored property");
        assertNull(stored.getDeletedBy());
        assertEquals("edited during restore", stored.getTitle());
    }

    // ---- M2: exactly one archive wins ----------------------------------------------------------------

    @Test
    void ofTwoConcurrentArchivesExactlyOneWinsAndTheAuditValuesAreTheWinners() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < ROUNDS; round++) {
                Property property = saveProperty("Race " + round, tenantA.getId(), "PUBLISHED");
                UUID first = UUID.randomUUID();
                UUID second = UUID.randomUUID();

                List<Boolean> results = raceAtTheSameInstant(pool, List.of(
                        () -> propertyService.archiveProperty(property.getId(), tenantA.getId(), first),
                        () -> propertyService.archiveProperty(property.getId(), tenantA.getId(), second)));

                assertEquals(1, results.stream().filter(Boolean::booleanValue).count(),
                        "exactly one archive may perform the transition (round " + round + "): " + results);
                Property stored = propertyRepository.findById(property.getId()).orElseThrow();
                UUID winner = results.get(0) ? first : second;
                assertEquals(winner, stored.getDeletedBy(),
                        "deletedBy must be the winner's id - the loser must not overwrite it");
                assertNotNull(stored.getDeletedAt());
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void aLaterArchiveNeverChangesTheOriginalAuditValues() throws Exception {
        // Sequential companion of the race test: the same rule without any threads.
        assertTrue(propertyService.archiveProperty(propertyA.getId(), tenantA.getId(), ownerA.getId()));
        Property first = propertyRepository.findById(propertyA.getId()).orElseThrow();

        assertFalse(propertyService.archiveProperty(propertyA.getId(), tenantA.getId(), UUID.randomUUID()));

        Property after = propertyRepository.findById(propertyA.getId()).orElseThrow();
        assertEquals(first.getDeletedAt(), after.getDeletedAt());
        assertEquals(first.getDeletedBy(), after.getDeletedBy());
        assertEquals(first.getUpdatedAt(), after.getUpdatedAt(), "a refused archive must not even touch updated_at");
    }

    // ---- restore and mixed races -----------------------------------------------------------------------------

    @Test
    void concurrentRestoresAllSucceedWithTheSameCleanEndState() throws Exception {
        // Restoring is idempotent by contract: whichever request performs the transition, every
        // request is a success and the property ends active with BOTH archive fields cleared,
        // its status untouched and not published by the restore.
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            for (int round = 0; round < ROUNDS; round++) {
                Property draft = saveProperty("Restore race " + round, tenantA.getId(), "DRAFT");
                assertTrue(propertyService.archiveProperty(draft.getId(), tenantA.getId(), ownerA.getId()));

                List<Callable<Boolean>> restores = new ArrayList<>();
                for (int i = 0; i < 4; i++) {
                    restores.add(() -> propertyService.restoreProperty(draft.getId(), tenantA.getId()).isPresent());
                }
                List<Boolean> results = raceAtTheSameInstant(pool, restores);

                assertFalse(results.contains(false), "every concurrent restore must succeed: " + results);
                Property stored = propertyRepository.findById(draft.getId()).orElseThrow();
                assertNull(stored.getDeletedAt());
                assertNull(stored.getDeletedBy());
                assertEquals("DRAFT", stored.getStatus(), "restore must never change or publish the status");
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void archiveRacingARestoreNeverLeavesHalfSetArchiveFields() throws Exception {
        // Whichever wins, deleted_at and deleted_by must be set together or cleared together; a
        // mixed state (one null, one not) would break "archived" checks and the audit trail.
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < ROUNDS; round++) {
                Property property = saveProperty("Mixed " + round, tenantA.getId(), "AVAILABLE");
                assertTrue(propertyService.archiveProperty(property.getId(), tenantA.getId(), ownerA.getId()));

                raceAtTheSameInstant(pool, List.of(
                        () -> propertyService.restoreProperty(property.getId(), tenantA.getId()).isPresent(),
                        () -> {
                            propertyService.archiveProperty(property.getId(), tenantA.getId(), ownerA.getId());
                            return true;
                        }));

                Property stored = propertyRepository.findById(property.getId()).orElseThrow();
                assertEquals(stored.getDeletedAt() == null, stored.getDeletedBy() == null,
                        "deletedAt and deletedBy must always be set or cleared together (round " + round + ")");
                assertEquals("AVAILABLE", stored.getStatus());
            }
        } finally {
            pool.shutdownNow();
        }
    }

    // ---- transition semantics and persistence-context behaviour -------------------------------------

    @Test
    void restoreOfAnActivePropertyIsASuccessThatChangesNothing() throws Exception {
        Property before = propertyRepository.findById(propertyA.getId()).orElseThrow();

        assertTrue(propertyService.restoreProperty(propertyA.getId(), tenantA.getId()).isPresent());

        Property after = propertyRepository.findById(propertyA.getId()).orElseThrow();
        assertEquals(before.getUpdatedAt(), after.getUpdatedAt(), "a no-op restore must not touch updated_at");
        assertNull(after.getDeletedAt());
    }

    @Test
    void archiveAndRestoreUpdateUpdatedAtAndKeepEverythingElse() throws Exception {
        Property before = propertyRepository.findById(propertyA.getId()).orElseThrow();
        String statusBefore = before.getStatus();

        assertTrue(propertyService.archiveProperty(propertyA.getId(), tenantA.getId(), ownerA.getId()));
        Property archived = propertyRepository.findById(propertyA.getId()).orElseThrow();
        assertEquals(archived.getDeletedAt(), archived.getUpdatedAt(), "archive stamps updated_at in the same statement");

        propertyService.restoreProperty(propertyA.getId(), tenantA.getId());
        Property restored = propertyRepository.findById(propertyA.getId()).orElseThrow();
        assertTrue(!restored.getUpdatedAt().isBefore(archived.getUpdatedAt()));
        assertEquals(statusBefore, restored.getStatus());
        assertEquals(before.getTitle(), restored.getTitle());
    }

    @Test
    void foreignOrMissingPropertiesAreNeitherArchivedNorRestored() throws Exception {
        assertFalse(propertyService.archiveProperty(propertyB.getId(), tenantA.getId(), ownerA.getId()));
        assertFalse(propertyService.archiveProperty(UUID.randomUUID(), tenantA.getId(), ownerA.getId()));
        assertTrue(propertyService.restoreProperty(propertyB.getId(), tenantA.getId()).isEmpty());
        assertTrue(propertyService.restoreProperty(UUID.randomUUID(), tenantA.getId()).isEmpty());

        assertNull(propertyRepository.findById(propertyB.getId()).orElseThrow().getDeletedAt());
    }

    @Test
    void bulkTransitionsDoNotLeaveAStaleEntityInsideTheSameTransaction() throws Exception {
        // A bulk UPDATE bypasses the persistence context. Within ONE transaction, an entity loaded
        // before the transition must not keep answering with the old state afterwards (the
        // repository clears the context). Guards the response of restoreProperty and any later
        // read in the same transaction.
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.executeWithoutResult(status -> {
            Property loaded = propertyRepository.findById(propertyA.getId()).orElseThrow();
            assertNull(loaded.getDeletedAt());

            assertTrue(propertyService.archiveProperty(propertyA.getId(), tenantA.getId(), ownerA.getId()));
            assertNotNull(propertyRepository.findById(propertyA.getId()).orElseThrow().getDeletedAt(),
                    "a re-read after the archive must see the archived row, not the cached entity");

            assertNull(propertyService.restoreProperty(propertyA.getId(), tenantA.getId())
                    .orElseThrow().getDeletedAt(), "the restore response must reflect the database");
        });
    }

    // ---- edit / archive / restore serialize on the property row -------------------------------------------
    // Technique shared by the three ordering tests below (no sleeps, no timing assumptions):
    //  1. the test thread runs the FIRST operation through the real service inside a transaction it
    //     keeps open, so that operation has taken the row lock but NOT released it;
    //  2. the competing operation is started on another thread. A CountDownLatch ("started") is
    //     released by that thread immediately before it calls the service, so the test knows the
    //     operation has really begun before it looks for any blocking;
    //  3. the test then asks the DATABASE whether a session is blocked by the lock holder's session
    //     (H2: INFORMATION_SCHEMA.SESSIONS.BLOCKER_ID). Between checks it waits on the competing
    //     Future with a short bounded get(); if that Future COMPLETES, the operation did not wait
    //     for the lock and the test fails immediately. The only way to proceed is the database
    //     reporting "blocked by the holder";
    //  4. only then is the holder's transaction committed (releasing the lock), and the competing
    //     operation's outcome is asserted with a bounded Future.get().
    // So each test asserts a fact the database reports (the second request IS waiting on the first
    // one's lock), not that "nothing happened for N milliseconds".

    @Test
    void archiveFirst_theEditWaitsForTheLockThenFailsWith409() throws Exception {
        // Scenario: an archive has locked the row (uncommitted) when an edit arrives. The edit must
        // wait; once the archive commits it must read the ARCHIVED row and be refused, instead of
        // silently editing (or publishing) a listing the owner just hid.
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        AtomicReference<Future<?>> edit = new AtomicReference<>();
        try {
            tx.executeWithoutResult(status -> {
                // Holder: the real archive path; it now owns the row lock, uncommitted.
                assertTrue(propertyService.archiveProperty(propertyA.getId(), tenantA.getId(), ownerA.getId()));
                edit.set(startCompetingAndAwaitItBlocking(pool, () -> propertyService.updateProperty(
                        propertyA.getId(), tenantA.getId(), request("edit that must lose", "PUBLISHED"))));
            });   // commit releases the lock; the waiting edit now reads the archived row

            ExecutionException failure = assertThrows(ExecutionException.class, () -> edit.get().get(30, TimeUnit.SECONDS));
            assertInstanceOf(PropertyArchivedException.class, failure.getCause(),
                    "the edit must be refused with the 409 exception, not silently applied");

            Property stored = propertyRepository.findById(propertyA.getId()).orElseThrow();
            assertNotNull(stored.getDeletedAt());
            assertEquals(ownerA.getId(), stored.getDeletedBy());
            assertEquals("Alpha Flat", stored.getTitle(), "the refused edit must not have changed anything");
            assertEquals("PUBLISHED", stored.getStatus());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void editFirst_theArchiveWaitsForTheLockThenArchivesTheUpdatedProperty() throws Exception {
        // Scenario: an edit has locked the row (uncommitted) when an archive arrives. The archive
        // must wait, then archive the property as the edit left it - the edit is not lost and the
        // archive is not skipped. The edit goes through the real service path (updateProperty).
        //
        // Flow: (1) test opens a transaction; (2) inside it PropertyService.updateProperty() runs to
        // completion - it takes the row lock via its locking read and applies the edit, and because
        // the service joins the test's transaction nothing is committed yet, so the lock stays
        // held; (3) the archive is started on another thread and the latch + H2 blocker check
        // prove it is waiting on THAT transaction's lock; (4) the test transaction commits, which
        // flushes the edit and releases the lock; (5) the archive proceeds and is asserted.
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        AtomicReference<Future<Boolean>> archive = new AtomicReference<>();
        try {
            tx.executeWithoutResult(status -> {
                // Holder: the production edit path, completed inside the held transaction.
                PropertyResponse edited = propertyService.updateProperty(
                        propertyA.getId(), tenantA.getId(), request("edited first", "PUBLISHED")).orElseThrow();
                assertEquals("edited first", edited.getTitle(), "the edit completed inside the held transaction");
                assertNull(edited.getDeletedAt(), "and the property was still active when it did");

                archive.set(startCompetingAndAwaitItBlocking(pool, () -> propertyService.archiveProperty(
                        propertyA.getId(), tenantA.getId(), ownerA.getId())));
            });   // commit: edit becomes durable, lock released, the waiting archive can run

            assertTrue(archive.get().get(30, TimeUnit.SECONDS), "the archive performs the transition once it gets the lock");
            Property stored = propertyRepository.findById(propertyA.getId()).orElseThrow();
            assertEquals("edited first", stored.getTitle(), "the edit that held the lock first is preserved");
            assertEquals("PUBLISHED", stored.getStatus());
            assertNotNull(stored.getDeletedAt(), "and the property is archived afterwards");
            assertEquals(ownerA.getId(), stored.getDeletedBy());
            // Ordering is proven structurally (the database reported the archive blocked on the
            // edit's lock, and the edit survives) - not by comparing timestamps: the in-memory edit
            // time has nanosecond digits while the database stores microseconds, so a comparison
            // can be off by a rounding step within the same microsecond.
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void restoreFirst_theEditWaitsForTheLockThenAppliesToTheRestoredProperty() throws Exception {
        // Scenario: a restore holds the row lock while an edit arrives. The edit must wait and then
        // see the ACTIVE row; it must not be rejected on the strength of the archived state it
        // would have seen had it read before the restore.
        assertTrue(propertyService.archiveProperty(propertyA.getId(), tenantA.getId(), ownerA.getId()));
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        AtomicReference<Future<?>> edit = new AtomicReference<>();
        try {
            tx.executeWithoutResult(status -> {
                assertTrue(propertyService.restoreProperty(propertyA.getId(), tenantA.getId()).isPresent());
                edit.set(startCompetingAndAwaitItBlocking(pool, () -> propertyService.updateProperty(
                        propertyA.getId(), tenantA.getId(), request("edited after restore", "DRAFT"))));
            });

            edit.get().get(30, TimeUnit.SECONDS);   // must succeed: by now the property is active again
            Property stored = propertyRepository.findById(propertyA.getId()).orElseThrow();
            assertNull(stored.getDeletedAt());
            assertNull(stored.getDeletedBy());
            assertEquals("edited after restore", stored.getTitle());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void editingAnotherTenantsPropertyStillFindsNothingAndChangesNothing() throws Exception {
        // The lock must not weaken tenant isolation: the locking read is tenant-scoped, so a
        // foreign id is "not found" (404 at the controller) and never locked, read or modified.
        String before = propertySnapshot(propertyB.getId());

        assertTrue(propertyService.updateProperty(
                propertyB.getId(), tenantA.getId(), request("hijack", "PUBLISHED")).isEmpty());
        assertTrue(propertyService.updateProperty(
                UUID.randomUUID(), tenantA.getId(), request("missing", "PUBLISHED")).isEmpty());

        assertEquals(before, propertySnapshot(propertyB.getId()));
    }

    // ---- helpers ---------------------------------------------------------------------------------------------------

    private PropertyRequest request(String title, String status) {
        PropertyRequest request = new PropertyRequest();
        request.setTitle(title);
        request.setDescription("d");
        request.setTransactionType("SALE");
        request.setPropertyCategory("RESIDENTIAL");
        request.setPropertyType("APARTMENT");
        request.setCity("Hyderabad");
        request.setPrice(new java.math.BigDecimal("250000"));
        request.setAreaSqft(new java.math.BigDecimal("900"));
        request.setBedrooms(2);
        request.setBathrooms(2);
        request.setStatus(status);
        return request;
    }

    /**
     * Starts {@code competing} on another thread and returns only once the DATABASE reports that
     * thread's session is blocked by the lock held by the calling (test) thread's transaction.
     * Must be called from inside the holder's open transaction.
     *
     * Coordination: a latch proves the competing operation has begun; then, until the database
     * reports a session blocked by the holder, the competing Future is polled with a short bounded
     * get(). If that Future completes first, the operation never waited for the lock and the test
     * fails. Everything is bounded by a hard deadline so a broken lock cannot hang the build.
     */
    private <T> Future<T> startCompetingAndAwaitItBlocking(ExecutorService pool, Callable<T> competing) {
        // Runs on the holder's own connection (JpaTransactionManager exposes it to JdbcTemplate),
        // so this is the id of the session that owns the row lock.
        long holderSession = jdbcTemplate.queryForObject("select session_id()", Long.class);

        CountDownLatch started = new CountDownLatch(1);
        Future<T> future = pool.submit(() -> {
            started.countDown();          // released immediately before the service call begins
            return competing.call();
        });

        try {
            assertTrue(started.await(20, TimeUnit.SECONDS), "the competing operation never started");

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            while (!isSomeSessionBlockedBy(holderSession)) {
                assertTrue(System.nanoTime() < deadline,
                        "no database session became blocked by the lock holder within 20s");
                try {
                    future.get(25, TimeUnit.MILLISECONDS);   // bounded wait that doubles as a completion check
                    fail("the competing operation finished without waiting for the row lock, "
                            + "so edit and archive/restore are NOT serialized");
                } catch (TimeoutException stillRunning) {
                    // expected: still running, keep asking the database
                } catch (ExecutionException e) {
                    fail("the competing operation failed before blocking: " + e.getCause());
                }
            }
            return future;
        } catch (InterruptedException e) {
            // This helper runs inside transaction callbacks, which cannot throw checked exceptions.
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /** H2 reports lock waits in INFORMATION_SCHEMA.SESSIONS: BLOCKER_ID is the session holding the lock. */
    private boolean isSomeSessionBlockedBy(long holderSession) {
        Integer blocked = jdbcTemplate.queryForObject(
                "select count(*) from information_schema.sessions where blocker_id = ?",
                Integer.class, holderSession);
        return blocked != null && blocked > 0;
    }

    /**
     * Interleaves an "edit request" with another operation: the edit loads the property and
     * pauses, {@code concurrentTransition} runs and commits in the test thread, then the edit
     * changes the title and commits. This is the exact interleaving that used to write stale
     * archive columns back.
     */
    private void runEditAroundATransition(
            UUID propertyId, UUID tenantId, String newTitle, Callable<Void> concurrentTransition)
            throws Exception {

        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        CountDownLatch editLoaded = new CountDownLatch(1);
        CountDownLatch transitionDone = new CountDownLatch(1);
        ExecutorService editThread = Executors.newSingleThreadExecutor();
        try {
            Future<?> edit = editThread.submit(() -> tx.executeWithoutResult(status -> {
                Property managed = propertyRepository.findByIdAndTenantId(propertyId, tenantId).orElseThrow();
                editLoaded.countDown();
                try {
                    assertTrue(transitionDone.await(20, TimeUnit.SECONDS), "transition never finished");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
                managed.setTitle(newTitle);
                managed.setUpdatedAt(LocalDateTime.now());
            }));

            assertTrue(editLoaded.await(20, TimeUnit.SECONDS), "edit never loaded the property");
            concurrentTransition.call();
            transitionDone.countDown();
            edit.get(30, TimeUnit.SECONDS);
        } finally {
            transitionDone.countDown();
            editThread.shutdownNow();
        }
    }

    /** Starts every call on its own thread behind one gate so they hit the database together. */
    private <T> List<T> raceAtTheSameInstant(ExecutorService pool, List<Callable<T>> calls) throws Exception {
        CountDownLatch ready = new CountDownLatch(calls.size());
        CountDownLatch go = new CountDownLatch(1);
        List<Future<T>> futures = new ArrayList<>();
        for (Callable<T> call : calls) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                go.await();
                return call.call();
            }));
        }
        assertTrue(ready.await(20, TimeUnit.SECONDS));
        go.countDown();
        List<T> results = new ArrayList<>();
        for (Future<T> future : futures) {
            results.add(future.get(30, TimeUnit.SECONDS));
        }
        return results;
    }
}
