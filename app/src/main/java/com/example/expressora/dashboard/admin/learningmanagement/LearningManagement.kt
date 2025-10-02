package com.example.expressora.dashboard.admin.learningmanagement

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.PlayCircleFilled
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.expressora.components.admin_bottom_nav.BottomNav2
import com.example.expressora.components.top_nav3.TopNav3
import com.example.expressora.dashboard.admin.analytics.AnalyticsDashboardActivity
import com.example.expressora.dashboard.admin.communityspacemanagement.CommunitySpaceManagementActivity
import com.example.expressora.dashboard.admin.quizmanagement.QuizManagementActivity
import com.example.expressora.ui.theme.InterFontFamily
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

data class Lesson(
    val id: String = UUID.randomUUID().toString(),
    var title: String = "",
    var content: String = "",
    var attachments: List<Uri> = emptyList(),
    var tryItems: List<String> = emptyList(),
    var lastUpdated: Long = System.currentTimeMillis()
)

private val AppBackground = Color(0xFFF8F8F8)
private val CardSurface = Color.White
private val MutedText = Color(0xFF666666)
private val Accent = Color(0xFFFACC15)

fun getTimeAgo(time: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - time
    val minutes = diff / 60000
    val hours = diff / 3600000
    val days = diff / 86400000
    return when {
        minutes < 1 -> "Just now"
        minutes < 60 -> "$minutes min ago"
        hours < 24 -> "$hours hr ago"
        else -> "$days day${if (days > 1) "s" else ""} ago"
    }
}

fun formatDate(time: Long): String {
    val fmt = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())
    return fmt.format(Date(time))
}

fun getFileName(context: Context, uri: Uri): String? {
    var name: String? = null
    val cursor = context.contentResolver.query(uri, null, null, null, null)
    cursor?.use {
        if (it.moveToFirst()) {
            val index = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index != -1) name = it.getString(index)
        }
    }
    return name
}

fun getMimeType(context: Context, uri: Uri): String? {
    return try {
        context.contentResolver.getType(uri)
    } catch (e: Exception) {
        null
    }
}

fun getVideoFrame(context: Context, uri: Uri): Bitmap? {
    return try {
        val retriever = MediaMetadataRetriever()
        retriever.setDataSource(context, uri)
        val bitmap = retriever.getFrameAtTime(1_000_000)
        retriever.release()
        bitmap
    } catch (e: Exception) {
        null
    }
}

class LearningManagementActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { LessonApp() }
    }
}

