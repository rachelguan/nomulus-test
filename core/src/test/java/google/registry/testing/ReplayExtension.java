// Copyright 2020 The Nomulus Authors. All Rights Reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package google.registry.testing;

import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;
import static google.registry.model.ImmutableObjectSubject.assertAboutImmutableObjects;
import static google.registry.model.ofy.ObjectifyService.auditedOfy;
import static google.registry.persistence.transaction.TransactionManagerFactory.jpaTm;
import static google.registry.persistence.transaction.TransactionManagerFactory.ofyTm;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.RetentionPolicy.RUNTIME;
import static org.junit.Assert.fail;

import com.google.common.base.Ascii;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Streams;
import com.google.common.flogger.FluentLogger;
import com.googlecode.objectify.Key;
import google.registry.model.EntityClasses;
import google.registry.model.ImmutableObject;
import google.registry.model.domain.DomainBase;
import google.registry.model.ofy.CommitLogBucket;
import google.registry.model.ofy.ReplayQueue;
import google.registry.model.ofy.TransactionInfo;
import google.registry.model.replay.DatastoreEntity;
import google.registry.model.replay.ReplicateToDatastoreAction;
import google.registry.model.replay.SqlEntity;
import google.registry.persistence.VKey;
import google.registry.persistence.transaction.JpaEntityCoverageExtension;
import google.registry.persistence.transaction.JpaTransactionManagerImpl;
import google.registry.persistence.transaction.Transaction;
import google.registry.persistence.transaction.Transaction.Delete;
import google.registry.persistence.transaction.Transaction.Mutation;
import google.registry.persistence.transaction.Transaction.Update;
import google.registry.persistence.transaction.TransactionEntity;
import google.registry.util.RequestStatusChecker;
import java.io.IOException;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import javax.annotation.Nullable;
import org.junit.jupiter.api.TestTemplate;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.mockito.Mockito;

/**
 * A JUnit extension that replays datastore transactions against postgresql.
 *
 * <p>This extension must be ordered before AppEngineExtension so that the test entities saved in
 * that extension are also replayed. If AppEngineExtension is not used,
 * JpaTransactionManagerExtension must be, and this extension should be ordered _after_
 * JpaTransactionManagerExtension so that writes to SQL work.
 */
public class ReplayExtension implements BeforeEachCallback, AfterEachCallback {

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  private static ImmutableSet<String> NON_REPLICATED_TYPES =
      ImmutableSet.of(
          "PremiumList",
          "PremiumListRevision",
          "PremiumListEntry",
          "ReservedList",
          "RdeRevision",
          "ServerSecret",
          "SignedMarkRevocationList",
          "ClaimsListShard",
          "TmchCrl",
          "EppResourceIndex",
          "ForeignKeyIndex",
          "ForeignKeyHostIndex",
          "ForeignKeyContactIndex",
          "ForeignKeyDomainIndex");

  // Entity classes to be ignored during the final database comparison.  Note that this is just a
  // mash-up of Datastore and SQL classes, and used for filtering both sets.  We could split them
  // out, but there is plenty of overlap and no name collisions so it doesn't matter very much.
  private static ImmutableSet<String> IGNORED_ENTITIES =
      Streams.concat(
              ImmutableSet.of(
                  // These entities are @Embed-ded in Datastore
                  "DelegationSignerData",
                  "DomainDsDataHistory",
                  "DomainTransactionRecord",
                  "GracePeriod",
                  "GracePeriodHistory",

                  // These entities are legitimately not comparable.
                  "ClaimsEntry",
                  "ClaimsList",
                  "CommitLogBucket",
                  "CommitLogManifest",
                  "CommitLogMutation",
                  "PremiumEntry",
                  "ReservedListEntry")
                  .stream(),
              NON_REPLICATED_TYPES.stream())
          .collect(toImmutableSet());

