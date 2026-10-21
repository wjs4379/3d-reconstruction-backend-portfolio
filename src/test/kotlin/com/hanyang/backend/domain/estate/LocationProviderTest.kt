package com.hanyang.backend.domain.estate

import com.hanyang.backend.common.ApiException
import com.hanyang.backend.domain.location.KakaoLocationProvider
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import tools.jackson.databind.json.JsonMapper
import java.math.BigDecimal

/** Pure response fixtures: these tests do not call Kakao or establish accuracy of a real address. */
class LocationProviderTest {
    private val mapper = JsonMapper.builder().build()
    private val provider = KakaoLocationProvider(mapper, "")
    private val document = """{"address_type":"ROAD_ADDR","x":"127.0443","y":"37.5445",
      "address":{"address_name":"서울 성동구 성수동1가 656-78","b_code":"1120011400","region_1depth_name":"서울",
      "region_2depth_name":"성동구","region_3depth_name":"성수동1가","x":"127.0443","y":"37.5445"},
      "road_address":{"address_name":"서울 성동구 왕십리로 83","zone_no":"04769","x":"127.0443","y":"37.5445"}}"""

    @Test fun `확인된 건물 응답에서 x를 경도 y를 위도로 정규화하고 법정동 우편번호를 보존한다`() {
        val result = provider.parse(mapper.readTree("""{"documents":[$document]}"""))
        assertEquals("서울 성동구 왕십리로 83", result.canonicalAddress)
        assertEquals("1120011400", result.regionCode); assertEquals("04769", result.postalCode)
        assertEquals(BigDecimal("37.5445000"), result.latitude)
        assertEquals(BigDecimal("127.0443000"), result.longitude)
    }
    @Test fun `도로명 없는 지번 주소는 지번 정규 주소를 유지한다`() {
        val jibun = document.replace("\"ROAD_ADDR\"", "\"REGION_ADDR\"").replace(Regex(""",\s*"road_address":\{[^}]+\}"""), "")
        val result = provider.parse(mapper.readTree("""{"documents":[$jibun]}"""))
        assertEquals("서울 성동구 성수동1가 656-78", result.canonicalAddress)
        assertNull(result.postalCode)
    }
    @Test fun `빈 결과나 여러 주소와 동네 단위 결과를 좌표로 추측하지 않는다`() {
        assertStatus(HttpStatus.UNPROCESSABLE_ENTITY) { provider.parse(mapper.readTree("""{"documents":[]}""")) }
        assertStatus(HttpStatus.UNPROCESSABLE_ENTITY) { provider.parse(mapper.readTree("""{"documents":[$document,$document]}""")) }
        val regionOnly = document.replace("ROAD_ADDR", "REGION")
        assertStatus(HttpStatus.UNPROCESSABLE_ENTITY) { provider.parse(mapper.readTree("""{"documents":[$regionOnly]}""")) }
    }
    @Test fun `외부 응답의 없는 지역 코드와 범위 밖 좌표를 거부한다`() {
        val missingCode = document.replace("1120011400", "bad-code")
        assertStatus(HttpStatus.SERVICE_UNAVAILABLE) { provider.parse(mapper.readTree("""{"documents":[$missingCode]}""")) }
        val outside = document.replace("37.5445", "-37.5445")
        assertStatus(HttpStatus.SERVICE_UNAVAILABLE) { provider.parse(mapper.readTree("""{"documents":[$outside]}""")) }
    }
    @Test fun `공급자 키 미설정은 임의 좌표 대신 명확한 503을 반환한다`() {
        val error = assertThrows(ApiException::class.java) { provider.resolve("정확한 주소") }
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, error.status)
        assertEquals("LOCATION_PROVIDER_UNAVAILABLE", error.code)
    }
    private fun assertStatus(status: HttpStatus, action: () -> kotlin.Unit) =
        assertEquals(status, assertThrows(ApiException::class.java, action).status)
}
