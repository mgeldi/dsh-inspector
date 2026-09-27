package inspector;

import com.zaxxer.hikari.HikariDataSource;
import inspector.store.CohortRepository;
import inspector.store.FindingRepository;
import inspector.store.IndexWriter;
import inspector.store.MetaRepository;
import inspector.store.OverviewRepository;
import inspector.store.SchemaGate;
import inspector.store.SessionRepository;
import inspector.store.ShellEvidenceRepository;
import inspector.store.StepRepository;
import inspector.store.ToolCallRepository;
import inspector.store.VocabularyService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.nio.file.Path;
import java.util.Map;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;

/**
 * The store, assembled by hand over one SQLite file — the JPA counterpart of what the tests used
 * to do with a {@code JdbcTemplate} and a {@code DataSourceTransactionManager}. No Spring context:
 * the same entity manager factory, transaction manager, repositories and schema gate the
 * application wires, built directly, so a test can open two index files side by side or open one
 * that a hand-built pipeline is about to fill.
 *
 * <p>Opening it runs the schema gate, exactly as a boot does: the file is brought to the current
 * schema version (created, or dropped and rebuilt) and the entities are validated against it.
 */
public final class TestStore implements AutoCloseable {

    private final HikariDataSource dataSource;
    private final EntityManagerFactory emf;
    private final EntityManager em;
    private final JpaTransactionManager transactions;
    private final JpaRepositoryFactory repositories;
    private final IndexWriter writer;

    private TestStore(final Path dbFile, final boolean gate, final Map<String, Object> extra) {
        dataSource = new HikariDataSource();
        // foreign_keys=on, like the application URL: every delete order is tested against a
        // database that would notice the wrong one.
        dataSource.setJdbcUrl("jdbc:sqlite:" + dbFile + "?journal_mode=WAL&busy_timeout=5000&foreign_keys=on");
        dataSource.setMaximumPoolSize(4);

        final LocalContainerEntityManagerFactoryBean factory = new LocalContainerEntityManagerFactoryBean();
        factory.setDataSource(dataSource);
        factory.setPackagesToScan("inspector.store.entity");
        factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        final Map<String, Object> properties = new java.util.HashMap<>(Map.of(
                "hibernate.dialect", "inspector.store.SqliteAffinityDialect",
                "hibernate.jdbc.batch_size", "500",
                "hibernate.order_inserts", "true"));
        properties.putAll(extra);
        factory.setJpaPropertyMap(properties);
        factory.afterPropertiesSet();
        emf = factory.getObject();
        em = SharedEntityManagerCreator.createSharedEntityManager(emf);
        transactions = new JpaTransactionManager(emf);
        transactions.setDataSource(dataSource);
        repositories = new JpaRepositoryFactory(em);
        writer = new IndexWriter(em, transactions, repository(SessionRepository.class),
                repository(StepRepository.class), repository(ToolCallRepository.class),
                repository(FindingRepository.class), repository(ShellEvidenceRepository.class),
                repository(MetaRepository.class));
        if (gate) {
            new SchemaGate(writer, emf).open();
        }
    }

    /** The store over {@code dbFile}, with the schema gate run — a booted application's state. */
    public static TestStore open(final Path dbFile) {
        return new TestStore(dbFile, true, Map.of());
    }

    /** The same, with extra JPA properties — a statement inspector, for instance. */
    public static TestStore open(final Path dbFile, final Map<String, Object> jpaProperties) {
        return new TestStore(dbFile, true, jpaProperties);
    }

    /**
     * The store over {@code dbFile} with the gate <em>not</em> run: for a test that arranges an
     * old or odd schema by hand and then calls the gate itself.
     */
    public static TestStore openUngated(final Path dbFile) {
        return new TestStore(dbFile, false, Map.of());
    }

    public <T> T repository(final Class<T> type) {
        return repositories.getRepository(type);
    }

    public IndexWriter writer() {
        return writer;
    }

    public OverviewRepository overview() {
        return new OverviewRepository(em);
    }

    public CohortRepository cohorts() {
        return new CohortRepository(em);
    }

    public FindingRepository findings() {
        return repository(FindingRepository.class);
    }

    public ToolCallRepository toolCalls() {
        return repository(ToolCallRepository.class);
    }

    public VocabularyService vocabulary() {
        return new VocabularyService(em);
    }

    public SchemaGate gate() {
        return new SchemaGate(writer, emf);
    }

    public EntityManager entityManager() {
        return em;
    }

    public org.springframework.transaction.PlatformTransactionManager transactions() {
        return transactions;
    }

    /** Raw SQL for assertions about what is on disk, which is a different question from what JPA reads. */
    public JdbcTemplate jdbc() {
        return new JdbcTemplate(dataSource);
    }

    @Override
    public void close() {
        emf.close();
        dataSource.close();
    }
}
