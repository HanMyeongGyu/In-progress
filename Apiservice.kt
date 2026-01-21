package com.example.giftguard

import retrofit2.Call
import retrofit2.http.GET

// API 통신을 정의할 인터페이스
interface ApiService {

    // 루트 경로("/")로 GET 요청, 응답 본문을 문자열(String)로 받습니다.
    @GET("/")
    fun getSimpleCheck(): Call<String>
}