@file:OptIn(UnstableApi::class)

package com.example.expressora.dashboard.user.community_space

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.ModeComment
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import com.example.expressora.R
import com.example.expressora.components.bottom_nav.BottomNav
import com.example.expressora.components.top_nav.TopNav
import com.example.expressora.components.top_nav2.TopTabNav2
import com.example.expressora.dashboard.user.learn.LearnActivity
import com.example.expressora.dashboard.user.notification.NotificationActivity
import com.example.expressora.dashboard.user.quiz.QuizActivity
import com.example.expressora.dashboard.user.settings.SettingsActivity
import com.example.expressora.dashboard.user.translation.TranslationActivity
import com.example.expressora.ui.theme.InterFontFamily
import com.google.accompanist.swiperefresh.SwipeRefresh
import com.google.accompanist.swiperefresh.rememberSwipeRefreshState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

sealed class TempMedia {
    data class Image(val uri: Uri) : TempMedia()
    data class Video(val uri: Uri) : TempMedia()
}

data class Comment(
    val id: Int,
    val author: String,
    val avatarRes: Int,
    val message: String,
    val timestamp: Long = System.currentTimeMillis(),
    var edited: Boolean = false
)

data class Post(
    val id: Int,
    val author: String,
    val avatarRes: Int,
    val content: String,
    val imageUris: List<Uri> = emptyList(),
    val videoUris: List<Uri> = emptyList(),
    var likes: Int = 0,
    var comments: MutableList<Comment> = mutableListOf(),
    var isLiked: Boolean = false,
    var showComments: Boolean = false,
    val timestamp: Long = System.currentTimeMillis(),
    var edited: Boolean = false
)

fun getVideoThumbnail(uri: Uri, context: Context): Bitmap? {
    return try {
        val retriever = MediaMetadataRetriever()
        retriever.setDataSource(context, uri)
        retriever.frameAtTime
    } catch (e: Exception) {
        e.printStackTrace()
        null
    }
}

fun getTimeAgo(time: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - time
    val minutes = TimeUnit.MILLISECONDS.toMinutes(diff)
    val hours = TimeUnit.MILLISECONDS.toHours(diff)
    val days = TimeUnit.MILLISECONDS.toDays(diff)

    return when {
        minutes < 1 -> "Just now"
        minutes < 60 -> "$minutes min ago"
        hours < 24 -> "$hours hr ago"
        else -> "$days day${if (days > 1) "s" else ""} ago"
    }
}

private fun nextPostId(posts: List<Post>): Int = (posts.maxOfOrNull { it.id } ?: 0) + 1
private fun nextCommentId(post: Post): Int = (post.comments.maxOfOrNull { it.id } ?: 0) + 1

class CommunitySpaceActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { CommunitySpaceScreen() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommunitySpaceScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val currentUserName = remember { "Jennie Kim" }

    val bgColor = Color(0xFFF8F8F8)
    val subtitleColor = Color(0xFF666666)

    val tempMedia = remember { mutableStateListOf<TempMedia>() }

    var posts by remember {
        mutableStateOf(
            listOf(
                Post(
                    id = 1,
                    author = "Taylor Swift",
                    avatarRes = R.drawable.taylor_swift,
                    content = "Yall be doing the most",
                    likes = 2,
                    comments = mutableListOf(
                        Comment(
                            1,
                            "Hyein Lee",
                            R.drawable.sample_profile2,
                            "Problema mo, bakla?",
                            System.currentTimeMillis() - 1000 * 60 * 5
                        )
                    ),
                    timestamp = System.currentTimeMillis() - 1000 * 60 * 50
                ), Post(
                    id = 2,
                    author = "Azealia Banks",
                    avatarRes = R.drawable.azealia_banks,
                    content = "Iggy Azalea is like my albino child...",
                    imageUris = listOf(Uri.parse("https://static.scientificamerican.com/sciam/cache/file/3A647477-C180-4D23-B4265C83F4906F2A_source.jpg?w=600")),
                    likes = 5,
                    comments = mutableListOf(),
                    timestamp = System.currentTimeMillis() - 1000 * 60 * 60 * 5
                ), Post(
                    id = 3,
                    author = "LOONA",
                    avatarRes = R.drawable.loona,
                    content = "Bye",
                    videoUris = listOf(Uri.parse("https://www.learningcontainer.com/wp-content/uploads/2020/05/sample-mp4-file.mp4")),
                    likes = 1,
                    comments = mutableListOf(),
                    timestamp = System.currentTimeMillis() - 1000 * 60 * 60 * 24
                )
            )
        )
    }

