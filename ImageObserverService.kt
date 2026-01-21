package com.example.giftguard

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.provider.MediaStore
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import kotlinx.coroutines.*
import java.util.Calendar

class ImageObserverService : Service() {

    private val TAG = "ImageObserverService"
    private lateinit var notificationManager: NotificationManager

    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)

    private val NOTIFICATION_ID = 1001
    private val CHANNEL_ID = "GifticonOCRChannel"
    private val CHANNEL_NAME = "기프티콘 자동 인식"

    private val CONTENT_URI: Uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
    private lateinit var db: GifticonDbHelper

    companion object {
        const val ACTION_RUN_OCR = "ACTION_RUN_OCR"
        const val ACTION_CONFIRM_NO = "ACTION_CONFIRM_NO"

        // OcrActivity.kt에서 가져온 상수들
        private val CAFE_MENU = listOf(
            "아메리카노","에스프레소","라떼","카페라떼","바닐라라떼","카푸치노","콜드브루",
            "헤이즐넛","카라멜마키아토","카페모카","화이트모카","돌체라떼","샷","디카페인",
            "아이스아메리카노","아이스라떼","아이스바닐라라떼","아이스모카","아이스콜드브루","아이스티",
            "그린티","블랙티","얼그레이","캐모마일","유자차","자몽","레몬에이드","복숭아아이스티","초코","초콜릿",
            "스콘","케이크","마카롱","쿠키"
        )
        private val BRANDS = listOf(
            "스타벅스","이디야","투썸","할리스","폴바셋","파스쿠찌","메가커피",
            "배스킨라빈스","던킨","파리바게뜨","뚜레쥬르","버거킹","맥도날드",
            "CU","GS25","세븐일레븐","미니스톱"
        )
        private val QUANTITY_WORDS = listOf("수량","매수","개","수량:", "QTY", "Qty", "qty")
        private val LABEL_WORDS = listOf("상품명","제품명","메뉴명","상품","Item","ITEM","Product","PRODUCT")
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "ImageObserverService started.")

        db = GifticonDbHelper(this) // DB 헬퍼 초기화
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel()
        startAsForeground()

        // 최신 이미지 감지 ContentObserver 등록
        contentResolver.registerContentObserver(
            CONTENT_URI,
            true,
            object : android.database.ContentObserver(Handler(mainLooper)) {
                override fun onChange(selfChange: Boolean, uri: Uri?) {
                    super.onChange(selfChange, uri)
                    if (uri == null) return
                    Log.d(TAG, "새로운 이미지 감지됨: $uri")
                    Handler(mainLooper).postDelayed({
                        sendConfirmationNotification(uri)
                    }, 1000)
                }
            }
        )
    }

    private fun startAsForeground() {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("기프티콘 감지 서비스 실행 중")
            .setContentText("갤러리 이미지 변경을 감시하고 있습니다.")
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .build()

        startForeground(NOTIFICATION_ID, notification)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        val uriFromData: Uri? = intent?.data
        val uriFromExtra = intent?.getStringExtra("EXTRA_IMAGE_URI")?.let { Uri.parse(it) }
        val targetUri = uriFromData ?: uriFromExtra

        when (action) {
            ACTION_RUN_OCR -> {
                if (targetUri == null) {
                    Log.e(TAG, "ACTION_RUN_OCR 수신했지만 URI가 없음")
                    return START_NOT_STICKY
                }
                Log.d(TAG, "OCR 요청 수신됨. URI: $targetUri")
                serviceScope.launch { runOcrAndSave(targetUri) }
            }
            ACTION_CONFIRM_NO -> {
                val notificationId = intent?.getIntExtra("EXTRA_NOTIFICATION_ID", -1) ?: -1
                if (notificationId != -1) notificationManager.cancel(notificationId)
                Log.d(TAG, "사용자가 OCR 자동 저장을 취소했습니다. 알림 ID $notificationId 닫음.")
            }
        }

        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceJob.cancel()
        Log.d(TAG, "ImageObserverService stopped.")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH
            )
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun sendConfirmationNotification(uri: Uri) {
        val confirmationNotifId = NOTIFICATION_ID + 2

        val yesIntent = Intent(this, ImageObserverService::class.java).apply {
            action = ACTION_RUN_OCR
            data = uri
            clipData = ClipData.newUri(contentResolver, "image", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            putExtra("EXTRA_IMAGE_URI", uri.toString())
            putExtra("EXTRA_NOTIFICATION_ID", confirmationNotifId)
        }

        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }

        val yesPendingIntent = PendingIntent.getService(
            this, uri.hashCode(), yesIntent, flags
        )

        val noIntent = Intent(this, ImageObserverService::class.java).apply {
            action = ACTION_CONFIRM_NO
            putExtra("EXTRA_NOTIFICATION_ID", confirmationNotifId)
        }

        val noPendingIntent = PendingIntent.getService(
            this, confirmationNotifId + 1, noIntent, flags
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("새 사진 감지: 기프티콘인가요?")
            .setContentText("✅ YES를 누르면 백그라운드에서 OCR을 실행하고 자동 저장합니다.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .addAction(R.drawable.ic_launcher_foreground, "✅ YES (자동 저장)", yesPendingIntent)
            .addAction(R.drawable.ic_launcher_foreground, "❌ NO (취소)", noPendingIntent)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(confirmationNotifId, notification)
    }

    /**
     * OcrActivity의 핵심 추출 로직과 DB 저장 로직을 통합하여 백그라운드에서 실행합니다.
     */
    private suspend fun runOcrAndSave(uri: Uri) {
        Log.i(TAG, "OCR 처리 시작: $uri")
        try {
            val image = withContext(Dispatchers.IO) {
                InputImage.fromFilePath(this@ImageObserverService, uri)
            }

            val recognizer = TextRecognition.getClient(
                KoreanTextRecognizerOptions.Builder().build()
            )

            recognizer.process(image)
                .addOnSuccessListener { visionText ->
                    val recognizedText = visionText.text
                    Log.d(TAG, "OCR 성공. 인식 일부: ${recognizedText.take(100)}")

                    if (recognizedText.isBlank()) {
                        showResultNotification("자동 저장 실패", "이미지에서 텍스트를 찾지 못했어요.")
                        return@addOnSuccessListener
                    }

                    // 🌟 OcrActivity의 추출 로직 사용
                    val menuName = extractMenuName(recognizedText)
                    val merchant = extractMerchant(recognizedText)
                    val expiryRaw = extractExpiryDate(recognizedText)
                    val expiryYmd = toYmd(expiryRaw) ?: ""

                    if (menuName.isBlank() || merchant.isBlank() || !isValidYmd(expiryYmd)) {
                        showResultNotification("자동 저장 실패", "필수 정보(메뉴, 사용처, 유효기간) 추출 실패.")
                        return@addOnSuccessListener
                    }

                    // 🌟 DB 저장 로직 실행 (OcrActivity의 saveLite와 동일 기능)
                    val ok = db.insertGifticonLite(
                        menuName,
                        merchant,
                        expiryYmd,
                        uri.toString(),
                        code = extractGiftCode(recognizedText),
                        memo = "자동 인식 저장"
                    )

                    if (ok) {
                        showResultNotification("✅ 자동 저장 완료", "$menuName ($merchant) 기프티콘 저장 완료.")
                    } else {
                        showResultNotification("❌ 자동 저장 실패", "DB에 저장할 수 없습니다. (중복 또는 DB 오류)")
                    }
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "ML Kit OCR 실패: ${e.message}", e)
                    showResultNotification("자동 저장 실패", "텍스트 인식 중 오류: ${e.message}")
                }
        } catch (e: Exception) {
            Log.e(TAG, "이미지 접근/처리 실패: ${e.message}", e)
            showResultNotification("자동 저장 실패", "이미지를 열 수 없습니다. (권한/경로)")
        }
    }

    private fun extractGiftCode(text: String): String {
        val regex = Regex("(\\w{4}[-\\s]?){2}\\w{4}")
        return regex.find(text)?.value?.replace("\\s".toRegex(), "") ?: "코드 추출 실패"
    }

    private fun showResultNotification(title: String, content: String) {
        val resultNotification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(content)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(NOTIFICATION_ID + 3, resultNotification)
    }

    // ===== OcrActivity에서 가져온 추출/검증 유틸리티 함수들 =====

    private fun extractExpiryDate(original: String): String {
        val norm = normalizeOcrNoise(original)
        val lines = norm.lines().map { it.trim() }.filter { it.isNotBlank() }
        val keywordLines = lines.filter {
            it.contains("유효기간") || it.contains("만료") || it.contains("까지") ||
                    it.contains("사용기한") || it.contains("교환기한") ||
                    it.contains("valid", true) || it.contains("expire", true)
        }
        val pools = (keywordLines + norm).distinct()

        fun rightOfRange(s: String): String {
            val parts = s.split('~','〜','–','—').map { it.trim() }
            return if (parts.size >= 2) parts.last() else s
        }

        val patterns = listOf(
            Regex("""(20\d{2})\s*년\s*(1[0-2]|0?[1-9])\s*월\s*(3[01]|[12]?\d)\s*일?(\s*\([^)]+\))?(\s*\d{1,2}:\d{2})?\s*(까지|만료)?"""),
            Regex("""(20\d{2})[.\-/](1[0-2]|0?[1-9])[.\-/](3[01]|[12]?\d)"""),
            Regex("""(2\d)[.\-/](1[0-2]|0?[1-9])[.\-/](3[01]|[12]?\d)"""),
            Regex("""\b((20\d{2})(1[0-2]|0[1-9])(3[01]|[12]\d))\b"""), // YYYYMMDD
            Regex("""\b((\d{2})(1[0-2]|0[1-9])(3[01]|[12]\d))\b"""),   // YYMMDD
            Regex("""\b(1[0-2]|0?[1-9])[.\-/](3[01]|[12]?\d)\b""")      // MM-DD
        )

        val allCandidates = mutableListOf<String>()
        for (pool in pools) {
            val target = rightOfRange(pool)
            for (p in patterns) {
                p.findAll(target).forEach { m ->
                    toYmd(m.value)?.let { ymd ->
                        if (isValidYmd(ymd)) allCandidates.add(ymd)
                    }
                }
            }
        }
        val max = allCandidates.maxByOrNull { it }
        return max ?: ""
    }

    private fun normalizeOcrNoise(s: String): String {
        return s
            .replace('–', '-')
            .replace('—', '-')
            .map { ch ->
                when (ch) {
                    'l', 'I' -> '1'
                    'O' -> '0'
                    else -> ch
                }
            }.joinToString("")
    }

    private fun toYmd(raw: String): String? {
        val nums = Regex("""\d+""").findAll(raw).map { it.value }.toList()
        if (nums.size >= 3 && nums[0].length == 4) {
            val y = nums[0].toIntOrNull() ?: return null
            val m = nums[1].toIntOrNull() ?: return null
            val d = nums[2].toIntOrNull() ?: return null
            return "%04d-%02d-%02d".format(y, m, d)
        }
        if (nums.size >= 3 && nums[0].length == 2) {
            val y = 2000 + (nums[0].toIntOrNull() ?: return null)
            val m = nums[1].toIntOrNull() ?: return null
            val d = nums[2].toIntOrNull() ?: return null
            return "%04d-%02d-%02d".format(y, m, d)
        }
        if (nums.size == 1) {
            val n = nums[0]
            if (n.length == 8) {
                val y = n.substring(0, 4).toIntOrNull() ?: return null
                val m = n.substring(4, 6).toIntOrNull() ?: return null
                val d = n.substring(6, 8).toIntOrNull() ?: return null
                return "%04d-%02d-%02d".format(y, m, d)
            } else if (n.length == 6) {
                val y = 2000 + (n.substring(0, 2).toIntOrNull() ?: return null)
                val m = n.substring(2, 4).toIntOrNull() ?: return null
                val d = n.substring(4, 6).toIntOrNull() ?: return null
                return "%04d-%02d-%02d".format(y, m, d)
            }
        }
        if (nums.size == 2) {
            val m = nums[0].toIntOrNull() ?: return null
            val d = nums[1].toIntOrNull() ?: return null
            val (year, mm, dd) = inferYear(m, d) ?: return null
            return "%04d-%02d-%02d".format(year, mm, dd)
        }
        return null
    }

    private fun inferYear(m: Int, d: Int): Triple<Int, Int, Int>? {
        if (m !in 1..12 || d !in 1..31) return null
        val cal = Calendar.getInstance()
        val yNow = cal.get(Calendar.YEAR)
        val mNow = cal.get(Calendar.MONTH) + 1
        val dNow = cal.get(Calendar.DAY_OF_MONTH)
        val thisKey = yNow * 10000 + m * 100 + d
        val todayKey = yNow * 10000 + mNow * 100 + dNow
        val year = if (thisKey < todayKey) yNow + 1 else yNow
        return Triple(year, m, d)
    }

    private fun isValidYmd(ymd: String): Boolean {
        val m = Regex("""^(20\d{2})-(0[1-9]|1[0-2])-(0[1-9]|[12]\d|3[01])$""").matchEntire(ymd) ?: return false
        val y = m.groupValues[1].toInt()
        val mo = m.groupValues[2].toInt()
        val d = m.groupValues[3].toInt()
        val maxDay = when (mo) {
            1,3,5,7,8,10,12 -> 31
            4,6,9,11 -> 30
            2 -> if (isLeap(y)) 29 else 28
            else -> return false
        }
        return d in 1..maxDay
    }
    private fun isLeap(y: Int) = (y % 4 == 0 && y % 100 != 0) || (y % 400 == 0)

    private fun extractMerchant(text: String): String {
        val lines = text.lines()
        for (b in BRANDS) {
            lines.firstOrNull { it.contains(b, ignoreCase = true) }?.let { return b }
        }
        return ""
    }

    private fun extractMenuName(text: String): String {
        val linesRaw = text.lines().map { it.trim() }.filter { it.isNotBlank() }
        val lines = linesRaw.map { normalizeLabelNoise(it) }

        fun isQuantityLine(s: String): Boolean {
            if (QUANTITY_WORDS.any { s.contains(it, ignoreCase = true) }) return true
            if (Regex("""\b(\d+)\s*개\b""").containsMatchIn(s)) return true
            if (Regex("""\bx\s*\d+\b""", RegexOption.IGNORE_CASE).containsMatchIn(s)) return true
            return false
        }

        fun looksBad(s: String): Boolean {
            if (s.isBlank()) return true
            if (isQuantityLine(s)) return true
            if (LABEL_WORDS.any { s.startsWith(it, ignoreCase = true) }) return true
            if (s.length !in 2..40) return true
            if (Regex("""\d{8,}""").containsMatchIn(s)) return true
            if (Regex("""[₩\\]?\s?\d{2,3}(,\d{3})*\s*(원|KRW)?""").containsMatchIn(s)) return true
            if (Regex("""\b(옵션|사이즈|HOT|ICE|L|R|Tall|Grande|Venti)\b""", RegexOption.IGNORE_CASE).containsMatchIn(s)) return true
            val black = listOf("유효기간","까지","만료","사용처","안내","고객센터","교환","코드","바코드","포인트","결제","주문")
            if (black.any { s.contains(it, ignoreCase = true) }) return true
            return false
        }

        fun clean(s: String): String {
            var t = s
            t = t.replace(Regex("""^(상품명|제품명|메뉴명|상품|Item|ITEM|Product|PRODUCT)\s*[:：\-]?\s*"""), "")
            t = t.replace(Regex("""\([^)]*\)"""), "")
            t = t.replace(Regex("""\[[^\]]*]"""), "")
            t = t.replace(Regex("""\s{2,}"""), " ")
            return t.trim().trim('-','•','·',':','：')
        }

        val labelRegex = Regex("""^(상품명|제품명|메뉴명|상품|Item|ITEM|Product|PRODUCT)\s*[:：\-]?\s*(.*)$""")
        for (i in lines.indices) {
            val m = labelRegex.find(lines[i]) ?: continue
            val after = m.groupValues.getOrNull(2)?.trim().orEmpty()
            if (after.isNotBlank()) {
                val v = clean(after)
                if (!looksBad(v) && containsCafeWord(v)) return v
            }
            if (i + 1 < lines.size) {
                val next = clean(lines[i + 1])
                if (!looksBad(next) && containsCafeWord(next)) return next
            }
        }

        val brandIdx = lines.indexOfFirst { line -> BRANDS.any { b -> line.contains(b, ignoreCase = true) } }
        if (brandIdx >= 0) {
            for (i in brandIdx + 1 until minOf(brandIdx + 4, lines.size)) {
                val v = clean(lines[i])
                if (!looksBad(v) && containsCafeWord(v)) return v
            }
        }

        lines.map(::clean).firstOrNull { !looksBad(it) && containsCafeWord(it) }?.let { return it }
        lines.map(::clean).firstOrNull { !looksBad(it) }?.let { return it }

        return ""
    }

    private fun containsCafeWord(s: String): Boolean =
        CAFE_MENU.any { kw -> s.contains(kw, ignoreCase = true) }

    private fun normalizeLabelNoise(s: String): String {
        return s
            .replace('：', ':')
            .replace("I ", ": ")
            .replace("l ", ": ")
            .trim()
    }
}