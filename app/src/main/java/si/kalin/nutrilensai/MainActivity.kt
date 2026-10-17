package si.kalin.nutrilensai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.launch
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.room.*
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.content
import com.google.ai.client.generativeai.type.generationConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

// ============================================================================
// 1. DATA MODELS & ROOM DATABASE
// ============================================================================

@Serializable
data class AiMealAnalysis(
    val mealDescription: String = "Detected Meal",
    val totalCalories: Double = 0.0,
    val proteinGrams: Double = 0.0,
    val carbsGrams: Double = 0.0,
    val fatGrams: Double = 0.0,
    val confidenceNote: String = ""
) {
    val totalCaloriesInt: Int get() = Math.round(totalCalories).toInt()
    val proteinGramsInt: Int get() = Math.round(proteinGrams).toInt()
    val carbsGramsInt: Int get() = Math.round(carbsGrams).toInt()
    val fatGramsInt: Int get() = Math.round(fatGrams).toInt()
}

@Entity(tableName = "meal_entries")
data class MealEntry(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val timestamp: Long = System.currentTimeMillis(),
    val initialTimestamp: Long = timestamp,
    val dateString: String,
    val description: String,
    val calories: Int,
    val protein: Int,
    val carbs: Int,
    val fat: Int,
    val imagePaths: List<String> = emptyList()
)

class Converters {
    @TypeConverter
    fun fromStringList(value: List<String>): String {
        return Json.encodeToString(value)
    }

    @TypeConverter
    fun toStringList(value: String): List<String> {
        return try {
            Json.decodeFromString(value)
        } catch (e: Exception) {
            emptyList()
        }
    }
}

@Dao
interface MealDao {
    @Insert
    suspend fun insertMeal(meal: MealEntry)

    @Update
    suspend fun updateMeal(meal: MealEntry)

    @Delete
    suspend fun deleteMeal(meal: MealEntry)

    @Query("SELECT * FROM meal_entries WHERE dateString = :date ORDER BY timestamp DESC")
    fun getMealsForDate(date: String): Flow<List<MealEntry>>

    @Query("SELECT * FROM meal_entries")
    fun getAllMealsFlow(): Flow<List<MealEntry>>
    
    @Query("SELECT * FROM meal_entries")
    suspend fun getAllMeals(): List<MealEntry>
}

@Database(entities = [MealEntry::class], version = 3, exportSchema = false)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun mealDao(): MealDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "nutrilens_db"
                )
                .fallbackToDestructiveMigration()
                .build().also { INSTANCE = it }
            }
        }
    }
}

// ============================================================================
// 2. GEMINI VISION SERVICE
// ============================================================================

class FoodVisionService {
    private val jsonParser = Json { ignoreUnknownKeys = true }

