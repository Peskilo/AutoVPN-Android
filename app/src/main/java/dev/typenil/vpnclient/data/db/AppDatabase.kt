package dev.typenil.vpnclient.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "subscriptions")
data class SubscriptionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    /** Full subscription URL — sensitive; never log or export. */
    val url: String,
    val createdAtEpochMs: Long,
    val lastUpdatedAtEpochMs: Long?,
    val lastAttemptAtEpochMs: Long?,
    val lastError: String?,
    val enabled: Boolean = true,
    val userInfoJson: String?,
    val supportUrl: String?,
    val updateIntervalMinutes: Int?,
    /**
     * Explicit per-subscription opt-in for cleartext HTTP fetches.
     * HTTPS is the default; an https→http redirect is never followed.
     */
    @ColumnInfo(defaultValue = "0") val allowInsecureHttp: Boolean = false,
    /** Provider announcement text (Remnawave `announce` header). */
    val announce: String?,
    /** Provider asks the client to refresh this subscription on every launch. */
    @ColumnInfo(defaultValue = "0") val updateAlways: Boolean = false,
    /** Alternate fetch URL tried when the primary is unreachable. */
    val fallbackUrl: String?,
    /**
     * Per-subscription auto-refresh policy token — decoded via
     * `RefreshPolicy.fromStorage` ("inherit"/"provider"/"disabled"/"fixed").
     * Pre-v7 rows carry the default "inherit": the old app-wide behavior.
     */
    @ColumnInfo(defaultValue = "inherit") val refreshPolicy: String = "inherit",
    /** Fixed interval in minutes — only meaningful while policy is "fixed". */
    val refreshFixedMinutes: Int? = null,
)

@Entity(
    tableName = "nodes",
    indices = [Index("subscriptionId")],
)
data class NodeEntity(
    /** Stable id: parser-computed hash (protocol+server+port+credential). */
    @PrimaryKey val id: String,
    val subscriptionId: Long,
    val name: String,
    val protocol: String,
    val server: String,
    val port: Int,
    /** Engine-native outbound object as JSON — opaque to the app. */
    val outboundJson: String,
    /** Original share link when parsed from a URI list — sensitive. */
    val rawUri: String?,
    val position: Int,
)

@Dao
interface SubscriptionDao {
    @Query("SELECT * FROM subscriptions ORDER BY createdAtEpochMs")
    fun observeAll(): Flow<List<SubscriptionEntity>>

    @Query("SELECT * FROM subscriptions WHERE id = :id")
    suspend fun get(id: Long): SubscriptionEntity?

    @Query("SELECT id FROM subscriptions WHERE url = :url")
    suspend fun findIdByUrl(url: String): Long?

    @Query("SELECT * FROM subscriptions")
    suspend fun getAll(): List<SubscriptionEntity>

    @Insert
    suspend fun insert(entity: SubscriptionEntity): Long

    @Update
    suspend fun update(entity: SubscriptionEntity)

    @Query("UPDATE subscriptions SET enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(
        id: Long,
        enabled: Boolean,
    )

    @Query("UPDATE subscriptions SET name = :name WHERE id = :id")
    suspend fun updateName(
        id: Long,
        name: String,
    )

    /** Policy columns only — a user override must not touch refresh metadata
     *  or the URL; rescheduling is the caller's job (under the row lock). */
    @Query(
        """UPDATE subscriptions SET
            refreshPolicy = :policy,
            refreshFixedMinutes = :fixedMinutes
        WHERE id = :id""",
    )
    suspend fun updateRefreshPolicy(
        id: Long,
        policy: String,
        fixedMinutes: Int?,
    )

    @Query("DELETE FROM subscriptions WHERE id = :id")
    suspend fun delete(id: Long)

    @Query(
        """UPDATE subscriptions SET
            lastAttemptAtEpochMs = :attemptAt,
            lastError = :error
        WHERE id = :id""",
    )
    suspend fun markAttempt(
        id: Long,
        attemptAt: Long,
        error: String?,
    )

    @Query(
        """UPDATE subscriptions SET
            lastUpdatedAtEpochMs = :updatedAt,
            lastAttemptAtEpochMs = :attemptAt,
            lastError = NULL,
            userInfoJson = :userInfoJson,
            supportUrl = :supportUrl,
            updateIntervalMinutes = :updateIntervalMinutes,
            announce = :announce,
            updateAlways = :updateAlways,
            fallbackUrl = :fallbackUrl
        WHERE id = :id""",
    )
    suspend fun markSuccess(
        id: Long,
        updatedAt: Long,
        attemptAt: Long,
        userInfoJson: String?,
        supportUrl: String?,
        updateIntervalMinutes: Int?,
        announce: String?,
        updateAlways: Boolean,
        fallbackUrl: String?,
    )