    var searchQuery by remember { mutableStateOf("") }
    var isRefreshing by remember { mutableStateOf(false) }
    val swipeRefreshState = rememberSwipeRefreshState(isRefreshing)
    val listState = rememberLazyListState()

    var showCreateDialog by remember { mutableStateOf(false) }
    var newPostText by remember { mutableStateOf("") }
    var previewImageUri by remember { mutableStateOf<Uri?>(null) }
    var previewVideoUri by remember { mutableStateOf<Uri?>(null) }

    val imagePicker =
        rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            uri?.let { tempMedia.add(TempMedia.Image(it)) }
        }
    val videoPicker =
        rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            uri?.let { tempMedia.add(TempMedia.Video(it)) }
        }

    var editingPost by remember { mutableStateOf<Post?>(null) }
    var editingPostMedia by remember { mutableStateOf(listOf<TempMedia>()) }
    var showEditPostDialog by remember { mutableStateOf(false) }

    var editingCommentPair by remember { mutableStateOf<Pair<Post, Comment>?>(null) }
    var showEditCommentDialog by remember { mutableStateOf(false) }

    var showDeletePostId by remember { mutableStateOf<Int?>(null) }
    var showDeleteCommentPair by remember { mutableStateOf<Pair<Int, Int>?>(null) } // Pair(postId, commentId)

    fun deletePost(postId: Int) {
        posts = posts.filter { it.id != postId }
        Toast.makeText(context, "Post deleted", Toast.LENGTH_SHORT).show()
    }

    fun editPost(postId: Int, newContent: String, newMedia: List<TempMedia>) {
        posts = posts.map { post ->
            if (post.id != postId) return@map post
            val newImages = newMedia.filterIsInstance<TempMedia.Image>().map { it.uri }
            val newVideos = newMedia.filterIsInstance<TempMedia.Video>().map { it.uri }

            val contentChanged = post.content != newContent
            val imagesChanged = post.imageUris != newImages
            val videosChanged = post.videoUris != newVideos

            if (contentChanged || imagesChanged || videosChanged) {
                Toast.makeText(context, "Post edited", Toast.LENGTH_SHORT).show()
                post.copy(
                    content = newContent,
                    imageUris = newImages,
                    videoUris = newVideos,
                    edited = true
                )
            } else {
                post
            }
        }
    }

    fun deleteComment(postId: Int, commentId: Int) {
        posts = posts.map { post ->
            if (post.id == postId) post.copy(comments = post.comments.filter { it.id != commentId }
                .toMutableList()) else post
        }
        Toast.makeText(context, "Comment deleted", Toast.LENGTH_SHORT).show()
    }

    fun editComment(postId: Int, commentId: Int, newMessage: String) {
        posts = posts.map { post ->
            if (post.id != postId) return@map post
            val updatedComments = post.comments.map { c ->
                if (c.id == commentId) {
                    if (c.message != newMessage) {
                        Toast.makeText(context, "Comment edited", Toast.LENGTH_SHORT).show()
                        c.copy(message = newMessage, edited = true)
                    } else c
                } else c
            }.toMutableList()
            post.copy(comments = updatedComments)
        }
    }

    fun refreshPosts() {
        scope.launch {
            isRefreshing = true
            delay(1200)
            val newId = nextPostId(posts)
            val newPost = Post(
                id = newId,
                author = "Expressora",
                avatarRes = R.drawable.expressora_logo,
                content = "Here's a new post from refresh!",
                timestamp = System.currentTimeMillis()
            )
            posts = listOf(newPost) + posts
            isRefreshing = false
            listState.animateScrollToItem(0)
        }
    }

    fun removeTempMedia(item: TempMedia) {
        tempMedia.remove(item)
    }

    Scaffold(topBar = {
        Column {
            TopNav(notificationCount = 2, onProfileClick = {
                context.startActivity(Intent(context, SettingsActivity::class.java))
            }, onTranslateClick = {}, onNotificationClick = {
                context.startActivity(Intent(context, NotificationActivity::class.java))
            })

            var selectedTab by remember { mutableStateOf(0) }
            TopTabNav2(selectedTab = selectedTab, onTabSelected = { selectedTab = it })

            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Search posts", color = Color(0xFF666666)) },
                leadingIcon = {
                    Icon(
                        Icons.Default.Search, contentDescription = "Search", tint = Color.Black
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 12.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color.White, RoundedCornerShape(50))
                    .shadow(2.dp, RoundedCornerShape(50)),
                singleLine = true,
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
        }
    }, bottomBar = {
        BottomNav(onLearnClick = {
            context.startActivity(
                Intent(
                    context, LearnActivity::class.java
                )
            )
        }, onCameraClick = {
            context.startActivity(
                Intent(
                    context, TranslationActivity::class.java
                )
            )
        }, onQuizClick = { context.startActivity(Intent(context, QuizActivity::class.java)) })
    }, floatingActionButton = {
        FloatingActionButton(
            onClick = { showCreateDialog = true },
            containerColor = Color(0xFFFACC15),
            contentColor = Color.Black,
            shape = CircleShape
        ) {
            Icon(Icons.Default.Add, contentDescription = "Add Post", tint = Color.Black)
        }
    }) { paddingValues ->
        SwipeRefresh(
            state = swipeRefreshState,
            onRefresh = { refreshPosts() },
            modifier = Modifier
                .fillMaxSize()
                .background(bgColor)
                .padding(paddingValues)
        ) {
            val filteredPosts = if (searchQuery.isBlank()) posts else posts.filter {
                it.content.contains(searchQuery, ignoreCase = true) || it.author.contains(
                    searchQuery, ignoreCase = true
                )
            }

            LazyColumn(
                state = listState,
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(
                    top = 0.dp, bottom = 12.dp, start = 24.dp, end = 24.dp
                ),
                modifier = Modifier.fillMaxSize()
            ) {
                if (searchQuery.isNotBlank() && filteredPosts.isEmpty()) {
                    item {
                        Text(
                            "No posts found.",
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 24.dp),
                            textAlign = TextAlign.Center,
                            color = Color(0xFF666666)
                        )
                    }
                }

                itemsIndexed(filteredPosts, key = { _, post -> post.id }) { index, post ->
                    PostCard(
                        post = post,
                        subtitleColor = subtitleColor,
                        currentUser = currentUserName,
                        onLikeToggle = {
                            posts = posts.map {
                                if (it.id == post.id) {
                                    val liked = !it.isLiked
                                    it.copy(
                                        isLiked = liked,
                                        likes = if (liked) it.likes + 1 else it.likes - 1
                                    )
                                } else it
                            }
                        },
                        onCommentToggle = {
                            posts =
                                posts.map { if (it.id == post.id) it.copy(showComments = !it.showComments) else it }
                        },
                        onAddComment = { message ->
                            val newC = Comment(
                                nextCommentId(post),
                                currentUserName,
                                R.drawable.sample_profile,
                                message,
                                System.currentTimeMillis()
                            )
                            posts = posts.map {
                                if (it.id == post.id) it.copy(comments = (it.comments + newC).toMutableList()) else it
                            }
                            Toast.makeText(context, "Comment posted", Toast.LENGTH_SHORT).show()
                        },
                        onImageClick = { previewImageUri = it },
                        onVideoClick = { previewVideoUri = it },
                        onNext = {
                            scope.launch {
                                val target = index + 1
                                if (target < filteredPosts.size) listState.animateScrollToItem(
                                    target
                                )
                            }
                        },
                        onEditPost = {
                            editingPost = post

                            val combined = mutableListOf<TempMedia>()
                            post.imageUris.forEach { combined.add(TempMedia.Image(it)) }
                            post.videoUris.forEach { combined.add(TempMedia.Video(it)) }
                            editingPostMedia = combined
                            showEditPostDialog = true
                        },
                        onDeletePost = { showDeletePostId = it.id },
                        onEditComment = { comment ->
                            editingCommentPair = post to comment
                            showEditCommentDialog = true
                        },
                        onDeleteComment = { comment ->
                            showDeleteCommentPair = post.id to comment.id
                        })
                }
            }
        }
    }

    if (showCreateDialog) {
        CreatePostDialog(
            newPostText = newPostText,
            tempMedia = tempMedia,
            onTextChange = { newPostText = it },
            onDismiss = {
                showCreateDialog = false
                newPostText = ""
                tempMedia.clear()
            },
            onPost = {
                if (newPostText.isNotBlank() || tempMedia.isNotEmpty()) {
                    val newId = nextPostId(posts)
                    val contentValue = newPostText
                    val newPost = Post(
                        id = newId,
                        author = currentUserName,
                        avatarRes = R.drawable.sample_profile,
                        content = contentValue,
                        imageUris = tempMedia.filterIsInstance<TempMedia.Image>().map { it.uri },
                        videoUris = tempMedia.filterIsInstance<TempMedia.Video>().map { it.uri },
                        timestamp = System.currentTimeMillis()
                    )
                    posts = listOf(newPost) + posts
                    newPostText = ""
                    tempMedia.clear()
                    showCreateDialog = false
                    scope.launch { listState.animateScrollToItem(0) }
                    Toast.makeText(context, "Post published", Toast.LENGTH_SHORT).show()
                }
            },
            onPickImage = { imagePicker.launch("image/*") },
            onPickVideo = { videoPicker.launch("video/*") },
            onRemoveMedia = { removeTempMedia(it = it, tempMedia = tempMedia) })
    }

    if (showEditPostDialog && editingPost != null) {
        EditPostDialog(
            initialPost = editingPost!!,
            initialMedia = editingPostMedia,
            onDismiss = {
                editingPost = null
                editingPostMedia = emptyList()
                showEditPostDialog = false
            },
            onSave = { newText, newMedia ->
                val pid = editingPost!!.id
                editPost(pid, newText, newMedia)
                editingPost = null
                editingPostMedia = emptyList()
                showEditPostDialog = false
            },
            onPickImage = { uri -> editingPostMedia = editingPostMedia + TempMedia.Image(uri) },
            onPickVideo = { uri -> editingPostMedia = editingPostMedia + TempMedia.Video(uri) },
            onRemoveMedia = { item -> editingPostMedia = editingPostMedia.filter { it != item } })
    }

    if (showEditCommentDialog && editingCommentPair != null) {
        val (post, comment) = editingCommentPair!!
        EditCommentDialogStyled(initialMessage = comment.message, onDismiss = {
            showEditCommentDialog = false
            editingCommentPair = null
        }, onSave = { newMsg ->
            editComment(post.id, comment.id, newMsg)
            showEditCommentDialog = false
            editingCommentPair = null
        })
    }

    showDeletePostId?.let { pid ->
        ConfirmDeleteDialogStyled(
            title = "Delete Post",
            message = "Are you sure you want to delete this post? This action cannot be undone.",
            onDismiss = { showDeletePostId = null },
            onConfirm = {
                deletePost(pid)
                showDeletePostId = null
            })
    }

    showDeleteCommentPair?.let { (postId, commentId) ->
        ConfirmDeleteDialogStyled(
            title = "Delete Comment",
            message = "Are you sure you want to delete this comment? This action cannot be undone.",
            onDismiss = { showDeleteCommentPair = null },
            onConfirm = {
                deleteComment(postId, commentId)
                showDeleteCommentPair = null
            })
    }

    previewImageUri?.let { uri ->
        Dialog(onDismissRequest = { previewImageUri = null }) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                AsyncImage(
                    model = uri,
                    contentDescription = "Preview Image",
                    modifier = Modifier
                        .fillMaxWidth(0.9f)
                        .wrapContentHeight()
                        .clip(RoundedCornerShape(12.dp)),
                    contentScale = ContentScale.Fit
                )
            }
        }
    }

    previewVideoUri?.let { uri ->
        VideoPreviewDialog(videoUri = uri, onDismiss = { previewVideoUri = null })
    }
}

