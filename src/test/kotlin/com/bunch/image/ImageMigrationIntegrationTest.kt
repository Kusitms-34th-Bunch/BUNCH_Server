package com.bunch.image

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.FlywayException
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.TestPropertySource
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.util.UUID
import javax.sql.DataSource

@JdbcTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = ["spring.flyway.enabled=false"])
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ImageMigrationIntegrationTest @Autowired constructor(
    private val dataSource: DataSource
) {
    private val jdbc = JdbcTemplate(dataSource)

    @Test
    fun `migration backfills shared assets and creates protected photo foundation`() {
        withTarget4 { schema ->
            val sharedPublicId = "profiles/shared"
            val sharedUrl = "https://images.example/profiles/shared.jpg"
            val memberWithImage = insertMember(schema, "member-with-image", sharedPublicId, sharedUrl)
            val memberWithoutImage = insertMember(schema, "member-without-image", null, null)
            val bunchWithImage = insertBunch(schema, "bunch-with-image", memberWithImage, sharedPublicId, sharedUrl)
            val bunchWithoutImage = insertBunch(schema, "bunch-without-image", memberWithoutImage, null, null)

            val current = configure(schema, "5")
            assertThat(current.migrate().migrationsExecuted).isEqualTo(1)

            val sharedAssetId = jdbc.queryForObject(
                "SELECT profile_image_asset_id FROM $schema.members WHERE id = ?",
                Long::class.java,
                memberWithImage
            )!!
            assertThat(jdbc.queryForObject(
                "SELECT profile_image_asset_id FROM $schema.bunches WHERE id = ?",
                Long::class.java,
                bunchWithImage
            )).isEqualTo(sharedAssetId)
            assertThat(jdbc.queryForObject(
                "SELECT profile_image_asset_id FROM $schema.members WHERE id = ?",
                Long::class.java,
                memberWithoutImage
            )).isNull()
            assertThat(jdbc.queryForObject(
                "SELECT profile_image_asset_id FROM $schema.bunches WHERE id = ?",
                Long::class.java,
                bunchWithoutImage
            )).isNull()
            assertThat(jdbc.queryForObject(
                "SELECT url FROM $schema.image_assets WHERE id = ?",
                String::class.java,
                sharedAssetId
            )).isEqualTo(sharedUrl)
            assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM $schema.image_assets",
                Long::class.java
            )).isEqualTo(1)

            assertThat(columnCount(schema, "members", "image_public_id")).isZero()
            assertThat(columnCount(schema, "members", "image_url")).isZero()
            assertThat(columnCount(schema, "bunches", "image_public_id")).isZero()
            assertThat(columnCount(schema, "bunches", "image_url")).isZero()
            assertThat(tableCount(schema, "bundles")).isZero()
            assertThat(indexCount(schema, "members", "ix_members_profile_image_asset_id")).isEqualTo(1)
            assertThat(indexCount(schema, "bunches", "ix_bunches_profile_image_asset_id")).isEqualTo(1)
            assertThat(indexCount(schema, "photos", "ix_photos_image_asset_id")).isEqualTo(1)
            assertThat(indexCount(schema, "photo_uploaders", "ix_photo_uploaders_member_id")).isEqualTo(1)

            val sharedHash = "a".repeat(64)
            val firstPhoto = insertPhoto(schema, bunchWithImage, sharedAssetId, sharedHash)
            val otherPhoto = insertPhoto(schema, bunchWithoutImage, sharedAssetId, sharedHash)
            assertThat(otherPhoto).isNotEqualTo(firstPhoto)

            insertUploader(schema, firstPhoto, memberWithImage)
            assertThatThrownBy { insertUploader(schema, firstPhoto, memberWithImage) }
                .isInstanceOf(DataAccessException::class.java)
            assertThatThrownBy { insertPhoto(schema, bunchWithImage, sharedAssetId, sharedHash) }
                .isInstanceOf(DataAccessException::class.java)

            assertThatThrownBy { jdbc.update("DELETE FROM $schema.image_assets WHERE id = ?", sharedAssetId) }
                .isInstanceOf(DataAccessException::class.java)

            current.validate()
            assertThat(current.migrate().migrationsExecuted).isZero()
        }
    }

    @Test
    fun `photo checks enforce statuses hashes and captured date source`() {
        withTarget4 { schema ->
            val memberId = insertMember(schema, "photo-member", null, null)
            val bunchId = insertBunch(schema, "photo-bunch", memberId, null, null)
            configure(schema, "5").migrate()
            val assetId = insertAsset(schema)

            insertPhoto(
                schema,
                bunchId,
                assetId,
                "b".repeat(64),
                processingStatus = "PENDING",
                capturedDate = null,
                capturedTime = null,
                dateSource = "UNKNOWN"
            )
            insertPhoto(
                schema,
                bunchId,
                assetId,
                "c".repeat(64),
                processingStatus = "FAILED",
                capturedDate = "2026-10-25",
                capturedTime = null,
                dateSource = "USER"
            )

            assertThatThrownBy {
                insertPhoto(
                    schema,
                    bunchId,
                    assetId,
                    "A".repeat(64),
                    capturedDate = null,
                    capturedTime = null,
                    dateSource = "UNKNOWN"
                )
            }.isInstanceOf(DataAccessException::class.java)
            assertThatThrownBy {
                insertPhoto(
                    schema,
                    bunchId,
                    assetId,
                    "d".repeat(63),
                    capturedDate = null,
                    capturedTime = null,
                    dateSource = "UNKNOWN"
                )
            }.isInstanceOf(DataAccessException::class.java)
            assertThatThrownBy {
                insertPhoto(
                    schema,
                    bunchId,
                    assetId,
                    "e".repeat(64),
                    processingStatus = "PROCESSING",
                    capturedDate = null,
                    capturedTime = null,
                    dateSource = "UNKNOWN"
                )
            }.isInstanceOf(DataAccessException::class.java)
            assertThatThrownBy {
                insertPhoto(
                    schema,
                    bunchId,
                    assetId,
                    "f".repeat(64),
                    capturedDate = "2026-10-25",
                    capturedTime = null,
                    dateSource = "UNKNOWN"
                )
            }.isInstanceOf(DataAccessException::class.java)
            assertThatThrownBy {
                insertPhoto(
                    schema,
                    bunchId,
                    assetId,
                    "0".repeat(64),
                    capturedDate = null,
                    capturedTime = "12:00:00",
                    dateSource = "EXIF"
                )
            }.isInstanceOf(DataAccessException::class.java)
        }
    }

    @Test
    fun `incomplete legacy profile image data fails before changing the schema`() {
        withTarget4 { schema ->
            val memberId = insertMember(schema, "incomplete-member", "profiles/incomplete", null)

            assertMigrationFailsAndRollsBack(schema)

            assertThat(columnCount(schema, "members", "image_public_id")).isEqualTo(1)
            assertThat(columnCount(schema, "members", "image_url")).isEqualTo(1)
            assertThat(tableCount(schema, "image_assets")).isZero()
            assertThat(jdbc.queryForObject(
                "SELECT image_public_id FROM $schema.members WHERE id = ?",
                String::class.java,
                memberId
            )).isEqualTo("profiles/incomplete")
        }
    }

    @Test
    fun `conflicting legacy URLs for one public ID fail before changing the schema`() {
        withTarget4 { schema ->
            val memberId = insertMember(
                schema,
                "conflicting-member",
                "profiles/conflict",
                "https://images.example/profiles/one.jpg"
            )
            insertBunch(
                schema,
                "conflicting-bunch",
                memberId,
                "profiles/conflict",
                "https://images.example/profiles/two.jpg"
            )

            assertMigrationFailsAndRollsBack(schema)

            assertThat(columnCount(schema, "bunches", "image_public_id")).isEqualTo(1)
            assertThat(columnCount(schema, "bunches", "image_url")).isEqualTo(1)
            assertThat(tableCount(schema, "image_assets")).isZero()
            assertThat(jdbc.queryForObject(
                "SELECT image_url FROM $schema.bunches WHERE name = ?",
                String::class.java,
                "conflicting-bunch"
            )).isEqualTo("https://images.example/profiles/two.jpg")
        }
    }

    private fun assertMigrationFailsAndRollsBack(schema: String) {
        assertThatThrownBy { configure(schema, "5").migrate() }
            .isInstanceOf(FlywayException::class.java)
        assertThat(columnCount(schema, "members", "profile_image_asset_id")).isZero()
        assertThat(columnCount(schema, "bunches", "profile_image_asset_id")).isZero()
        assertThat(tableCount(schema, "photos")).isZero()
        assertThat(tableCount(schema, "photo_uploaders")).isZero()
    }

    private fun withTarget4(block: (String) -> Unit) {
        val schema = buildString {
            append("image_migration_")
            append(UUID.randomUUID().toString().replace("-", ""))
        }
        try {
            configure(schema, "4").migrate()
            block(schema)
        } finally {
            jdbc.execute("DROP SCHEMA IF EXISTS $schema CASCADE")
        }
    }

    private fun configure(schema: String, target: String): Flyway = Flyway.configure()
        .dataSource(dataSource)
        .locations("classpath:db/migration")
        .schemas(schema)
        .defaultSchema(schema)
        .target(target)
        .load()

    private fun insertMember(schema: String, name: String, publicId: String?, url: String?): Long = jdbc.queryForObject(
        """INSERT INTO $schema.members
            (role, signup_status, account_status, nickname, image_public_id, image_url)
            VALUES ('ROLE_USER', 'COMPLETED', 'ACTIVE', ?, ?, ?)
            RETURNING id""",
        Long::class.java,
        name,
        publicId,
        url
    )!!

    private fun insertBunch(
        schema: String,
        name: String,
        createdBy: Long,
        publicId: String?,
        url: String?
    ): Long = jdbc.queryForObject(
        """INSERT INTO $schema.bunches
            (name, introduction, visibility, character_type, image_public_id, image_url,
             created_by, creation_request_key, creation_fingerprint, created_at)
            VALUES (?, '함께 기록해요', 'PRIVATE', 'TYPE_2', ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
            RETURNING id""",
        Long::class.java,
        name,
        publicId,
        url,
        createdBy,
        UUID.randomUUID(),
        "fingerprint-$name"
    )!!

    private fun insertAsset(schema: String): Long = jdbc.queryForObject(
        """INSERT INTO $schema.image_assets (public_id, url, created_at)
            VALUES (?, ?, CURRENT_TIMESTAMP)
            RETURNING id""",
        Long::class.java,
        "photos/${UUID.randomUUID()}",
        "https://images.example/photos/${UUID.randomUUID()}.jpg"
    )!!

    private fun insertPhoto(
        schema: String,
        bunchId: Long,
        assetId: Long,
        fileHash: String,
        processingStatus: String = "READY",
        capturedDate: String? = "2026-10-25",
        capturedTime: String? = "12:00:00",
        dateSource: String = "EXIF"
    ): Long = jdbc.queryForObject(
        """INSERT INTO $schema.photos
            (bunch_id, image_asset_id, file_hash, processing_status, captured_date,
             captured_time, date_source, created_at)
            VALUES (?, ?, ?, ?, CAST(? AS DATE), CAST(? AS TIME), ?, CURRENT_TIMESTAMP)
            RETURNING id""",
        Long::class.java,
        bunchId,
        assetId,
        fileHash,
        processingStatus,
        capturedDate,
        capturedTime,
        dateSource
    )!!

    private fun insertUploader(schema: String, photoId: Long, memberId: Long) {
        jdbc.update(
            """INSERT INTO $schema.photo_uploaders
                (photo_id, member_id, upload_batch_id, uploaded_at)
                VALUES (?, ?, ?, CURRENT_TIMESTAMP)""",
            photoId,
            memberId,
            UUID.randomUUID()
        )
    }

    private fun columnCount(schema: String, table: String, column: String): Long = jdbc.queryForObject(
        """SELECT count(*) FROM information_schema.columns
            WHERE table_schema = ? AND table_name = ? AND column_name = ?""",
        Long::class.java,
        schema,
        table,
        column
    )!!

    private fun tableCount(schema: String, table: String): Long = jdbc.queryForObject(
        """SELECT count(*) FROM information_schema.tables
            WHERE table_schema = ? AND table_name = ?""",
        Long::class.java,
        schema,
        table
    )!!

    private fun indexCount(schema: String, table: String, index: String): Long = jdbc.queryForObject(
        """SELECT count(*) FROM pg_indexes
            WHERE schemaname = ? AND tablename = ? AND indexname = ?""",
        Long::class.java,
        schema,
        table,
        index
    )!!
}