    /** [markSuccess] plus the URL repoint — used by editUrl so the new URL
     *  and the node swap commit atomically: a crash between them can't
     *  leave nodes fetched from a URL the row doesn't record. */
    @Query(
        """UPDATE subscriptions SET
            url = :url,
            lastUpdatedAtEpochMs = :updatedAt,
            lastAttemptAtEpochMs = :attemptAt,
            lastError = NULL,
            userInfoJson = :userInfoJson,
            supportUrl = :supportUrl,
            updateIntervalMinutes = :updateIntervalMinutes,
            announce = :announce,
            updateAlways = :updateAlways,
            fallbackUrl = :fallbackUrl
        WHERE id = :id""",
    )
    suspend fun updateUrlAndMarkSuccess(
        id: Long,
        url: String,
        updatedAt: Long,
        attemptAt: Long,
        userInfoJson: String?,
        supportUrl: String?,
        updateIntervalMinutes: Int?,
        announce: String?,
        updateAlways: Boolean,
        fallbackUrl: String?,
    )
}

@Entity(tableName = "node_preferences")
data class NodePreferenceEntity(
    /** Same stable id the parser computes for the node row it refers to. */
    @PrimaryKey val nodeId: String,
    /** Priority display — surfaces in the Favorites filter and Home pick. */
    @ColumnInfo(defaultValue = "0") val isFavorite: Boolean = false,
    /** Whether the node joins the effective VPN node set at all. */
    @ColumnInfo(defaultValue = "1") val isEnabled: Boolean = true,
    /** Local display name override — never written back into node rows. */
    val customName: String? = null,
    /** Hide from user lists without disabling (lists filter, not engine). */
    @ColumnInfo(defaultValue = "0") val isHidden: Boolean = false,
)

/**
 * Per-node user preferences, stored apart from `nodes`: the nodes table is
 * rewritten wholesale on every refresh, while these rows are user-owned and
 * must survive atomic node-set replacement. Rows reference node ids — a
 * credential change gives a node a new id and its prefs are orphaned by
 * design (no fuzzy matching), then pruned by [deleteOrphans].
 */
@Dao
abstract class NodePreferenceDao {
    @Query("SELECT * FROM node_preferences")
    abstract fun observeAll(): Flow<List<NodePreferenceEntity>>

    @Query("SELECT * FROM node_preferences WHERE nodeId = :nodeId")
    abstract suspend fun get(nodeId: String): NodePreferenceEntity?

    /** Single-statement read-modify-write — concurrent toggles can't tear. */
    @Query(
        """INSERT INTO node_preferences (nodeId, isFavorite, isEnabled, customName, isHidden)
            VALUES (:nodeId, :favorite, 1, NULL, 0)
            ON CONFLICT(nodeId) DO UPDATE SET isFavorite = :favorite""",
    )
    abstract suspend fun setFavorite(
        nodeId: String,
        favorite: Boolean,
    )

    @Query(
        """INSERT INTO node_preferences (nodeId, isFavorite, isEnabled, customName, isHidden)
            VALUES (:nodeId, 0, :enabled, NULL, 0)
            ON CONFLICT(nodeId) DO UPDATE SET isEnabled = :enabled""",
    )
    abstract suspend fun setEnabled(
        nodeId: String,
        enabled: Boolean,
    )

    @Query(
        """INSERT INTO node_preferences (nodeId, isFavorite, isEnabled, customName, isHidden)
            VALUES (:nodeId, 0, 1, :customName, 0)
            ON CONFLICT(nodeId) DO UPDATE SET customName = :customName""",
    )
    abstract suspend fun setCustomName(
        nodeId: String,
        customName: String?,
    )

    @Query(
        """INSERT INTO node_preferences (nodeId, isFavorite, isEnabled, customName, isHidden)
            VALUES (:nodeId, 0, 1, NULL, :hidden)
            ON CONFLICT(nodeId) DO UPDATE SET isHidden = :hidden""",
    )
    abstract suspend fun setHidden(
        nodeId: String,
        hidden: Boolean,
    )

    @Query("DELETE FROM node_preferences WHERE nodeId = :nodeId")
    abstract suspend fun delete(nodeId: String)

