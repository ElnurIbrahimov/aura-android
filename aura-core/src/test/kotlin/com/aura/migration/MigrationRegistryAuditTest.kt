package com.aura.migration

import com.aura.agent.requireNonEmpty
import com.aura.agent.sourceDir
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals

/**
 * Every `@Database(version = N)` must have a migration registered for every hop
 * from 1 to N, and a committed schema export for every one of those versions.
 *
 * What this replaced could not fail. It asserted `maxTo >= 1` per module and
 * nothing else: it never read a `@Database` annotation, never read the array
 * actually passed to `RoomConfig.builder`, and its reflective helper looked for
 * `MIGRATION_*` fields on the module class — which finds none at all for
 * `EvolutionModule`, whose migrations are declared at file scope and therefore
 * compile onto `EvolutionModuleKt`. At that point the helper fell back to
 * `ALL_MIGRATIONS.size` and called that the highest version, and `maxTo >= 1`
 * was satisfied by any module with a single migration. The KDoc described the
 * "forgot to append it to the arrayOf" defect; the assertion could not detect it.
 * It also said "9 Room databases" while listing 8 modules for 11 databases.
 *
 * The rewrite reads source rather than reflection, for the same reason: Room's
 * `@Database` carries `AnnotationRetention.BINARY`, so it is simply not present
 * at runtime and any reflective version of this audit would be scanning an empty
 * set. Three contracts:
 *
 * - **Every declared version has a committed schema export.** [MigrationReplayTest]
 *   keys off the highest export it finds on disk, so bumping `@Database(version)`
 *   without exporting the new schema leaves it checking the old chain and
 *   reporting green. This one keys off the annotation, so the export is what has
 *   to catch up.
 * - **Every declared version has a complete registered chain.** Read from the
 *   `RoomConfig.builder(...)` call itself, not from what the module happens to
 *   declare — a `MIGRATION_16_17` that exists but never reaches `addMigrations`
 *   is exactly the crash the old KDoc described.
 * - **Every migration constant is named for the versions it migrates.** The
 *   chain check above trusts the name; this makes the name trustworthy, so a
 *   copy-pasted `MIGRATION_5_6 = object : Migration(4, 5)` cannot satisfy it.
 */
class MigrationRegistryAuditTest {

    private val mainSource = sourceDir("src/main/kotlin/com/aura")
    private val schemaRoot = sourceDir("schemas")

    /**
     * Read the tree once. Resolving eleven databases re-walks and re-reads the
     * same 444 files a dozen times over otherwise, for no additional coverage.
     */
    private val kotlinSources: List<File> by lazy {
        mainSource.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .toList()
            .requireNonEmpty("Kotlin sources under src/main/kotlin/com/aura")
    }

    private val sourceText: Map<File, String> by lazy { kotlinSources.associateWith { it.readText() } }

    private fun textOf(file: File): String = sourceText.getValue(file)

    private data class DeclaredDatabase(
        val qualifiedName: String,
        val simpleName: String,
        val version: Int,
        val file: File,
    )

    private val packageDecl = Regex("""^package\s+([\w.]+)""", RegexOption.MULTILINE)
    private val databaseAnnotation = Regex("""@Database\s*\(([\s\S]*?)\)""")
    private val versionArg = Regex("""version\s*=\s*(\d+)""")
    private val databaseClass = Regex("""abstract class (\w+)\s*:\s*RoomDatabase""")
    private val migrationDecl =
        Regex("""val\s+(MIGRATION_(\d+)_(\d+))\s*=\s*object\s*:\s*Migration\(\s*(\d+)\s*,\s*(\d+)\s*\)""")
    private val allMigrationsDecl = Regex("""ALL_MIGRATIONS[^=]*=\s*arrayOf\(([^)]*)\)""")
    private val allMigrationsRef = Regex("""\b(?:(\w+)\.)?ALL_MIGRATIONS\b""")
    private val migrationRef = Regex("""\bMIGRATION_(\d+)_(\d+)\b""")

    private fun declaredDatabases(): List<DeclaredDatabase> {
        val annotated = kotlinSources
            .filter { textOf(it).contains("@Database(") }
            .requireNonEmpty("files carrying an @Database annotation")
        val parsed = annotated.mapNotNull { file ->
            val text = textOf(file)
            val args = databaseAnnotation.find(text)?.groupValues?.get(1) ?: return@mapNotNull null
            val version = versionArg.find(args)?.groupValues?.get(1)?.toIntOrNull() ?: return@mapNotNull null
            val simple = databaseClass.find(text)?.groupValues?.get(1) ?: return@mapNotNull null
            val pkg = packageDecl.find(text)?.groupValues?.get(1) ?: return@mapNotNull null
            DeclaredDatabase("$pkg.$simple", simple, version, file)
        }
        // A file that carries the annotation but does not parse is a silent hole,
        // and a silent hole in a scan is the failure mode this whole file is about.
        check(parsed.size == annotated.size) {
            "could not read @Database(version = N) plus `abstract class X : RoomDatabase` out of: " +
                (annotated.map { it.name } - parsed.map { it.file.name }.toSet()).joinToString()
        }
        return parsed
    }

