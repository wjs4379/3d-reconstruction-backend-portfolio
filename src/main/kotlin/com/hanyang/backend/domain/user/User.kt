package com.hanyang.backend.domain.user

import jakarta.persistence.* //JPA 관련 어노테이션 사용
import java.time.LocalDateTime //날짜와 시간을 표현하기 위한 클래스

@Entity //DB에 저장되는 클래스다.
@Table(name = "users", uniqueConstraints = [UniqueConstraint(name = "uk_user_social_identity", columnNames = ["provider", "provider_id"])]) //DB와 연결시켜주는데 DB는 테이블명이 복수형을 자주 씀. 그래서 users
class User(
    @Id //Primary Key. 거의 필수
    @GeneratedValue(strategy = GenerationType.IDENTITY)//DB가 ID 값을 자동생성. 항상 필수는 아니고 ID 자동생성할 때만 붙인다.
    @Column(name = "user_id") //이름 명시
    val id: Long? = null, //기본값은 null이고 ?가 붙으면 null이 가능함. val붙으면 값 안 바뀜
                            // 객체 처음 만들 때는 db가 id를 발급하기 전이라 null일 수 밖에 없으니 Long?으로. DB에 저장 전에 잠깐동안은NULL

    @Column(nullable = false) //null이 되면 안된다.
    var email: String, //var은 나중에 변경이 가능하다.

    @Column(nullable = false)
    var name: String,

    @Column(nullable = false)
    var provider: String, // KAKAO, GOOGLE

    @Column(name = "provider_id", nullable = false)
    var providerId: String,

    @Column(nullable = false)
    var role: String, // HOST, GUEST, ADMIN

    @Column(name = "created_at", nullable = false)
    var createdAt: LocalDateTime = LocalDateTime.now()
)