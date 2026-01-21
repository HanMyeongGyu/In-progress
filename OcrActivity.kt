package com.example.giftguard

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.giftguard.databinding.ActivityOcrBinding
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import kotlinx.coroutines.*

class OcrActivity : AppCompatActivity() {

    private lateinit var binding: ActivityOcrBinding
    private var pickedUri: Uri? = null
    private var lastRecognizedText: String = ""

    // 목록
    private lateinit var recycler: RecyclerView
    private val items = mutableListOf<Gifticon>()
    private lateinit var db: GifticonDbHelper
    private var listVisible = false

    // 카페 메뉴 키워드(우선 추출 대상)
    private val CAFE_MENU = listOf(
        // 커피류
        "아메리카노","에스프레소","라떼","카페라떼","바닐라라떼","카푸치노","콜드브루",
        "헤이즐넛","카라멜마키아토","카페모카","화이트모카","돌체라떼","샷","디카페인",
        // 아이스/핫 표기
        "아이스아메리카노","아이스라떼","아이스바닐라라떼","아이스모카","아이스콜드브루","아이스티",
        // 티/음료
        "그린티","블랙티","얼그레이","캐모마일","유자차","자몽","레몬에이드","복숭아아이스티","초코","초콜릿",
        // 디저트/기타
        "스콘","케이크","마카롱","쿠키"
    )

    // 브랜드 후보
    private val BRANDS = listOf(
        "스타벅스","이디야","투썸","할리스","폴바셋","파스쿠찌","메가커피",
        "배스킨라빈스","던킨","파리바게뜨","뚜레쥬르","버거킹","맥도날드",
        "CU","GS25","세븐일레븐","미니스톱"
    )

    // 수량/라벨 제외 키워드
    private val QUANTITY_WORDS = listOf("수량","매수","개","수량:", "QTY", "Qty", "qty")
    private val LABEL_WORDS = listOf("상품명","제품명","메뉴명","상품","Item","ITEM","Product","PRODUCT")