    /** The `RoomConfig.builder(...)` call that constructs [simpleName], parenthesis-matched. */
    private fun builderCall(simpleName: String): Pair<File, String> {
        for (file in kotlinSources) {
            val text = textOf(file)
            var idx = text.indexOf("RoomConfig.builder(")
            while (idx >= 0) {
                val call = parenthesised(text, text.indexOf('(', idx))
                if (call.contains("$simpleName::class.java")) return file to call
                idx = text.indexOf("RoomConfig.builder(", idx + 1)
            }
        }
        error(
            "no RoomConfig.builder(...) call constructs $simpleName. Every Room database in this app " +
                "is built through RoomConfig so its migration array lives in one readable place; a " +
                "database built with Room.databaseBuilder directly is invisible to this audit.",
        )
    }

    private fun parenthesised(source: String, open: Int): String {
        var depth = 0
        for (i in open until source.length) {
            when (source[i]) {
                '(' -> depth++
                ')' -> if (--depth == 0) return source.substring(open, i + 1)
            }
        }
        error("unbalanced parentheses at offset $open")
    }

    private fun fileDeclaring(typeName: String): File = kotlinSources.firstOrNull { file ->
        Regex("""\b(?:abstract\s+)?(?:class|object)\s+${Regex.escape(typeName)}\b""")
            .containsMatchIn(textOf(file))
    } ?: error("no source file declares $typeName")

    /** The (from, to) hops the builder call actually hands to `addMigrations`. */
    private fun registeredHops(db: DeclaredDatabase): Set<Pair<Int, Int>> {
        val (file, call) = builderCall(db.simpleName)
        val hops = migrationRef.findAll(call)
            .map { it.groupValues[1].toInt() to it.groupValues[2].toInt() }
            .toMutableSet()
        for (ref in allMigrationsRef.findAll(call)) {
            val owner = ref.groupValues[1]
            val source = if (owner.isEmpty()) textOf(file) else textOf(fileDeclaring(owner))
            val array = allMigrationsDecl.find(source)?.groupValues?.get(1)
                ?: error(
                    "${ref.value} is passed to RoomConfig.builder for ${db.simpleName}, but no " +
                        "`ALL_MIGRATIONS = arrayOf(...)` declares it where this audit can read it",
                )
            migrationRef.findAll(array).forEach {
                hops += it.groupValues[1].toInt() to it.groupValues[2].toInt()
            }
        }
        return hops
    }

    @Test
    fun `every declared database version has a committed schema export`() {
        val missing = declaredDatabases().flatMap { db ->
            (1..db.version).mapNotNull { version ->
                val export = File(schemaRoot, "${db.qualifiedName}/$version.json")
                if (export.isFile) null else "${db.qualifiedName}/$version.json"
            }
        }
        assertEquals(
            emptyList<String>(), missing,
            "These schema exports are missing: $missing. A version with no export is a version " +
                "MigrationReplayTest cannot replay against anything, so the migration into it has " +
                "no baseline and is verified by nothing.",
        )
    }

    @Test
    fun `every declared database version has a complete registered migration chain`() {
        val problems = mutableListOf<String>()
        for (db in declaredDatabases()) {
            val expected = (1 until db.version).map { it to it + 1 }.toSet()
            val registered = registeredHops(db)
            val missing = (expected - registered).sortedBy { it.first }
            val extra = (registered - expected).sortedBy { it.first }
            if (missing.isNotEmpty()) {
                problems += "${db.qualifiedName} declares version ${db.version} but nothing is " +
                    "registered with RoomConfig.builder for " +
                    missing.joinToString(", ") { "${it.first}->${it.second}" } +
                    " — an install on the older version crashes on open with 'no migration path'"
            }
            if (extra.isNotEmpty()) {
                problems += "${db.qualifiedName} registers " +
                    extra.joinToString(", ") { "${it.first}->${it.second}" } +
                    " which lies outside the 1..${db.version} chain its @Database version declares"
            }
        }
        assertEquals(emptyList<String>(), problems, problems.joinToString("\n"))
    }

    @Test
    fun `every migration constant is named for the versions it actually migrates`() {
        val declarations = kotlinSources.flatMap { file ->
            migrationDecl.findAll(textOf(file)).map { file.name to it }.toList()
        }.requireNonEmpty("MIGRATION_X_Y declarations")

        val mismatched = declarations
            .filter { (_, m) -> m.groupValues[2] != m.groupValues[4] || m.groupValues[3] != m.groupValues[5] }
            .map { (name, m) ->
                "$name: ${m.groupValues[1]} constructs Migration(${m.groupValues[4]}, ${m.groupValues[5]})"
            }

        assertEquals(
            emptyList<String>(), mismatched,
            "A migration constant's name is what the chain audit above reads, so a name that does " +
                "not match its constructor arguments makes that audit report a chain the code does " +
                "not have: $mismatched",
        )
    }
}


