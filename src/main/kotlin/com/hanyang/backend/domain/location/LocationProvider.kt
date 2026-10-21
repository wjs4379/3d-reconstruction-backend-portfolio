package com.hanyang.backend.domain.location

import com.hanyang.backend.common.ApiException
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.math.BigDecimal
import java.math.RoundingMode
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration

data class ResolvedAddress(
    val canonicalAddress: String, val roadAddress: String?, val jibunAddress: String?, val postalCode: String?,
    val regionCode: String, val regionName: String, val latitude: BigDecimal, val longitude: BigDecimal
)
interface LocationProvider { fun resolve(address: String): ResolvedAddress }

/** The REST key never leaves this server. https://developers.kakao.com/docs/ko/local/dev-guide#address-coord */
@Component
class KakaoLocationProvider(
    private val mapper: JsonMapper,
    @param:Value("\${app.location.kakao-rest-api-key:}") private val restApiKey: String
) : LocationProvider {
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER).build()
    override fun resolve(address: String): ResolvedAddress {
        if (restApiKey.isBlank()) unavailable()
        val query = URLEncoder.encode(address, StandardCharsets.UTF_8)
        val request = HttpRequest.newBuilder(URI("https://dapi.kakao.com/v2/local/search/address.json?query=$query&analyze_type=exact&size=2"))
            .timeout(Duration.ofSeconds(5)).header("Authorization", "KakaoAK $restApiKey").GET().build()
        val response = try { client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)) }
        catch (e: InterruptedException) { Thread.currentThread().interrupt(); unavailable() }
        catch (_: Exception) { unavailable() }
        if (response.statusCode() != 200) unavailable()
        return try { parse(mapper.readTree(response.body())) } catch (e: ApiException) { throw e } catch (_: Exception) { unavailable() }
    }
    internal fun parse(root: JsonNode): ResolvedAddress {
        val documents = root.path("documents")
        if (!documents.isArray || documents.size() != 1) throw ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
            "ADDRESS_NOT_EXACT", "건물까지 포함한 정확한 도로명 또는 지번 주소를 선택해 주세요.")
        val document = documents.get(0)
        if (document.path("address_type").asText() !in setOf("REGION_ADDR", "ROAD_ADDR")) throw ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
            "ADDRESS_NOT_BUILDING", "동네 이름 대신 건물 주소를 선택해 주세요.")
        val road = document.path("road_address"); val jibun = document.path("address")
        val roadAddress = text(road, "address_name"); val jibunAddress = text(jibun, "address_name")
        val canonical = roadAddress ?: jibunAddress ?: unavailable()
        val latitude = coordinate(text(road, "y") ?: text(jibun, "y") ?: text(document, "y"), 32.0, 40.0)
        val longitude = coordinate(text(road, "x") ?: text(jibun, "x") ?: text(document, "x"), 123.0, 133.0)
        val code = text(jibun, "b_code")?.takeIf { Regex("[0-9]{10}").matches(it) } ?: unavailable()
        val region = listOf("region_1depth_name", "region_2depth_name", "region_3depth_name").mapNotNull { text(jibun, it) }.joinToString(" ")
        if (region.isBlank() || canonical.length > 255 || region.length > 100) unavailable()
        return ResolvedAddress(canonical, roadAddress, jibunAddress, text(road, "zone_no")?.takeIf { Regex("[0-9]{5}").matches(it) },
            code, region, latitude, longitude)
    }
    private fun text(node: JsonNode, name: String): String? = node.path(name).takeIf { it.isString }?.asText()?.trim()?.takeIf { it.isNotEmpty() }
    private fun coordinate(value: String?, min: Double, max: Double): BigDecimal {
        val parsed = value?.toBigDecimalOrNull() ?: unavailable()
        if (parsed.toDouble() !in min..max) unavailable()
        return parsed.setScale(7, RoundingMode.HALF_UP)
    }
    private fun unavailable(): Nothing = throw ApiException(HttpStatus.SERVICE_UNAVAILABLE,
        "LOCATION_PROVIDER_UNAVAILABLE", "주소 좌표 확인 서비스를 사용할 수 없습니다. 수동 주소는 위치 미확인으로 저장하거나 잠시 후 다시 시도해 주세요.")
}