@Composable
fun LessonApp() {
    val context = LocalContext.current
    val navController = rememberNavController()

    val allLessons = remember {
        mutableStateListOf(
            Lesson(
                title = "Alphabets",
                content = "Overview of Sign Language alphabets",
                tryItems = listOf("A", "B"),
                lastUpdated = System.currentTimeMillis() - 86_400_000L
            ), Lesson(
                title = "Greetings and Phrases",
                content = "Characters and samples",
                tryItems = listOf("Hello", "Thank you"),
                lastUpdated = System.currentTimeMillis() - 2 * 86_400_000L
            )
        )
    }

    Scaffold(
        topBar = {
            TopNav3(onTranslateClick = {
                context.startActivity(Intent(context, CommunitySpaceManagementActivity::class.java))
            })
        }, bottomBar = {
            BottomNav2(onLearnClick = { /* already in learning management */ }, onAnalyticsClick = {
                context.startActivity(Intent(context, AnalyticsDashboardActivity::class.java))
            }, onQuizClick = {
                context.startActivity(Intent(context, QuizManagementActivity::class.java))
            })
        }, containerColor = AppBackground
    ) { padding ->
        Box(
            modifier = Modifier
                .padding(padding)
                .background(AppBackground)
                .fillMaxSize()
        ) {
            NavHost(navController = navController, startDestination = "list") {

                composable("list") {
                    LessonListScreen(
                        lessons = allLessons,
                        onAddLesson = { navController.navigate("add") },
                        onEditLesson = { lessonId -> navController.navigate("edit/$lessonId") },
                        onDeleteLesson = { lessonId ->
                            val idx = allLessons.indexOfFirst { it.id == lessonId }
                            if (idx != -1) {
                                allLessons.removeAt(idx)
                                Toast.makeText(context, "Lesson deleted", Toast.LENGTH_SHORT).show()
                            }
                        })
                }

                composable("add") {
                    val temp = remember { Lesson() }
                    LessonEditorScreen(
                        title = "Add Lesson",
                        lesson = temp,
                        isEditMode = false,
                        onSaveConfirmed = { saved ->
                            allLessons.add(0, saved.copy(lastUpdated = System.currentTimeMillis()))
                            Toast.makeText(context, "Lesson added", Toast.LENGTH_SHORT).show()
                            navController.popBackStack()
                        })
                }

                composable("edit/{lessonId}") { backEntry ->
                    val lessonId = backEntry.arguments?.getString("lessonId") ?: return@composable
                    val idx = allLessons.indexOfFirst { it.id == lessonId }
                    if (idx == -1) return@composable
                    val lessonCopy = remember {
                        allLessons[idx].copy(
                            attachments = allLessons[idx].attachments.toList(),
                            tryItems = allLessons[idx].tryItems.toList()
                        )
                    }
                    LessonEditorScreen(
                        title = "Edit Lesson",
                        lesson = lessonCopy,
                        isEditMode = true,
                        onSaveConfirmed = { updated ->
                            val i = allLessons.indexOfFirst { it.id == updated.id }
                            if (i != -1) {
                                allLessons[i] =
                                    updated.copy(lastUpdated = System.currentTimeMillis())
                                Toast.makeText(context, "Lesson updated", Toast.LENGTH_SHORT).show()
                            }
                            navController.popBackStack()
                        })
                }
            }
        }
    }
}