    /** Drop prefs for every node of a subscription — called inside the same
     *  transaction that removes the node rows so no orphans linger. */
    @Query(
        """DELETE FROM node_preferences WHERE nodeId IN
            (SELECT id FROM nodes WHERE subscriptionId = :subscriptionId)""",
    )
    abstract suspend fun deleteForSubscription(subscriptionId: Long)

    /** Drop every pref whose node id no longer exists — safety net for ids
     *  retired by a provider credential change (prefs are never migrated
     *  across ids: the match would be a guess, not a fact). */
    @Query(
        """DELETE FROM node_preferences WHERE nodeId NOT IN
            (SELECT id FROM nodes)""",
    )
    abstract suspend fun deleteOrphans()
}

@Dao
abstract class NodeDao {
    @Query("SELECT * FROM nodes WHERE subscriptionId = :subscriptionId ORDER BY position")
    abstract suspend fun forSubscription(subscriptionId: Long): List<NodeEntity>

    @Query(
        """SELECT * FROM nodes WHERE subscriptionId IN
            (SELECT id FROM subscriptions WHERE enabled = 1)
            ORDER BY subscriptionId, position""",
    )
    abstract fun observeEnabled(): Flow<List<NodeEntity>>

    @Query(
        """SELECT * FROM nodes WHERE subscriptionId IN
            (SELECT id FROM subscriptions WHERE enabled = 1)""",
    )
    abstract suspend fun getEnabled(): List<NodeEntity>

    /** Enabled-subscription nodes that are also enabled in user prefs —
     *  the effective VPN node set. Feed for config compilation, the node-set
     *  fingerprint, and every "does the selection still exist" check. Hidden
     *  nodes stay usable: hiding is presentation, disabling is routing. */
    @Query(
        """SELECT n.* FROM nodes n
            LEFT JOIN node_preferences p ON p.nodeId = n.id
            WHERE n.subscriptionId IN
                (SELECT id FROM subscriptions WHERE enabled = 1)
              AND COALESCE(p.isEnabled, 1) = 1
            ORDER BY n.subscriptionId, n.position""",
    )
    abstract fun observeUsable(): Flow<List<NodeEntity>>

    @Query(
        """SELECT n.* FROM nodes n
            LEFT JOIN node_preferences p ON p.nodeId = n.id
            WHERE n.subscriptionId IN
                (SELECT id FROM subscriptions WHERE enabled = 1)
              AND COALESCE(p.isEnabled, 1) = 1""",
    )
    abstract suspend fun getUsable(): List<NodeEntity>

    @Query("SELECT * FROM nodes WHERE id = :id")
    abstract suspend fun get(id: String): NodeEntity?

    /** Nodes of one subscription in display order, addressed by the owning
     *  row's URL — drives the manual sentinel's per-node delete list. */
    @Query(
        """SELECT * FROM nodes WHERE subscriptionId IN
            (SELECT id FROM subscriptions WHERE url = :url)
            ORDER BY position""",
    )
    abstract fun observeForSubscriptionUrl(url: String): Flow<List<NodeEntity>>

