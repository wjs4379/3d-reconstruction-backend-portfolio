package com.hanyang.backend

import org.springframework.web.bind.annotation.*

@CrossOrigin(origins = ["*"])
@RestController
@RequestMapping("/api/test")
class TestController {
    @GetMapping("/hello")
    fun hello() = "Hello, Seong-min! Sprint is working!"
}