private fun removeTempMedia(it: TempMedia, tempMedia: SnapshotStateList<TempMedia>) {
    tempMedia.remove(it)
}

@Composable
fun PostCard(
    post: Post,
    subtitleColor: Color,
    currentUser: String,
    onLikeToggle: () -> Unit,
    onCommentToggle: () -> Unit,
    onAddComment: (String) -> Unit,
    onImageClick: (Uri) -> Unit,
    onVideoClick: (Uri) -> Unit,
    onNext: () -> Unit,
    onEditPost: (Post) -> Unit,
    onDeletePost: (Post) -> Unit,
    onEditComment: (Comment) -> Unit,
    onDeleteComment: (Comment) -> Unit
) {
    var newComment by remember { mutableStateOf("") }
    var expanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Image(
                        painter = painterResource(id = post.avatarRes),
                        contentDescription = "Avatar",
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape),
                        contentScale = ContentScale.Crop
                    )
                    Spacer(Modifier.width(12.dp))
                    Column {
                        val editedLabel = if (post.edited) " (edited)" else ""
                        Text(
                            "${post.author}$editedLabel",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = InterFontFamily
                        )
                        Text(
                            getTimeAgo(post.timestamp),
                            fontSize = 12.sp,
                            fontFamily = InterFontFamily,
                            color = subtitleColor
                        )
                    }
                }

                if (post.author == currentUser) {
                    Row {
                        IconButton(onClick = { onEditPost(post) }) {
                            Icon(
                                Icons.Default.Edit,
                                contentDescription = "Edit Post",
                                tint = Color.Black
                            )
                        }
                        IconButton(onClick = { onDeletePost(post) }) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = "Delete Post",
                                tint = Color.Red
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            if (post.content.isNotBlank()) {
                Text(
                    text = post.content,
                    fontSize = 14.sp,
                    fontFamily = InterFontFamily,
                    color = subtitleColor,
                    maxLines = if (expanded) Int.MAX_VALUE else 3,
                    overflow = TextOverflow.Ellipsis
                )
            }

            val isLongContent = post.content.length > 120
            if (isLongContent) {
                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = if (expanded) "Show less" else "See more",
                        modifier = Modifier.clickable { expanded = !expanded },
                        fontSize = 13.sp,
                        fontFamily = InterFontFamily,
                        color = Color(0xFF666666)
                    )
                    Text(
                        text = "Next",
                        modifier = Modifier.clickable { onNext() },
                        fontSize = 13.sp,
                        fontFamily = InterFontFamily,
                        color = Color(0xFF666666)
                    )
                }
            }

            post.imageUris.forEach { uri ->
                Spacer(Modifier.height(8.dp))
                AsyncImage(
                    model = uri,
                    contentDescription = "Post Image",
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { onImageClick(uri) },
                    contentScale = ContentScale.Crop
                )
            }

            post.videoUris.forEach { uri ->
                Spacer(Modifier.height(8.dp))
                val context = LocalContext.current
                val thumbnail = remember(uri) { getVideoThumbnail(uri, context) }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { onVideoClick(uri) },
                    contentAlignment = Alignment.Center
                ) {
                    if (thumbnail != null) {
                        Image(
                            bitmap = thumbnail.asImageBitmap(),
                            contentDescription = "Video Thumbnail",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color.Black)
                        )
                    }
                    Icon(
                        Icons.Default.PlayArrow,
                        contentDescription = "Play",
                        tint = Color.White,
                        modifier = Modifier.size(48.dp)
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onLikeToggle) {
                    Icon(
                        imageVector = Icons.Default.ThumbUp,
                        contentDescription = "Like",
                        modifier = Modifier.size(20.dp),
                        tint = if (post.isLiked) Color(0xFFFACC15) else Color.Black
                    )
                }
                Spacer(Modifier.width(4.dp))
                Text(
                    "${post.likes}",
                    fontSize = 14.sp,
                    fontFamily = InterFontFamily,
                    color = subtitleColor
                )

                Spacer(Modifier.width(16.dp))
                IconButton(onClick = onCommentToggle) {
                    Icon(
                        Icons.Default.ModeComment,
                        contentDescription = "Comment",
                        modifier = Modifier.size(20.dp),
                        tint = Color.Black
                    )
                }
                Spacer(Modifier.width(4.dp))
                Text(
                    "${post.comments.size}",
                    fontSize = 14.sp,
                    fontFamily = InterFontFamily,
                    color = subtitleColor
                )
            }

            if (post.showComments) {
                Spacer(Modifier.height(8.dp))
                Column {
                    post.comments.forEach { comment ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Image(
                                    painter = painterResource(id = comment.avatarRes),
                                    contentDescription = "Avatar",
                                    modifier = Modifier
                                        .size(28.dp)
                                        .clip(CircleShape),
                                    contentScale = ContentScale.Crop
                                )
                                Spacer(Modifier.width(8.dp))
                                Column {
                                    val editedTag = if (comment.edited) " (edited)" else ""
                                    Text(
                                        comment.author + editedTag,
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = InterFontFamily,
                                        fontSize = 13.sp
                                    )
                                    Text(
                                        "${comment.message} · ${getTimeAgo(comment.timestamp)}",
                                        fontSize = 13.sp,
                                        fontFamily = InterFontFamily,
                                        color = subtitleColor
                                    )
                                }
                            }

                            if (comment.author == "Jennie Kim") {
                                Row {
                                    IconButton(onClick = { onEditComment(comment) }) {
                                        Icon(
                                            Icons.Default.Edit,
                                            contentDescription = "Edit Comment",
                                            tint = Color.Black
                                        )
                                    }
                                    IconButton(onClick = { onDeleteComment(comment) }) {
                                        Icon(
                                            Icons.Default.Delete,
                                            contentDescription = "Delete Comment",
                                            tint = Color.Red
                                        )
                                    }
                                }
                            }
                        }
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        OutlinedTextField(
                            value = newComment,
                            onValueChange = { newComment = it },
                            placeholder = { Text("Write a reply...", color = Color(0xFF666666)) },
                            modifier = Modifier.weight(1f),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color.Black,
                                unfocusedTextColor = Color(0xFF666666),
                                focusedBorderColor = Color.Black,
                                unfocusedBorderColor = Color(0xFF666666)
                            )
                        )
                        IconButton(onClick = {
                            if (newComment.isNotBlank()) {
                                onAddComment(newComment)
                                newComment = ""
                            }
                        }) {
                            Icon(
                                Icons.Default.Send, contentDescription = "Reply", tint = Color.Black
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun CreatePostDialog(
    newPostText: String,
    tempMedia: SnapshotStateList<TempMedia>,
    onTextChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onPost: () -> Unit,
    onPickImage: () -> Unit,
    onPickVideo: () -> Unit,
    onRemoveMedia: (TempMedia) -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            tonalElevation = 8.dp,
            modifier = Modifier.fillMaxWidth(),
            color = Color.White
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    "Create Post",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = InterFontFamily,
                    modifier = Modifier.padding(bottom = 12.dp)
                )

                OutlinedTextField(
                    value = newPostText,
                    onValueChange = onTextChange,
                    label = { Text("What’s on your mind?", color = Color(0xFF666666)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 60.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.Black,
                        unfocusedTextColor = Color(0xFF666666),
                        focusedBorderColor = Color.Black,
                        unfocusedBorderColor = Color(0xFF666666)
                    ),
                    maxLines = 5
                )

                Spacer(Modifier.height(12.dp))
                Divider()
                Spacer(Modifier.height(12.dp))

                if (tempMedia.isNotEmpty()) {
                    Column {
                        tempMedia.chunked(2).forEach { rowItems ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                rowItems.forEach { media ->
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(180.dp)
                                            .clip(RoundedCornerShape(12.dp)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        when (media) {
                                            is TempMedia.Image -> AsyncImage(
                                                model = media.uri,
                                                contentDescription = "Image Preview",
                                                contentScale = ContentScale.Crop,
                                                modifier = Modifier.fillMaxSize()
                                            )

                                            is TempMedia.Video -> VideoPreviewThumbnail(uri = media.uri)
                                        }

                                        IconButton(
                                            onClick = { onRemoveMedia(media) },
                                            modifier = Modifier
                                                .align(Alignment.TopEnd)
                                                .size(24.dp)
                                                .background(
                                                    Color.Black.copy(alpha = 0.5f), CircleShape
                                                )
                                        ) {
                                            Icon(
                                                Icons.Default.Close,
                                                contentDescription = "Remove",
                                                tint = Color.White,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    }
                                }
                                if (rowItems.size < 2) Spacer(modifier = Modifier.weight(1f))
                            }
                        }
                    }

                    Spacer(Modifier.height(12.dp))
                    Divider()
                    Spacer(Modifier.height(12.dp))
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row {
                        IconButton(onClick = onPickImage) {
                            Icon(
                                Icons.Default.Image,
                                contentDescription = "Add Image",
                                tint = Color.Black
                            )
                        }
                        IconButton(onClick = onPickVideo) {
                            Icon(
                                Icons.Default.Videocam,
                                contentDescription = "Add Video",
                                tint = Color.Black
                            )
                        }
                    }

                    Row {
                        TextButton(
                            onClick = onDismiss, colors = ButtonDefaults.textButtonColors(
                                contentColor = Color.Gray
                            )
                        ) {
                            Text(
                                "Cancel", color = Color(0xFF666666)
                            )
                        }
                        Button(
                            onClick = onPost, colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFFFACC15), contentColor = Color.Black
                            )
                        ) { Text("Post") }
                    }
                }
            }
        }
    }
}