@Composable
fun LessonListScreen(
    lessons: SnapshotStateList<Lesson>,
    onAddLesson: () -> Unit,
    onEditLesson: (String) -> Unit,
    onDeleteLesson: (String) -> Unit
) {
    val context = LocalContext.current

    var searchQuery by rememberSaveable { mutableStateOf("") }
    var sortExpanded by remember { mutableStateOf(false) }
    var sortSelection by rememberSaveable { mutableStateOf("Latest") }

    val deleteDialog = remember { mutableStateOf<Pair<Boolean, String?>>(false to null) }
    val allSortOptions = listOf("Latest", "Oldest")
    val sortOptions = remember { mutableStateListOf(*allSortOptions.toTypedArray()) }

    val filtered = lessons.filter { lesson ->
        val q = searchQuery.trim().lowercase()
        q.isEmpty() || lesson.title.lowercase().contains(q) || lesson.content.lowercase()
            .contains(q)
    }.sortedWith(compareBy { if (sortSelection == "Latest") -it.lastUpdated else it.lastUpdated })

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(AppBackground),
        contentPadding = PaddingValues(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(
                verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    "Lessons",
                    fontSize = 26.sp,
                    fontWeight = FontWeight.ExtraBold,
                    fontFamily = InterFontFamily
                )
                Spacer(modifier = Modifier.weight(1f))
                IconButton(onClick = onAddLesson) {
                    Icon(Icons.Default.Add, contentDescription = "Add Lesson")
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                "Add a lesson to cover new topics",
                fontSize = 14.sp,
                color = MutedText,
                fontFamily = InterFontFamily,
                modifier = Modifier.padding(start = 4.dp)
            )

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()
            ) {
                val searchModifier = Modifier
                    .weight(1f)
                    .height(48.dp)
                    .padding(end = 8.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color.White, RoundedCornerShape(50))
                    .shadow(2.dp, RoundedCornerShape(50))

                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Search lessons", color = Color(0xFF666666)) },
                    leadingIcon = {
                        Icon(
                            Icons.Default.Search, contentDescription = "Search", tint = Color.Black
                        )
                    },
                    modifier = searchModifier,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions.Default.copy(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { }),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color.Transparent,
                        unfocusedBorderColor = Color.Transparent,
                        focusedTextColor = Color.Black,
                        unfocusedTextColor = Color(0xFF666666),
                        cursorColor = Color.Black,
                        focusedContainerColor = Color.White,
                        unfocusedContainerColor = Color.White
                    )
                )

                var dropdownWidth by remember { mutableStateOf(0) }
                Box(modifier = Modifier.onGloballyPositioned { coords ->
                    dropdownWidth = coords.size.width
                }) {
                    Row(
                        modifier = Modifier
                            .height(48.dp)
                            .widthIn(min = 100.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color.White)
                            .clickable { sortExpanded = true }
                            .padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(sortSelection, color = Color.Black)
                        Spacer(modifier = Modifier.width(6.dp))
                        Icon(
                            Icons.Default.ArrowDropDown,
                            contentDescription = "Sort",
                            tint = Color.Black
                        )
                    }

                    DropdownMenu(
                        expanded = sortExpanded,
                        onDismissRequest = { sortExpanded = false },
                        modifier = Modifier
                            .width(with(LocalDensity.current) { dropdownWidth.toDp() })
                            .background(Color.White)
                    ) {
                        sortOptions.filter { it != sortSelection }.forEach { opt ->
                            DropdownMenuItem(text = { Text(opt, color = Color.Black) }, onClick = {
                                sortSelection = opt
                                sortExpanded = false
                            })
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            Text(
                "${filtered.size} lesson(s)",
                color = MutedText,
                modifier = Modifier.padding(start = 4.dp)
            )
            Spacer(modifier = Modifier.height(12.dp))
        }

        items(filtered, key = { it.id }) { lesson ->
            LessonCard(
                lesson = lesson,
                onEdit = { onEditLesson(lesson.id) },
                onDelete = { deleteDialog.value = true to lesson.id })
        }
    }

    if (deleteDialog.value.first) {
        ConfirmStyledDialog(
            title = "Delete Lesson",
            message = "Are you sure you want to delete this lesson?",
            confirmText = "Delete",
            confirmColor = Color.Red,
            onDismiss = { deleteDialog.value = false to null },
            onConfirm = {
                val idToDelete = deleteDialog.value.second
                if (idToDelete != null) {
                    lessons.removeIf { it.id == idToDelete }
                    onDeleteLesson(idToDelete)
                }
                deleteDialog.value = false to null
                Toast.makeText(context, "Lesson deleted", Toast.LENGTH_SHORT).show()
            })
    }
}

@Composable
fun LessonCard(lesson: Lesson, onEdit: () -> Unit, onDelete: () -> Unit) {
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = CardSurface),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFFFDE58A)), contentAlignment = Alignment.Center
            ) {
                if (lesson.attachments.isNotEmpty()) {
                    val first = lesson.attachments.first()
                    val ctx = LocalContext.current
                    val type = getMimeType(ctx, first) ?: ""
                    when {
                        type.startsWith("image") -> AsyncImage(
                            model = ImageRequest.Builder(ctx).data(first).crossfade(true).build(),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )

                        type.startsWith("video") -> VideoThumbnail(first)
                        else -> Icon(
                            Icons.Default.MenuBook, contentDescription = null, tint = Color.Black
                        )
                    }
                } else {
                    Icon(Icons.Default.MenuBook, contentDescription = null, tint = Color.Black)
                }
            }

            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    lesson.title.ifBlank { "Untitled Lesson" },
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    fontFamily = InterFontFamily
                )
                Text(
                    "Updated ${getTimeAgo(lesson.lastUpdated)}\n${formatDate(lesson.lastUpdated)}",
                    color = MutedText,
                    fontSize = 14.sp,
                    fontFamily = InterFontFamily
                )
            }

            IconButton(onClick = onEdit) { Icon(Icons.Default.Edit, contentDescription = "Edit") }
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Default.Delete, contentDescription = "Delete", tint = Color.Red
                )
            }
        }
    }
}

