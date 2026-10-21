package com.hanyang.backend.migration

import com.hanyang.backend.domain.inquiry.Inquiry
import com.hanyang.backend.domain.location.VerifiedLocation
import com.hanyang.backend.domain.favorite.ListingFavorite
import com.hanyang.backend.domain.listing.Listing
import com.hanyang.backend.domain.media.MediaAsset
import com.hanyang.backend.domain.media.UploadSession
import com.hanyang.backend.domain.model3d.Room3DModel
import com.hanyang.backend.domain.model3d.outbox.OutboxEvent
import com.hanyang.backend.domain.property.Property
import com.hanyang.backend.domain.room.Room
import com.hanyang.backend.domain.unit.Unit
import com.hanyang.backend.domain.usage.RoomUsage
import com.hanyang.backend.domain.user.User
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.FlywayException
import org.hibernate.boot.MetadataSources
import org.hibernate.boot.registry.StandardServiceRegistryBuilder
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.core.io.ClassPathResource
import org.springframework.jdbc.datasource.init.ScriptUtils
import org.testcontainers.DockerClientFactory
import org.testcontainers.mysql.MySQLContainer
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException

/** Real MySQL, via a disposable Docker database or explicitly isolated local native database. */
@Tag("mysql")
class MySqlMigrationIntegrationTest {
    companion object {
        private var container: MySQLContainer? = null
        private lateinit var jdbcUrl: String
        private lateinit var username: String
        private lateinit var password: String

        @JvmStatic @BeforeAll fun prepareDisposableMySql() {
            val nativeUrl = System.getenv("ESTATE_MYSQL_TEST_URL")
            if (nativeUrl != null) {
                // clean() is intentionally destructive only in this exact loopback test schema.
                // Never reuse DB_URL or application credentials for this suite.
                require(Regex("""^jdbc:mysql://(?:localhost|127\.0\.0\.1):[0-9]+/estate_migration_test(?:\?.*)?$""").matches(nativeUrl)) {
                    "ESTATE_MYSQL_TEST_URL must use loopback and the isolated estate_migration_test schema"
                }
                jdbcUrl = nativeUrl
                username = requireNotNull(System.getenv("ESTATE_MYSQL_TEST_USERNAME")) { "Set ESTATE_MYSQL_TEST_USERNAME" }
                password = requireNotNull(System.getenv("ESTATE_MYSQL_TEST_PASSWORD")) { "Set ESTATE_MYSQL_TEST_PASSWORD" }
            } else {
                assumeTrue(DockerClientFactory.instance().isDockerAvailable, "Docker or explicit isolated native MySQL is required")
                val owned = MySQLContainer("mysql:8.4").withDatabaseName("estate_migration_test")
                    .withUsername("test").withPassword("test")
                owned.start()
                container = owned
                jdbcUrl = owned.jdbcUrl
                username = owned.username
                password = owned.password
            }
            DriverManager.getConnection(jdbcUrl, username, password).use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery("SELECT DATABASE()").use { result ->
                        check(result.next() && result.getString(1) == "estate_migration_test") {
                            "Refusing migration test cleanup outside isolated schema"
                        }
                    }
                }
            }
        }

        @JvmStatic @AfterAll fun stopOwnedContainer() {
            container?.stop()
        }
    }

    @BeforeEach fun resetDisposableDatabase() {
        // clean is enabled ONLY for the verified isolated test schema, never in application configuration.
        flyway().cleanDisabled(false).load().clean()
    }

    @Test fun `빈 MySQL에 V1 V2 V3 V4 V5가 적용되고 모든 엔티티를 validate할 수 있다`() {
        val flyway = flyway().load()
        assertEquals(5, flyway.migrate().migrationsExecuted)
        assertEquals("5", flyway.info().current().version.version)
        validateHibernateSchema()
        connect().use { connection ->
            assertEquals(15L, scalar(connection, "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name <> 'flyway_schema_history'"))
        }
    }

    @Test fun `기존 DB는 자동 baseline을 거부하고 명시적 baseline 이후 기존 식별자와 데이터를 보존한다`() {
        connect().use { connection ->
            ScriptUtils.executeSqlScript(connection, ClassPathResource("db/migration/V1__legacy_schema.sql"))
            legacyFixture(connection)
        }
        val flyway = flyway().load()
        assertThrows(FlywayException::class.java) { flyway.migrate() }
        connect().use { assertEquals(0L, tableCount(it, "units")) }
        flyway.baseline()
        assertEquals(4, flyway.migrate().migrationsExecuted)
        validateHibernateSchema()
        connect().use { connection ->
            assertEquals(2L, scalar(connection, "SELECT COUNT(*) FROM users"))
            assertEquals(51L, scalar(connection, "SELECT property_id FROM properties"))
            assertEquals(61L, scalar(connection, "SELECT room_id FROM rooms"))
            assertEquals(42L, scalar(connection, "SELECT owner_id FROM rooms"))
            assertEquals(120000L, scalar(connection, "SELECT price_per_night FROM rooms"))
            assertEquals(3L, scalar(connection, "SELECT max_guests FROM rooms"))
            assertEquals(1L, scalar(connection, "SELECT COUNT(*) FROM rooms WHERE unit_id IS NULL"))
            assertEquals(1L, scalar(connection, "SELECT COUNT(*) FROM properties WHERE creator_user_id IS NULL"))
            assertEquals(1L, scalar(connection, "SELECT COUNT(*) FROM room_3d_models WHERE model_id=71 AND ai_room_id='legacy-ai-stable' AND status='SUCCESS' AND model_url='https://archive.example/model.glb' AND room_data='{\"preserved\":true}'"))
            assertEquals(1L, scalar(connection, "SELECT COUNT(*) FROM room_3d_models WHERE model_id=71 AND source_type='LEGACY_AI' AND source_image_asset_ids IS NULL"))
            assertEquals(1L, scalar(connection, "SELECT COUNT(*) FROM room_3d_models WHERE model_id=71 AND CHAR_LENGTH(original_image_url)>500"))
            assertEquals(81L, scalar(connection, "SELECT usage_id FROM room_usages"))
            assertEquals(1L, scalar(connection, "SELECT COUNT(*) FROM outbox_events WHERE id=91 AND idempotency_key='legacy-idempotency' AND status='PENDING' AND payload='{\"roomId\":61}'"))
            assertEquals(0L, scalar(connection, "SELECT COUNT(*) FROM listings"))
        }
    }

    @Test fun `V2의 명시 모델 선택과 문의 버전은 V3 V4 V5 확장에도 보존된다`() {
        flyway().target("2").load().migrate()
        connect().use { connection ->
            legacyFixture(connection)
            execute(connection, "UPDATE properties SET creator_user_id=42 WHERE property_id=51")
            execute(connection, "INSERT INTO units(unit_id,property_id,creator_user_id,name,region_code,created_at) VALUES(101,51,42,'301호','SEOUL',NOW(6))")
            execute(connection, "UPDATE rooms SET unit_id=101 WHERE room_id=61")
            execute(connection, "INSERT INTO media_assets(asset_id,unit_id,owner_user_id,kind,status,object_key,size_bytes,content_type,source_model_id,created_at) VALUES(301,101,42,'MODEL','VERIFIED','existing-fixed.glb',100,'model/gltf-binary',71,NOW(6))")
            execute(connection, "INSERT INTO listings(listing_id,unit_id,deal_type,status,title,deposit,monthly_rent,model_asset_id,selected_model_id,selected_space_id,version,created_at,updated_at) VALUES(201,101,'MONTHLY_RENT','DRAFT','기존 초안',10000000,500000,301,71,61,7,NOW(6),NOW(6))")
            execute(connection, "INSERT INTO inquiries(inquiry_id,listing_id,unit_id,author_user_id,listing_version,title_snapshot,deal_type_snapshot,deposit_snapshot,rent_snapshot,message,status,created_at) VALUES(401,201,101,43,6,'문의 당시 제목','MONTHLY_RENT',9000000,450000,'기존 문의','OPEN',NOW(6))")
        }
        assertEquals(3, flyway().load().migrate().migrationsExecuted)
        validateHibernateSchema()
        connect().use { connection ->
            assertEquals(7L, scalar(connection, "SELECT version FROM listings WHERE listing_id=201"))
            assertEquals(1L, scalar(connection, "SELECT COUNT(*) FROM listing_models WHERE listing_id=201 AND model_order=0 AND space_id=61 AND model_asset_id=301 AND selected_model_id=71 AND space_name='기존 거실'"))
            assertEquals(1L, scalar(connection, "SELECT COUNT(*) FROM units WHERE unit_id=101 AND location_id IS NULL AND location_visibility='HIDDEN' AND version=0"))
            assertEquals(1L, scalar(connection, "SELECT COUNT(*) FROM inquiries WHERE inquiry_id=401 AND listing_version=6 AND title_snapshot='문의 당시 제목' AND rent_snapshot=450000 AND sale_price_snapshot IS NULL"))
        }
    }

    @Test fun `중복 소셜 사용자에는 임의 병합 없이 migration이 멈춘다`() {
        flyway().target("1").load().migrate()
        connect().use { connection ->
            legacyFixture(connection)
            execute(connection, "INSERT INTO users(user_id,email,name,provider,provider_id,role,created_at) VALUES(44,'duplicate@example.com','중복','GOOGLE','owner-provider','GUEST',NOW(6))")
        }
        assertThrows(FlywayException::class.java) { flyway().load().migrate() }
        connect().use { connection ->
            assertEquals(2L, scalar(connection, "SELECT COUNT(*) FROM users WHERE provider='GOOGLE' AND provider_id='owner-provider'"))
            assertEquals(0L, tableCount(connection, "units"))
            assertEquals(0L, scalar(connection, "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='properties' AND column_name='creator_user_id'"))
        }
    }

    @Test fun `MySQL이 금액 범위와 호실 월세 중복을 차단하고 BIGINT 금액을 보존한다`() {
        flyway().load().migrate()
        connect().use { connection ->
            legacyFixture(connection)
            execute(connection, "INSERT INTO units(unit_id,property_id,creator_user_id,name,region_code,created_at) VALUES(101,51,42,'301호','SEOUL',NOW(6))")
            val prefix = "INSERT INTO listings(listing_id,unit_id,deal_type,status,title,deposit,monthly_rent,version,created_at,updated_at) VALUES"
            assertThrows(SQLException::class.java) { execute(connection, "$prefix(201,101,'MONTHLY_RENT','DRAFT','초안',-1,500000,0,NOW(6),NOW(6))") }
            assertThrows(SQLException::class.java) { execute(connection, "$prefix(201,101,'MONTHLY_RENT','DRAFT','초안',0,0,0,NOW(6),NOW(6))") }
            execute(connection, "$prefix(201,101,'MONTHLY_RENT','DRAFT','초안',3000000000,500000,0,NOW(6),NOW(6))")
            assertEquals(3_000_000_000L, scalar(connection, "SELECT deposit FROM listings WHERE listing_id=201"))
            assertThrows(SQLException::class.java) { execute(connection, "$prefix(203,101,'JEONSE','DRAFT','월세 혼합 전세',300000000,500000,0,NOW(6),NOW(6))") }
            execute(connection, "$prefix(203,101,'JEONSE','DRAFT','전세',300000000,NULL,0,NOW(6),NOW(6))")
            execute(connection, "INSERT INTO listings(listing_id,unit_id,deal_type,status,title,sale_price,version,created_at,updated_at) VALUES(204,101,'SALE','DRAFT','매매',900000000,0,NOW(6),NOW(6))")
            assertEquals(1L, scalar(connection, "SELECT COUNT(*) FROM listings WHERE listing_id=204 AND deposit IS NULL AND monthly_rent IS NULL"))
            assertThrows(SQLException::class.java) { execute(connection, "UPDATE units SET location_visibility='BUILDING' WHERE unit_id=101") }
            assertThrows(SQLException::class.java) { execute(connection, "$prefix(202,101,'MONTHLY_RENT','DRAFT','중복',0,500000,0,NOW(6),NOW(6))") }
        }
    }

    @Test fun `V3 to latest preserves unknown details and database enforces structural and completion invariants`() {
        flyway().target("3").load().migrate()
        connect().use { c ->
            legacyFixture(c)
            execute(c,"INSERT INTO units(unit_id,property_id,creator_user_id,name,region_code,floor,created_at) VALUES(101,51,42,'301호','SEOUL',3,NOW(6))")
            execute(c,"INSERT INTO listings(listing_id,unit_id,deal_type,status,title,deposit,monthly_rent,version,created_at,updated_at) VALUES(201,101,'MONTHLY_RENT','DRAFT','기존 초안',10000000,500000,7,NOW(6),NOW(6))")
        }
        assertEquals(2,flyway().load().migrate().migrationsExecuted)
        validateHibernateSchema()
        connect().use { c ->
            assertEquals(1L,scalar(c,"SELECT COUNT(*) FROM units WHERE unit_id=101 AND has_parking IS NULL AND room_count IS NULL AND building_type IS NULL"))
            assertEquals(7L,scalar(c,"SELECT version FROM listings WHERE listing_id=201 AND completed_at IS NULL"))
            assertThrows(SQLException::class.java) { execute(c,"UPDATE units SET total_floors=2 WHERE unit_id=101") }
            assertThrows(SQLException::class.java) { execute(c,"UPDATE units SET room_count=100 WHERE unit_id=101") }
            assertThrows(SQLException::class.java) { execute(c,"UPDATE units SET has_parking=2 WHERE unit_id=101") }
            execute(c,"UPDATE units SET total_floors=8,room_count=2,has_parking=0,building_type='VILLA' WHERE unit_id=101")
            assertThrows(SQLException::class.java) { execute(c,"UPDATE listings SET status='COMPLETED' WHERE listing_id=201") }
            execute(c,"UPDATE listings SET status='COMPLETED',completed_at=NOW(6) WHERE listing_id=201")
            assertThrows(SQLException::class.java) { execute(c,"UPDATE listings SET status='DRAFT' WHERE listing_id=201") }
            execute(c,"UPDATE listings SET status='DRAFT',completed_at=NULL WHERE listing_id=201")
            assertEquals(1L,scalar(c,"SELECT COUNT(*) FROM listings WHERE listing_id=201 AND deposit=10000000 AND monthly_rent=500000 AND version=7"))
        }
    }

    @Test fun `V4 to V5 leaves prior image and model assets unchanged and enforces verified dimension pairs`() {
        flyway().target("4").load().migrate()
        connect().use { c ->
            legacyFixture(c)
            execute(c,"INSERT INTO units(unit_id,property_id,creator_user_id,name,region_code,created_at) VALUES(101,51,42,'301호','SEOUL',NOW(6))")
            execute(c,"INSERT INTO media_assets(asset_id,unit_id,owner_user_id,kind,status,object_key,object_version,size_bytes,content_type,checksum,created_at) VALUES(301,101,42,'IMAGE','VERIFIED','original-photo.png','preserved-version',100,'image/png','preserved-checksum',NOW(6))")
            execute(c,"INSERT INTO media_assets(asset_id,unit_id,owner_user_id,kind,status,object_key,size_bytes,content_type,source_model_id,created_at) VALUES(302,101,42,'MODEL','VERIFIED','original-model.glb',100,'model/gltf-binary',71,NOW(6))")
        }
        assertEquals(1,flyway().load().migrate().migrationsExecuted)
        validateHibernateSchema()
        connect().use { c ->
            assertEquals(1L,scalar(c,"SELECT COUNT(*) FROM media_assets WHERE asset_id=301 AND object_key='original-photo.png' AND object_version='preserved-version' AND checksum='preserved-checksum' AND pixel_width IS NULL AND pixel_height IS NULL"))
            assertEquals(1L,scalar(c,"SELECT COUNT(*) FROM media_assets WHERE asset_id=302 AND object_key='original-model.glb' AND source_model_id=71 AND pixel_width IS NULL AND pixel_height IS NULL"))
            for (dimensions in listOf("pixel_width=64", "pixel_width=0,pixel_height=32", "pixel_width=8193,pixel_height=32", "pixel_width=4000,pixel_height=4000")) {
                assertThrows(SQLException::class.java) { execute(c,"UPDATE media_assets SET $dimensions WHERE asset_id=301") }
            }
            assertThrows(SQLException::class.java) { execute(c,"UPDATE media_assets SET pixel_width=64,pixel_height=32 WHERE asset_id=302") }
            execute(c,"UPDATE media_assets SET pixel_width=64,pixel_height=32 WHERE asset_id=301")
            assertEquals(1L,scalar(c,"SELECT COUNT(*) FROM media_assets WHERE asset_id=301 AND pixel_width=64 AND pixel_height=32 AND object_version='preserved-version'"))
        }
    }

    private fun flyway() = Flyway.configure().dataSource(jdbcUrl, username, password)
        .schemas("estate_migration_test")
        .locations("classpath:db/migration").baselineOnMigrate(false).baselineVersion("1")
    private fun connect(): Connection = DriverManager.getConnection(jdbcUrl, username, password)
    private fun execute(connection: Connection, sql: String) = connection.createStatement().use { it.execute(sql) }
    private fun scalar(connection: Connection, sql: String): Long = connection.createStatement().use { statement ->
        statement.executeQuery(sql).use { result -> assertTrue(result.next()); result.getLong(1) }
    }
    private fun tableCount(connection: Connection, name: String) = scalar(connection,
        "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='$name'")

    private fun validateHibernateSchema() {
        val registry = StandardServiceRegistryBuilder()
            .applySetting("hibernate.connection.driver_class", "com.mysql.cj.jdbc.Driver")
            .applySetting("hibernate.connection.url", jdbcUrl)
            .applySetting("hibernate.connection.username", username)
            .applySetting("hibernate.connection.password", password)
            .applySetting("hibernate.hbm2ddl.auto", "validate")
            .applySetting("hibernate.physical_naming_strategy", "org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy")
            .build()
        try {
            val metadata = MetadataSources(registry)
            listOf(User::class.java, Property::class.java, Room::class.java, RoomUsage::class.java,
                Room3DModel::class.java, OutboxEvent::class.java, Unit::class.java, MediaAsset::class.java,
                UploadSession::class.java, Listing::class.java, Inquiry::class.java, VerifiedLocation::class.java, ListingFavorite::class.java).forEach { metadata.addAnnotatedClass(it) }
            metadata.buildMetadata().buildSessionFactory().use { assertTrue(it.isOpen) }
        } finally {
            StandardServiceRegistryBuilder.destroy(registry)
        }
    }

    private fun legacyFixture(connection: Connection) {
        execute(connection, "INSERT INTO users(user_id,email,name,provider,provider_id,role,created_at) VALUES(42,'owner@example.com','게시자','GOOGLE','owner-provider','HOST',NOW(6)),(43,'guest@example.com','사용자','GOOGLE','guest-provider','GUEST',NOW(6))")
        execute(connection, "INSERT INTO properties(property_id,title,address,created_at) VALUES(51,'기존 건물','기존 주소',NOW(6))")
        execute(connection, "INSERT INTO rooms(room_id,property_id,owner_id,room_name,price_per_night,max_guests,created_at) VALUES(61,51,42,'기존 거실',120000,3,NOW(6))")
        val legacyImageUrl = "https://archive.example/" + "x".repeat(600) + ".jpg"
        execute(connection, "INSERT INTO room_3d_models(model_id,room_id,ai_room_id,original_image_url,model_url,status,room_data,uploaded_at) VALUES(71,61,'legacy-ai-stable','$legacyImageUrl','https://archive.example/model.glb','SUCCESS','{\"preserved\":true}',NOW(6))")
        execute(connection, "INSERT INTO room_usages(usage_id,user_id,room_id,status,started_at,ended_at) VALUES(81,43,61,'ACTIVE','2026-10-20 15:00:00','2026-10-21 11:00:00')")
        execute(connection, "INSERT INTO outbox_events(id,idempotency_key,event_type,aggregate_id,payload,status,attempt_count,next_attempt_at,created_at,updated_at) VALUES(91,'legacy-idempotency','AI_ANALYZE','legacy-ai-stable','{\"roomId\":61}','PENDING',0,NOW(6),NOW(6),NOW(6))")
    }
}