    @Query("DELETE FROM nodes WHERE id = :id")
    abstract suspend fun delete(id: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertAll(nodes: List<NodeEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsert(node: NodeEntity)

    @Query("DELETE FROM nodes WHERE subscriptionId = :subscriptionId")
    abstract suspend fun deleteForSubscription(subscriptionId: Long)

    @Query("SELECT COUNT(*) FROM nodes WHERE subscriptionId = :subscriptionId")
    abstract suspend fun countForSubscription(subscriptionId: Long): Int

    /** Delete + insert as one transaction — a mid-write failure can't
     *  leave a subscription with a partial node list. */
    @Transaction
    open suspend fun replaceForSubscription(
        subscriptionId: Long,
        nodes: List<NodeEntity>,
    ) {
        deleteForSubscription(subscriptionId)
        upsertAll(nodes)
    }
}

/**
 * Last latency verdict per node, stored apart from `nodes` for the same
 * reason as [NodePreferenceEntity]: the node table is rewritten wholesale on
 * every refresh, so an FK `ON DELETE CASCADE` would wipe every result on each
 * refresh — even for nodes whose id is unchanged. The v8 schema's nodeId
 * primary key stores a logical (node id, method) key separated by U+001F;
 * old rows containing only a node id remain readable. There is no FK;
 * [NodeLatencyDao.observeLive] projects the original node id and only returns
 * rows whose node still exists, while [NodeLatencyDao.record] sweeps orphans.
 */
@Entity(tableName = "node_latency")
data class NodeLatencyEntity(
    @PrimaryKey val nodeId: String,
    /** Measured milliseconds; null = the probe ran and failed. */
    val latencyMs: Int?,
    val checkedAtEpochMs: Long,
    /** `LatencyMethod.storage` — "tcp" or "proxy". */
    val method: String,
)

@Dao
abstract class NodeLatencyDao {
    /** Inner join: results of vanished nodes are invisible even before swept. */
    @Query(
        """SELECT
            CASE WHEN instr(l.nodeId, char(31)) > 0
                 THEN substr(l.nodeId, 1, instr(l.nodeId, char(31)) - 1)
                 ELSE l.nodeId END AS nodeId,
            l.latencyMs, l.checkedAtEpochMs, l.method
            FROM node_latency l
            INNER JOIN nodes n ON n.id =
                CASE WHEN instr(l.nodeId, char(31)) > 0
                     THEN substr(l.nodeId, 1, instr(l.nodeId, char(31)) - 1)
                     ELSE l.nodeId END""",
    )
    abstract fun observeLive(): Flow<List<NodeLatencyEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertAll(rows: List<NodeLatencyEntity>)

    /** Remove a legacy v8 row for the same method before writing its keyed replacement. */
    @Query("DELETE FROM node_latency WHERE nodeId = :nodeId AND method = :method")
    abstract suspend fun deleteLegacy(nodeId: String, method: String)

    @Query(
        """DELETE FROM node_latency
            WHERE (CASE WHEN instr(nodeId, char(31)) > 0
                        THEN substr(nodeId, 1, instr(nodeId, char(31)) - 1)
                        ELSE nodeId END)
            NOT IN (SELECT id FROM nodes)""",
    )
    abstract suspend fun deleteOrphans()

    /** Write + sweep as one transaction: a result for a node that no longer
     *  exists (refresh raced the probe) never lingers. */
    @Transaction
    open suspend fun record(rows: List<NodeLatencyEntity>) {
        // A v8 row used the bare node id as its primary key. On the first
        // write of a method after upgrade, replace that legacy row instead
        // of leaving two logical verdicts for the same method.
        for (row in rows) {
            deleteLegacy(row.nodeId.substringBefore('\u001F'), row.method)
        }
        upsertAll(rows)
        deleteOrphans()
    }
}

/**
 * A user-authored routing rule. `kind` selects the match dimension
 * (domain / ip_cidr / port), `action` the outcome. [orderIndex] preserves
 * user ordering — rules compile to sing-box `route.rules` entries evaluated
 * top-down before the mode rules, so a direct LAN exception can shadow a
 * broad proxy rule.
 */
@Entity(tableName = "routing_rules")
data class RoutingRuleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** "domain" / "ip_cidr" / "port" — the sing-box match field. */
    val kind: String,
    /** domain suffix/keyword (e.g. "example.com") or CIDR/port text. */
    val pattern: String,
    /** "proxy" / "direct" / "block". */
    val action: String,
    /** User ordering — lower = earlier. */
    val orderIndex: Int,
    /** Soft switch without deleting the entry. */
    @ColumnInfo(defaultValue = "1") val isEnabled: Boolean = true,
)

@Dao
interface RoutingRuleDao {
    @Query("SELECT * FROM routing_rules ORDER BY orderIndex, id")
    fun observeAll(): Flow<List<RoutingRuleEntity>>

    @Query("SELECT * FROM routing_rules ORDER BY orderIndex, id")
    suspend fun getAll(): List<RoutingRuleEntity>

    @Insert
    suspend fun insert(rule: RoutingRuleEntity): Long

    @Update
    suspend fun update(rule: RoutingRuleEntity)

    @Query("DELETE FROM routing_rules WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT COALESCE(MAX(orderIndex), -1) + 1 FROM routing_rules")
    suspend fun nextOrderIndex(): Int
}

@Database(
    entities = [
        SubscriptionEntity::class,
        NodeEntity::class,
        NodePreferenceEntity::class,
        RoutingRuleEntity::class,
        NodeLatencyEntity::class,
    ],
    version = 8,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun subscriptionDao(): SubscriptionDao

    abstract fun nodeDao(): NodeDao

    abstract fun nodePreferenceDao(): NodePreferenceDao

    abstract fun routingRuleDao(): RoutingRuleDao

    abstract fun nodeLatencyDao(): NodeLatencyDao

    companion object {
        /** v2: per-subscription cleartext opt-in. */
        val MIGRATION_1_2 =
            object : androidx.room.migration.Migration(1, 2) {
                override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                    db.execSQL(
                        "ALTER TABLE subscriptions " +
                            "ADD COLUMN allowInsecureHttp INTEGER NOT NULL DEFAULT 0",
                    )
                }
            }

        /**
         * v3: `updateIntervalMinutes` was reinterpreted hours→minutes — rows
         * written before the change hold raw header hours. NULL them out;
         * the next successful refresh repopulates the column. Also preserve
         * cleartext for existing http:// subscriptions — they were added
         * under a cleartext-allowed regime and would otherwise be stuck with
         * no way to re-enable the opt-in.
         */
        val MIGRATION_2_3 =
            object : androidx.room.migration.Migration(2, 3) {
                override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                    db.execSQL("UPDATE subscriptions SET updateIntervalMinutes = NULL")
                    db.execSQL(
                        "UPDATE subscriptions SET allowInsecureHttp = 1 " +
                            "WHERE url LIKE 'http://%'",
                    )
                }
            }

