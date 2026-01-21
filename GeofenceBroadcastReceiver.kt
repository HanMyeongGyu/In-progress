package com.example.giftguard

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofenceStatusCodes
import com.google.android.gms.location.GeofencingEvent

class GeofenceBroadcastReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "GeofenceReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val geofencingEvent = GeofencingEvent.fromIntent(intent)

        if (geofencingEvent == null) {
            Log.e(TAG, "GeofencingEvent is null.")
            return
        }

        if (geofencingEvent.hasError()) {
            val errorCode = geofencingEvent.errorCode
            val errorString = GeofenceStatusCodes.getStatusCodeString(errorCode)
            Log.e(TAG, "Geofencing Error: $errorString (Code: $errorCode)")
            Toast.makeText(context, "Geofencing Error: $errorString", Toast.LENGTH_LONG).show()
            return
        }

        // 지오펜스 전환 유형 가져오기
        val geofenceTransition = geofencingEvent.geofenceTransition

        // 이벤트 유형 확인
        when (geofenceTransition) {
            Geofence.GEOFENCE_TRANSITION_ENTER -> {
                Log.d(TAG, "지오펜스 진입 (ENTER)")
                Toast.makeText(context, "지오펜스 진입!", Toast.LENGTH_SHORT).show()
            }
            Geofence.GEOFENCE_TRANSITION_EXIT -> {
                Log.d(TAG, "지오펜스 이탈 (EXIT)")
                Toast.makeText(context, "지오펜스 이탈!", Toast.LENGTH_SHORT).show()
            }
            Geofence.GEOFENCE_TRANSITION_DWELL -> {
                Log.d(TAG, "지오펜스 체류 (DWELL)")
                Toast.makeText(context, "지오펜스 체류 중!", Toast.LENGTH_SHORT).show()
            }
            else -> {
                Log.e(TAG, "알 수 없는 지오펜스 전환 유형: $geofenceTransition")
            }
        }
    }
}