  FakeClock clock;
  boolean replayed = false;
  boolean inOfyContext;
  InjectExtension injectExtension = new InjectExtension();
  @Nullable ReplicateToDatastoreAction sqlToDsReplicator;
  List<DomainBase> expectedUpdates = new ArrayList<>();
  boolean enableDomainTimestampChecks;
  boolean enableDatabaseCompare = true;

  private ReplayExtension(FakeClock clock, @Nullable ReplicateToDatastoreAction sqlToDsReplicator) {
    this.clock = clock;
    this.sqlToDsReplicator = sqlToDsReplicator;
  }

  public static ReplayExtension createWithCompare(FakeClock clock) {
    return new ReplayExtension(clock, null);
  }

  // This allows us to disable the replay tests from an environment variable in specific
  // environments (namely kokoro) where we see flakiness of unknown origin.
  //
  // TODO(b/197534789): Remove this once we get to the bottom of test flakiness
  public static boolean replayTestsEnabled() {
    String disableReplayTests = System.getenv("NOMULUS_DISABLE_REPLAY_TESTS");
    if (disableReplayTests == null) {
      return true;
    }
    return !Ascii.toLowerCase(disableReplayTests).equals("true");
  }

  /**
   * Create a replay extension that replays from SQL to cloud datastore when running in SQL mode.
   */
  public static ReplayExtension createWithDoubleReplay(FakeClock clock) {
    // TODO: use the proper double-replay extension when the tests are not flaky
    if (replayTestsEnabled()) {
      return new ReplayExtension(
          clock,
          new ReplicateToDatastoreAction(
              clock, Mockito.mock(RequestStatusChecker.class), new FakeResponse()));
    } else {
      return createWithCompare(clock);
    }
  }

  /**
   * Enable checking of domain timestamps during replay.
   *
   * <p>This was added to facilitate testing of a very specific bug wherein create/update
   * auto-timestamps serialized to the SQL -> DS Transaction table had different values from those
   * actually stored in SQL.
   *
   * <p>In order to use this, you also need to use expectUpdateFor() to store the states of a
   * DomainBase object at a given point in time.
   */
  public void enableDomainTimestampChecks() {
    enableDomainTimestampChecks = true;
  }

  /**
   * If we're doing domain time checks, add the current state of a domain to check against.
   *
   * <p>A null argument is a placeholder to deal with b/217952766. Basically it allows us to ignore
   * one particular state in the sequence (where the timestamp is not what we expect it to be).
   */
  public void expectUpdateFor(@Nullable DomainBase domain) {
    expectedUpdates.add(domain);
  }

  @Override
  public void beforeEach(ExtensionContext context) {
    Optional<Method> elem = context.getTestMethod();
    if (elem.isPresent() && elem.get().isAnnotationPresent(NoDatabaseCompare.class)) {
      enableDatabaseCompare = false;
    }

    // Use a single bucket to expose timestamp inversion problems. This typically happens when
    // a test with this extension rolls back the fake clock in the setup method, creating inverted
    // timestamp with the canned data preloaded by AppengineExtension. The solution is to move
    // the clock change to the test's constructor.
    injectExtension.setStaticField(
        CommitLogBucket.class, "bucketIdSupplier", Suppliers.ofInstance(1));
    DatabaseHelper.setClock(clock);
    DatabaseHelper.setAlwaysSaveWithBackup(true);
    ReplayQueue.clear();

    // When running in JPA mode with double replay enabled, enable JPA transaction replication.
    // Note that we can't just use isOfy() here because this extension gets run before the dual-test
    // transaction manager gets injected.
    inOfyContext = DualDatabaseTestInvocationContextProvider.inOfyContext(context);
    if (sqlToDsReplicator != null && !inOfyContext) {
      JpaTransactionManagerImpl.setReplaySqlToDatastoreOverrideForTest(true);
    }

    context.getStore(ExtensionContext.Namespace.GLOBAL).put(ReplayExtension.class, this);
  }