@Composable
fun EditPostDialog(
    initialPost: Post,
    initialMedia: List<TempMedia>,
    onDismiss: () -> Unit,
    onSave: (String, List<TempMedia>) -> Unit,
    onPickImage: (Uri) -> Unit = {},
    onPickVideo: (Uri) -> Unit = {},
    onRemoveMedia: (TempMedia) -> Unit = {}
) {
    var updatedText by remember { mutableStateOf(initialPost.content) }
    val updatedMedia = remember { mutableStateListOf<TempMedia>().apply { addAll(initialMedia) } }

    val imagePicker =
        rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            uri?.let { updatedMedia.add(TempMedia.Image(it)) }
        }
    val videoPicker =
        rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            uri?.let { updatedMedia.add(TempMedia.Video(it)) }
        }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            tonalElevation = 8.dp,
            modifier = Modifier.fillMaxWidth(),
            color = Color.White
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    "Edit Post",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = InterFontFamily,
                    modifier = Modifier.padding(bottom = 12.dp)
                )

                OutlinedTextField(
                    value = updatedText,
                    onValueChange = { updatedText = it },
                    label = { Text("What’s on your mind?", color = Color(0xFF666666)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 60.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.Black,
                        unfocusedTextColor = Color(0xFF666666),
                        focusedBorderColor = Color.Black,
                        unfocusedBorderColor = Color(0xFF666666)
                    ),
                    maxLines = 5
                )

                Spacer(Modifier.height(12.dp))
                Divider()
                Spacer(Modifier.height(12.dp))

                if (updatedMedia.isNotEmpty()) {
                    Column {
                        updatedMedia.chunked(2).forEach { rowItems ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                rowItems.forEach { media ->
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(180.dp)
                                            .clip(RoundedCornerShape(12.dp)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        when (media) {
                                            is TempMedia.Image -> AsyncImage(
                                                model = media.uri,
                                                contentDescription = "Image",
                                                contentScale = ContentScale.Crop,
                                                modifier = Modifier.fillMaxSize()
                                            )

                                            is TempMedia.Video -> VideoPreviewThumbnail(uri = media.uri)
                                        }

                                        IconButton(
                                            onClick = { updatedMedia.remove(media) },
                                            modifier = Modifier
                                                .align(Alignment.TopEnd)
                                                .size(24.dp)
                                                .background(
                                                    Color.Black.copy(alpha = 0.5f), CircleShape
                                                )
                                        ) {
                                            Icon(
                                                Icons.Default.Close,
                                                contentDescription = "Remove",
                                                tint = Color.White,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    }
                                }
                                if (rowItems.size < 2) Spacer(modifier = Modifier.weight(1f))
                            }
                        }
                    }

                    Spacer(Modifier.height(12.dp))
                    Divider()
                    Spacer(Modifier.height(12.dp))
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row {
                        IconButton(onClick = { imagePicker.launch("image/*") }) {
                            Icon(
                                Icons.Default.Image,
                                contentDescription = "Add Image",
                                tint = Color.Black
                            )
                        }
                        IconButton(onClick = { videoPicker.launch("video/*") }) {
                            Icon(
                                Icons.Default.Videocam,
                                contentDescription = "Add Video",
                                tint = Color.Black
                            )
                        }
                    }

                    Row {
                        TextButton(
                            onClick = onDismiss, colors = ButtonDefaults.textButtonColors(
                                contentColor = Color.Gray
                            )
                        ) {
                            Text(
                                "Cancel", color = Color(0xFF666666)
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Button(
                            onClick = { onSave(updatedText, updatedMedia.toList()) },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFFFACC15), contentColor = Color.Black
                            )
                        ) {
                            Text("Save")
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun EditCommentDialogStyled(
    initialMessage: String, onDismiss: () -> Unit, onSave: (String) -> Unit
) {
    var text by remember { mutableStateOf(initialMessage) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            tonalElevation = 8.dp,
            modifier = Modifier.fillMaxWidth(),
            color = Color.White
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    "Edit Comment",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = InterFontFamily,
                    modifier = Modifier.padding(bottom = 12.dp)
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 60.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.Black,
                        unfocusedTextColor = Color(0xFF666666),
                        focusedBorderColor = Color.Black,
                        unfocusedBorderColor = Color(0xFF666666)
                    ),
                    maxLines = 5
                )

                Spacer(Modifier.height(12.dp))
                Divider()
                Spacer(Modifier.height(12.dp))

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(
                        onClick = onDismiss, colors = ButtonDefaults.textButtonColors(
                            contentColor = Color.Gray
                        )
                    ) { Text("Cancel", color = Color(0xFF666666)) }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = { onSave(text) }, colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFFFACC15), contentColor = Color.Black
                        )
                    ) {
                        Text("Save")
                    }
                }
            }
        }
    }
}

