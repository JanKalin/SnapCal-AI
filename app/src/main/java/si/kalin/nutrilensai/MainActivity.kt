package si.kalin.nutrilensai

import android.app.Activity
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
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
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
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import com.google.api.services.drive.DriveScopes
import si.kalin.nutrilensai.backup.BackupManager
import si.kalin.nutrilensai.backup.DriveBackupWorker
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
data class FoodCropBox(
    val top: Double = 0.0,
    val left: Double = 0.0,
    val bottom: Double = 1.0,
    val right: Double = 1.0
)

@Serializable
data class AiMealAnalysis(
    val mealDescription: String = "Detected Meal",
    val totalCalories: Double = 0.0,
    val proteinGrams: Double = 0.0,
    val carbsGrams: Double = 0.0,
    val fatGrams: Double = 0.0,
    val confidenceNote: String = "",
    val cropBox: FoodCropBox? = null
) {
    val totalCaloriesInt: Int get() = Math.round(totalCalories).toInt()
    val proteinGramsInt: Int get() = Math.round(proteinGrams).toInt()
    val carbsGramsInt: Int get() = Math.round(carbsGrams).toInt()
    val fatGramsInt: Int get() = Math.round(fatGrams).toInt()
}

@Serializable
data class DishEntry(
    val imagePath: String? = null,
    val description: String = "",
    val calories: Int = 0,
    val protein: Int = 0,
    val carbs: Int = 0,
    val fat: Int = 0,
    val multiplier: Float = 1.0f,
    val baseCalories: Int = 0,
    val baseProtein: Int = 0,
    val baseCarbs: Int = 0,
    val baseFat: Int = 0
) {
    val effectiveBaseCalories: Int get() = if (baseCalories > 0) baseCalories else if (multiplier > 0f) (calories / multiplier).roundToInt() else calories
    val effectiveBaseProtein: Int get() = if (baseProtein > 0) baseProtein else if (multiplier > 0f) (protein / multiplier).roundToInt() else protein
    val effectiveBaseCarbs: Int get() = if (baseCarbs > 0) baseCarbs else if (multiplier > 0f) (carbs / multiplier).roundToInt() else carbs
    val effectiveBaseFat: Int get() = if (baseFat > 0) baseFat else if (multiplier > 0f) (fat / multiplier).roundToInt() else fat
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
    val imagePaths: List<String> = emptyList(),
    val dishes: List<DishEntry> = emptyList()
) {
    fun getEffectiveDishes(): List<DishEntry> {
        if (dishes.isNotEmpty()) return dishes
        if (imagePaths.isEmpty() || imagePaths.size <= 1) {
            return listOf(
                DishEntry(
                    imagePath = imagePaths.firstOrNull(),
                    description = description,
                    calories = calories,
                    protein = protein,
                    carbs = carbs,
                    fat = fat,
                    multiplier = 1.0f,
                    baseCalories = calories,
                    baseProtein = protein,
                    baseCarbs = carbs,
                    baseFat = fat
                )
            )
        }
        val descParts = description.split(" + ").map { it.trim() }
        val count = imagePaths.size
        return imagePaths.mapIndexed { index, path ->
            val partDesc = descParts.getOrNull(index) ?: "Dish ${index + 1}"
            DishEntry(
                imagePath = path,
                description = partDesc,
                calories = calories / count,
                protein = protein / count,
                carbs = carbs / count,
                fat = fat / count,
                multiplier = 1.0f,
                baseCalories = calories / count,
                baseProtein = protein / count,
                baseCarbs = carbs / count,
                baseFat = fat / count
            )
        }
    }
}

class Converters {
    @TypeConverter
    fun fromStringList(value: List<String>): String = Json.encodeToString(value)

    @TypeConverter
    fun toStringList(value: String): List<String> {
        return try {
            Json.decodeFromString(value)
        } catch (e: Exception) {
            emptyList()
        }
    }

    @TypeConverter
    fun fromDishList(value: List<DishEntry>): String = Json.encodeToString(value)