  @Override
  public void afterEach(ExtensionContext context) {
    // This ensures that we do the replay even if we're not called from AppEngineExtension.  It
    // is safe to call replay() twice, as the method ensures idempotence.
    replay();
    injectExtension.afterEach(context);
    if (sqlToDsReplicator != null) {
      JpaTransactionManagerImpl.removeReplaySqlToDsOverrideForTest();
    }
  }

  public void replay() {
    if (!replayed) {
      if (inOfyContext) {
        replayToSql();
      } else {
        // Disable database backups.  For unknown reason, if we don't do this we get residual commit
        // log entries that cause timestamp inversions in other tests.
        DatabaseHelper.setAlwaysSaveWithBackup(false);

        // Do the ofy replay.
        replayToOfy();

        // Clean out anything that ends up in the replay queue.
        ReplayQueue.clear();
      }
      replayed = true;
    }
  }

  private void replayToSql() {
    DatabaseHelper.setAlwaysSaveWithBackup(false);
    ImmutableMap<Key<?>, Object> changes = ReplayQueue.replay();

    // Compare JPA to OFY, if requested.
    for (ImmutableMap.Entry<Key<?>, Object> entry : changes.entrySet()) {
      // Don't verify non-replicated types.
      if (NON_REPLICATED_TYPES.contains(entry.getKey().getKind())) {
        continue;
      }

      // Since the object may have changed in datastore by the time we're doing the replay, we
      // have to compare the current value in SQL (which we just mutated) against the value that
      // we originally would have persisted (that being the object in the entry).
      VKey<?> vkey = VKey.from(entry.getKey());
      jpaTm()
          .transact(
              () -> {
                Optional<?> jpaValue = jpaTm().loadByKeyIfPresent(vkey);
                if (entry.getValue().equals(TransactionInfo.Delete.SENTINEL)) {
                  assertThat(jpaValue.isPresent()).isFalse();
                } else {
                  ImmutableObject immutJpaObject = (ImmutableObject) jpaValue.get();
                  assertAboutImmutableObjects().that(immutJpaObject).hasCorrectHashValue();
                  assertAboutImmutableObjects()
                      .that(immutJpaObject)
                      .isEqualAcrossDatabases(
                          (ImmutableObject)
                              ((DatastoreEntity) entry.getValue()).toSqlEntity().get());
                }
              });
    }
  }

  private void replayToOfy() {
    if (sqlToDsReplicator == null) {
      return;
    }

    List<TransactionEntity> transactionBatch;
    do {
      transactionBatch = sqlToDsReplicator.getTransactionBatchAtSnapshot();
      for (TransactionEntity txn : transactionBatch) {
        ReplicateToDatastoreAction.applyTransaction(txn);
        ofyTm().transact(() -> compareSqlTransaction(txn));
        clock.advanceOneMilli();
      }
    } while (!transactionBatch.isEmpty());

    // Now that everything has been replayed, compare the databases.
    if (enableDatabaseCompare) {
      compareDatabases();
    }
  }

  /** Verifies that the replaying the SQL transaction created the same entities in Datastore. */
  private void compareSqlTransaction(TransactionEntity transactionEntity) {
    Transaction transaction;
    try {
      transaction = Transaction.deserialize(transactionEntity.getContents());
    } catch (IOException e) {
      throw new RuntimeException("Error during transaction deserialization.", e);
    }
    for (Mutation mutation : transaction.getMutations()) {
      if (mutation instanceof Update) {
        Update update = (Update) mutation;
        ImmutableObject fromTransactionEntity = (ImmutableObject) update.getEntity();
        ImmutableObject fromDatastore = ofyTm().loadByEntity(fromTransactionEntity);
        if (fromDatastore instanceof SqlEntity) {
          // We store the Datastore entity in the transaction, so use that if necessary
          fromDatastore = (ImmutableObject) ((SqlEntity) fromDatastore).toDatastoreEntity().get();
        }
        assertAboutImmutableObjects().that(fromDatastore).hasCorrectHashValue();
        assertAboutImmutableObjects()
            .that(fromDatastore)
            .isEqualAcrossDatabases(fromTransactionEntity);

        // Check DomainBase timestamps if appropriate.
        if (enableDomainTimestampChecks && fromTransactionEntity instanceof DomainBase) {
          DomainBase expectedDomain = expectedUpdates.remove(0);

          // Just skip it if the expectedDomain is null.
          if (expectedDomain == null) {
            continue;
          }

          DomainBase domainEntity = (DomainBase) fromTransactionEntity;
          assertThat(domainEntity.getCreationTime()).isEqualTo(expectedDomain.getCreationTime());
          assertThat(domainEntity.getUpdateTimestamp())
              .isEqualTo(expectedDomain.getUpdateTimestamp());
        }
      } else {
        Delete delete = (Delete) mutation;
        VKey<?> key = delete.getKey();
        assertWithMessage(String.format("Expected key %s to not exist in Datastore", key))
            .that(ofyTm().exists(key))
            .isFalse();
      }
    }
  }

