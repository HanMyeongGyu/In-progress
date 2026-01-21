package com.example.giftguard

// 서버 응답 데이터를 담을 데이터 클래스 (DTO)
// 백엔드에서 주는 JSON 필드 이름과 동일하게 작성해야 합니다.
data class User(
    // 예시 필드입니다. 실제 백엔드 응답에 맞게 수정하세요.
    val id: Int,
    val name: String,
    val email: String
)