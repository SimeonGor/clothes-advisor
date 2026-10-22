package ru.itmo.clothesadvisor.accountservice.migration

import java.nio.file.Path
import java.util.UUID
import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.jdbc.datasource.SingleConnectionDataSource
import org.testcontainers.containers.Container
import org.testcontainers.images.builder.Transferable
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.MountableFile

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AccountDataTransferTests {
    private val postgres = PostgreSQLContainer("postgres:18-alpine")
    private val repositoryRoot = Path.of("..").toAbsolutePath().normalize()
    private val privateDirectory = "/tmp/account-transfer"
    private val databaseNames = mutableListOf<String>()
    private lateinit var sourceName: String
    private lateinit var targetName: String
    private lateinit var source: JdbcTemplate
    private lateinit var target: JdbcTemplate

    @BeforeAll
    fun startPostgres() {
        postgres.start()
        requireSuccess(postgres.execInContainer("mkdir", "-m", "700", privateDirectory))
        for (file in listOf("require-empty-target", "manifest", "detach-user-foreign-keys")) {
            postgres.copyFileToContainer(
                MountableFile.forHostPath(
                    repositoryRoot.resolve("ops/account-transfer/$file.sql"), 384,
                ),
                "$privateDirectory/$file.sql",
            )
        }
        postgres.copyFileToContainer(
            MountableFile.forClasspathResource("account-transfer/synthetic-source.sql", 384),
            "$privateDirectory/synthetic-source.sql",
        )
    }

    @BeforeEach
    fun createIsolatedDatabases() {
        val suffix = UUID.randomUUID().toString().replace("-", "")
        sourceName = "source_$suffix"
        targetName = "target_$suffix"
        source = createDatabase(
            sourceName,
            "filesystem:" + repositoryRoot.resolve(
                "wardrobe-service/src/main/resources/db/migration",
            ),
            "10",
        )
        target = createDatabase(targetName, "classpath:db/migration", "2")
    }

    @AfterEach
    fun dropIsolatedDatabases() {
        val admin = jdbc(postgres.databaseName)
        admin.dataSource!!.connection.use { connection ->
            check(connection.metaData.url == postgres.jdbcUrl) {
                "Cleanup is restricted to the owned test container"
            }
        }
        for (name in databaseNames) {
            check(name == sourceName || name == targetName)
            admin.execute("DROP DATABASE $name WITH (FORCE)")
        }
        databaseNames.clear()
    }

    @AfterAll
    fun stopPostgres() {
        postgres.close()
    }

    @Test
    fun `native dump transfers full account rows and sequence without changing wardrobe`() {
        // given
        seedSource()
        val beforeAccounts = snapshot(source, accountTables)
        val beforeWardrobe = snapshot(source, wardrobeTables)
        val beforeSequence = sequence(source)
        val targetMigrations = snapshot(target, mapOf("flyway_schema_history" to "installed_rank"))
        val beforeManifest = manifest(sourceName)

        // when
        val dump = exportAccounts()
        requireSuccess(restore(dump))

        // then
        assertSnapshotsEqual(snapshot(target, accountTables), beforeAccounts)
        assertSnapshotsEqual(snapshot(source, accountTables), beforeAccounts)
        assertSnapshotsEqual(snapshot(source, wardrobeTables), beforeWardrobe)
        assertSnapshotsEqual(
            snapshot(target, mapOf("flyway_schema_history" to "installed_rank")), targetMigrations,
        )
        assertThat(sequence(target)).isEqualTo(beforeSequence)
        assertThat(manifest(targetName)).isEqualTo(beforeManifest)
        assertThat(manifest(sourceName)).isEqualTo(beforeManifest)
        assertThat(sequence(source)).isEqualTo(beforeSequence)
        assertThat(userReferences()).containsExactly(11L, 29L)
        assertThat(target.queryForList("SELECT id FROM public.app_user", Long::class.java))
            .containsAll(userReferences())
        assertThat(
            source.queryForObject(
                "SELECT count(*) FROM public.outfit_rating_history WHERE outfit_id = 399",
                Long::class.java,
            ),
        ).isEqualTo(1)
        val nextId = target.queryForObject(
            "SELECT nextval('public.app_user_id_seq')", Long::class.java,
        )!!
        assertThat(nextId).isEqualTo(901)
        assertThat(nextId).isGreaterThan(
            target.queryForObject("SELECT max(id) FROM public.app_user", Long::class.java)!!,
        )
    }

    @ParameterizedTest
    @CsvSource("false,1,false", "false,900,true", "true,900,false", "true,900,true")
    fun `empty and populated transfers preserve both sequence states`(
        populated: Boolean,
        lastValue: Long,
        called: Boolean,
    ) {
        // given
        if (populated) seedSource()
        source.queryForObject(
            "SELECT setval('public.app_user_id_seq', ?, ?)", Long::class.java, lastValue, called,
        )
        val before = sequence(source)
        val sourceManifest = manifest(sourceName)

        // when
        requireSuccess(restore(exportAccounts()))
        val targetManifest = manifest(targetName)
        val repeatedManifest = manifest(targetName)

        // then
        assertThat(targetManifest).isEqualTo(sourceManifest).isEqualTo(repeatedManifest)
        assertThat(targetManifest.trim().lines()).hasSize(4)
        for (line in targetManifest.trim().lines().take(3)) {
            assertThat(line).matches("[a-z_]+\\|[0-9]+\\|[0-9a-f]{64}")
        }
        assertSnapshotsEqual(snapshot(target, accountTables), snapshot(source, accountTables))
        assertThat(sequence(source)).isEqualTo(before)
        assertThat(sequence(target)).isEqualTo(before)
        assertThat(
            target.queryForObject("SELECT nextval('public.app_user_id_seq')", Long::class.java),
        ).isEqualTo(if (called) lastValue + 1 else lastValue)
    }

    @Test
    fun `populated target is refused before any rows or sequence change`() {
        // given
        seedSource()
        target.update(
            """
            INSERT INTO public.app_user (id, login, password_hash, role, status, version)
            VALUES (88, 'existing-target', 'synthetic-target-hash', 'ADMIN', 'ACTIVE', 1)
            """,
        )
        target.queryForObject(
            "SELECT setval('public.app_user_id_seq', 222, false)", Long::class.java,
        )
        val before = snapshot(target, accountTables)
        val beforeSequence = sequence(target)

        // when
        val result = restore(exportAccounts())

        // then
        assertRefused(result)
        assertSnapshotsEqual(snapshot(target, accountTables), before)
        assertThat(sequence(target)).isEqualTo(beforeSequence)
    }

    @Test
    fun `second restore is refused while repeated verification is read only`() {
        // given
        seedSource()
        val dump = exportAccounts()
        requireSuccess(restore(dump))
        val before = snapshot(target, accountTables)
        val beforeSequence = sequence(target)
        val beforeManifest = manifest(targetName)

        // when
        val result = restore(dump)

        // then
        assertRefused(result)
        assertSnapshotsEqual(snapshot(target, accountTables), before)
        assertThat(manifest(targetName)).isEqualTo(beforeManifest)
        assertThat(sequence(target)).isEqualTo(beforeSequence)
    }

    @Test
    fun `malformed copy rolls back rows but setval is not rolled back`() {
        // given
        seedSource()
        val before = snapshot(target, accountTables)
        val beforeSequence = sequence(target)
        val malformed = "$privateDirectory/malformed-copy.sql"
        postgres.copyFileToContainer(
            Transferable.of(
                "COPY public.access_grant (owner_id, stylist_id) FROM stdin;\n" +
                    "not-a-number\t29\n\\.\n",
                384,
            ),
            malformed,
        )

        // when
        val result = restore(exportAccounts(), malformed)

        // then
        assertThat(result.exitCode).isEqualTo(3)
        assertThat(result.stdout).contains("COPY 3")
        assertThat(result.stderr.contains("invalid input syntax")).isTrue()
        assertSnapshotsEqual(snapshot(target, accountTables), before)
        assertThat(sequence(target)).isNotEqualTo(beforeSequence).isEqualTo(sequence(source))
    }

    @Test
    fun `same count changed password hash fails manifest comparison`() {
        // given
        seedSource()
        requireSuccess(restore(exportAccounts()))
        val sourceManifest = manifest(sourceName)
        val sourceFile = manifestFile(sourceName)
        val targetFile = manifestFile(targetName)
        requireSuccess(postgres.execInContainer("cmp", "-s", sourceFile, targetFile))
        val beforeSequence = sequence(target)
        val beforeCount = target.queryForObject(
            "SELECT count(*) FROM public.app_user", Long::class.java,
        )

        // when
        target.update(
            "UPDATE public.app_user SET password_hash = 'tampered-synthetic' WHERE id = 11",
        )
        val changedManifest = manifest(targetName)
        manifestFile(targetName)
        val comparison = postgres.execInContainer("cmp", "-s", sourceFile, targetFile)

        // then
        assertThat(target.queryForObject("SELECT count(*) FROM public.app_user", Long::class.java))
            .isEqualTo(beforeCount)
        assertThat(changedManifest).isNotEqualTo(sourceManifest)
        assertThat(comparison.exitCode).isEqualTo(1)
        assertThat(comparison.stdout).isEmpty()
        assertThat(manifest(targetName)).isEqualTo(changedManifest)
        assertThat(sequence(target)).isEqualTo(beforeSequence)
    }

    @Test
    fun `detachment removes exactly five cross domain foreign keys and rollback restores them`() {
        // given
        seedSource()
        val before = snapshot(source, accountTables + wardrobeTables)
        val beforeSequence = sequence(source)
        val beforeConstraints = userForeignKeys(source)
        assertThat(beforeConstraints).containsExactlyInAnyOrderElementsOf(
            accountForeignKeys + crossDomainForeignKeys,
        )

        // when
        source.dataSource!!.connection.use { connection ->
            connection.autoCommit = false
            try {
                val transaction = JdbcTemplate(SingleConnectionDataSource(connection, true))
                transaction.execute(
                    repositoryRoot.resolve("ops/account-transfer/detach-user-foreign-keys.sql")
                        .toFile().readText(),
                )

                // then
                assertThat(userForeignKeys(transaction))
                    .containsExactlyInAnyOrderElementsOf(accountForeignKeys)
                assertSnapshotsEqual(snapshot(transaction, accountTables + wardrobeTables), before)
            } finally {
                connection.rollback()
            }
        }
        assertThat(userForeignKeys(source)).isEqualTo(beforeConstraints)
        assertSnapshotsEqual(snapshot(source, accountTables + wardrobeTables), before)
        assertThat(sequence(source)).isEqualTo(beforeSequence)
    }

    private fun createDatabase(
        name: String,
        migrations: String,
        schemaVersion: String,
    ): JdbcTemplate {
        jdbc(postgres.databaseName).execute("CREATE DATABASE $name")
        databaseNames.add(name)
        val database = jdbc(name)
        Flyway.configure().dataSource(database.dataSource!!).locations(migrations)
            .target(schemaVersion).load().migrate()
        return database
    }

    private fun jdbc(name: String) = JdbcTemplate(
        DriverManagerDataSource(
            postgres.jdbcUrl.replace("/${postgres.databaseName}", "/$name"),
            postgres.username, postgres.password,
        ),
    )

    private fun seedSource() {
        requireSuccess(psql(sourceName, "--file=$privateDirectory/synthetic-source.sql"))
    }

    private fun exportAccounts(): String {
        val path = "$privateDirectory/$sourceName.sql"
        requireSuccess(
            postgres.execInContainer(
                "pg_dump", "--username=${postgres.username}",
                "--data-only", "--no-owner", "--no-privileges", "--strict-names",
                "--table=public.app_user", "--table=public.app_user_history",
                "--table=public.access_grant", "--table=public.app_user_id_seq",
                "--file=$path", sourceName,
            ),
        )
        return path
    }

    private fun restore(dump: String, vararg followingFiles: String): Container.ExecResult = psql(
        targetName,
        "--single-transaction", "--file=$privateDirectory/require-empty-target.sql",
        "--file=$dump", *followingFiles.map { "--file=$it" }.toTypedArray(),
    )

    private fun manifest(database: String): String {
        val result = psql(database, "-qAt", "--file=$privateDirectory/manifest.sql")
        requireSuccess(result)
        return result.stdout
    }

    private fun manifestFile(database: String): String {
        val path = "$privateDirectory/$database.manifest"
        requireSuccess(
            psql(database, "-qAt", "--file=$privateDirectory/manifest.sql", "--output=$path"),
        )
        return path
    }

    private fun psql(database: String, vararg arguments: String): Container.ExecResult =
        postgres.execInContainer(
            "psql", "-X", "--username=${postgres.username}", "--set=ON_ERROR_STOP=1",
            *arguments, database,
        )

    private fun requireSuccess(result: Container.ExecResult) {
        // Never attach CLI output: a failed COPY can include a complete account row.
        assertThat(result.exitCode).describedAs("PostgreSQL CLI exit code").isZero()
    }

    private fun assertRefused(result: Container.ExecResult) {
        assertThat(result.exitCode).isEqualTo(3)
        assertThat(result.stderr.contains("Account transfer requires an empty target")).isTrue()
        assertThat(result.stdout).doesNotContain("COPY", "setval")
    }

    private fun sequence(database: JdbcTemplate): Map<String, Any?> = database.queryForMap(
        "SELECT last_value, is_called FROM public.app_user_id_seq",
    )

    private fun snapshot(
        database: JdbcTemplate,
        tables: Map<String, String>,
    ): Map<String, List<Map<String, Any?>>> = tables.mapValues { (table, ordering) ->
        database.queryForList("SELECT * FROM public.$table ORDER BY $ordering")
    }

    private fun assertSnapshotsEqual(
        actual: Map<String, List<Map<String, Any?>>>,
        expected: Map<String, List<Map<String, Any?>>>,
    ) {
        assertThat(actual.keys).isEqualTo(expected.keys)
        for (table in expected.keys) {
            assertThat(actual[table] == expected[table])
                .describedAs("All columns and rows of %s match without logging row data", table)
                .isTrue()
        }
    }

    private fun userReferences(): List<Long?> = source.queryForList(
        """
        SELECT owner_id AS id FROM public.wardrobe_item
        UNION SELECT owner_id FROM public.outfit
        UNION SELECT author_id FROM public.outfit
        UNION SELECT stylist_id FROM public.outfit_rating
        UNION SELECT stylist_id FROM public.outfit_rating_history
        ORDER BY id
        """,
        Long::class.java,
    )

    private fun userForeignKeys(database: JdbcTemplate): List<String?> =
        database.queryForList(userForeignKeyQuery, String::class.java)

    companion object {
        private val accountTables = mapOf(
            "app_user" to "id",
            "app_user_history" to "user_id, version",
            "access_grant" to "owner_id, stylist_id",
        )
        private val wardrobeTables = mapOf(
            "wardrobe_category" to "id",
            "precipitation_type" to "id",
            "wardrobe_item" to "id",
            "wardrobe_item_history" to "wardrobe_item_id, version",
            "wardrobe_item_photo" to "id",
            "outfit" to "id",
            "outfit_item" to "outfit_id, wardrobe_item_id",
            "outfit_weather" to "outfit_id",
            "outfit_rating" to "outfit_id, stylist_id",
            "outfit_rating_history" to "outfit_id, stylist_id, version",
        )
        private val accountForeignKeys = listOf(
            "app_user_history_user_id_fkey",
            "access_grant_owner_id_fkey",
            "access_grant_stylist_id_fkey",
        )
        private val crossDomainForeignKeys = listOf(
            "wardrobe_item_owner_id_fkey",
            "outfit_owner_id_fkey",
            "outfit_author_id_fkey",
            "outfit_rating_stylist_id_fkey",
            "outfit_rating_history_stylist_id_fkey",
        )
        private const val userForeignKeyQuery = """
            SELECT conname FROM pg_catalog.pg_constraint
            WHERE contype = 'f' AND confrelid = 'public.app_user'::regclass
            ORDER BY conname
        """
    }
}
