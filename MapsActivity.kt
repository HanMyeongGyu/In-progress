package com.example.giftguard

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.giftguard.databinding.ActivityMapsBinding
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingClient
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.GeofenceStatusCodes
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response


class MapsActivity : AppCompatActivity(), OnMapReadyCallback {

    private lateinit var map: GoogleMap
    private lateinit var binding: ActivityMapsBinding

    private lateinit var geofencingClient: GeofencingClient

    private val geofencePendingIntent: PendingIntent by lazy { createGeofencePendingIntent() }

    private val TAG = "MapsActivity"

    private val GEOFENCE_ID = "Seoul_City_Hall"
    private val GEOFENCE_LATLNG = LatLng(37.5665, 126.9780)
    private val GEOFENCE_RADIUS_IN_METERS = 100f
    private val GEOFENCE_REQUEST_CODE = 2609

    // 🌟 알림 권한 요청 런처 추가
    private val requestNotificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            Log.d(TAG, "알림 권한 허용됨.")
        } else {
            Log.w(TAG, "알림 권한 거부됨. 새로운 기프티콘 감지 알림을 받을 수 없습니다.")
            Toast.makeText(this, "알림 권한이 없어 기프티콘 자동 감지 알림을 받을 수 없습니다.", Toast.LENGTH_LONG).show()
        }
    }

    private val requestLocationPerms = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val fineLocationGranted = result[Manifest.permission.ACCESS_FINE_LOCATION] == true
        val backgroundLocationGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            result[Manifest.permission.ACCESS_BACKGROUND_LOCATION] == true
        } else true

        if (fineLocationGranted && backgroundLocationGranted) {
            Log.d(TAG, "모든 위치 권한 허용됨. 맵에 내 위치 표시 및 지오펜스 등록 시작.")
            activateMyLocationAndGeofence()
        } else {
            Toast.makeText(this, "⚠️ 지오펜싱을 위해 '항상 허용' 권한이 필요합니다. 설정에서 변경해주세요.", Toast.LENGTH_LONG).show()

            val intent = Intent(
                android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", packageName, null)
            )
            intent.addCategory(Intent.CATEGORY_DEFAULT)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            startActivity(intent)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMapsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 🌟 onCreate에서 알림 권한 확인 및 요청
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        geofencingClient = LocationServices.getGeofencingClient(this)

        binding.ocrButton.setOnClickListener {
            val intent = Intent(this, OcrActivity::class.java)
            startActivity(intent)
        }

        binding.apiTestButton.setOnClickListener {
            Log.d(TAG, "API Test Button Clicked. Starting simple check.")
            fetchSimpleCheck()
        }

        // ImageObserverService 시작
        startService(Intent(this, ImageObserverService::class.java))

        val mapFragment = supportFragmentManager
            .findFragmentById(R.id.map) as SupportMapFragment
        mapFragment.getMapAsync(this)
    }

    override fun onMapReady(googleMap: GoogleMap) {
        map = googleMap

        map.uiSettings.isZoomControlsEnabled = true
        map.uiSettings.isCompassEnabled = true
        map.uiSettings.isMapToolbarEnabled = true

        map.addMarker(
            MarkerOptions()
                .position(GEOFENCE_LATLNG)
                .title("지오펜스 중심: 서울시청")
        )
        map.moveCamera(CameraUpdateFactory.newLatLngZoom(GEOFENCE_LATLNG, 14f))

        checkLocationPermissions()
    }

    // =========================================================================
    // Retrofit GET 요청 함수
    // =========================================================================
    private fun fetchSimpleCheck() {
        Log.d(TAG, "Retrofit Simple Check 요청 시작 (Target: ${RetrofitClient.BASE_URL})")

        RetrofitClient.apiService.getSimpleCheck().enqueue(object : Callback<String> {

            override fun onResponse(call: Call<String>, response: Response<String>) {
                if (response.isSuccessful) {
                    val messageBody = response.body() ?: "응답 본문 없음"
                    Log.d(TAG, "✅ 통신 성공! (HTTP Code: ${response.code()}, Body: $messageBody)")
                    Toast.makeText(this@MapsActivity, "서버 응답: $messageBody", Toast.LENGTH_LONG).show()
                } else {
                    val errorBody = response.errorBody()?.string() ?: "알 수 없음"
                    Log.e(TAG, "❌ 통신 실패: HTTP Code ${response.code()}, Error: $errorBody")
                    Toast.makeText(this@MapsActivity, "통신 실패: Code ${response.code()}", Toast.LENGTH_LONG).show()
                }
            }

            override fun onFailure(call: Call<String>, t: Throwable) {
                val errorMessage = "❌ 네트워크 오류: ${t.message}"
                Log.e(TAG, errorMessage)
                Toast.makeText(this@MapsActivity, "네트워크 오류 발생: ${t.message}", Toast.LENGTH_LONG).show()
            }
        })
    }
    // =========================================================================

    @SuppressLint("MissingPermission")
    private fun activateMyLocationAndGeofence() {
        val fineGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

        if (::map.isInitialized && fineGranted) {
            map.isMyLocationEnabled = true
            addGeofence()
        } else {
            Log.e(TAG, "Map이 초기화되지 않았거나 위치 권한이 부족하여 내 위치를 활성화할 수 없습니다.")
        }
    }

    private fun checkLocationPermissions() {
        val fineGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val backgroundRequired = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        val backgroundGranted = if (backgroundRequired) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
        } else true

        if (fineGranted && backgroundGranted) {
            activateMyLocationAndGeofence()
        } else {
            val permissionsToRequest = mutableListOf<String>()
            if (!fineGranted) permissionsToRequest.add(Manifest.permission.ACCESS_FINE_LOCATION)
            if (backgroundRequired && !backgroundGranted) permissionsToRequest.add(Manifest.permission.ACCESS_BACKGROUND_LOCATION)

            requestLocationPerms.launch(permissionsToRequest.toTypedArray())
        }
    }

    private fun createGeofencePendingIntent(): PendingIntent {
        val intent = Intent(this, GeofenceBroadcastReceiver::class.java)
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        return PendingIntent.getBroadcast(this, GEOFENCE_REQUEST_CODE, intent, flags)
    }

    private fun getErrorString(errorCode: Int): String {
        return when (errorCode) {
            GeofenceStatusCodes.GEOFENCE_NOT_AVAILABLE -> "서비스를 사용할 수 없습니다."
            GeofenceStatusCodes.GEOFENCE_TOO_MANY_GEOFENCES -> "등록된 지오펜스가 너무 많습니다."
            GeofenceStatusCodes.GEOFENCE_TOO_MANY_PENDING_INTENTS -> "PendingIntent가 너무 많습니다."
            GeofenceStatusCodes.ERROR -> "일반 Geofence 오류 발생."
            -1 -> "Unknown Error: Google Play Services 불안정."
            else -> "알 수 없는 오류 코드: $errorCode"
        }
    }

    private fun createGeofenceRequest(geofence: Geofence): GeofencingRequest {
        return GeofencingRequest.Builder().apply {
            setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_ENTER)
            addGeofence(geofence)
        }.build()
    }

    @SuppressLint("MissingPermission")
    private fun addGeofence() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "addGeofence 호출 시점에 FINE_LOCATION 권한이 없습니다. 등록 실패 예상.")
            return
        }

        val geofence = Geofence.Builder()
            .setRequestId(GEOFENCE_ID)
            .setCircularRegion(
                GEOFENCE_LATLNG.latitude,
                GEOFENCE_LATLNG.longitude,
                GEOFENCE_RADIUS_IN_METERS
            )
            .setExpirationDuration(Geofence.NEVER_EXPIRE)
            .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER or Geofence.GEOFENCE_TRANSITION_EXIT)
            .build()

        geofencingClient.addGeofences(createGeofenceRequest(geofence), geofencePendingIntent)
            .addOnSuccessListener {
                Toast.makeText(this, "✅ 지오펜스 등록 성공: ${GEOFENCE_ID}", Toast.LENGTH_SHORT).show()
                Log.d(TAG, "Geofence added successfully.")
            }
            .addOnFailureListener { e ->
                val statusCode = if (e is ApiException) {
                    e.statusCode
                } else {
                    -1
                }

                val errorMessage = getErrorString(statusCode)
                Toast.makeText(this, "❌ 지오펜스 등록 실패: $errorMessage", Toast.LENGTH_LONG).show()
                Log.e(TAG, "Geofence registration failed: $errorMessage (Code: $statusCode)")
            }
    }
}