  /** Compares the final state of both databases after replay is complete. */
  private void compareDatabases() {
    boolean gotDiffs = false;

    // Build a map containing all of the SQL entities indexed by their key.
    HashMap<Object, Object> sqlEntities = new HashMap<>();
    for (Class<?> cls : JpaEntityCoverageExtension.ALL_JPA_ENTITIES) {
      if (IGNORED_ENTITIES.contains(cls.getSimpleName())) {
        continue;
      }

      jpaTm()
          .transact(
              () -> jpaTm().loadAllOfStream(cls).forEach(e -> sqlEntities.put(getSqlKey(e), e)));
    }

    for (Class<? extends ImmutableObject> cls : EntityClasses.ALL_CLASSES) {
      if (IGNORED_ENTITIES.contains(cls.getSimpleName())) {
        continue;
      }

      for (ImmutableObject entity : auditedOfy().load().type(cls).list()) {
        // Find the entity in SQL and verify that it's the same.
        Key<?> ofyKey = Key.create(entity);
        Object sqlKey = VKey.from(ofyKey).getSqlKey();
        ImmutableObject sqlEntity = (ImmutableObject) sqlEntities.get(sqlKey);
        Optional<SqlEntity> expectedSqlEntity = ((DatastoreEntity) entity).toSqlEntity();
        if (expectedSqlEntity.isPresent()) {
          // Check for null just so we get a better error message.
          if (sqlEntity == null) {
            logger.atSevere().log("Entity %s is in Datastore but not in SQL.", ofyKey);
            gotDiffs = true;
          } else {
            try {
              assertAboutImmutableObjects()
                  .that((ImmutableObject) expectedSqlEntity.get())
                  .isEqualAcrossDatabases(sqlEntity);
            } catch (AssertionError e) {
              // Show the message but swallow the stack trace (we'll get that from the fail() at
              // the end of the comparison).
              logger.atSevere().log("For entity %s: %s", ofyKey, e.getMessage());
              gotDiffs = true;
            }
          }
        } else {
          logger.atInfo().log("Datastore entity has no sql representation for %s", ofyKey);
        }
        sqlEntities.remove(sqlKey);
      }
    }

    // Report any objects in the SQL set that we didn't remove while iterating over the Datastore
    // objects.
    if (!sqlEntities.isEmpty()) {
      for (Object item : sqlEntities.values()) {
        logger.atSevere().log(
            "Entity of %s found in SQL but not in datastore: %s", item.getClass().getName(), item);
      }
      gotDiffs = true;
    }

    if (gotDiffs) {
      fail("There were differences between the final SQL and Datastore contents.");
    }
  }

  private static Object getSqlKey(Object entity) {
    return jpaTm()
        .getEntityManager()
        .getEntityManagerFactory()
        .getPersistenceUnitUtil()
        .getIdentifier(entity);
  }

  /** Annotation to use for test methods where we don't want to do a database comparison yet. */
  @Target({METHOD})
  @Retention(RUNTIME)
  @TestTemplate
  public @interface NoDatabaseCompare {}
}
