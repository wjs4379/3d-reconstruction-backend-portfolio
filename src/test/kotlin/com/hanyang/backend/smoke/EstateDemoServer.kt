package com.hanyang.backend.smoke

import com.hanyang.backend.BackendApplication
import com.hanyang.backend.domain.user.JwtTokenProvider
import com.hanyang.backend.domain.user.User
import com.hanyang.backend.domain.user.UserRepository
import org.springframework.boot.SpringApplication
import tools.jackson.databind.json.JsonMapper
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission

/** Local fixture identities with the real MySQL/Flyway/storage implementation; never in bootJar. */
fun main(args: Array<String>) {
    require(args.isEmpty()) { "Use the private local runtime configuration, not CLI overrides." }
    val configPath = Path.of(requireNotNull(System.getenv("ESTATE_DEMO_CONFIG"))).toAbsolutePath()
    require(Files.isRegularFile(configPath) && !Files.isSymbolicLink(configPath))
    require(Files.size(configPath) <= 16_384)
    if (Files.getFileStore(configPath).supportsFileAttributeView("posix")) {
        require(Files.getPosixFilePermissions(configPath).all {
            it == PosixFilePermission.OWNER_READ || it == PosixFilePermission.OWNER_WRITE
        }) { "Runtime secrets must be readable only by the local owner." }
    }
    val mapper = JsonMapper.builder().build()
    val config = mapper.readTree(Files.readString(configPath))
    fun text(name: String): String = requireNotNull(config.get(name)?.asText())
    fun port(name: String): Int = text(name).toInt().also { require(it in 1024..65535) }
    val backendPort = port("backendPort")
    val mysqlPort = port("mysqlPort")
    val aiPort = port("aiPort")
    val origin = "http://127.0.0.1:$backendPort"
    val runtime = requireNotNull(configPath.parent)
    require(text("database") == "gp_estate_demo" && text("dbUsername") == "gp_demo")
    require(text("jwtSecret").toByteArray().size >= 32 && text("aiServiceKey").toByteArray().size >= 32)
    val properties = arrayOf(
        "--server.address=127.0.0.1", "--server.port=$backendPort", "--spring.main.banner-mode=off",
        "--spring.datasource.url=jdbc:mysql://127.0.0.1:$mysqlPort/gp_estate_demo?serverTimezone=Asia/Seoul&characterEncoding=UTF-8&sslMode=DISABLED",
        "--spring.datasource.username=gp_demo", "--spring.datasource.password=${text("dbPassword")}",
        "--spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver",
        "--spring.flyway.enabled=true", "--spring.flyway.baseline-on-migrate=false",
        "--spring.jpa.hibernate.ddl-auto=validate", "--spring.jpa.open-in-view=false",
        "--app.jwt.secret=${text("jwtSecret")}", "--app.jwt.issuer=estate-local-mysql-demo",
        "--app.cors.allowed-origins=http://127.0.0.1:5173,http://localhost:5173",
        "--app.media.mode=local", "--app.media.local-directory=${runtime.resolve("media")}",
        "--app.media.public-base-url=$origin", "--app.ai.pipeline-enabled=true",
        "--app.ai.service-key=${text("aiServiceKey")}", "--app.ai.backend-base-url=$origin",
        "--ai.server.base-url=http://127.0.0.1:$aiPort", "--outbox.worker.enabled=true",
        "--outbox.worker.max-concurrency=1", "--outbox.worker.batch-size=1",
        "--management.health.redis.enabled=false"
    )
    val context = SpringApplication(BackendApplication::class.java, EstateSmokeConfiguration::class.java).run(*properties)
    try {
        val users = context.getBean(UserRepository::class.java)
        val tokens = context.getBean(JwtTokenProvider::class.java)
        fun identity(providerId: String, email: String, name: String, role: String): User =
            users.findByProviderAndProviderId("KAKAO", providerId).orElseGet {
                users.saveAndFlush(User(email = email, name = name, provider = "KAKAO", providerId = providerId, role = role))
            }
        fun fixture(user: User): Map<String, Any> {
            val response = tokens.createTokenResponse(requireNotNull(user.id), user.role)
            return mapOf("userId" to requireNotNull(user.id), "name" to user.name, "role" to user.role,
                "accessToken" to response.accessToken, "refreshToken" to response.refreshToken)
        }
        val output = runtime.resolve("smoke-users.json")
        val credentials = mapOf(
            "owner" to fixture(identity("estate-smoke-owner", "owner@estate-smoke.invalid", "시연 게시자", "HOST")),
            "buyer" to fixture(identity("estate-smoke-buyer", "buyer@estate-smoke.invalid", "시연 탐색자", "GUEST")),
            "aiServiceKey" to text("aiServiceKey")
        )
        require(!Files.isSymbolicLink(output)) { "Fixture output must not be a symbolic link." }
        if (!Files.exists(output)) Files.createFile(output)
        Files.setPosixFilePermissions(output, setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE))
        Files.writeString(output, mapper.writeValueAsString(credentials), StandardCharsets.UTF_8)
        println("DEMO_READY=$origin (MySQL + Flyway; persistent local media; test-only login)")
    } catch (failure: Exception) {
        context.close()
        throw failure
    }
}