    @TypeConverter
    fun toDishList(value: String): List<DishEntry> {
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

    @Query("SELECT * FROM meal_entries WHERE dateString = :date ORDER BY timestamp ASC")
    fun getMealsForDate(date: String): Flow<List<MealEntry>>

    @Query("SELECT * FROM meal_entries")
    fun getAllMealsFlow(): Flow<List<MealEntry>>
    
    @Query("SELECT * FROM meal_entries")
    suspend fun getAllMeals(): List<MealEntry>
}

@Database(entities = [MealEntry::class], version = 4, exportSchema = false)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun mealDao(): MealDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE meal_entries ADD COLUMN dishes TEXT NOT NULL DEFAULT '[]'")
            }
        }

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "nutrilens_db"
                )
                .addMigrations(MIGRATION_3_4)
                .fallbackToDestructiveMigration()
                .build().also { INSTANCE = it }
            }
        }

        fun checkpoint(context: Context) {
            val db = getDatabase(context)
            val supportDb = db.openHelper.writableDatabase
            supportDb.query("PRAGMA wal_checkpoint(TRUNCATE)").use { cursor ->
                cursor.moveToFirst()
            }
        }

        fun closeAndReset() {
            synchronized(this) {
                if (INSTANCE?.isOpen == true) {
                    INSTANCE?.close()
                }
                INSTANCE = null
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
            
            Also provide a bounding box `cropBox` enclosing all visible food, plates, bowls, dishes, and drinks, normalized from 0.0 to 1.0 (top, left, bottom, right). Leave slight padding around the tableware if possible.
            
            Return ONLY a valid JSON object matching this exact structure:
            {
              "mealDescription": "Concise name of the meal and components",
              "totalCalories": 0,
              "proteinGrams": 0,
              "carbsGrams": 0,
              "fatGrams": 0,
              "confidenceNote": "Brief note on portion assumptions made",
              "cropBox": {
                "top": 0.1,
                "left": 0.1,
                "bottom": 0.9,
                "right": 0.9
              }
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

    suspend fun detectPlateCropBox(apiKey: String, bitmap: Bitmap): Result<FoodCropBox> {
        if (apiKey.isBlank()) {
            return Result.failure(Exception("Missing Gemini API Key. Tap the gear icon to set it."))
        }

        val model = GenerativeModel(
            modelName = "gemini-3.8-flash",
            apiKey = apiKey.trim(),
            generationConfig = generationConfig {
                responseMimeType = "application/json"
                temperature = 0.1f
            }
        )

        val scaledBitmap = scaleBitmap(bitmap, 1024)

        val prompt = """
            Detect all food items, plates, bowls, dishes, and drinks in this image.
            Provide a bounding box `cropBox` enclosing all of them, normalized from 0.0 to 1.0 (top, left, bottom, right).
            Include a comfortable margin around the tableware and plates.
            
            Return ONLY a valid JSON object matching this exact structure:
            {
              "cropBox": {
                "top": 0.05,
                "left": 0.05,
                "bottom": 0.95,
                "right": 0.95
              }
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
            val box = parsed.cropBox ?: throw Exception("No crop box detected in response")
            Result.success(box)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun analyzeFoodText(
        apiKey: String,
        textDescription: String
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

        val prompt = """
            Estimate the nutritional content for this described food or meal:
            "$textDescription"
            
            Identify the food items, estimate reasonable portion sizes based on typical servings, and calculate the combined total calories and macronutrients (protein, carbs, fat in grams).
            
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
            val response = model.generateContent(prompt)
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

fun cropBitmapToBoundingBox(originalBitmap: Bitmap, cropBox: FoodCropBox): Bitmap {
    val origW = originalBitmap.width
    val origH = originalBitmap.height

    // Clamp coordinates to [0.0, 1.0]
    val topF = cropBox.top.coerceIn(0.0, 0.95)
    val leftF = cropBox.left.coerceIn(0.0, 0.95)
    val bottomF = cropBox.bottom.coerceIn(topF + 0.05, 1.0)
    val rightF = cropBox.right.coerceIn(leftF + 0.05, 1.0)

    val x = (leftF * origW).toInt().coerceIn(0, origW - 1)
    val y = (topF * origH).toInt().coerceIn(0, origH - 1)
    val width = ((rightF - leftF) * origW).toInt().coerceIn(1, origW - x)
    val height = ((bottomF - topF) * origH).toInt().coerceIn(1, origH - y)

    return try {
        Bitmap.createBitmap(originalBitmap, x, y, width, height)
    } catch (e: Exception) {
        originalBitmap
    }
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
    val visionService: FoodVisionService,
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
    
    fun processNewImage(imagePath: String, apiKey: String, userNote: String, mealGroupingWindowMinutes: Int, capturedTimestamp: Long = System.currentTimeMillis(), autoCrop: Boolean = false) {
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
        analyzePhoto(apiKey, imagePath, userNote, autoCrop)
    }
    
    fun onMealGroupChoice(choice: MealGroupChoice, imagePath: String, targetMeal: MealEntry?, capturedTimestamp: Long, apiKey: String, userNote: String, autoCrop: Boolean = false) {
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
                    analyzePhoto(apiKey, imagePath, context, autoCrop)
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
                    analyzePhoto(apiKey, imagePath, context, autoCrop)
                }
            }
            MealGroupChoice.NEW_MEAL -> {
                updatingMealTarget = null
                isSameDishMode = false
                val capturedDate = Instant.ofEpochMilli(capturedTimestamp).atZone(ZoneId.systemDefault()).toLocalDate()
                if (_selectedDate.value != capturedDate) {
                    _selectedDate.value = capturedDate
                }
                analyzePhoto(apiKey, imagePath, userNote, autoCrop)
            }
        }
    }

    private fun analyzePhoto(apiKey: String, imagePath: String, contextNote: String, autoCrop: Boolean = false) {
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
                if (autoCrop && analysis.cropBox != null) {
                    try {
                        val origFile = File(imagePath.replace(".jpg", "_orig.jpg"))
                        if (!origFile.exists()) {
                            File(imagePath).copyTo(origFile, overwrite = false)
                        }

                        val cropped = cropBitmapToBoundingBox(bitmap, analysis.cropBox)
                        if (cropped != bitmap) {
                            FileOutputStream(File(imagePath)).use { out ->
                                cropped.compress(Bitmap.CompressFormat.JPEG, 92, out)
                            }
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }

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
                        // Another dish in same meal:
                        // Keep pendingAnalysis focused on THIS new dish!
                        // This allows applying the quantity multiplier specifically to THIS dish (e.g. 8x for 1/8 meat)
                        pendingAnalysis = analysis
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

    fun confirmAndSaveMeal(
        description: String,
        calories: Int,
        protein: Int,
        carbs: Int,
        fat: Int,
        timestamp: Long,
        multiplier: Float = 1.0f,
        baseCalories: Int = calories,
        baseProtein: Int = protein,
        baseCarbs: Int = carbs,
        baseFat: Int = fat
    ) {
        viewModelScope.launch {
            val target = updatingMealTarget
            if (target != null) {
                if (isSameDishMode) {
                    val updatedPaths = if (pendingImagePath != null && !target.imagePaths.contains(pendingImagePath)) {
                        target.imagePaths + pendingImagePath!!
                    } else {
                        target.imagePaths
                    }
                    val existingDishes = target.getEffectiveDishes()
                    val updatedDishes = if (existingDishes.isNotEmpty()) {
                        existingDishes.mapIndexed { index, dish ->
                            if (index == 0) {
                                dish.copy(
                                    description = description,
                                    calories = calories,
                                    protein = protein,
                                    carbs = carbs,
                                    fat = fat,
                                    multiplier = multiplier,
                                    baseCalories = baseCalories,
                                    baseProtein = baseProtein,
                                    baseCarbs = baseCarbs,
                                    baseFat = baseFat
                                )
                            } else dish
                        }
                    } else emptyList()

                    dao.updateMeal(
                        target.copy(
                            description = description,
                            calories = calories,
                            protein = protein,
                            carbs = carbs,
                            fat = fat,
                            timestamp = timestamp,
                            imagePaths = updatedPaths,
                            dishes = updatedDishes
                        )
                    )
                } else {
                    // Adding dish to existing meal:
                    // 'description', 'calories', 'protein', 'carbs', 'fat' are for THIS newly added dish (multiplied by user)
                    val newDish = DishEntry(
                        imagePath = pendingImagePath,
                        description = description,
                        calories = calories,
                        protein = protein,
                        carbs = carbs,
                        fat = fat,
                        multiplier = multiplier,
                        baseCalories = baseCalories,
                        baseProtein = baseProtein,
                        baseCarbs = baseCarbs,
                        baseFat = baseFat
                    )
                    val updatedDishes = target.getEffectiveDishes() + newDish
                    val combinedDesc = if (target.description.isNotBlank()) {
                        "${target.description} + $description"
                    } else {
                        description
                    }
                    val updatedPaths = if (pendingImagePath != null && !target.imagePaths.contains(pendingImagePath)) {
                        target.imagePaths + pendingImagePath!!
                    } else {
                        target.imagePaths
                    }
                    dao.updateMeal(
                        target.copy(
                            description = combinedDesc,
                            calories = target.calories + calories,
                            protein = target.protein + protein,
                            carbs = target.carbs + carbs,
                            fat = target.fat + fat,
                            timestamp = timestamp,
                            imagePaths = updatedPaths,
                            dishes = updatedDishes
                        )
                    )
                }
            } else {
                val paths = pendingImagePath?.let { listOf(it) } ?: emptyList()
                val dateString = Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()).toLocalDate().toString()
                val newDish = DishEntry(
                    imagePath = pendingImagePath,
                    description = description,
                    calories = calories,
                    protein = protein,
                    carbs = carbs,
                    fat = fat,
                    multiplier = multiplier,
                    baseCalories = baseCalories,
                    baseProtein = baseProtein,
                    baseCarbs = baseCarbs,
                    baseFat = baseFat
                )
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
                        imagePaths = paths,
                        dishes = listOf(newDish)
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

    fun updateMeal(meal: MealEntry) {
        viewModelScope.launch {
            dao.updateMeal(meal)
        }
    }

    fun insertManualMeal(
        description: String,
        calories: Int,
        protein: Int,
        carbs: Int,
        fat: Int,
        targetDate: LocalDate,
        hour: Int,
        minute: Int,
        multiplier: Float = 1.0f,
        baseCalories: Int = calories,
        baseProtein: Int = protein,
        baseCarbs: Int = carbs,
        baseFat: Int = fat
    ) {
        viewModelScope.launch {
            val localTime = LocalTime.of(hour.coerceIn(0, 23), minute.coerceIn(0, 59))
            val targetDateTime = LocalDateTime.of(targetDate, localTime)
            val timestamp = targetDateTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

            val dish = DishEntry(
                imagePath = null,
                description = description,
                calories = calories,
                protein = protein,
                carbs = carbs,
                fat = fat,
                multiplier = multiplier,
                baseCalories = baseCalories,
                baseProtein = baseProtein,
                baseCarbs = baseCarbs,
                baseFat = baseFat
            )

            val mealEntry = MealEntry(
                timestamp = timestamp,
                initialTimestamp = timestamp,
                dateString = targetDate.toString(),
                description = description,
                calories = calories,
                protein = protein,
                carbs = carbs,
                fat = fat,
                imagePaths = emptyList(),
                dishes = listOf(dish)
            )

            dao.insertMeal(mealEntry)
            _selectedDate.value = targetDate
        }
    }

    fun duplicateMeal(meal: MealEntry, targetDate: LocalDate, targetHour: Int, targetMinute: Int) {
        viewModelScope.launch {
            val localTime = LocalTime.of(targetHour.coerceIn(0, 23), targetMinute.coerceIn(0, 59))
            val targetDateTime = LocalDateTime.of(targetDate, localTime)
            val newTimestamp = targetDateTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

            val duplicatedMeal = MealEntry(
                timestamp = newTimestamp,
                initialTimestamp = newTimestamp,
                dateString = targetDate.toString(),
                description = meal.description,
                calories = meal.calories,
                protein = meal.protein,
                carbs = meal.carbs,
                fat = meal.fat,
                imagePaths = meal.imagePaths,
                dishes = meal.dishes
            )
            dao.insertMeal(duplicatedMeal)
            _selectedDate.value = targetDate
        }
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

        // Schedule periodic Google Drive backup (WorkManager)
        DriveBackupWorker.schedulePeriodicBackup(this)

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

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun CalorieTrackerApp(viewModel: CalorieViewModel, context: Context) {
    val prefs = remember { context.getSharedPreferences("nutrilens_prefs", Context.MODE_PRIVATE) }
    var apiKey by remember { mutableStateOf(prefs.getString("gemini_api_key", "") ?: "") }
    var dailyGoal by remember { mutableIntStateOf(prefs.getInt("daily_goal", 2200)) }
    var mealGroupingWindowMinutes by remember { mutableIntStateOf(prefs.getInt("meal_grouping_window_minutes", 60)) }
    var imageDownscaleDays by remember { mutableIntStateOf(prefs.getInt("image_downscale_days", 7)) }
    var autoCropFood by remember { mutableStateOf(prefs.getBoolean("auto_crop_food", true)) }
    var sortAscendingAndScrollBottom by remember { mutableStateOf(prefs.getBoolean("sort_ascending_scroll_bottom", true)) }
    
    var showSettingsDialog by remember { mutableStateOf(apiKey.isBlank()) }
    var showMonthlySummaryDialog by remember { mutableStateOf(false) }
    var mealToDelete by remember { mutableStateOf<MealEntry?>(null) }
    var editingMeal by remember { mutableStateOf<MealEntry?>(null) }
    var mealToDuplicate by remember { mutableStateOf<MealEntry?>(null) }
    var showManualEntryDialog by remember { mutableStateOf(false) }
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
                viewModel.processNewImage(path, apiKey, userNote, mealGroupingWindowMinutes, autoCrop = autoCropFood)
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
            viewModel.processNewImage(newFile.absolutePath, apiKey, userNote, mealGroupingWindowMinutes, finalTimestamp, autoCrop = autoCropFood)
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

            // Action Buttons (Camera, Gallery & Manual Entry)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
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
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp)
                ) {
                    Icon(Icons.Default.PhotoCamera, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Camera", maxLines = 1)
                }

                OutlinedButton(
                    onClick = {
                        galleryLauncher.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    },
                    enabled = !viewModel.isAnalyzing,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp)
                ) {
                    Icon(Icons.Default.PhotoLibrary, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Gallery", maxLines = 1)
                }

                OutlinedButton(
                    onClick = { showManualEntryDialog = true },
                    enabled = !viewModel.isAnalyzing,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp)
                ) {
                    Icon(Icons.Default.EditNote, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Manual", maxLines = 1)
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

            // Displayed meals list according to sort preference
            val displayedMeals = remember(meals, sortAscendingAndScrollBottom) {
                if (sortAscendingAndScrollBottom) meals else meals.reversed()
            }

            // Logged Meals List State (scrolls to bottom on load/update if enabled)
            val listState = rememberLazyListState()
            LaunchedEffect(displayedMeals.size, selectedDate, sortAscendingAndScrollBottom) {
                if (sortAscendingAndScrollBottom && displayedMeals.isNotEmpty()) {
                    listState.scrollToItem(displayedMeals.size - 1)
                } else if (!sortAscendingAndScrollBottom && displayedMeals.isNotEmpty()) {
                    listState.scrollToItem(0)
                }
            }

            // Logged Meals List
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(displayedMeals, key = { it.id }) { meal ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .combinedClickable(
                                onClick = { /* Normal tap */ },
                                onLongClick = { editingMeal = meal }
                            )
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 10.dp)
                        ) {
                            // 1. Full-width meal description
                            Text(
                                text = meal.description,
                                fontWeight = FontWeight.SemiBold,
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.fillMaxWidth()
                            )

                            // Multiplier badges if any dish has a non-1.0x multiplier
                            val multipliers = remember(meal.dishes, meal.calories) {
                                meal.getEffectiveDishes().filter { Math.abs(it.multiplier - 1.0f) > 0.05f }
                            }
                            if (multipliers.isNotEmpty()) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    multipliers.forEach { dish ->
                                        val multLabel = if (dish.multiplier % 1f == 0f) "${dish.multiplier.toInt()}x" else "${dish.multiplier}x"
                                        val textLabel = if (meal.getEffectiveDishes().size > 1 && dish.description.isNotBlank()) {
                                            "${dish.description}: $multLabel portion"
                                        } else {
                                            "$multLabel portion"
                                        }
                                        Surface(
                                            color = MaterialTheme.colorScheme.tertiaryContainer,
                                            shape = RoundedCornerShape(4.dp)
                                        ) {
                                            Text(
                                                text = textLabel,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onTertiaryContainer,
                                                fontWeight = FontWeight.SemiBold,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                            )
                                        }
                                    }
                                }
                            }

                            // 2. Photos (if any)
                            if (meal.imagePaths.isNotEmpty()) {
                                Spacer(modifier = Modifier.height(8.dp))
                                LazyRow(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    items(meal.imagePaths) { path ->
                                        val bitmap = remember(path) {
                                            val options = BitmapFactory.Options().apply {
                                                inJustDecodeBounds = true
                                                BitmapFactory.decodeFile(path, this)
                                                val reqWidth = 200
                                                val reqHeight = 200
                                                var inSampleSize = 1
                                                if (outHeight > reqHeight || outWidth > reqWidth) {
                                                    val halfHeight = outHeight / 2
                                                    val halfWidth = outWidth / 2
                                                    while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                                                        inSampleSize *= 2
                                                    }
                                                }
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
                                                    .clip(RoundedCornerShape(8.dp))
                                                    .clickable { fullScreenImagePath = path },
                                                contentScale = ContentScale.Crop
                                            )
                                        }
                                    }
                                }
                            }

                            // 3. Bottom Row: Left-aligned timestamps and macros, Right-aligned calories & action icons
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(end = 8.dp),
                                    verticalArrangement = Arrangement.spacedBy(2.dp)
                                ) {
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

                                    Text(
                                        text = timeDisplay,
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Text(
                                        text = "p: ${meal.protein}g, c: ${meal.carbs}g, f: ${meal.fat}g",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }

                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                                ) {
                                    Text(
                                        text = "${meal.calories} kcal",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold
                                    )
                                    IconButton(
                                        onClick = { mealToDuplicate = meal },
                                        modifier = Modifier.size(36.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.ContentCopy,
                                            contentDescription = "Duplicate Meal",
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(19.dp)
                                        )
                                    }
                                    IconButton(
                                        onClick = { editingMeal = meal },
                                        modifier = Modifier.size(36.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Edit,
                                            contentDescription = "Edit Meal",
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(19.dp)
                                        )
                                    }
                                    IconButton(
                                        onClick = { mealToDelete = meal },
                                        modifier = Modifier.size(36.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Delete,
                                            contentDescription = "Delete",
                                            tint = MaterialTheme.colorScheme.error.copy(alpha = 0.8f),
                                            modifier = Modifier.size(19.dp)
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
                            viewModel.onMealGroupChoice(MealGroupChoice.SAME_DISH, imagePath, targetMeal, capturedTimestamp, apiKey, userNote, autoCrop = autoCropFood)
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Same Dish (Refine / Average)")
                    }
                    Button(
                        onClick = {
                            viewModel.onMealGroupChoice(MealGroupChoice.ADD_DISH_TO_MEAL, imagePath, targetMeal, capturedTimestamp, apiKey, userNote, autoCrop = autoCropFood)
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("New Dish in this Meal (Add calories)")
                    }
                    OutlinedButton(
                        onClick = {
                            viewModel.onMealGroupChoice(MealGroupChoice.NEW_MEAL, imagePath, targetMeal, capturedTimestamp, apiKey, userNote, autoCrop = autoCropFood)
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

    // Review & Edit AI Analysis Dialog (for newly captured photo)
    viewModel.pendingAnalysis?.let { analysis ->
        val initialTimestamp = viewModel.pendingTimestamp ?: System.currentTimeMillis()
        val paths = viewModel.pendingImagePath?.let { listOf(it) } ?: emptyList()
        val target = viewModel.updatingMealTarget
        val isAddingDish = target != null && !viewModel.isSameDishMode

        ReviewAnalysisDialog(
            analysis = analysis,
            initialTimestamp = initialTimestamp,
            imagePaths = paths,
            apiKey = apiKey,
            visionService = viewModel.visionService,
            title = if (isAddingDish) "Review New Dish (Adding to Meal)" else "Review AI Estimate",
            confirmButtonText = if (isAddingDish) "Add Dish to Meal" else "Log Meal",
            targetMeal = if (isAddingDish) target else null,
            onConfirm = { desc, cal, pro, carb, fat, timestamp, mult, baseCal, basePro, baseCarb, baseFat ->
                viewModel.confirmAndSaveMeal(desc, cal, pro, carb, fat, timestamp, mult, baseCal, basePro, baseCarb, baseFat)
            },
            onDismiss = { viewModel.dismissPendingAnalysis() }
        )
    }

    // Edit Existing Meal Dialog (from long-press or edit button)
    editingMeal?.let { meal ->
        val dishes = remember(meal) { meal.getEffectiveDishes() }
        var selectedDishIndex by remember { mutableIntStateOf(0) }
        val currentDish = dishes.getOrNull(selectedDishIndex) ?: dishes.first()

        ReviewAnalysisDialog(
            analysis = AiMealAnalysis(
                mealDescription = currentDish.description,
                totalCalories = currentDish.effectiveBaseCalories.toDouble(),
                proteinGrams = currentDish.effectiveBaseProtein.toDouble(),
                carbsGrams = currentDish.effectiveBaseCarbs.toDouble(),
                fatGrams = currentDish.effectiveBaseFat.toDouble()
            ),
            initialTimestamp = meal.timestamp,
            imagePaths = if (dishes.size > 1 && currentDish.imagePath != null) listOf(currentDish.imagePath) else meal.imagePaths,
            apiKey = apiKey,
            visionService = viewModel.visionService,
            title = if (dishes.size > 1) "Edit Dish (${selectedDishIndex + 1} of ${dishes.size})" else "Review AI Estimate",
            confirmButtonText = "Save",
            initialMultiplier = currentDish.multiplier,
            initialBaseCalories = currentDish.effectiveBaseCalories,
            initialBaseProtein = currentDish.effectiveBaseProtein,
            initialBaseCarbs = currentDish.effectiveBaseCarbs,
            initialBaseFat = currentDish.effectiveBaseFat,
            dishTabs = if (dishes.size > 1) dishes.map {
                val multStr = if (it.multiplier != 1.0f) " (${if (it.multiplier % 1f == 0f) it.multiplier.toInt() else it.multiplier}x)" else ""
                "${it.description.ifBlank { "Dish" }}$multStr"
            } else emptyList(),
            selectedDishIndex = selectedDishIndex,
            onSelectDishTab = { selectedDishIndex = it },
            onConfirm = { desc, cal, pro, carb, fat, timestamp, mult, baseCal, basePro, baseCarb, baseFat ->
                val dateStr = Instant.ofEpochMilli(timestamp)
                    .atZone(ZoneId.systemDefault())
                    .toLocalDate()
                    .toString()

                if (dishes.size > 1) {
                    val updatedDishes = dishes.toMutableList()
                    updatedDishes[selectedDishIndex] = currentDish.copy(
                        description = desc,
                        calories = cal,
                        protein = pro,
                        carbs = carb,
                        fat = fat,
                        multiplier = mult,
                        baseCalories = baseCal,
                        baseProtein = basePro,
                        baseCarbs = baseCarb,
                        baseFat = baseFat
                    )
                    viewModel.updateMeal(
                        meal.copy(
                            description = updatedDishes.joinToString(" + ") { it.description },
                            calories = updatedDishes.sumOf { it.calories },
                            protein = updatedDishes.sumOf { it.protein },
                            carbs = updatedDishes.sumOf { it.carbs },
                            fat = updatedDishes.sumOf { it.fat },
                            timestamp = timestamp,
                            dateString = dateStr,
                            dishes = updatedDishes
                        )
                    )
                } else {
                    val updatedDish = DishEntry(
                        imagePath = meal.imagePaths.firstOrNull(),
                        description = desc,
                        calories = cal,
                        protein = pro,
                        carbs = carb,
                        fat = fat,
                        multiplier = mult,
                        baseCalories = baseCal,
                        baseProtein = basePro,
                        baseCarbs = baseCarb,
                        baseFat = baseFat
                    )
                    viewModel.updateMeal(
                        meal.copy(
                            description = desc,
                            calories = cal,
                            protein = pro,
                            carbs = carb,
                            fat = fat,
                            timestamp = timestamp,
                            dateString = dateStr,
                            dishes = listOf(updatedDish)
                        )
                    )
                }
                editingMeal = null
            },
            onDismiss = { editingMeal = null }
        )
    }

    // Duplicate Meal Dialog (opens calendar and hour dialogue)
    mealToDuplicate?.let { meal ->
        DuplicateMealDialog(
            meal = meal,
            onConfirm = { targetDate, targetHour, targetMinute ->
                viewModel.duplicateMeal(meal, targetDate, targetHour, targetMinute)
                mealToDuplicate = null
            },
            onDismiss = { mealToDuplicate = null }
        )
    }

    // Manual Meal Entry Dialog
    if (showManualEntryDialog) {
        ManualEntryDialog(
            initialDescription = userNote,
            initialDate = selectedDate,
            apiKey = apiKey,
            visionService = viewModel.visionService,
            onConfirm = { desc, cal, pro, carb, fat, targetDate, hour, minute, mult, baseCal, basePro, baseCarb, baseFat ->
                viewModel.insertManualMeal(
                    description = desc,
                    calories = cal,
                    protein = pro,
                    carbs = carb,
                    fat = fat,
                    targetDate = targetDate,
                    hour = hour,
                    minute = minute,
                    multiplier = mult,
                    baseCalories = baseCal,
                    baseProtein = basePro,
                    baseCarbs = baseCarb,
                    baseFat = baseFat
                )
                userNote = ""
                showManualEntryDialog = false
            },
            onDismiss = { showManualEntryDialog = false }
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
            currentAutoCrop = autoCropFood,
            currentSortAscending = sortAscendingAndScrollBottom,
            onSave = { newKey, newGoal, newWindow, newDays, newAutoCrop, newSortAscending ->
                apiKey = newKey
                dailyGoal = newGoal
                mealGroupingWindowMinutes = newWindow
                imageDownscaleDays = newDays
                autoCropFood = newAutoCrop
                sortAscendingAndScrollBottom = newSortAscending
                prefs.edit()
                    .putString("gemini_api_key", newKey)
                    .putInt("daily_goal", newGoal)
                    .putInt("meal_grouping_window_minutes", newWindow)
                    .putInt("image_downscale_days", newDays)
                    .putBoolean("auto_crop_food", newAutoCrop)
                    .putBoolean("sort_ascending_scroll_bottom", newSortAscending)
                    .apply()
                showSettingsDialog = false
            },
            onRestoreComplete = {
                showSettingsDialog = false
                (context as? Activity)?.recreate()
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
    imagePaths: List<String> = emptyList(),
    apiKey: String = "",
    visionService: FoodVisionService? = null,
    title: String = "Review AI Estimate",
    confirmButtonText: String = "Log Meal",
    initialMultiplier: Float = 1.0f,
    initialBaseCalories: Int? = null,
    initialBaseProtein: Int? = null,
    initialBaseCarbs: Int? = null,
    initialBaseFat: Int? = null,
    targetMeal: MealEntry? = null,
    dishTabs: List<String> = emptyList(),
    selectedDishIndex: Int = 0,
    onSelectDishTab: (Int) -> Unit = {},
    onConfirm: (
        description: String,
        calories: Int,
        protein: Int,
        carbs: Int,
        fat: Int,
        timestamp: Long,
        multiplier: Float,
        baseCalories: Int,
        baseProtein: Int,
        baseCarbs: Int,
        baseFat: Int
    ) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val baseCalories = remember(analysis, initialBaseCalories) {
        initialBaseCalories ?: analysis.totalCaloriesInt
    }
    val baseProtein = remember(analysis, initialBaseProtein) {
        initialBaseProtein ?: analysis.proteinGramsInt
    }
    val baseCarbs = remember(analysis, initialBaseCarbs) {
        initialBaseCarbs ?: analysis.carbsGramsInt
    }
    val baseFat = remember(analysis, initialBaseFat) {
        initialBaseFat ?: analysis.fatGramsInt
    }

    var multiplier by remember(analysis, initialMultiplier) { mutableFloatStateOf(initialMultiplier) }

    var desc by remember(analysis) { mutableStateOf(analysis.mealDescription) }
    var calories by remember(analysis, initialMultiplier, baseCalories) {
        mutableStateOf((baseCalories * initialMultiplier).roundToInt().toString())
    }
    var protein by remember(analysis, initialMultiplier, baseProtein) {
        mutableStateOf((baseProtein * initialMultiplier).roundToInt().toString())
    }
    var carbs by remember(analysis, initialMultiplier, baseCarbs) {
        mutableStateOf((baseCarbs * initialMultiplier).roundToInt().toString())
    }
    var fat by remember(analysis, initialMultiplier, baseFat) {
        mutableStateOf((baseFat * initialMultiplier).roundToInt().toString())
    }

    var showCustomMultiplierDialog by remember { mutableStateOf(false) }
    var customMultiplierInput by remember { mutableStateOf("") }

    fun applyMultiplier(m: Float) {
        val newCal = (baseCalories * m).roundToInt()
        val newPro = (baseProtein * m).roundToInt()
        val newCarb = (baseCarbs * m).roundToInt()
        val newFat = (baseFat * m).roundToInt()
        calories = newCal.toString()
        protein = newPro.toString()
        carbs = newCarb.toString()
        fat = newFat.toString()
    }

    // Parse initial hour and minute
    val initialDateTime = remember(initialTimestamp) {
        Instant.ofEpochMilli(initialTimestamp).atZone(ZoneId.systemDefault()).toLocalDateTime()
    }
    var hourInput by remember { mutableStateOf(initialDateTime.format(DateTimeFormatter.ofPattern("HH"))) }
    var minuteInput by remember { mutableStateOf(initialDateTime.format(DateTimeFormatter.ofPattern("mm"))) }

    // Selected image from list (default to first)
    var selectedImagePath by remember(imagePaths) { mutableStateOf(imagePaths.firstOrNull()) }
    var reloadTrigger by remember { mutableIntStateOf(0) }

    val currentBitmap = remember(selectedImagePath, reloadTrigger) {
        selectedImagePath?.let { path ->
            try {
                val options = BitmapFactory.Options().apply {
                    inJustDecodeBounds = true
                    BitmapFactory.decodeFile(path, this)
                    var sampleSize = 1
                    val maxDim = 1024
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
            } catch (e: Exception) {
                null
            }
        }
    }

    val origFile = remember(selectedImagePath, reloadTrigger) {
        selectedImagePath?.let { path ->
            val file = File(path.replace(".jpg", "_orig.jpg"))
            if (file.exists()) file else null
        }
    }

    var isCropping by remember { mutableStateOf(false) }
    var cropStatusMessage by remember { mutableStateOf<String?>(null) }

    // Gallery picker to replace incorrectly cropped photo with full original if needed
    val pickOriginalLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null && selectedImagePath != null) {
            try {
                val destFile = File(selectedImagePath!!)
                val backupOrig = File(selectedImagePath!!.replace(".jpg", "_orig.jpg"))

                // Copy picked image to original backup AND active file
                context.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(destFile).use { output ->
                        input.copyTo(output)
                    }
                }
                destFile.copyTo(backupOrig, overwrite = true)
                reloadTrigger++
                cropStatusMessage = "Loaded uncropped photo. You can use it as-is or tap 'Re-crop AI'."
            } catch (e: Exception) {
                cropStatusMessage = "Failed to load image: ${e.message}"
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Dish selector tabs when editing a multi-dish meal
                if (dishTabs.size > 1) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = "Select Dish to Edit / Scale:",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold
                        )
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            items(dishTabs.size) { index ->
                                val dishName = dishTabs[index]
                                FilterChip(
                                    selected = index == selectedDishIndex,
                                    onClick = { onSelectDishTab(index) },
                                    label = { Text("Dish ${index + 1}: $dishName", maxLines = 1) },
                                    modifier = Modifier.height(32.dp)
                                )
                            }
                        }
                    }
                }

                // If adding to an existing meal: Show existing meal summary banner
                if (targetMeal != null) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f))
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = "Adding to: ${targetMeal.description}",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                            val thisCal = calories.toIntOrNull() ?: 0
                            val totalCal = targetMeal.calories + thisCal
                            Text(
                                text = "Existing: ${targetMeal.calories} kcal  +  This dish: $thisCal kcal  =  $totalCal kcal total",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        }
                    }
                }

                // Image Preview Card & Cropping Actions
                if (currentBitmap != null && selectedImagePath != null) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                    ) {
                        Column(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
                            // If multiple photos, let user choose which to view
                            if (imagePaths.size > 1) {
                                LazyRow(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    modifier = Modifier.padding(bottom = 6.dp)
                                ) {
                                    items(imagePaths) { path ->
                                        val isSel = path == selectedImagePath
                                        val thumb = remember(path, reloadTrigger) {
                                            try {
                                                val opt = BitmapFactory.Options().apply { inSampleSize = 4 }
                                                BitmapFactory.decodeFile(path, opt)
                                            } catch (e: Exception) { null }
                                        }
                                        if (thumb != null) {
                                            Image(
                                                bitmap = thumb.asImageBitmap(),
                                                contentDescription = null,
                                                modifier = Modifier
                                                    .size(44.dp)
                                                    .clickable { selectedImagePath = path }
                                                    .then(
                                                        if (isSel) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(4.dp))
                                                        else Modifier
                                                    ),
                                                contentScale = ContentScale.Crop
                                            )
                                        }
                                    }
                                }
                            }

                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(190.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Image(
                                    bitmap = currentBitmap.asImageBitmap(),
                                    contentDescription = "Meal photo preview",
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Fit
                                )
                            }

                            if (cropStatusMessage != null) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    cropStatusMessage!!,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }

                            if (isCropping) {
                                Spacer(modifier = Modifier.height(6.dp))
                                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            } else {
                                Spacer(modifier = Modifier.height(6.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    // Button: Re-crop with AI
                                    OutlinedButton(
                                        onClick = {
                                            if (apiKey.isBlank()) {
                                                cropStatusMessage = "Gemini API key is required to re-crop."
                                                return@OutlinedButton
                                            }
                                            if (visionService == null) {
                                                cropStatusMessage = "Vision service not available."
                                                return@OutlinedButton
                                            }
                                            isCropping = true
                                            cropStatusMessage = "AI detecting plate & re-cropping..."
                                            scope.launch {
                                                val sourceBitmap = (if (origFile?.exists() == true) {
                                                    BitmapFactory.decodeFile(origFile.absolutePath)
                                                } else null) ?: BitmapFactory.decodeFile(selectedImagePath!!)

                                                if (sourceBitmap == null) {
                                                    cropStatusMessage = "Could not decode source photo."
                                                    isCropping = false
                                                    return@launch
                                                }

                                                val cropResult = visionService.detectPlateCropBox(apiKey, sourceBitmap)
                                                cropResult.onSuccess { newBox ->
                                                    try {
                                                        // Ensure original is saved if not already
                                                        val backupOrig = File(selectedImagePath!!.replace(".jpg", "_orig.jpg"))
                                                        if (!backupOrig.exists()) {
                                                            FileOutputStream(backupOrig).use { out ->
                                                                sourceBitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)
                                                            }
                                                        }

                                                        val newlyCropped = cropBitmapToBoundingBox(sourceBitmap, newBox)
                                                        FileOutputStream(File(selectedImagePath!!)).use { out ->
                                                            newlyCropped.compress(Bitmap.CompressFormat.JPEG, 92, out)
                                                        }
                                                        reloadTrigger++
                                                        cropStatusMessage = "Successfully re-cropped with AI!"
                                                    } catch (e: Exception) {
                                                        cropStatusMessage = "Error saving cropped photo: ${e.message}"
                                                    }
                                                }.onFailure { err ->
                                                    cropStatusMessage = "Re-crop failed: ${err.message}"
                                                }
                                                isCropping = false
                                            }
                                        },
                                        modifier = Modifier.weight(1f),
                                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp)
                                    ) {
                                        Icon(Icons.Default.Crop, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text("Re-crop AI", style = MaterialTheme.typography.labelSmall)
                                    }

                                    // Button: Use Original / Pick Original
                                    if (origFile?.exists() == true) {
                                        OutlinedButton(
                                            onClick = {
                                                try {
                                                    val origBitmap = BitmapFactory.decodeFile(origFile.absolutePath)
                                                    if (origBitmap != null) {
                                                        origFile.copyTo(File(selectedImagePath!!), overwrite = true)
                                                        reloadTrigger++
                                                        cropStatusMessage = "Restored original uncropped photo."
                                                    }
                                                } catch (e: Exception) {
                                                    cropStatusMessage = "Error restoring original: ${e.message}"
                                                }
                                            },
                                            modifier = Modifier.weight(1f),
                                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp)
                                        ) {
                                            Icon(Icons.Default.CropFree, contentDescription = null, modifier = Modifier.size(16.dp))
                                            Spacer(Modifier.width(4.dp))
                                            Text("Use Original", style = MaterialTheme.typography.labelSmall)
                                        }
                                    } else {
                                        OutlinedButton(
                                            onClick = {
                                                pickOriginalLauncher.launch(
                                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                                )
                                            },
                                            modifier = Modifier.weight(1f),
                                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp)
                                        ) {
                                            Icon(Icons.Default.PhotoLibrary, contentDescription = null, modifier = Modifier.size(16.dp))
                                            Spacer(Modifier.width(4.dp))
                                            Text("Pick Original", style = MaterialTheme.typography.labelSmall)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

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
                    label = { Text("Meal Description") },
                    modifier = Modifier.fillMaxWidth()
                )

                // Portion / Quantity Multiplier Card
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Dish Portion Multiplier",
                                    fontWeight = FontWeight.SemiBold,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                val subtitleText = when {
                                    Math.abs(multiplier - 1.0f) < 0.01f -> if (targetMeal != null) "100% of this dish photo (scales only this dish)" else "100% of photo estimate"
                                    Math.abs(multiplier - 8.0f) < 0.05f -> "8x (ate ⅞ before photo / ⅛ in photo)"
                                    Math.abs(multiplier - 4.0f) < 0.05f -> "4x (ate ¾ before photo / ¼ in photo)"
                                    Math.abs(multiplier - 2.0f) < 0.05f -> "2x (ate ½ before photo / ½ in photo)"
                                    else -> "${Math.round(multiplier * 100)}% of this dish photo"
                                }
                                Text(
                                    text = subtitleText,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                FilledTonalIconButton(
                                    onClick = {
                                        val step = if (multiplier > 2.0f) 0.5f else 0.25f
                                        val newMult = (multiplier - step).coerceAtLeast(0.25f)
                                        val rounded = Math.round(newMult * 100f) / 100f
                                        multiplier = rounded
                                        applyMultiplier(rounded)
                                    },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(Icons.Default.Remove, contentDescription = "Decrease portion", modifier = Modifier.size(16.dp))
                                }

                                Surface(
                                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f),
                                    shape = RoundedCornerShape(6.dp),
                                    modifier = Modifier.clickable {
                                        customMultiplierInput = if (multiplier % 1.0f == 0.0f) multiplier.toInt().toString() else multiplier.toString()
                                        showCustomMultiplierDialog = true
                                    }
                                ) {
                                    Text(
                                        text = String.format(Locale.US, "%.2fx", multiplier),
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }

                                FilledTonalIconButton(
                                    onClick = {
                                        val step = if (multiplier >= 2.0f) 0.5f else 0.25f
                                        val newMult = (multiplier + step).coerceAtMost(20.0f)
                                        val rounded = Math.round(newMult * 100f) / 100f
                                        multiplier = rounded
                                        applyMultiplier(rounded)
                                    },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(Icons.Default.Add, contentDescription = "Increase portion", modifier = Modifier.size(16.dp))
                                }
                            }
                        }

                        // Quick preset chips including 4x and 8x
                        val presets = listOf(0.5f, 0.75f, 1.0f, 1.5f, 2.0f, 3.0f, 4.0f, 8.0f)
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            items(presets) { preset ->
                                val isSelected = Math.abs(multiplier - preset) < 0.02f
                                FilterChip(
                                    selected = isSelected,
                                    onClick = {
                                        multiplier = preset
                                        applyMultiplier(preset)
                                    },
                                    label = {
                                        val labelText = if (preset % 1.0f == 0.0f) "${preset.toInt()}x" else "${preset}x"
                                        Text(labelText, style = MaterialTheme.typography.labelSmall)
                                    },
                                    modifier = Modifier.height(30.dp)
                                )
                            }
                            item {
                                FilterChip(
                                    selected = showCustomMultiplierDialog || (presets.none { Math.abs(multiplier - it) < 0.02f } && multiplier != 1.0f),
                                    onClick = {
                                        customMultiplierInput = if (multiplier % 1.0f == 0.0f) multiplier.toInt().toString() else multiplier.toString()
                                        showCustomMultiplierDialog = true
                                    },
                                    label = { Text("Custom...", style = MaterialTheme.typography.labelSmall) },
                                    modifier = Modifier.height(30.dp)
                                )
                            }
                        }
                    }
                }

                OutlinedTextField(
                    value = calories,
                    onValueChange = { calories = it },
                    label = { Text("Calories for this dish (kcal)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
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
                    finalTimestamp,
                    multiplier,
                    baseCalories,
                    baseProtein,
                    baseCarbs,
                    baseFat
                )
            }) {
                Text(confirmButtonText)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )

    if (showCustomMultiplierDialog) {
        AlertDialog(
            onDismissRequest = { showCustomMultiplierDialog = false },
            title = { Text("Custom Portion Multiplier") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Enter a portion multiplier for this dish (e.g. 8 if only 1/8 was photographed, 2 if half was eaten, 0.5 for half portion):",
                        style = MaterialTheme.typography.bodySmall
                    )
                    OutlinedTextField(
                        value = customMultiplierInput,
                        onValueChange = { customMultiplierInput = it },
                        label = { Text("Multiplier (e.g. 8, 1.5, 2.5)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    val parsed = customMultiplierInput.toFloatOrNull()
                    if (parsed != null && parsed > 0f) {
                        multiplier = parsed
                        applyMultiplier(parsed)
                    }
                    showCustomMultiplierDialog = false
                }) {
                    Text("Apply")
                }
            },
            dismissButton = {
                TextButton(onClick = { showCustomMultiplierDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DuplicateMealDialog(
    meal: MealEntry,
    onConfirm: (targetDate: LocalDate, targetHour: Int, targetMinute: Int) -> Unit,
    onDismiss: () -> Unit
) {
    var targetDate by remember { mutableStateOf(LocalDate.now()) }
    var showDatePicker by remember { mutableStateOf(false) }

    // Initial hour & minute: default to the meal's original hour/minute
    val originalDateTime = remember(meal.timestamp) {
        Instant.ofEpochMilli(meal.timestamp).atZone(ZoneId.systemDefault()).toLocalDateTime()
    }
    var hourInput by remember { mutableStateOf(originalDateTime.format(DateTimeFormatter.ofPattern("HH"))) }
    var minuteInput by remember { mutableStateOf(originalDateTime.format(DateTimeFormatter.ofPattern("mm"))) }

    val dateFormatter = remember { DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy", Locale.getDefault()) }

    if (showDatePicker) {
        val datePickerState = rememberDatePickerState(
            initialSelectedDateMillis = targetDate.atStartOfDay(ZoneId.of("UTC")).toInstant().toEpochMilli()
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    datePickerState.selectedDateMillis?.let { millis ->
                        targetDate = Instant.ofEpochMilli(millis).atZone(ZoneId.of("UTC")).toLocalDate()
                    }
                    showDatePicker = false
                }) {
                    Text("OK")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) {
                    Text("Cancel")
                }
            }
        ) {
            DatePicker(state = datePickerState)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.ContentCopy,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Text("Duplicate Meal", fontWeight = FontWeight.Bold)
            }
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Meal Info Card
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Text(
                            text = meal.description,
                            fontWeight = FontWeight.SemiBold,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = "${meal.calories} kcal  •  P: ${meal.protein}g  C: ${meal.carbs}g  F: ${meal.fat}g",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // Date Selection Section
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Target Date", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelLarge)

                    // Quick date presets
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        FilterChip(
                            selected = targetDate == LocalDate.now(),
                            onClick = { targetDate = LocalDate.now() },
                            label = { Text("Today") },
                            modifier = Modifier.height(32.dp)
                        )
                        FilterChip(
                            selected = targetDate == LocalDate.now().minusDays(1),
                            onClick = { targetDate = LocalDate.now().minusDays(1) },
                            label = { Text("Yesterday") },
                            modifier = Modifier.height(32.dp)
                        )
                    }

                    // Clickable Date Display
                    OutlinedCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showDatePicker = true },
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = targetDate.format(dateFormatter),
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    text = "Tap to choose from calendar",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            Icon(
                                imageVector = Icons.Default.CalendarMonth,
                                contentDescription = "Open Calendar",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }

                // Time Selection Section
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Target Hour & Minute", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelLarge)

                    // Time quick presets
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        FilterChip(
                            selected = hourInput == "08" && minuteInput == "30",
                            onClick = { hourInput = "08"; minuteInput = "30" },
                            label = { Text("Morning (8:30)") },
                            modifier = Modifier.height(32.dp)
                        )
                        FilterChip(
                            selected = hourInput == "12" && minuteInput == "30",
                            onClick = { hourInput = "12"; minuteInput = "30" },
                            label = { Text("Noon (12:30)") },
                            modifier = Modifier.height(32.dp)
                        )
                        FilterChip(
                            selected = hourInput == "19" && minuteInput == "00",
                            onClick = { hourInput = "19"; minuteInput = "00" },
                            label = { Text("Evening (19:00)") },
                            modifier = Modifier.height(32.dp)
                        )
                    }

                    // Editable Hour & Minute fields
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
                        Text(":", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)
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
            }
        },
        confirmButton = {
            Button(onClick = {
                val h = hourInput.toIntOrNull()?.coerceIn(0, 23) ?: 8
                val m = minuteInput.toIntOrNull()?.coerceIn(0, 59) ?: 0
                onConfirm(targetDate, h, m)
            }) {
                Text("Duplicate Meal")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManualEntryDialog(
    initialDescription: String = "",
    initialDate: LocalDate = LocalDate.now(),
    apiKey: String = "",
    visionService: FoodVisionService? = null,
    onConfirm: (
        description: String,
        calories: Int,
        protein: Int,
        carbs: Int,
        fat: Int,
        targetDate: LocalDate,
        hour: Int,
        minute: Int,
        multiplier: Float,
        baseCalories: Int,
        baseProtein: Int,
        baseCarbs: Int,
        baseFat: Int
    ) -> Unit,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()

    var desc by remember { mutableStateOf(initialDescription) }
    var targetDate by remember { mutableStateOf(initialDate) }
    var showDatePicker by remember { mutableStateOf(false) }

    val now = remember { LocalDateTime.now() }
    var hourInput by remember { mutableStateOf(now.format(DateTimeFormatter.ofPattern("HH"))) }
    var minuteInput by remember { mutableStateOf(now.format(DateTimeFormatter.ofPattern("mm"))) }

    var calories by remember { mutableStateOf("") }
    var protein by remember { mutableStateOf("") }
    var carbs by remember { mutableStateOf("") }
    var fat by remember { mutableStateOf("") }

    var baseCalories by remember { mutableIntStateOf(0) }
    var baseProtein by remember { mutableIntStateOf(0) }
    var baseCarbs by remember { mutableIntStateOf(0) }
    var baseFat by remember { mutableIntStateOf(0) }

    var multiplier by remember { mutableFloatStateOf(1.0f) }
    var isEstimating by remember { mutableStateOf(false) }
    var estimateStatusMessage by remember { mutableStateOf<String?>(null) }
    var showCustomMultiplierDialog by remember { mutableStateOf(false) }
    var customMultiplierInput by remember { mutableStateOf("") }

    fun applyMultiplier(m: Float) {
        if (baseCalories > 0) {
            val newCal = (baseCalories * m).roundToInt()
            val newPro = (baseProtein * m).roundToInt()
            val newCarb = (baseCarbs * m).roundToInt()
            val newFat = (baseFat * m).roundToInt()
            calories = newCal.toString()
            protein = newPro.toString()
            carbs = newCarb.toString()
            fat = newFat.toString()
        }
    }

    val dateFormatter = remember { DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy", Locale.getDefault()) }

    if (showDatePicker) {
        val datePickerState = rememberDatePickerState(
            initialSelectedDateMillis = targetDate.atStartOfDay(ZoneId.of("UTC")).toInstant().toEpochMilli()
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    datePickerState.selectedDateMillis?.let { millis ->
                        targetDate = Instant.ofEpochMilli(millis).atZone(ZoneId.of("UTC")).toLocalDate()
                    }
                    showDatePicker = false
                }) {
                    Text("OK")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) {
                    Text("Cancel")
                }
            }
        ) {
            DatePicker(state = datePickerState)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.EditNote,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Text("Manual Meal Entry", fontWeight = FontWeight.Bold)
            }
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Dish Description input
                OutlinedTextField(
                    value = desc,
                    onValueChange = { desc = it },
                    label = { Text("Dish Description") },
                    placeholder = { Text("e.g. 2 fried eggs, 2 slices of bacon, buttered toast") },
                    modifier = Modifier.fillMaxWidth()
                )

                // AI Estimation Button
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        onClick = {
                            if (desc.isBlank()) {
                                estimateStatusMessage = "Please describe the dish first."
                                return@Button
                            }
                            if (apiKey.isBlank()) {
                                estimateStatusMessage = "Gemini API key is required. Set it in Settings."
                                return@Button
                            }
                            if (visionService == null) {
                                estimateStatusMessage = "Vision service not available."
                                return@Button
                            }
                            isEstimating = true
                            estimateStatusMessage = "Estimating nutrition with AI..."
                            scope.launch {
                                val result = visionService.analyzeFoodText(apiKey, desc)
                                result.onSuccess { analysis ->
                                    baseCalories = analysis.totalCaloriesInt
                                    baseProtein = analysis.proteinGramsInt
                                    baseCarbs = analysis.carbsGramsInt
                                    baseFat = analysis.fatGramsInt
                                    multiplier = 1.0f
                                    calories = analysis.totalCaloriesInt.toString()
                                    protein = analysis.proteinGramsInt.toString()
                                    carbs = analysis.carbsGramsInt.toString()
                                    fat = analysis.fatGramsInt.toString()
                                    estimateStatusMessage = analysis.confidenceNote.ifBlank { "Estimated successfully!" }
                                }.onFailure { err ->
                                    estimateStatusMessage = "Estimation failed: ${err.message}"
                                }
                                isEstimating = false
                            }
                        },
                        enabled = !isEstimating && desc.isNotBlank(),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        if (isEstimating) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                            Spacer(Modifier.width(6.dp))
                            Text("Estimating...", style = MaterialTheme.typography.labelMedium)
                        } else {
                            Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Estimate with AI", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }

                if (estimateStatusMessage != null) {
                    Text(
                        text = estimateStatusMessage!!,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                // Portion Multiplier (shown if calories > 0)
                if (baseCalories > 0 || calories.isNotBlank()) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Portion Multiplier",
                                        fontWeight = FontWeight.SemiBold,
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                    val subtitleText = when {
                                        Math.abs(multiplier - 1.0f) < 0.01f -> "100% of estimate"
                                        Math.abs(multiplier - 8.0f) < 0.05f -> "8x portion"
                                        Math.abs(multiplier - 2.0f) < 0.05f -> "2x portion"
                                        else -> "${Math.round(multiplier * 100)}% portion"
                                    }
                                    Text(
                                        text = subtitleText,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }

                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    FilledTonalIconButton(
                                        onClick = {
                                            val step = if (multiplier > 2.0f) 0.5f else 0.25f
                                            val newMult = (multiplier - step).coerceAtLeast(0.25f)
                                            val rounded = Math.round(newMult * 100f) / 100f
                                            multiplier = rounded
                                            applyMultiplier(rounded)
                                        },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(Icons.Default.Remove, contentDescription = "Decrease portion", modifier = Modifier.size(16.dp))
                                    }

                                    Surface(
                                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f),
                                        shape = RoundedCornerShape(6.dp),
                                        modifier = Modifier.clickable {
                                            customMultiplierInput = if (multiplier % 1.0f == 0.0f) multiplier.toInt().toString() else multiplier.toString()
                                            showCustomMultiplierDialog = true
                                        }
                                    ) {
                                        Text(
                                            text = String.format(Locale.US, "%.2fx", multiplier),
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }

                                    FilledTonalIconButton(
                                        onClick = {
                                            val step = if (multiplier >= 2.0f) 0.5f else 0.25f
                                            val newMult = (multiplier + step).coerceAtMost(20.0f)
                                            val rounded = Math.round(newMult * 100f) / 100f
                                            multiplier = rounded
                                            applyMultiplier(rounded)
                                        },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(Icons.Default.Add, contentDescription = "Increase portion", modifier = Modifier.size(16.dp))
                                    }
                                }
                            }

                            // Quick preset chips
                            val presets = listOf(0.5f, 0.75f, 1.0f, 1.5f, 2.0f, 3.0f, 4.0f, 8.0f)
                            LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                items(presets) { preset ->
                                    val isSelected = Math.abs(multiplier - preset) < 0.02f
                                    FilterChip(
                                        selected = isSelected,
                                        onClick = {
                                            multiplier = preset
                                            applyMultiplier(preset)
                                        },
                                        label = {
                                            val labelText = if (preset % 1.0f == 0.0f) "${preset.toInt()}x" else "${preset}x"
                                            Text(labelText, style = MaterialTheme.typography.labelSmall)
                                        },
                                        modifier = Modifier.height(30.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                // Calories and Macros Inputs
                OutlinedTextField(
                    value = calories,
                    onValueChange = {
                        calories = it
                        baseCalories = it.toIntOrNull() ?: 0
                    },
                    label = { Text("Calories (kcal)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = protein,
                        onValueChange = {
                            protein = it
                            baseProtein = it.toIntOrNull() ?: 0
                        },
                        label = { Text("Protein (g)") },
                        modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                    OutlinedTextField(
                        value = carbs,
                        onValueChange = {
                            carbs = it
                            baseCarbs = it.toIntOrNull() ?: 0
                        },
                        label = { Text("Carbs (g)") },
                        modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                    OutlinedTextField(
                        value = fat,
                        onValueChange = {
                            fat = it
                            baseFat = it.toIntOrNull() ?: 0
                        },
                        label = { Text("Fat (g)") },
                        modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                // Date Selection Section
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Date", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelLarge)
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        FilterChip(
                            selected = targetDate == LocalDate.now(),
                            onClick = { targetDate = LocalDate.now() },
                            label = { Text("Today") },
                            modifier = Modifier.height(32.dp)
                        )
                        FilterChip(
                            selected = targetDate == LocalDate.now().minusDays(1),
                            onClick = { targetDate = LocalDate.now().minusDays(1) },
                            label = { Text("Yesterday") },
                            modifier = Modifier.height(32.dp)
                        )
                    }

                    OutlinedCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showDatePicker = true },
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = targetDate.format(dateFormatter),
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    text = "Tap to choose from calendar",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            Icon(
                                imageVector = Icons.Default.CalendarMonth,
                                contentDescription = "Open Calendar",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }

                // Time Selection Section
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Time", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelLarge)
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        FilterChip(
                            selected = hourInput == "08" && minuteInput == "30",
                            onClick = { hourInput = "08"; minuteInput = "30" },
                            label = { Text("Morning (8:30)") },
                            modifier = Modifier.height(32.dp)
                        )
                        FilterChip(
                            selected = hourInput == "12" && minuteInput == "30",
                            onClick = { hourInput = "12"; minuteInput = "30" },
                            label = { Text("Noon (12:30)") },
                            modifier = Modifier.height(32.dp)
                        )
                        FilterChip(
                            selected = hourInput == "19" && minuteInput == "00",
                            onClick = { hourInput = "19"; minuteInput = "00" },
                            label = { Text("Evening (19:00)") },
                            modifier = Modifier.height(32.dp)
                        )
                    }

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
                        Text(":", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)
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
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val h = hourInput.toIntOrNull()?.coerceIn(0, 23) ?: 12
                    val m = minuteInput.toIntOrNull()?.coerceIn(0, 59) ?: 0
                    val cal = calories.toIntOrNull() ?: 0
                    val pro = protein.toIntOrNull() ?: 0
                    val carb = carbs.toIntOrNull() ?: 0
                    val f = fat.toIntOrNull() ?: 0
                    val baseCal = if (baseCalories > 0) baseCalories else cal
                    val basePro = if (baseProtein > 0) baseProtein else pro
                    val baseCarb = if (baseCarbs > 0) baseCarbs else carb
                    val baseF = if (baseFat > 0) baseFat else f

                    onConfirm(
                        desc.trim(),
                        cal,
                        pro,
                        carb,
                        f,
                        targetDate,
                        h,
                        m,
                        multiplier,
                        baseCal,
                        basePro,
                        baseCarb,
                        baseF
                    )
                },
                enabled = desc.isNotBlank()
            ) {
                Text("Log Meal")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )

    if (showCustomMultiplierDialog) {
        AlertDialog(
            onDismissRequest = { showCustomMultiplierDialog = false },
            title = { Text("Custom Portion Multiplier") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Enter a portion multiplier (e.g. 2 for double portion, 0.5 for half):",
                        style = MaterialTheme.typography.bodySmall
                    )
                    OutlinedTextField(
                        value = customMultiplierInput,
                        onValueChange = { customMultiplierInput = it },
                        label = { Text("Multiplier (e.g. 2, 1.5, 0.5)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    val parsed = customMultiplierInput.toFloatOrNull()
                    if (parsed != null && parsed > 0f) {
                        multiplier = parsed
                        applyMultiplier(parsed)
                    }
                    showCustomMultiplierDialog = false
                }) {
                    Text("Apply")
                }
            },
            dismissButton = {
                TextButton(onClick = { showCustomMultiplierDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
fun SettingsDialog(
    currentApiKey: String,
    currentGoal: Int,
    currentGroupingWindow: Int,
    currentDownscaleDays: Int,
    currentAutoCrop: Boolean,
    currentSortAscending: Boolean,
    onSave: (String, Int, Int, Int, Boolean, Boolean) -> Unit,
    onRestoreComplete: () -> Unit,
    onDismiss: () -> Unit
) {
    var keyInput by remember { mutableStateOf(currentApiKey) }
    var goalInput by remember { mutableStateOf(currentGoal.toString()) }
    var windowInput by remember { mutableStateOf(currentGroupingWindow.toString()) }
    var daysInput by remember { mutableStateOf(currentDownscaleDays.toString()) }
    var autoCropInput by remember { mutableStateOf(currentAutoCrop) }
    var sortAscendingInput by remember { mutableStateOf(currentSortAscending) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Settings") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = keyInput,
                    onValueChange = { keyInput = it },
                    label = { Text("Gemini API Key") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = goalInput,
                    onValueChange = { goalInput = it },
                    label = { Text("Daily Calorie Goal (kcal)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = windowInput,
                    onValueChange = { windowInput = it },
                    label = { Text("Meal Grouping Window (min)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = daysInput,
                    onValueChange = { daysInput = it },
                    label = { Text("Image Downscale After (days)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Intelligent Plate Crop", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "Auto-crop photo around the plate/dishes using AI",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = autoCropInput,
                        onCheckedChange = { autoCropInput = it }
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Sort Ascending & Scroll to Bottom", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "Morning meals at top, automatically scrolls to latest meal",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = sortAscendingInput,
                        onCheckedChange = { sortAscendingInput = it }
                    )
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                GoogleDriveBackupSection(
                    onRestoreComplete = onRestoreComplete
                )
            }
        },
        confirmButton = {
            Button(onClick = { 
                onSave(
                    keyInput, 
                    goalInput.toIntOrNull() ?: 2200,
                    windowInput.toIntOrNull() ?: 60,
                    daysInput.toIntOrNull() ?: 7,
                    autoCropInput,
                    sortAscendingInput
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
fun GoogleDriveBackupSection(
    onRestoreComplete: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences("nutrilens_prefs", Context.MODE_PRIVATE) }

    var autoBackupEnabled by remember {
        mutableStateOf(prefs.getBoolean("auto_drive_backup_enabled", true))
    }
    var lastBackupTime by remember {
        mutableLongStateOf(prefs.getLong("last_drive_backup_timestamp", 0L))
    }
    var currentAccount by remember {
        mutableStateOf<GoogleSignInAccount?>(null)
    }
    var isOperating by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var isSuccessStatus by remember { mutableStateOf(true) }
    var showRestoreConfirm by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val account = GoogleSignIn.getLastSignedInAccount(context)
        if (account != null && GoogleSignIn.hasPermissions(account, Scope(DriveScopes.DRIVE_APPDATA))) {
            currentAccount = account
        }
    }

    val gso = remember {
        GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestScopes(Scope(DriveScopes.DRIVE_APPDATA))
            .build()
    }
    val googleSignInClient = remember { GoogleSignIn.getClient(context, gso) }

    val signInLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
        try {
            val account = task.getResult(ApiException::class.java)
            currentAccount = account
            statusMessage = "Connected as ${account.email}"
            isSuccessStatus = true
            if (autoBackupEnabled) {
                DriveBackupWorker.schedulePeriodicBackup(context)
            }
        } catch (e: ApiException) {
            statusMessage = "Sign-in failed (code ${e.statusCode}): ${e.message}"
            isSuccessStatus = false
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = Icons.Default.CloudSync,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
            Column {
                Text(
                    text = "Google Drive Backup",
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = "Private appDataFolder (Safe & Invisible)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        if (currentAccount == null) {
            Text(
                text = "Back up your meals, history, settings, and photos securely to Google Drive. Sign in to enable automatic backups.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Button(
                onClick = { signInLauncher.launch(googleSignInClient.signInIntent) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.AccountCircle, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Connect Google Drive")
            }
        } else {
            OutlinedCard(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Account",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = currentAccount?.email ?: "Connected",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium
                            )
                        }
                        TextButton(
                            onClick = {
                                googleSignInClient.signOut().addOnCompleteListener {
                                    currentAccount = null
                                    DriveBackupWorker.cancelPeriodicBackup(context)
                                    statusMessage = "Signed out of Google Drive"
                                    isSuccessStatus = true
                                }
                            },
                            enabled = !isOperating
                        ) {
                            Text("Disconnect", color = MaterialTheme.colorScheme.error)
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "Auto Daily Backup",
                                fontWeight = FontWeight.SemiBold,
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                "Syncs photos and data (Mobile & Wi-Fi)",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = autoBackupEnabled,
                            onCheckedChange = { enabled ->
                                autoBackupEnabled = enabled
                                prefs.edit().putBoolean("auto_drive_backup_enabled", enabled).apply()
                                if (enabled) {
                                    DriveBackupWorker.schedulePeriodicBackup(context)
                                } else {
                                    DriveBackupWorker.cancelPeriodicBackup(context)
                                }
                            },
                            enabled = !isOperating
                        )
                    }

                    val lastBackupFormatted = remember(lastBackupTime) {
                        if (lastBackupTime > 0L) {
                            SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault()).format(Date(lastBackupTime))
                        } else {
                            "Never"
                        }
                    }
                    Text(
                        text = "Last backup: $lastBackupFormatted",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (isOperating) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = {
                        val account = currentAccount ?: return@Button
                        isOperating = true
                        statusMessage = "Starting backup..."
                        scope.launch {
                            val res = BackupManager.performBackup(context, account) { msg ->
                                statusMessage = msg
                            }
                            isOperating = false
                            isSuccessStatus = res.success
                            statusMessage = res.message
                            if (res.success) {
                                lastBackupTime = prefs.getLong("last_drive_backup_timestamp", 0L)
                            }
                        }
                    },
                    enabled = !isOperating,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.CloudUpload, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Backup Now")
                }

                OutlinedButton(
                    onClick = { showRestoreConfirm = true },
                    enabled = !isOperating,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.CloudDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Restore")
                }
            }
        }

        if (statusMessage != null) {
            Text(
                text = statusMessage!!,
                style = MaterialTheme.typography.bodySmall,
                color = if (isSuccessStatus) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
            )
        }
    }

    if (showRestoreConfirm) {
        AlertDialog(
            onDismissRequest = { showRestoreConfirm = false },
            icon = { Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
            title = { Text("Restore from Google Drive?") },
            text = {
                Text(
                    "Restoring will download all backed-up photos and overwrite your current meal entries and settings with the cloud backup. Do you want to continue?"
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showRestoreConfirm = false
                        val account = currentAccount ?: return@Button
                        isOperating = true
                        statusMessage = "Starting restore..."
                        scope.launch {
                            val res = BackupManager.performRestore(context, account) { msg ->
                                statusMessage = msg
                            }
                            isOperating = false
                            isSuccessStatus = res.success
                            statusMessage = res.message
                            if (res.success) {
                                onRestoreComplete()
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Overwrite & Restore")
                }
            },
            dismissButton = {
                TextButton(onClick = { showRestoreConfirm = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

fun formatCaloriesToTwoSigFigs(calories: Int): String {
    if (calories <= 0) return "0"
    return when {
        calories < 100 -> calories.toString()
        calories < 1000 -> {
            // Round to nearest 10 (e.g. 587 -> 590, 595 -> 600)
            val rounded = (Math.round(calories / 10.0) * 10).toInt()
            rounded.toString()
        }
        calories < 10000 -> {
            // E.g. 1540 -> 1.5k, 2010 -> 2.0k or 2k
            val inK = calories / 1000.0
            val rounded100 = Math.round(inK * 10.0) / 10.0
            if (rounded100 % 1.0 == 0.0) {
                "${rounded100.toInt()}k"
            } else {
                "${rounded100}k"
            }
        }
        else -> {
            // >= 10,000 -> nearest whole k, e.g. 12k
            val roundedK = Math.round(calories / 1000.0).toInt()
            "${roundedK}k"
        }
    }
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
                                                    text = formatCaloriesToTwoSigFigs(dayCalories),
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