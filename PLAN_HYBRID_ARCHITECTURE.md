# Hybrid Architecture Transition & UI Overhaul

## Overview
Transition to Hybrid Architecture (On-Device Detection + Cloud Translation), fix invisible gloss bug, and implement modern split-screen UI with 7-slot grid layout.

## Step 1: Force On-Device Detection Mode

**File:** `app/src/main/java/com/example/expressora/recognition/di/RecognitionProvider.kt`

**Problem:** App is using ONLINE mode but server is silent. Local TFLite model is detecting signs but not being used.

**Solution:** Hardcode `useOnlineMode` to `false` to force local detection.

**Location:** Line 223
**Current Code:**
```kotlin
val useOnlineMode = prefs.getBoolean("use_online_mode", true) // Default to online
```

**Change To:**
```kotlin
val useOnlineMode = false // FORCED: Use on-device detection (TFLite) instead of gRPC streaming
```

**Note:** This forces the app to use `TfLiteRecognitionEngine` for detection while still allowing `translateSequence()` to use the server for translation.

## Step 2: Verify Invisible Gloss Bug Fix (Already Applied)

**File:** `app/src/main/java/com/example/expressora/recognition/pipeline/RecognitionViewModel.kt`

**Status:** Already fixed - using `_glossList.update { }` pattern at line 507.

**Verification:** Confirm the update pattern is correct:
```kotlin
_glossList.update { currentList ->
    val newList = currentList + newGloss
    newList
}
```

**Note:** The fix is already in place. No changes needed.

## Step 3: Verify sendLandmarks Removal (Already Applied)

**File:** `app/src/main/java/com/example/expressora/recognition/pipeline/RecognitionViewModel.kt`

**Status:** Already removed - line 386 shows landmark streaming is skipped.

**Verification:** Confirm `streamer.sendLandmarks()` is not being called in `onLandmarks()`.

**Note:** The optimization is already in place. No changes needed.

## Step 4: Fix LazyRow to Use items(list) API

**File:** `app/src/main/java/com/example/expressora/dashboard/user/translation/Translation.kt`

**Problem:** Using `items(glossList.size)` which may not detect list changes properly.

**Solution:** Switch to `items(items = glossList)` with proper keys.

**Location:** Line 811
**Current Code:**
```kotlin
items(glossList.size) { index ->
    val gloss = glossList[index]
    val isLastItem = index == glossList.size - 1
```

**Change To:**
```kotlin
items(
    items = glossList,
    key = { gloss -> gloss }
) { gloss ->
    val index = glossList.indexOf(gloss)
    val isLastItem = index == glossList.size - 1
```

**Note:** If glosses can be duplicated, use `key = { "${gloss}_$index" }` instead.

## Step 5: Implement 7-Slot Grid Layout

**File:** `app/src/main/java/com/example/expressora/dashboard/user/translation/Translation.kt`

**Problem:** Current LazyRow scrolls. Need fixed 7-slot grid showing filled/empty states.

**Solution:** Replace LazyRow with a Row containing 7 fixed slots.

**Location:** Replace lines 802-844 (the LazyRow block)
**New Code:**
```kotlin
// 7-Slot Grid for Gloss Sequence
Row(
    modifier = Modifier
        .fillMaxWidth()
        .padding(vertical = 8.dp),
    horizontalArrangement = Arrangement.spacedBy(8.dp)
) {
    repeat(7) { index ->
        val isFilled = index < glossList.size
        val gloss = if (isFilled) glossList[index] else null
        val isLastItem = index == glossList.size - 1
        
        // Slot container
        Box(
            modifier = Modifier
                .weight(1f)
                .height(48.dp)
                .then(
                    if (isFilled) {
                        Modifier
                            .background(Color(0xFF4CAF50), RoundedCornerShape(12.dp))
                            .border(1.dp, Color.White.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
                    } else {
                        Modifier
                            .background(Color.Transparent)
                            .border(1.dp, Color.White.copy(alpha = 0.3f), RoundedCornerShape(12.dp), style = androidx.compose.ui.graphics.StrokePathEffect(androidx.compose.ui.graphics.PathDashPathEffect(androidx.compose.ui.geometry.Path(), 4f, 0f, androidx.compose.ui.graphics.PathFillType.NonZero))
                            .alpha(0.3f)
                    }
                )
                .padding(horizontal = 8.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            if (isFilled && gloss != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = gloss,
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = InterFontFamily
                    )
                    if (isLastItem) {
                        IconButton(
                            onClick = onRemoveGloss,
                            modifier = Modifier.size(20.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription = "Delete Last",
                                tint = Color.White,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            } else {
                // Empty slot - show dashed border only
                Text(
                    text = "${index + 1}",
                    color = Color.White.copy(alpha = 0.3f),
                    fontSize = 12.sp,
                    fontFamily = InterFontFamily
                )
            }
        }
    }
}
```

**Note:** For dashed border, we may need to use a simpler approach with a regular border and reduced opacity for empty slots.

## Step 6: Verify Overlay Visibility Fix (Already Applied)

**File:** `app/src/main/java/com/example/expressora/recognition/view/HolisticLandmarkOverlay.kt`

**Status:** Already fixed - `STALE_DATA_THRESHOLD_MS = 5000L` at line 42.

**Verification:** Confirm the threshold is 5000L (5 seconds).

**Note:** The fix is already in place. No changes needed.

## Step 7: Update UI Background to Dark Theme

**File:** `app/src/main/java/com/example/expressora/dashboard/user/translation/Translation.kt`

**Status:** Already using dark background - `bgColor = Color(0xFF121212)` at line 661.

**Verification:** Confirm dark theme is applied.

**Note:** Already correct. No changes needed.

## Implementation Order
1. Step 1 (Force on-device mode) - Critical for detection to work
2. Step 4 (Fix LazyRow) - Critical for UI display
3. Step 5 (7-slot grid) - UI overhaul
4. Steps 2, 3, 6, 7 (Verification) - Already fixed, just verify

## Testing Checklist
- [ ] Code compiles without errors
- [ ] App uses on-device detection (check logs for "Using OFFLINE mode")
- [ ] Gloss chips appear in 7-slot grid when signs are detected
- [ ] Empty slots show dashed borders
- [ ] Filled slots show green chips with gloss text
- [ ] Translate button appears when glossList is not empty
- [ ] Overlay mesh remains visible during device freezes
- [ ] No landmark streaming to server (check network logs)