@Composable
fun VideoThumbnail(uri: Uri, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val bitmap = remember(uri) { getVideoFrame(ctx, uri) }
    if (bitmap != null) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier.fillMaxSize()
        )
    } else {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.VideoLibrary, contentDescription = null, tint = Color.White)
        }
    }
}

@Composable
fun ConfirmStyledDialog(
    title: String,
    message: String,
    confirmText: String,
    confirmColor: Color,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    confirmTextColor: Color = Color.White
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            tonalElevation = 8.dp,
            color = Color.White,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    title,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = InterFontFamily
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(message, color = MutedText)
                Spacer(modifier = Modifier.height(16.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(
                        onClick = onDismiss,
                        colors = ButtonDefaults.textButtonColors(contentColor = Color.Gray)
                    ) {
                        Text("Cancel", color = Color(0xFF666666))
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = onConfirm, colors = ButtonDefaults.buttonColors(
                            containerColor = confirmColor, contentColor = confirmTextColor
                        )
                    ) {
                        Text(confirmText)
                    }
                }
            }
        }
    }
}

@SuppressLint("RememberReturnType")
@Composable
fun LessonEditorScreen(
    title: String,
    lesson: Lesson,
    isEditMode: Boolean,
    onSaveConfirmed: (Lesson) -> Unit,
) {
    val context = LocalContext.current
    var lessonTitle by rememberSaveable { mutableStateOf(lesson.title) }
    var lessonContent by rememberSaveable { mutableStateOf(lesson.content) }
    val attachmentsState = remember { lesson.attachments.toMutableStateList() }
    var tryInput by rememberSaveable { mutableStateOf(lesson.tryItems.joinToString(", ")) }
    val saveDialogVisible = remember { mutableStateOf(false) }

    val multiplePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments(), onResult = { uris ->
            if (uris.isNotEmpty()) {
                uris.forEach { uri ->
                    try {
                        context.contentResolver.takePersistableUriPermission(
                            uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                        )
                    } catch (_: Exception) {
                    }
                }
                attachmentsState.addAll(uris)
                Toast.makeText(context, "Files selected: ${uris.size}", Toast.LENGTH_SHORT).show()
            }
        })

    val isSaveEnabled by remember(lessonTitle, lessonContent, tryInput, attachmentsState) {
        derivedStateOf {
            val requiredFilled =
                lessonTitle.isNotBlank() && lessonContent.isNotBlank() && tryInput.isNotBlank()

            if (!isEditMode) {
                requiredFilled
            } else {
                val titleChanged = lessonTitle.trim() != lesson.title.trim()
                val contentChanged = lessonContent.trim() != lesson.content.trim()
                val tryChanged =
                    tryInput.split(",").map { it.trim() } != lesson.tryItems.map { it.trim() }
                val attachmentsChanged = attachmentsState.toList() != lesson.attachments

                requiredFilled && (titleChanged || contentChanged || tryChanged || attachmentsChanged)
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AppBackground)
            .padding(24.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(
                title,
                fontSize = 26.sp,
                fontWeight = FontWeight.ExtraBold,
                fontFamily = InterFontFamily
            )
            Spacer(modifier = Modifier.weight(1f))
            IconButton(
                onClick = { if (isSaveEnabled) saveDialogVisible.value = true },
                enabled = isSaveEnabled
            ) {
                Icon(
                    Icons.Default.Save,
                    contentDescription = "Save Lesson",
                    tint = if (isSaveEnabled) Color.Black else Color.Gray
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        OutlinedTextField(
            value = lessonTitle,
            onValueChange = { lessonTitle = it },
            label = { Text("Lesson Title", fontFamily = InterFontFamily, color = MutedText) },
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
                cursorColor = Color.Black,
                focusedTextColor = Color.Black,
                unfocusedTextColor = MutedText,
                focusedBorderColor = Color.Black,
                unfocusedBorderColor = MutedText
            )
        )

        Spacer(modifier = Modifier.height(12.dp))

        OutlinedTextField(
            value = lessonContent,
            onValueChange = { lessonContent = it },
            label = { Text("Lesson Content", fontFamily = InterFontFamily, color = MutedText) },
            modifier = Modifier
                .fillMaxWidth()
                .height(180.dp),
            maxLines = 10,
            colors = OutlinedTextFieldDefaults.colors(
                cursorColor = Color.Black,
                focusedTextColor = Color.Black,
                unfocusedTextColor = MutedText,
                focusedBorderColor = Color.Black,
                unfocusedBorderColor = MutedText
            )
        )

        Spacer(modifier = Modifier.height(12.dp))

        OutlinedTextField(
            value = tryInput,
            onValueChange = { tryInput = it },
            label = {
                Text(
                    "Try It Out (comma separated)", fontFamily = InterFontFamily, color = MutedText
                )
            },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                cursorColor = Color.Black,
                focusedTextColor = Color.Black,
                unfocusedTextColor = MutedText,
                focusedBorderColor = Color.Black,
                unfocusedBorderColor = MutedText
            )
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text("Attachments", fontFamily = InterFontFamily, fontWeight = FontWeight.SemiBold)
        Spacer(modifier = Modifier.height(8.dp))

        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            modifier = Modifier
                .fillMaxWidth()
                .height(260.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(attachmentsState.size + 1) { index ->
                if (index < attachmentsState.size) {
                    val uri = attachmentsState[index]
                    val type = getMimeType(context, uri) ?: ""
                    val fileName = getFileName(context, uri) ?: "File"

                    Box(
                        modifier = Modifier
                            .size(100.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .shadow(2.dp, RoundedCornerShape(12.dp))
                            .background(Color(0xFFF2F4F7)),
                        contentAlignment = Alignment.Center
                    ) {
                        when {
                            type.startsWith("image") -> AsyncImage(
                                model = ImageRequest.Builder(context).data(uri).crossfade(true)
                                    .build(),
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )

                            type.startsWith("video") -> {
                                val thumb = remember(uri) { getVideoFrame(context, uri) }
                                if (thumb != null) Image(
                                    bitmap = thumb.asImageBitmap(),
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                                Icon(
                                    Icons.Default.PlayCircleFilled,
                                    contentDescription = "Play",
                                    tint = Color.Black.copy(alpha = 0.8f),
                                    modifier = Modifier
                                        .size(36.dp)
                                        .align(Alignment.Center)
                                )
                            }

                            else -> Column(
                                modifier = Modifier.fillMaxSize(),
                                verticalArrangement = Arrangement.Center,
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Icon(
                                    Icons.Default.InsertDriveFile,
                                    contentDescription = null,
                                    modifier = Modifier.size(36.dp)
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = fileName,
                                    fontSize = 12.sp,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    textAlign = TextAlign.Center,
                                    color = Color.Black
                                )
                            }
                        }

                        IconButton(
                            onClick = { attachmentsState.remove(uri) },
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .size(24.dp)
                                .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                        ) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "Remove",
                                tint = Color.White,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .size(100.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFFF2F4F7))
                            .clickable {
                                multiplePickerLauncher.launch(
                                    arrayOf("image/*", "video/*", "*/*")
                                )
                            }, contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.Add,
                            contentDescription = null,
                            tint = Color.Black,
                            modifier = Modifier.size(36.dp)
                        )
                    }
                }
            }
        }
    }

    if (saveDialogVisible.value) {
        ConfirmStyledDialog(
            title = "Save Lesson",
            message = "Do you want to save this lesson?",
            confirmText = "Save",
            confirmColor = Accent,
            onDismiss = { saveDialogVisible.value = false },
            onConfirm = {
                saveDialogVisible.value = false
                val parsedTry = tryInput.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                onSaveConfirmed(
                    lesson.copy(
                        title = lessonTitle.trim(),
                        content = lessonContent.trim(),
                        attachments = attachmentsState.toList(),
                        tryItems = parsedTry,
                        lastUpdated = System.currentTimeMillis()
                    )
                )
            },
            confirmTextColor = Color.Black
        )
    }
}