    // 사진 선택 런처
    private val pickImage = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            pickedUri = uri
            binding.imagePreview.setImageURI(uri)
            binding.tvResult.text = ""
            lastRecognizedText = ""
            setActionButtonsEnabled(false)
        } else {
            Toast.makeText(this, "이미지를 선택하지 않았어", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityOcrBinding.inflate(layoutInflater)
        setContentView(binding.root)

        db = GifticonDbHelper(this)
        recycler = binding.recyclerGifticons
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.addItemDecoration(DividerItemDecoration(this, DividerItemDecoration.VERTICAL))
        recycler.adapter = GifticonAdapter(items) { item -> requestDelete(item) }

        setActionButtonsEnabled(false)

        binding.btnPick.setOnClickListener {
            pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }

        binding.btnRecognize.setOnClickListener {
            val uri = pickedUri ?: run {
                Toast.makeText(this, "먼저 이미지를 선택해줘", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            runOcr(uri)
        }

        binding.btnCopy.setOnClickListener {
            val text = binding.tvResult.text?.toString().orEmpty()
            if (text.isBlank()) {
                Toast.makeText(this, "복사할 텍스트가 없어", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val cm = getSystemService(ClipboardManager::class.java)
            cm.setPrimaryClip(ClipData.newPlainText("OCR", text))
            Toast.makeText(this, "복사했어", Toast.LENGTH_SHORT).show()
        }

        binding.btnSaveOcr.setOnClickListener {
            val uri = pickedUri ?: run {
                Toast.makeText(this, "이미지를 다시 선택해줘", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (lastRecognizedText.isBlank()) {
                Toast.makeText(this, "먼저 인식해줘", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val text = lastRecognizedText
            showSaveLiteDialog(
                imageUri = uri,
                menuInit = extractMenuName(text),
                merchantInit = extractMerchant(text),
                expiryInit = extractExpiryDate(text)
            )
        }

        binding.btnViewSaved.setOnClickListener {
            if (listVisible) {
                recycler.visibility = View.GONE
                binding.btnViewSaved.text = "저장된 기프티콘 목록 보기"
            } else {
                loadGifticons()
                recycler.visibility = View.VISIBLE
                binding.btnViewSaved.text = "목록 숨기기"
            }
            listVisible = !listVisible
        }
    }

    private fun setActionButtonsEnabled(enabled: Boolean) {
        binding.btnSaveOcr.isEnabled = enabled
        binding.btnCopy.isEnabled = enabled
    }

    // ===== OCR =====
    private fun runOcr(uri: Uri) {
        try {
            val image = InputImage.fromFilePath(this, uri)
            val recognizer = TextRecognition.getClient(
                KoreanTextRecognizerOptions.Builder().build()
            )
            recognizer.process(image)
                .addOnSuccessListener { visionText ->
                    lastRecognizedText = visionText.text.orEmpty()
                    binding.tvResult.text = lastRecognizedText
                    setActionButtonsEnabled(lastRecognizedText.isNotBlank())
                }
                .addOnFailureListener { e ->
                    lastRecognizedText = ""
                    binding.tvResult.text = ""
                    setActionButtonsEnabled(false)
                    Toast.makeText(this, "인식 실패: ${e.message}", Toast.LENGTH_SHORT).show()
                }
        } catch (e: Exception) {
            Toast.makeText(this, "이미지 로드 실패: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    // ===== 저장 다이얼로그 =====
    private fun showSaveLiteDialog(imageUri: Uri, menuInit: String, merchantInit: String, expiryInit: String) {
        val etMenu = EditText(this).apply { hint = "메뉴 이름"; setText(menuInit) }
        val etMerchant = EditText(this).apply { hint = "사용처(브랜드/매장)"; setText(merchantInit) }
        val etExpiry = EditText(this).apply {
            hint = "유효기간 (YYYY-MM-DD)"
            setText(expiryInit)
            inputType = InputType.TYPE_CLASS_DATETIME or InputType.TYPE_DATETIME_VARIATION_DATE
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), 0)
            addView(etMenu)
            addView(etMerchant)
            addView(etExpiry)
        }

        AlertDialog.Builder(this)
            .setTitle("기프티콘으로 저장")
            .setMessage("메뉴/사용처/유효기간을 확인해줘")
            .setView(container)
            .setPositiveButton("저장") { d, _ ->
                val menu = etMenu.text?.toString()?.trim().orEmpty()
                val merchant = etMerchant.text?.toString()?.trim().orEmpty()
                val expiryRaw = etExpiry.text?.toString()?.trim().orEmpty()
                val expiryYmd = toYmd(expiryRaw) ?: ""

                if (menu.isBlank() || merchant.isBlank() || !isValidYmd(expiryYmd)) {
                    Toast.makeText(this, "입력을 확인해줘 (유효기간 예: 2025-12-31)", Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                saveLite(menu, merchant, expiryYmd, imageUri.toString(), null, null)
                d.dismiss()
            }
            .setNegativeButton("취소", null)
            .show()
    }

    // ===== DB 저장/조회/삭제 =====
    private fun saveLite(menu: String, merchant: String, expiry: String, imageUri: String, code: String?, memo: String?) {
        CoroutineScope(Dispatchers.IO).launch {
            val ok = db.insertGifticonLite(menu, merchant, expiry, imageUri, code, memo)
            withContext(Dispatchers.Main) {
                if (ok) {
                    Toast.makeText(this@OcrActivity, "저장 완료!", Toast.LENGTH_SHORT).show()
                    if (listVisible) loadGifticons()
                } else {
                    Toast.makeText(this@OcrActivity, "저장 실패(중복 또는 DB 오류)", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun loadGifticons() {
        items.clear()
        val c = db.readableDatabase.query(
            GifticonDbHelper.TABLE_NAME,
            arrayOf(
                GifticonDbHelper.COLUMN_ID,
                GifticonDbHelper.COLUMN_MENU_NAME,
                GifticonDbHelper.COLUMN_MERCHANT,
                GifticonDbHelper.COLUMN_EXPIRY_DATE,
                GifticonDbHelper.COLUMN_IMAGE_URI
            ),
            null, null, null, null,
            "${GifticonDbHelper.COLUMN_SAVED_DATE} DESC"
        )
        c.use {
            val id = it.getColumnIndexOrThrow(GifticonDbHelper.COLUMN_ID)
            val menu = it.getColumnIndexOrThrow(GifticonDbHelper.COLUMN_MENU_NAME)
            val merchant = it.getColumnIndexOrThrow(GifticonDbHelper.COLUMN_MERCHANT)
            val expiry = it.getColumnIndexOrThrow(GifticonDbHelper.COLUMN_EXPIRY_DATE)
            val image = it.getColumnIndexOrThrow(GifticonDbHelper.COLUMN_IMAGE_URI)
            while (it.moveToNext()) {
                items.add(
                    Gifticon(
                        id = it.getLong(id),
                        menuName = it.getString(menu),
                        merchant = it.getString(merchant),
                        expiryDate = it.getString(expiry),
                        imageUri = it.getString(image)
                    )
                )
            }
        }
        recycler.adapter?.notifyDataSetChanged()
    }

    private fun requestDelete(item: Gifticon) {
        AlertDialog.Builder(this)
            .setTitle("삭제할까요?")
            .setMessage("‘${item.menuName ?: "기프티콘"}’ 항목을 삭제합니다.")
            .setPositiveButton("삭제") { _, _ ->
                CoroutineScope(Dispatchers.IO).launch {
                    val ok = db.deleteGifticonById(item.id)
                    withContext(Dispatchers.Main) {
                        if (ok) {
                            items.removeAll { it.id == item.id }
                            recycler.adapter?.notifyDataSetChanged()
                            Toast.makeText(this@OcrActivity, "삭제 완료", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(this@OcrActivity, "삭제 실패", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
            .setNegativeButton("취소", null)
            .show()
    }

    // ===== 날짜 추출(강화) =====
    private fun extractExpiryDate(original: String): String {
        val norm = normalizeOcrNoise(original)
        val lines = norm.lines().map { it.trim() }.filter { it.isNotBlank() }

        // 키워드 포함 줄 우선 + 전체 텍스트도 후보군에 포함
        val keywordLines = lines.filter {
            it.contains("유효기간") || it.contains("만료") || it.contains("까지") ||
                    it.contains("사용기한") || it.contains("교환기한") ||
                    it.contains("valid", true) || it.contains("expire", true)
        }
        val pools = (keywordLines + norm).distinct()

        // ~ 범위가 있으면 오른쪽(종료일) 우선
        fun rightOfRange(s: String): String {
            val parts = s.split('~','〜','–','—').map { it.trim() }
            return if (parts.size >= 2) parts.last() else s
        }

        // 다양한 패턴
        val patterns = listOf(
            Regex("""(20\d{2})\s*년\s*(1[0-2]|0?[1-9])\s*월\s*(3[01]|[12]?\d)\s*일?(\s*\([^)]+\))?(\s*\d{1,2}:\d{2})?\s*(까지|만료)?"""),
            Regex("""(20\d{2})[.\-/](1[0-2]|0?[1-9])[.\-/](3[01]|[12]?\d)"""),
            Regex("""(2\d)[.\-/](1[0-2]|0?[1-9])[.\-/](3[01]|[12]?\d)"""),
            Regex("""\b((20\d{2})(1[0-2]|0[1-9])(3[01]|[12]\d))\b"""), // YYYYMMDD
            Regex("""\b((\d{2})(1[0-2]|0[1-9])(3[01]|[12]\d))\b"""),   // YYMMDD
            Regex("""\b(1[0-2]|0?[1-9])[.\-/](3[01]|[12]?\d)\b""")      // MM-DD
        )

        // 후보 날짜 전부 수집해서 유효한 것 중 "가장 늦은 날짜"를 만료일로 판단
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
        if (allCandidates.isEmpty()) return ""

        // 가장 늦은 날짜 선택
        val max = allCandidates.maxByOrNull { it } // "YYYY-MM-DD" 는 문자열 비교가 시간순과 동일
        return max ?: ""
    }

    // OCR 오인식 보정
    private fun normalizeOcrNoise(s: String): String {
        return s
            .replace('–', '-')     // en dash
            .replace('—', '-')     // em dash
            .map { ch ->
                when (ch) {
                    'l', 'I' -> '1'
                    'O' -> '0'
                    else -> ch
                }
            }.joinToString("")
    }

    // raw 날짜를 YYYY-MM-DD로
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
        val cal = java.util.Calendar.getInstance()
        val yNow = cal.get(java.util.Calendar.YEAR)
        val mNow = cal.get(java.util.Calendar.MONTH) + 1
        val dNow = cal.get(java.util.Calendar.DAY_OF_MONTH)
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

    // ===== 사용처(브랜드) 추출 =====
    private fun extractMerchant(text: String): String {
        val lines = text.lines()
        for (b in BRANDS) {
            lines.firstOrNull { it.contains(b, ignoreCase = true) }?.let { return b }
        }
        return ""
    }

    // ===== 메뉴명(상품명) 추출: 라벨/수량 제거 + 카페메뉴 우선 =====
    private fun extractMenuName(text: String): String {
        val linesRaw = text.lines().map { it.trim() }.filter { it.isNotBlank() }
        val lines = linesRaw.map { normalizeLabelNoise(it) }

        fun isQuantityLine(s: String): Boolean {
            if (QUANTITY_WORDS.any { s.contains(it, ignoreCase = true) }) return true
            // 예: "수량 1개", "2개", "x1"
            if (Regex("""\b(\d+)\s*개\b""").containsMatchIn(s)) return true
            if (Regex("""\bx\s*\d+\b""", RegexOption.IGNORE_CASE).containsMatchIn(s)) return true
            return false
        }

        fun looksBad(s: String): Boolean {
            if (s.isBlank()) return true
            if (isQuantityLine(s)) return true
            if (LABEL_WORDS.any { s.startsWith(it, ignoreCase = true) }) return true
            if (s.length !in 2..40) return true
            if (Regex("""\d{8,}""").containsMatchIn(s)) return true // 긴 숫자
            if (Regex("""[₩\\]?\s?\d{2,3}(,\d{3})*\s*(원|KRW)?""").containsMatchIn(s)) return true // 금액
            if (Regex("""\b(옵션|사이즈|HOT|ICE|L|R|Tall|Grande|Venti)\b""", RegexOption.IGNORE_CASE).containsMatchIn(s)) return true
            // 유효기간/안내류
            val black = listOf("유효기간","까지","만료","사용처","안내","고객센터","교환","코드","바코드","포인트","결제","주문")
            if (black.any { s.contains(it, ignoreCase = true) }) return true
            return false
        }

        fun clean(s: String): String {
            var t = s
            // 라벨 접두어 제거
            t = t.replace(Regex("""^(상품명|제품명|메뉴명|상품|Item|ITEM|Product|PRODUCT)\s*[:：\-]?\s*"""), "")
            // 괄호 내 옵션 제거
            t = t.replace(Regex("""\([^)]*\)"""), "")
            t = t.replace(Regex("""\[[^\]]*]"""), "")
            t = t.replace(Regex("""\s{2,}"""), " ")
            return t.trim().trim('-','•','·',':','：')
        }

        // 1) 라벨 줄: 콜론 뒤 값 또는 다음 줄, "카페 메뉴 키워드" 포함이면 바로 반환
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

        // 2) 브랜드 라인 아래 1~3줄에서 "카페 메뉴 키워드" 우선
        val brandIdx = lines.indexOfFirst { line -> BRANDS.any { b -> line.contains(b, ignoreCase = true) } }
        if (brandIdx >= 0) {
            for (i in brandIdx + 1 until minOf(brandIdx + 4, lines.size)) {
                val v = clean(lines[i])
                if (!looksBad(v) && containsCafeWord(v)) return v
            }
        }

        // 3) 전체에서 카페 메뉴 키워드 포함 첫 줄
        lines.map(::clean).firstOrNull { !looksBad(it) && containsCafeWord(it) }?.let { return it }

        // 4) 마지막 보호: 예전 방식(가장 그럴싸한 텍스트) — 수량/라벨/가격 제외
        lines.map(::clean).firstOrNull { !looksBad(it) }?.let { return it }

        return ""
    }

    private fun containsCafeWord(s: String): Boolean =
        CAFE_MENU.any { kw -> s.contains(kw, ignoreCase = true) }

    private fun normalizeLabelNoise(s: String): String {
        // OCR에서 ':'가 'I'나 'l'로 오인식되는 경우 정리 등
        return s
            .replace('：', ':')
            .replace("I ", ": ")  // 느슨한 치유(상황에 따라)
            .replace("l ", ": ")
            .trim()
    }

    private fun dp(v: Int): Int = (resources.displayMetrics.density * v).toInt()
}

// ===== RecyclerView 어댑터/뷰홀더 =====
private class GifticonAdapter(
    private val data: List<Gifticon>,
    private val onLongPressDelete: (Gifticon) -> Unit
) : RecyclerView.Adapter<GifticonViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): GifticonViewHolder {
        val layout = LinearLayout(parent.context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(8, 8, 8, 8)
        }
        val iv = ImageView(parent.context).apply {
            layoutParams = LinearLayout.LayoutParams(100, 100)
        }
        val box = LinearLayout(parent.context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setPadding(16, 0, 0, 0)
        }
        val tvMenu = TextView(parent.context).apply { textSize = 15f }
        val tvMerchant = TextView(parent.context).apply { textSize = 14f }
        val tvExpiry = TextView(parent.context).apply { textSize = 13f; setTextColor(0xFF666666.toInt()) }

        box.addView(tvMenu)
        box.addView(tvMerchant)
        box.addView(tvExpiry)
        layout.addView(iv)
        layout.addView(box)

        return GifticonViewHolder(layout, iv, tvMenu, tvMerchant, tvExpiry, onLongPressDelete)
    }

    override fun onBindViewHolder(holder: GifticonViewHolder, position: Int) {
        holder.bind(data[position])
    }

    override fun getItemCount(): Int = data.size
}

private class GifticonViewHolder(
    private val layout: LinearLayout,
    private val iv: ImageView,
    private val tvMenu: TextView,
    private val tvMerchant: TextView,
    private val tvExpiry: TextView,
    private val onLongPressDelete: (Gifticon) -> Unit
) : RecyclerView.ViewHolder(layout) {

    private var current: Gifticon? = null

    init {
        layout.setOnLongClickListener {
            current?.let { onLongPressDelete(it) }
            true
        }
    }

    fun bind(item: Gifticon) {
        current = item
        tvMenu.text = item.menuName ?: "(메뉴 미정)"
        tvMerchant.text = item.merchant ?: "(사용처 미정)"
        tvExpiry.text = item.expiryDate ?: "(유효기간 미정)"
        try {
            if (!item.imageUri.isNullOrBlank()) iv.setImageURI(Uri.parse(item.imageUri))
            else iv.setImageDrawable(null)
        } catch (_: Exception) {
            iv.setImageDrawable(null)
        }
    }
}
