package com.hanyang.backend.domain.model3d

import jakarta.validation.Validation
import org.hibernate.validator.messageinterpolation.ParameterMessageInterpolator
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ManagedAnalyzeRequestValidationTest {
    @Test fun `HTTP request validation requires four images`() {
        Validation.byDefaultProvider().configure().messageInterpolator(ParameterMessageInterpolator())
            .buildValidatorFactory().use { factory ->
                for (count in listOf(0,1,2,3,4,5,12)) {
                    val violations = factory.validator.validate(ManagedAnalyzeRequest((1..count).map { it.toLong() }))
                    assertEquals(count != 4, violations.isNotEmpty(), "count=$count")
                }
            }
    }
}
