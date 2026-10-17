package com.example.calorietracker

import android.content.Context
import android.graphics.Bitmap
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
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.room.*
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.content
import com.google.ai.client.generativeai.type.generationConfig
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.LocalDate
import kotlin.math.roundToInt

// ============================================================================
// 1. DATA MODELS & ROOM DATABASE
// ============================================================================

@Serializable
data class AiMealAnalysis(
    val mealDescription: String = "Detected Meal",
    val totalCalories: Int = 0,
    val proteinGrams: Int = 0,
    val carbsGrams: Int = 0,
    val fatGrams: Int = 0,
    val confidenceNote: String = ""
)

@Entity(tableName = "meal_entries")
data class MealEntry(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val timestamp: Long = System.currentTimeMillis(),
    val dateString: String,
    val description: String,
    val calories: Int,
    val protein: Int,
    val carbs: Int,
    val fat: Int
)

@Dao
interface MealDao {
    @Insert
    suspend fun insertMeal(meal: MealEntry)

    @Delete
    suspend fun deleteMeal(meal: MealEntry)

    @Query("SELECT * FROM meal_entries WHERE dateString = :date ORDER BY timestamp DESC")
    fun getMealsForDate(date: String): Flow<List<MealEntry>>
}

@Database(entities = [MealEntry::class], version = 1, exportSchema = false)
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
                ).build().also { INSTANCE = it }
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
            modelName = "gemini-2.5-flash",
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
// 3. VIEWMODEL
// ============================================================================

class CalorieViewModel(
    private val dao: MealDao,
    private val visionService: FoodVisionService
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

    fun changeDate(daysDelta: Long) {
        _selectedDate.value = _selectedDate.value.plusDays(daysDelta)
    }

    fun analyzePhoto(apiKey: String, bitmap: Bitmap, contextNote: String) {
        viewModelScope.launch {
            isAnalyzing = true
            errorMessage = null
            val result = visionService.analyzeFoodImage(apiKey, bitmap, contextNote)
            result.onSuccess { analysis ->
                pendingAnalysis = analysis
            }.onFailure { error ->
                errorMessage = error.localizedMessage ?: "Analysis failed"
            }
            isAnalyzing = false
        }
    }

    fun confirmAndSaveMeal(description: String, calories: Int, protein: Int, carbs: Int, fat: Int) {
        viewModelScope.launch {
            dao.insertMeal(
                MealEntry(
                    dateString = _selectedDate.value.toString(),
                    description = description,
                    calories = calories,
                    protein = protein,
                    carbs = carbs,
                    fat = fat
                )
            )
            pendingAnalysis = null
        }
    }

    fun dismissPendingAnalysis() {
        pendingAnalysis = null
    }

    fun deleteMeal(meal: MealEntry) {
        viewModelScope.launch {
            dao.deleteMeal(meal)
        }
    }
}

// ============================================================================
// 4. MAIN ACTIVITY & JETPACK COMPOSE UI
// ============================================================================

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val db = AppDatabase.getDatabase(this)
        val visionService = FoodVisionService()

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    val vm: CalorieViewModel = viewModel(
                        factory = object : ViewModelProvider.Factory {
                            @Suppress("UNCHECKED_CAST")
                            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                                return CalorieViewModel(db.mealDao(), visionService) as T
                            }
                        }
                    )
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
    var showSettingsDialog by remember { mutableStateOf(apiKey.isBlank()) }

    val selectedDate by viewModel.selectedDate.collectAsStateWithLifecycle()
    val meals by viewModel.mealsForSelectedDate.collectAsStateWithLifecycle()
    var userNote by remember { mutableStateOf("") }

    val totalCalories = meals.sumOf { it.calories }
    val totalProtein = meals.sumOf { it.protein }
    val totalCarbs = meals.sumOf { it.carbs }
    val totalFat = meals.sumOf { it.fat }

    // Camera Launcher
    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicturePreview()
    ) { bitmap: Bitmap? ->
        if (bitmap != null) {
            viewModel.analyzePhoto(apiKey, bitmap, userNote)
            userNote = ""
        }
    }

    // Gallery Launcher (Full Resolution)
    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) {
            val bitmap = loadBitmapFromUri(context, uri)
            if (bitmap != null) {
                viewModel.analyzePhoto(apiKey, bitmap, userNote)
                userNote = ""
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("NutriLens AI", fontWeight = FontWeight.Bold) },
                actions = {
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
                    style = MaterialTheme.typography.titleMedium
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
                    onClick = { cameraLauncher.launch() },
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
                        Row(
                            modifier = Modifier.padding(14.dp).fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(meal.description, fontWeight = FontWeight.SemiBold)
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
                            IconButton(onClick = { viewModel.deleteMeal(meal) }) {
                                Icon(Icons.Default.Delete, contentDescription = "Delete")
                            }
                        }
                    }
                }
            }
        }
    }

    // Review & Edit AI Analysis Dialog
    viewModel.pendingAnalysis?.let { analysis ->
        ReviewAnalysisDialog(
            analysis = analysis,
            onConfirm = { desc, cal, pro, carb, fat ->
                viewModel.confirmAndSaveMeal(desc, cal, pro, carb, fat)
            },
            onDismiss = { viewModel.dismissPendingAnalysis() }
        )
    }

    // Settings Dialog (API Key & Daily Goal)
    if (showSettingsDialog) {
        SettingsDialog(
            currentApiKey = apiKey,
            currentGoal = dailyGoal,
            onSave = { newKey, newGoal ->
                apiKey = newKey
                dailyGoal = newGoal
                prefs.edit()
                    .putString("gemini_api_key", newKey)
                    .putInt("daily_goal", newGoal)
                    .apply()
                showSettingsDialog = false
            },
            onDismiss = { showSettingsDialog = false }
        )
    }
}

@Composable
fun ReviewAnalysisDialog(
    analysis: AiMealAnalysis,
    onConfirm: (String, Int, Int, Int, Int) -> Unit,
    onDismiss: () -> Unit
) {
    var desc by remember { mutableStateOf(analysis.mealDescription) }
    var calories by remember { mutableStateOf(analysis.totalCalories.toString()) }
    var protein by remember { mutableStateOf(analysis.proteinGrams.toString()) }
    var carbs by remember { mutableStateOf(analysis.carbsGrams.toString()) }
    var fat by remember { mutableStateOf(analysis.fatGrams.toString()) }

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
            }
        },
        confirmButton = {
            Button(onClick = {
                onConfirm(
                    desc,
                    calories.toIntOrNull() ?: 0,
                    protein.toIntOrNull() ?: 0,
                    carbs.toIntOrNull() ?: 0,
                    fat.toIntOrNull() ?: 0
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
    onSave: (String, Int) -> Unit,
    onDismiss: () -> Unit
) {
    var keyInput by remember { mutableStateOf(currentApiKey) }
    var goalInput by remember { mutableStateOf(currentGoal.toString()) }

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
            }
        },
        confirmButton = {
            Button(onClick = { onSave(keyInput, goalInput.toIntOrNull() ?: 2200) }) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
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