@Composable
fun ConfirmDeleteDialogStyled(
    title: String, message: String, onDismiss: () -> Unit, onConfirm: () -> Unit
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
                    fontFamily = InterFontFamily,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                Text(message, color = Color(0xFF666666))

                Spacer(Modifier.height(12.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(
                        onClick = onDismiss, colors = ButtonDefaults.textButtonColors(
                            contentColor = Color.Gray
                        )
                    ) {
                        Text("Cancel", color = Color(0xFF666666))
                    }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = onConfirm, colors = ButtonDefaults.buttonColors(
                            containerColor = Color.Red, contentColor = Color.White
                        )
                    ) {
                        Text("Delete")
                    }
                }
            }
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
fun VideoPreviewDialog(videoUri: Uri, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var player: ExoPlayer? by remember { mutableStateOf(null) }

    DisposableEffect(videoUri) {
        val exoPlayer = ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(videoUri))
            prepare()
            playWhenReady = true
        }
        player = exoPlayer

        onDispose {
            exoPlayer.release()
            player = null
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        val retriever = MediaMetadataRetriever()
        retriever.setDataSource(context, videoUri)
        val videoWidth =
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toInt()
                ?: 16
        val videoHeight =
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toInt()
                ?: 9
        val aspectRatio = videoWidth.toFloat() / videoHeight.toFloat()

        Box(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .aspectRatio(aspectRatio)
        ) {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        this.player = player
                        this.useController = true
                        this.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                        this.setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
                    }
                }, modifier = Modifier.fillMaxSize()
            )
        }
    }
}

@Composable
fun VideoPreviewThumbnail(uri: Uri) {
    val context = LocalContext.current
    val thumbnail = remember(uri) { getVideoThumbnail(uri, context) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(12.dp))
    ) {
        if (thumbnail != null) {
            Image(
                bitmap = thumbnail.asImageBitmap(),
                contentDescription = "Video Thumbnail",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
            )
        }

        Icon(
            imageVector = Icons.Default.PlayArrow,
            contentDescription = "Play Video",
            tint = Color.White,
            modifier = Modifier
                .size(48.dp)
                .align(Alignment.Center)
        )
    }
}

@Preview(showBackground = true)
@Composable
fun PreviewCommunitySpaceScreen() {
    CommunitySpaceScreen()
}