    suspend fun analyzeFoodImage(
        apiKey: String,
        bitmap: Bitmap,
        userContextNote: String
    ): Result<AiMealAnalysis> {
        if (apiKey.isBlank()) {
            return Result.failure(Exception("Missing Gemini API Key. Tap the gear icon in the top right to set it."))
        }

        val model = GenerativeModel(
            modelName = "gemini-3.8-flash",
            apiKey = apiKey.trim(),
            generationConfig = generationConfig {
                responseMimeType = "application/json"
                temperature = 0.2f
            }
        )

        val scaledBitmap = scaleBitmap(bitmap, 1024)

        val prompt = """
            Analyze this image of food and estimate its nutritional content.
            User preparation note: "$userContextNote"
            
            Identify all visible food items, estimate their portion sizes based on standard tableware,
            and calculate the combined total calories and macronutrients.
            
            Return ONLY a valid JSON object matching this exact structure:
            {
              "mealDescription": "Concise name of the meal and components",
              "totalCalories": 0,
              "proteinGrams": 0,
              "carbsGrams": 0,
              "fatGrams": 0,
              "confidenceNote": "Brief note on portion assumptions made"
            }
        """.trimIndent()

        return try {
            val response = model.generateContent(
                content {
                    image(scaledBitmap)
                    text(prompt)
                }
            )
            val jsonText = response.text ?: throw Exception("Empty response from Gemini")
            val parsed = jsonParser.decodeFromString<AiMealAnalysis>(jsonText)
            Result.success(parsed)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun scaleBitmap(bitmap: Bitmap, maxDimension: Int): Bitmap {
        val ratio = minOf(
            maxDimension.toFloat() / bitmap.width,
            maxDimension.toFloat() / bitmap.height
        )
        if (ratio >= 1.0f) return bitmap
        val width = (ratio * bitmap.width).roundToInt()
        val height = (ratio * bitmap.height).roundToInt()
        return Bitmap.createScaledBitmap(bitmap, width, height, true)
    }
}

// ============================================================================
// 3. VIEWMODEL & HELPERS
// ============================================================================

fun createImageFile(context: Context): File {
    val timeStamp: String = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
    val storageDir = File(context.filesDir, "images").apply { mkdirs() }
    return File.createTempFile("JPEG_${timeStamp}_", ".jpg", storageDir)
}

suspend fun cleanupOldImages(context: Context, days: Int, dao: MealDao) {
    withContext(Dispatchers.IO) {
        val storageDir = File(context.filesDir, "images")
        if (!storageDir.exists()) return@withContext

        val thresholdTime = System.currentTimeMillis() - days * 24 * 60 * 60 * 1000L
        val files = storageDir.listFiles() ?: return@withContext

        for (file in files) {
            if (file.lastModified() < thresholdTime && !file.name.endsWith("_lowres.jpg")) {
                try {
                    val bitmap = BitmapFactory.decodeFile(file.absolutePath)
                    if (bitmap != null) {
                        val newFileName = file.name.replace(".jpg", "_lowres.jpg")
                        val newFile = File(storageDir, newFileName)
                        FileOutputStream(newFile).use { out ->
                            bitmap.compress(Bitmap.CompressFormat.JPEG, 60, out)
                        }
                        file.delete()
                        
                        // Update DB
                        val allMeals = dao.getAllMeals()
                        for (meal in allMeals) {
                            if (meal.imagePaths.contains(file.absolutePath)) {
                                val updatedPaths = meal.imagePaths.map { 
                                    if (it == file.absolutePath) newFile.absolutePath else it 
                                }
                                dao.updateMeal(meal.copy(imagePaths = updatedPaths))
                            }
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }
}

class CalorieViewModel(
    private val dao: MealDao,
    private val visionService: FoodVisionService,
    private val context: Context
) : ViewModel() {

    private val _selectedDate = MutableStateFlow(LocalDate.now())
    val selectedDate: StateFlow<LocalDate> = _selectedDate.asStateFlow()

    @OptIn(ExperimentalCoroutinesApi::class)
    val mealsForSelectedDate: StateFlow<List<MealEntry>> = _selectedDate
        .flatMapLatest { date -> dao.getMealsForDate(date.toString()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    var isAnalyzing by mutableStateOf(false)
        private set

    var errorMessage by mutableStateOf<String?>(null)
        private set

    var pendingAnalysis by mutableStateOf<AiMealAnalysis?>(null)
        private set
        
    var pendingImagePath by mutableStateOf<String?>(null)
        private set

    // When updating or adding to an existing meal
    var updatingMealTarget by mutableStateOf<MealEntry?>(null)
        private set
        
    // Mode for adding to meal: true = same dish (re-estimate/average), false = new dish (sum macros/calories)
    var isSameDishMode by mutableStateOf(false)
        private set

    var pendingTimestamp by mutableStateOf<Long?>(null)
        private set
        
    var askSameDishDialogState by mutableStateOf<Triple<String, MealEntry, Long>?>(null)
        private set

    fun changeDate(daysDelta: Long) {
        _selectedDate.value = _selectedDate.value.plusDays(daysDelta)
    }

    fun selectDate(date: LocalDate) {
        _selectedDate.value = date
    }

    val allMeals: StateFlow<List<MealEntry>> = dao.getAllMealsFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    
    fun processNewImage(imagePath: String, apiKey: String, userNote: String, mealGroupingWindowMinutes: Int, capturedTimestamp: Long = System.currentTimeMillis()) {
        // Find existing meal within the time window on the same date
        val capturedDate = Instant.ofEpochMilli(capturedTimestamp).atZone(ZoneId.systemDefault()).toLocalDate()
        val windowMillis = mealGroupingWindowMinutes * 60 * 1000L
        
        // Find closest meal within window
        val nearbyMeal = mealsForSelectedDate.value
            .filter { Math.abs(capturedTimestamp - it.timestamp) <= windowMillis }
            .minByOrNull { Math.abs(capturedTimestamp - it.timestamp) }
        
        if (nearbyMeal != null) {
            // Prompt user: Same dish vs New dish in this meal vs Brand New separate meal
            askSameDishDialogState = Triple(imagePath, nearbyMeal, capturedTimestamp)
            pendingImagePath = null
            return
        }
        
        // If image belongs to a different date than currently selected, switch to that date
        if (_selectedDate.value != capturedDate) {
            _selectedDate.value = capturedDate
        }
        
        // Proceed normally as a new meal
        updatingMealTarget = null
        isSameDishMode = false
        pendingTimestamp = capturedTimestamp
        analyzePhoto(apiKey, imagePath, userNote)
    }
    
    fun onMealGroupChoice(choice: MealGroupChoice, imagePath: String, targetMeal: MealEntry?, capturedTimestamp: Long, apiKey: String, userNote: String) {
        askSameDishDialogState = null
        pendingTimestamp = capturedTimestamp
        when (choice) {
            MealGroupChoice.SAME_DISH -> {
                if (targetMeal != null) {
                    updatingMealTarget = targetMeal
                    isSameDishMode = true
                    val context = if (userNote.isNotBlank()) {
                        "Additional photo/angle of existing dish: ${targetMeal.description}. Context: $userNote"
                    } else {
                        "Additional photo/angle of existing dish: ${targetMeal.description}"
                    }
                    analyzePhoto(apiKey, imagePath, context)
                }
            }
            MealGroupChoice.ADD_DISH_TO_MEAL -> {
                if (targetMeal != null) {
                    updatingMealTarget = targetMeal
                    isSameDishMode = false
                    val context = if (userNote.isNotBlank()) {
                        "Another dish in the same meal (${targetMeal.description}). User context: $userNote"
                    } else {
                        "Another dish in the same meal (${targetMeal.description})"
                    }
                    analyzePhoto(apiKey, imagePath, context)
                }
            }
            MealGroupChoice.NEW_MEAL -> {
                updatingMealTarget = null
                isSameDishMode = false
                val capturedDate = Instant.ofEpochMilli(capturedTimestamp).atZone(ZoneId.systemDefault()).toLocalDate()
                if (_selectedDate.value != capturedDate) {
                    _selectedDate.value = capturedDate
                }
                analyzePhoto(apiKey, imagePath, userNote)
            }
        }
    }

    private fun analyzePhoto(apiKey: String, imagePath: String, contextNote: String) {
        viewModelScope.launch {
            isAnalyzing = true
            errorMessage = null
            
            val bitmap = BitmapFactory.decodeFile(imagePath)
            if (bitmap == null) {
                errorMessage = "Failed to load image"
                isAnalyzing = false
                return@launch
            }
            
            val result = visionService.analyzeFoodImage(apiKey, bitmap, contextNote)
            result.onSuccess { analysis ->
                val target = updatingMealTarget
                if (target != null) {
                    if (isSameDishMode) {
                        // Same dish: Combine/average with existing meal estimate
                        val combinedCalories = Math.round((target.calories + analysis.totalCalories) / 2.0).toDouble()
                        val combinedProtein = Math.round((target.protein + analysis.proteinGrams) / 2.0).toDouble()
                        val combinedCarbs = Math.round((target.carbs + analysis.carbsGrams) / 2.0).toDouble()
                        val combinedFat = Math.round((target.fat + analysis.fatGrams) / 2.0).toDouble()
                        val combinedNote = "Averaged with previous estimate (${target.calories} kcal vs ${Math.round(analysis.totalCalories)} kcal). " + analysis.confidenceNote

                        pendingAnalysis = AiMealAnalysis(
                            mealDescription = target.description,
                            totalCalories = combinedCalories,
                            proteinGrams = combinedProtein,
                            carbsGrams = combinedCarbs,
                            fatGrams = combinedFat,
                            confidenceNote = combinedNote
                        )
                    } else {
                        // Additional dish in same meal: Sum macros and append description
                        val combinedCalories = (target.calories + analysis.totalCalories)
                        val combinedProtein = (target.protein + analysis.proteinGrams)
                        val combinedCarbs = (target.carbs + analysis.carbsGrams)
                        val combinedFat = (target.fat + analysis.fatGrams)
                        val combinedDescription = if (target.description.isNotBlank()) {
                            "${target.description} + ${analysis.mealDescription}"
                        } else {
                            analysis.mealDescription
                        }
                        val combinedNote = "Added to meal: ${analysis.mealDescription} (${Math.round(analysis.totalCalories)} kcal). " + analysis.confidenceNote

                        pendingAnalysis = AiMealAnalysis(
                            mealDescription = combinedDescription,
                            totalCalories = combinedCalories,
                            proteinGrams = combinedProtein,
                            carbsGrams = combinedCarbs,
                            fatGrams = combinedFat,
                            confidenceNote = combinedNote
                        )
                    }
                } else {
                    pendingAnalysis = analysis
                }
                pendingImagePath = imagePath
            }.onFailure { error ->
                errorMessage = error.localizedMessage ?: "Analysis failed"
                updatingMealTarget = null
            }
            isAnalyzing = false
        }
    }

    fun confirmAndSaveMeal(description: String, calories: Int, protein: Int, carbs: Int, fat: Int, timestamp: Long) {
        viewModelScope.launch {
            val target = updatingMealTarget
            if (target != null) {
                val updatedPaths = if (pendingImagePath != null && !target.imagePaths.contains(pendingImagePath)) {
                    target.imagePaths + pendingImagePath!!
                } else {
                    target.imagePaths
                }
                dao.updateMeal(
                    target.copy(
                        description = description,
                        calories = calories,
                        protein = protein,
                        carbs = carbs,
                        fat = fat,
                        timestamp = timestamp,
                        imagePaths = updatedPaths
                    )
                )
            } else {
                val paths = pendingImagePath?.let { listOf(it) } ?: emptyList()
                val dateString = Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()).toLocalDate().toString()
                dao.insertMeal(
                    MealEntry(
                        timestamp = timestamp,
                        initialTimestamp = timestamp,
                        dateString = dateString,
                        description = description,
                        calories = calories,
                        protein = protein,
                        carbs = carbs,
                        fat = fat,
                        imagePaths = paths
                    )
                )
            }
            pendingAnalysis = null
            pendingImagePath = null
            updatingMealTarget = null
            pendingTimestamp = null
        }
    }

    fun dismissPendingAnalysis() {
        pendingAnalysis = null
        pendingImagePath = null
        updatingMealTarget = null
        pendingTimestamp = null
    }

    fun deleteMeal(meal: MealEntry) {
        viewModelScope.launch {
            dao.deleteMeal(meal)
            // Optional: delete associated images here if they shouldn't be kept
        }
    }
    
    fun triggerCleanup(days: Int) {
        viewModelScope.launch {
            cleanupOldImages(context, days, dao)
        }
    }
}

enum class MealGroupChoice {
    SAME_DISH,
    ADD_DISH_TO_MEAL,
    NEW_MEAL
}

// ============================================================================
// 4. MAIN ACTIVITY & JETPACK COMPOSE UI
// ============================================================================

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val db = AppDatabase.getDatabase(this)
        val visionService = FoodVisionService()
        
        val prefs = getSharedPreferences("nutrilens_prefs", Context.MODE_PRIVATE)
        val imageDownscaleDays = prefs.getInt("image_downscale_days", 7)

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    val vm: CalorieViewModel = viewModel(
                        factory = object : ViewModelProvider.Factory {
                            @Suppress("UNCHECKED_CAST")
                            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                                return CalorieViewModel(db.mealDao(), visionService, this@MainActivity) as T
                            }
                        }
                    )
                    
                    LaunchedEffect(Unit) {
                        vm.triggerCleanup(imageDownscaleDays)
                    }
                    
                    CalorieTrackerApp(vm, this)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalorieTrackerApp(viewModel: CalorieViewModel, context: Context) {
    val prefs = remember { context.getSharedPreferences("nutrilens_prefs", Context.MODE_PRIVATE) }
    var apiKey by remember { mutableStateOf(prefs.getString("gemini_api_key", "") ?: "") }
    var dailyGoal by remember { mutableIntStateOf(prefs.getInt("daily_goal", 2200)) }
    var mealGroupingWindowMinutes by remember { mutableIntStateOf(prefs.getInt("meal_grouping_window_minutes", 60)) }
    var imageDownscaleDays by remember { mutableIntStateOf(prefs.getInt("image_downscale_days", 7)) }
    
    var showSettingsDialog by remember { mutableStateOf(apiKey.isBlank()) }
    var showMonthlySummaryDialog by remember { mutableStateOf(false) }
    var mealToDelete by remember { mutableStateOf<MealEntry?>(null) }
    var fullScreenImagePath by remember { mutableStateOf<String?>(null) }

    val selectedDate by viewModel.selectedDate.collectAsStateWithLifecycle()
    val meals by viewModel.mealsForSelectedDate.collectAsStateWithLifecycle()
    var userNote by remember { mutableStateOf("") }

    val totalCalories = meals.sumOf { it.calories }
    val totalProtein = meals.sumOf { it.protein }
    val totalCarbs = meals.sumOf { it.carbs }
    val totalFat = meals.sumOf { it.fat }
    
    var tempCameraFile by remember { mutableStateOf<File?>(null) }

    // Camera Launcher
    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { success: Boolean ->
        if (success) {
            tempCameraFile?.absolutePath?.let { path ->
                viewModel.processNewImage(path, apiKey, userNote, mealGroupingWindowMinutes)
                userNote = ""
            }
        }
    }

    // Gallery Launcher
    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) {
            // Read timestamp from media metadata if available
            var photoTimestamp: Long? = null
            try {
                val projection = arrayOf(MediaStore.Images.Media.DATE_TAKEN, MediaStore.Images.Media.DATE_MODIFIED)
                context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val dateTakenIndex = cursor.getColumnIndex(MediaStore.Images.Media.DATE_TAKEN)
                        if (dateTakenIndex != -1) {
                            val dateTaken = cursor.getLong(dateTakenIndex)
                            if (dateTaken > 0) {
                                photoTimestamp = dateTaken
                            }
                        }
                        if (photoTimestamp == null) {
                            val dateModifiedIndex = cursor.getColumnIndex(MediaStore.Images.Media.DATE_MODIFIED)
                            if (dateModifiedIndex != -1) {
                                val dateMod = cursor.getLong(dateModifiedIndex)
                                if (dateMod > 0) {
                                    photoTimestamp = dateMod * 1000L
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }

            // Copy to internal storage
            val newFile = createImageFile(context)
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(newFile).use { output ->
                    input.copyTo(output)
                }
            }
            val finalTimestamp = photoTimestamp ?: System.currentTimeMillis()
            viewModel.processNewImage(newFile.absolutePath, apiKey, userNote, mealGroupingWindowMinutes, finalTimestamp)
            userNote = ""
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("NutriLens AI", fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(onClick = { showMonthlySummaryDialog = true }) {
                        Icon(Icons.Default.CalendarMonth, contentDescription = "Monthly Summary")
                    }
                    IconButton(onClick = { showSettingsDialog = true }) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
        ) {
            // Date Switcher Bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { viewModel.changeDate(-1) }) {
                    Icon(Icons.Default.ChevronLeft, contentDescription = "Previous Day")
                }
                Text(
                    text = if (selectedDate == LocalDate.now()) "Today ($selectedDate)" else selectedDate.toString(),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clickable { showMonthlySummaryDialog = true }
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                )
                IconButton(onClick = { viewModel.changeDate(1) }) {
                    Icon(Icons.Default.ChevronRight, contentDescription = "Next Day")
                }
            }

            // Daily Summary Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.Bottom
                    ) {
                        Text(
                            text = "$totalCalories kcal",
                            style = MaterialTheme.typography.headlineLarge,
                            fontWeight = FontWeight.ExtraBold
                        )
                        Text(
                            text = "/ $dailyGoal kcal",
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { (totalCalories.toFloat() / dailyGoal.coerceAtLeast(1)).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().height(8.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Protein: ${totalProtein}g", style = MaterialTheme.typography.bodyMedium)
                        Text("Carbs: ${totalCarbs}g", style = MaterialTheme.typography.bodyMedium)
                        Text("Fat: ${totalFat}g", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Optional Preparation Note
            OutlinedTextField(
                value = userNote,
                onValueChange = { userNote = it },
                label = { Text("Optional context (e.g. 'fried in olive oil', '250g steak')") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(10.dp))

            // Action Buttons (Camera & Gallery)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    onClick = {
                        val file = createImageFile(context)
                        tempCameraFile = file
                        val uri = FileProvider.getUriForFile(
                            context,
                            "${context.packageName}.fileprovider",
                            file
                        )
                        cameraLauncher.launch(uri)
                    },
                    enabled = !viewModel.isAnalyzing,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.PhotoCamera, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Camera")
                }

                OutlinedButton(
                    onClick = {
                        galleryLauncher.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    },
                    enabled = !viewModel.isAnalyzing,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.PhotoLibrary, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Gallery")
                }
            }

            if (viewModel.isAnalyzing) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(12.dp))
                    Text("Gemini is analyzing portion & macros...")
                }
            }

            viewModel.errorMessage?.let { error ->
                Text(
                    text = error,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
            Text("Logged Meals", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))

            // Logged Meals List
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(meals, key = { it.id }) { meal ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(14.dp).fillMaxWidth()) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    val timeFormat = remember { DateTimeFormatter.ofPattern("HH:mm") }
                                    val currentTimeStr = remember(meal.timestamp) {
                                        Instant.ofEpochMilli(meal.timestamp)
                                            .atZone(ZoneId.systemDefault())
                                            .format(timeFormat)
                                    }
                                    val initialTimeStr = remember(meal.initialTimestamp) {
                                        if (meal.initialTimestamp > 0) {
                                            Instant.ofEpochMilli(meal.initialTimestamp)
                                                .atZone(ZoneId.systemDefault())
                                                .format(timeFormat)
                                        } else currentTimeStr
                                    }
                                    val timeDisplay = if (meal.initialTimestamp > 0 && meal.initialTimestamp != meal.timestamp) {
                                        "Started: $initialTimeStr • Updated: $currentTimeStr"
                                    } else {
                                        currentTimeStr
                                    }

                                    Text(meal.description, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium)
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        timeDisplay,
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        "P: ${meal.protein}g  •  C: ${meal.carbs}g  •  F: ${meal.fat}g",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Text(
                                    "${meal.calories} kcal",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                IconButton(onClick = { mealToDelete = meal }) {
                                    Icon(Icons.Default.Delete, contentDescription = "Delete")
                                }
                            }
                            if (meal.imagePaths.isNotEmpty()) {
                                Spacer(modifier = Modifier.height(8.dp))
                                LazyRow(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    items(meal.imagePaths) { path ->
                                        val bitmap = remember(path) {
                                        val options = BitmapFactory.Options().apply {
                                            // Decode bounds first to get dimensions
                                            inJustDecodeBounds = true
                                            BitmapFactory.decodeFile(path, this)
                                            
                                            // Calculate inSampleSize
                                            val reqWidth = 200 // Max thumbnail width
                                            val reqHeight = 200 // Max thumbnail height
                                            var inSampleSize = 1
                                            
                                            if (outHeight > reqHeight || outWidth > reqWidth) {
                                                val halfHeight = outHeight / 2
                                                val halfWidth = outWidth / 2
                                                while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                                                    inSampleSize *= 2
                                                }
                                            }
                                            
                                            // Decode actual image with inSampleSize
                                            inJustDecodeBounds = false
                                            this.inSampleSize = inSampleSize
                                        }
                                        BitmapFactory.decodeFile(path, options)
                                    }
                                        if (bitmap != null) {
                                            Image(
                                                bitmap = bitmap.asImageBitmap(),
                                                contentDescription = "Meal photo",
                                                modifier = Modifier
                                                    .size(60.dp)
                                                    .clickable { fullScreenImagePath = path },
                                                contentScale = ContentScale.Crop
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    
    // Delete Confirmation Dialog
    mealToDelete?.let { meal ->
        AlertDialog(
            onDismissRequest = { mealToDelete = null },
            title = { Text("Delete Meal?") },
            text = { Text("Are you sure you want to delete \"${meal.description}\" (${meal.calories} kcal)?") },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.deleteMeal(meal)
                        mealToDelete = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { mealToDelete = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Dialog for Meal Grouping (Same dish vs Add dish to meal vs Brand New Meal)
    viewModel.askSameDishDialogState?.let { (imagePath, targetMeal, capturedTimestamp) ->
        val timeFormat = remember { DateTimeFormatter.ofPattern("HH:mm") }
        val mealTime = remember(targetMeal.timestamp) {
            Instant.ofEpochMilli(targetMeal.timestamp).atZone(ZoneId.systemDefault()).format(timeFormat)
        }
        AlertDialog(
            onDismissRequest = {
                viewModel.onMealGroupChoice(MealGroupChoice.NEW_MEAL, imagePath, targetMeal, capturedTimestamp, apiKey, userNote)
            },
            title = { Text("Group with Existing Meal?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("This photo is close in time to your logged meal at $mealTime (\"${targetMeal.description}\").")
                    Text("How would you like to handle this photo?", fontWeight = FontWeight.SemiBold)
                }
            },
            confirmButton = {
                Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Button(
                        onClick = {
                            viewModel.onMealGroupChoice(MealGroupChoice.SAME_DISH, imagePath, targetMeal, capturedTimestamp, apiKey, userNote)
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Same Dish (Refine / Average)")
                    }
                    Button(
                        onClick = {
                            viewModel.onMealGroupChoice(MealGroupChoice.ADD_DISH_TO_MEAL, imagePath, targetMeal, capturedTimestamp, apiKey, userNote)
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("New Dish in this Meal (Add calories)")
                    }
                    OutlinedButton(
                        onClick = {
                            viewModel.onMealGroupChoice(MealGroupChoice.NEW_MEAL, imagePath, targetMeal, capturedTimestamp, apiKey, userNote)
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Separate Meal Entry")
                    }
                }
            },
            dismissButton = null
        )
    }

    // Review & Edit AI Analysis Dialog
    viewModel.pendingAnalysis?.let { analysis ->
        val initialTimestamp = viewModel.pendingTimestamp ?: System.currentTimeMillis()
        ReviewAnalysisDialog(
            analysis = analysis,
            initialTimestamp = initialTimestamp,
            onConfirm = { desc, cal, pro, carb, fat, timestamp ->
                viewModel.confirmAndSaveMeal(desc, cal, pro, carb, fat, timestamp)
            },
            onDismiss = { viewModel.dismissPendingAnalysis() }
        )
    }

    // Full Image Viewer Dialog
    fullScreenImagePath?.let { path ->
        val fullBitmap = remember(path) {
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
                BitmapFactory.decodeFile(path, this)
                val maxDim = 1920
                var sampleSize = 1
                if (outHeight > maxDim || outWidth > maxDim) {
                    val halfHeight = outHeight / 2
                    val halfWidth = outWidth / 2
                    while (halfHeight / sampleSize >= maxDim && halfWidth / sampleSize >= maxDim) {
                        sampleSize *= 2
                    }
                }
                inJustDecodeBounds = false
                inSampleSize = sampleSize
            }
            BitmapFactory.decodeFile(path, options)
        }
        AlertDialog(
            onDismissRequest = { fullScreenImagePath = null },
            confirmButton = {
                TextButton(onClick = { fullScreenImagePath = null }) {
                    Text("Close")
                }
            },
            text = {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .wrapContentHeight(),
                    contentAlignment = Alignment.Center
                ) {
                    if (fullBitmap != null) {
                        Image(
                            bitmap = fullBitmap.asImageBitmap(),
                            contentDescription = "Full photo",
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 480.dp),
                            contentScale = ContentScale.Fit
                        )
                    } else {
                        Text("Image could not be loaded")
                    }
                }
            }
        )
    }

    // Settings Dialog
    if (showSettingsDialog) {
        SettingsDialog(
            currentApiKey = apiKey,
            currentGoal = dailyGoal,
            currentGroupingWindow = mealGroupingWindowMinutes,
            currentDownscaleDays = imageDownscaleDays,
            onSave = { newKey, newGoal, newWindow, newDays ->
                apiKey = newKey
                dailyGoal = newGoal
                mealGroupingWindowMinutes = newWindow
                imageDownscaleDays = newDays
                prefs.edit()
                    .putString("gemini_api_key", newKey)
                    .putInt("daily_goal", newGoal)
                    .putInt("meal_grouping_window_minutes", newWindow)
                    .putInt("image_downscale_days", newDays)
                    .apply()
                showSettingsDialog = false
            },
            onDismiss = { showSettingsDialog = false }
        )
    }

    // Monthly Summary Dialog
    if (showMonthlySummaryDialog) {
        val allMealsList by viewModel.allMeals.collectAsStateWithLifecycle()
        MonthlySummaryDialog(
            currentSelectedDate = selectedDate,
            allMeals = allMealsList,
            dailyGoal = dailyGoal,
            onDateSelected = { date ->
                viewModel.selectDate(date)
                showMonthlySummaryDialog = false
            },
            onDismiss = { showMonthlySummaryDialog = false }
        )
    }
}

@Composable
fun ReviewAnalysisDialog(
    analysis: AiMealAnalysis,
    initialTimestamp: Long = System.currentTimeMillis(),
    onConfirm: (String, Int, Int, Int, Int, Long) -> Unit,
    onDismiss: () -> Unit
) {
    var desc by remember { mutableStateOf(analysis.mealDescription) }
    var calories by remember { mutableStateOf(analysis.totalCaloriesInt.toString()) }
    var protein by remember { mutableStateOf(analysis.proteinGramsInt.toString()) }
    var carbs by remember { mutableStateOf(analysis.carbsGramsInt.toString()) }
    var fat by remember { mutableStateOf(analysis.fatGramsInt.toString()) }

    // Parse initial hour and minute
    val initialDateTime = remember(initialTimestamp) {
        Instant.ofEpochMilli(initialTimestamp).atZone(ZoneId.systemDefault()).toLocalDateTime()
    }
    var hourInput by remember { mutableStateOf(initialDateTime.format(DateTimeFormatter.ofPattern("HH"))) }
    var minuteInput by remember { mutableStateOf(initialDateTime.format(DateTimeFormatter.ofPattern("mm"))) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Review AI Estimate") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (analysis.confidenceNote.isNotBlank()) {
                    Text(
                        text = analysis.confidenceNote,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                OutlinedTextField(
                    value = desc,
                    onValueChange = { desc = it },
                    label = { Text("Meal Description") }
                )
                OutlinedTextField(
                    value = calories,
                    onValueChange = { calories = it },
                    label = { Text("Calories (kcal)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = protein,
                        onValueChange = { protein = it },
                        label = { Text("Protein (g)") },
                        modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                    OutlinedTextField(
                        value = carbs,
                        onValueChange = { carbs = it },
                        label = { Text("Carbs (g)") },
                        modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                    OutlinedTextField(
                        value = fat,
                        onValueChange = { fat = it },
                        label = { Text("Fat (g)") },
                        modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))
                Text("Meal Time", style = MaterialTheme.typography.labelMedium)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = hourInput,
                        onValueChange = { if (it.length <= 2) hourInput = it },
                        label = { Text("Hour (0-23)") },
                        modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true
                    )
                    Text(":", fontWeight = FontWeight.Bold)
                    OutlinedTextField(
                        value = minuteInput,
                        onValueChange = { if (it.length <= 2) minuteInput = it },
                        label = { Text("Minute (0-59)") },
                        modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                val hour = hourInput.toIntOrNull()?.coerceIn(0, 23) ?: initialDateTime.hour
                val minute = minuteInput.toIntOrNull()?.coerceIn(0, 59) ?: initialDateTime.minute
                val updatedDateTime = initialDateTime.withHour(hour).withMinute(minute).withSecond(0)
                val finalTimestamp = updatedDateTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

                onConfirm(
                    desc,
                    calories.toIntOrNull() ?: 0,
                    protein.toIntOrNull() ?: 0,
                    carbs.toIntOrNull() ?: 0,
                    fat.toIntOrNull() ?: 0,
                    finalTimestamp
                )
            }) {
                Text("Log Meal")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
fun SettingsDialog(
    currentApiKey: String,
    currentGoal: Int,
    currentGroupingWindow: Int,
    currentDownscaleDays: Int,
    onSave: (String, Int, Int, Int) -> Unit,
    onDismiss: () -> Unit
) {
    var keyInput by remember { mutableStateOf(currentApiKey) }
    var goalInput by remember { mutableStateOf(currentGoal.toString()) }
    var windowInput by remember { mutableStateOf(currentGroupingWindow.toString()) }
    var daysInput by remember { mutableStateOf(currentDownscaleDays.toString()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Settings") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = keyInput,
                    onValueChange = { keyInput = it },
                    label = { Text("Gemini API Key") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true
                )
                OutlinedTextField(
                    value = goalInput,
                    onValueChange = { goalInput = it },
                    label = { Text("Daily Calorie Goal (kcal)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true
                )
                OutlinedTextField(
                    value = windowInput,
                    onValueChange = { windowInput = it },
                    label = { Text("Meal Grouping Window (min)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true
                )
                OutlinedTextField(
                    value = daysInput,
                    onValueChange = { daysInput = it },
                    label = { Text("Image Downscale After (days)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true
                )
            }
        },
        confirmButton = {
            Button(onClick = { 
                onSave(
                    keyInput, 
                    goalInput.toIntOrNull() ?: 2200,
                    windowInput.toIntOrNull() ?: 60,
                    daysInput.toIntOrNull() ?: 7
                ) 
            }) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
fun MonthlySummaryDialog(
    currentSelectedDate: LocalDate,
    allMeals: List<MealEntry>,
    dailyGoal: Int,
    onDateSelected: (LocalDate) -> Unit,
    onDismiss: () -> Unit
) {
    var displayMonth by remember { mutableStateOf(YearMonth.from(currentSelectedDate)) }

    // Aggregate calories per date string (yyyy-MM-dd)
    val caloriesPerDay = remember(allMeals) {
        allMeals.groupBy { it.dateString }
            .mapValues { entry -> entry.value.sumOf { it.calories } }
    }

    val daysInMonth = displayMonth.lengthOfMonth()
    val firstDayOfWeek = displayMonth.atDay(1).dayOfWeek.value // 1 (Mon) - 7 (Sun)
    // Offset for Monday-first: Monday is 0, Sunday is 6
    val leadingEmptyDays = firstDayOfWeek - 1

    val monthFormatter = remember { DateTimeFormatter.ofPattern("MMMM yyyy", Locale.getDefault()) }

    // Month totals
    val totalMonthCalories = remember(allMeals, displayMonth) {
        allMeals.filter {
            val mealDate = try { LocalDate.parse(it.dateString) } catch (e: Exception) { null }
            mealDate != null && YearMonth.from(mealDate) == displayMonth
        }.sumOf { it.calories }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { displayMonth = displayMonth.minusMonths(1) }) {
                    Icon(Icons.Default.ChevronLeft, contentDescription = "Previous Month")
                }
                Text(
                    text = displayMonth.format(monthFormatter),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                IconButton(onClick = { displayMonth = displayMonth.plusMonths(1) }) {
                    Icon(Icons.Default.ChevronRight, contentDescription = "Next Month")
                }
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                // Month Total Banner
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Month Total:", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "$totalMonthCalories kcal",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }

                // Days of week header (Mon - Sun)
                val dayHeaders = listOf("M", "T", "W", "T", "F", "S", "S")
                Row(modifier = Modifier.fillMaxWidth()) {
                    for (header in dayHeaders) {
                        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                            Text(
                                text = header,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                // Calendar Grid
                val totalSlots = leadingEmptyDays + daysInMonth
                val totalRows = (totalSlots + 6) / 7

                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (row in 0 until totalRows) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            for (col in 0 until 7) {
                                val slotIndex = row * 7 + col
                                val dayNumber = slotIndex - leadingEmptyDays + 1
                                if (dayNumber in 1..daysInMonth) {
                                    val date = displayMonth.atDay(dayNumber)
                                    val dateStr = date.toString()
                                    val dayCalories = caloriesPerDay[dateStr] ?: 0
                                    val isSelected = date == currentSelectedDate
                                    val isToday = date == LocalDate.now()

                                    val containerColor = when {
                                        isSelected -> MaterialTheme.colorScheme.primary
                                        dayCalories > dailyGoal -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.7f)
                                        dayCalories > 0 -> MaterialTheme.colorScheme.secondaryContainer
                                        else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                                    }

                                    val textColor = when {
                                        isSelected -> MaterialTheme.colorScheme.onPrimary
                                        dayCalories > dailyGoal -> MaterialTheme.colorScheme.onErrorContainer
                                        dayCalories > 0 -> MaterialTheme.colorScheme.onSecondaryContainer
                                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                                    }

                                    Surface(
                                        color = containerColor,
                                        shape = MaterialTheme.shapes.extraSmall,
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(46.dp)
                                            .clickable { onDateSelected(date) }
                                    ) {
                                        Column(
                                            modifier = Modifier.fillMaxSize().padding(1.dp),
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                            verticalArrangement = Arrangement.Center
                                        ) {
                                            Text(
                                                text = "$dayNumber",
                                                style = MaterialTheme.typography.labelSmall,
                                                fontWeight = if (isToday || isSelected) FontWeight.Bold else FontWeight.Normal,
                                                color = textColor
                                            )
                                            if (dayCalories > 0) {
                                                Text(
                                                    text = if (dayCalories >= 1000) "${dayCalories / 1000}k" else "$dayCalories",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    fontSize = androidx.compose.ui.unit.TextUnit(8.5f, androidx.compose.ui.unit.TextUnitType.Sp),
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = textColor,
                                                    maxLines = 1
                                                )
                                            }
                                        }
                                    }
                                } else {
                                    Spacer(modifier = Modifier.weight(1f).height(46.dp))
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}

fun loadBitmapFromUri(context: Context, uri: Uri): Bitmap? {
    return try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val source = ImageDecoder.createSource(context.contentResolver, uri)
            ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                decoder.isMutableRequired = true
            }
        } else {
            @Suppress("DEPRECATION")
            MediaStore.Images.Media.getBitmap(context.contentResolver, uri)
        }
    } catch (e: Exception) {
        null
    }
}