        /** v4: provider metadata — announcement, refresh-on-launch, fallback URL. */
        val MIGRATION_3_4 =
            object : androidx.room.migration.Migration(3, 4) {
                override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE subscriptions ADD COLUMN announce TEXT")
                    db.execSQL(
                        "ALTER TABLE subscriptions " +
                            "ADD COLUMN updateAlways INTEGER NOT NULL DEFAULT 0",
                    )
                    db.execSQL("ALTER TABLE subscriptions ADD COLUMN fallbackUrl TEXT")
                }
            }

        /** v5: per-node user preferences (favorite/enabled/custom name/hidden),
         *  keyed by the parser-computed node id and independent of node rows. */
        val MIGRATION_4_5 =
            object : androidx.room.migration.Migration(4, 5) {
                override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                    db.execSQL(
                        """CREATE TABLE IF NOT EXISTS `node_preferences` (
                            `nodeId` TEXT PRIMARY KEY NOT NULL,
                            `isFavorite` INTEGER NOT NULL DEFAULT 0,
                            `isEnabled` INTEGER NOT NULL DEFAULT 1,
                            `customName` TEXT,
                            `isHidden` INTEGER NOT NULL DEFAULT 0)""",
                    )
                }
            }

        /**
         * v7: per-subscription auto-refresh policy. Existing rows get the
         * "inherit" default — identical to the global-int behavior they
         * already had — and no fixed minutes.
         */
        val MIGRATION_6_7 =
            object : androidx.room.migration.Migration(6, 7) {
                override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                    db.execSQL(
                        "ALTER TABLE subscriptions " +
                            "ADD COLUMN refreshPolicy TEXT NOT NULL DEFAULT 'inherit'",
                    )
                    db.execSQL(
                        "ALTER TABLE subscriptions " +
                            "ADD COLUMN refreshFixedMinutes INTEGER",
                    )
                }
            }

        /** v6: user routing rules — domain/IP/port lists with
         *  proxy/direct/block outcomes, ordered and individually toggleable. */
        val MIGRATION_5_6 =
            object : androidx.room.migration.Migration(5, 6) {
                override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                    db.execSQL(
                        """CREATE TABLE IF NOT EXISTS `routing_rules` (
                            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            `kind` TEXT NOT NULL,
                            `pattern` TEXT NOT NULL,
                            `action` TEXT NOT NULL,
                            `orderIndex` INTEGER NOT NULL,
                            `isEnabled` INTEGER NOT NULL DEFAULT 1)""",
                    )
                }
            }

        /** v8: last latency verdict per node (method + time), see
         *  [NodeLatencyEntity]. Pure addition — no existing table is touched. */
        val MIGRATION_7_8 =
            object : androidx.room.migration.Migration(7, 8) {
                override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                    db.execSQL(
                        """CREATE TABLE IF NOT EXISTS `node_latency` (
                            `nodeId` TEXT NOT NULL,
                            `latencyMs` INTEGER,
                            `checkedAtEpochMs` INTEGER NOT NULL,
                            `method` TEXT NOT NULL,
                            PRIMARY KEY(`nodeId`))""",
                    )
                }
            }

        /** The complete upgrade chain — the one list AppModule registers and
         *  the migration tests check. A version bump without its entry here
         *  would fall through to the destructive fallback. */
        val ALL_MIGRATIONS: Array<androidx.room.migration.Migration> =
            arrayOf(
                MIGRATION_1_2,
                MIGRATION_2_3,
                MIGRATION_3_4,
                MIGRATION_4_5,
                MIGRATION_5_6,
                MIGRATION_6_7,
                MIGRATION_7_8,
            )
    }
}
