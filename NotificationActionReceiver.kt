package com.example.giftguard

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log

class NotificationActionReceiver : BroadcastReceiver() {

    private val TAG = "NotifReceiver"

    companion object {
        const val ACTION_CONFIRM_YES = "ACTION_CONFIRM_YES"
        const val ACTION_CONFIRM_NO = "ACTION_CONFIRM_NO"
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return

        val action = intent.action

        // 🚨 1. URI를 Intent Data 필드에서 가져옵니다. (ImageObserverService에서 data = uri로 설정했기 때문)
        val imageUri: Uri? = intent.data

        // Extra는 백업/로그 용도로만 사용 (imageUri가 null일 경우)
        val imageUriString = intent.getStringExtra("EXTRA_IMAGE_URI")
        val notificationId = intent.getIntExtra("EXTRA_NOTIFICATION_ID", -1)

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // imageUri (Data 필드)가 유효한지 확인합니다.
        if (action == ACTION_CONFIRM_YES && imageUri != null) {
            Log.d(TAG, "YES 요청 수신됨. OCR 실행 및 자동 저장 시작. URI: $imageUri")
            manager.cancel(notificationId)

            val serviceIntent = Intent(context, ImageObserverService::class.java).apply {
                setAction(ImageObserverService.ACTION_RUN_OCR)

                // 🚨 2. Service Intent에 URI를 Data 필드로 설정합니다.
                data = imageUri

                // 🚨 3. Receiver가 가진 임시 권한을 Service로 전달하는 플래그를 추가합니다.
                // 이 플래그가 Service 시작 시 RemoteException을 유발했지만,
                // data 필드를 사용함으로써 시스템이 권한 전달을 다르게 처리하도록 유도합니다.
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

                // Extra URI String은 혹시 모를 경우를 대비해 유지
                putExtra("EXTRA_IMAGE_URI", imageUriString)
            }

            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
                Log.d(TAG, "ImageObserverService에 OCR 실행 요청 및 URI 권한 전달 시도 완료.")
            } catch (e: SecurityException) {
                // Service 시작 시 SecurityException 발생 시 로그 기록
                Log.e(TAG, "Service 시작 중 SecurityException 발생: ${e.message}. 권한 전달 실패.")
            }


        } else if (action == ACTION_CONFIRM_NO) {
            if (notificationId != -1) {
                manager.cancel(notificationId)
            }
            Log.d(TAG, "사용자가 OCR 자동 저장을 취소했습니다.")
        }
    }
}