# Phase H — Diagnostics UI & Mode Toggle Requirements

## Current Status
- ✅ RecognitionDiagnostics.logStartupConfig() emits one-line summary with device telemetry
- ✅ Debug build warning banner implemented
- ✅ Auto-fallback detection logic (shouldAutoFallbackToLite()) implemented
- ✅ FPS rolling average tracking (getCurrentFPS()) implemented
- ✅ PerformanceConfig presets (Lite/Balanced/Accuracy) defined

## Remaining Work for Phase H

### 1. Overlay Chips Display
Location: `dashboard/user/translation/Translation.kt`

Add composable chips showing:
- **FPS**: `RecognitionDiagnostics.getCurrentFPS()` with color coding:
  - Green: >= target FPS
  - Yellow: 80-100% of target
  - Red: < 80% of target
- **Hand Count**: 0/1/2 from HandLandmarkerEngine state
- **Face Cadence**: Current face update rate (e.g., "/5f")
- **Delegate**: Hands & Classifier delegates from `RecognitionDiagnostics.getDelegates()`
- **Mode**: Current preset (Lite/Balanced/Accuracy) from `PerformanceConfig.modeLabel`

Example layout:
```
[ FPS: 18.3 ] [ Hands: 2 ] [ Face: /5f ] [ GPU/GPU ] [ Balanced ]
```

### 2. Mode Toggle Control
Location: `dashboard/user/translation/Translation.kt`

- Add dev icon/button to toggle between presets
- On toggle:
  - Call `PerformanceConfig.applyPreset(mode)`
  - Persist choice to SharedPreferences: `"performance_mode": "LITE"|"BALANCED"|"ACCURACY"`
  - Toast confirmation: "Switched to [Mode] preset"
- On app startup:
  - Read SharedPreferences and restore last mode
  - Already implemented at line 140-141

### 3. Auto-Fallback Toast
Location: `dashboard/user/translation/Translation.kt`

- Monitor `RecognitionDiagnostics.shouldAutoFallbackToLite()` every ~1s
- If returns true:
  - Show Toast: "⚠️ Low FPS detected. Switched to Lite mode. Revert in Settings."
  - Apply Lite preset
  - Persist to SharedPreferences
  - Provide settings link to revert

### 4. Startup Summary Display (Optional)
- Log already prints to Logcat
- Consider showing in debug overlay or dev menu
- Use `RecognitionDiagnostics.getStartupSummary()`

## Implementation Notes

### Composable Chips Example
```kotlin
@Composable
fun DiagnosticsChips(
    fps: Float,
    handCount: Int,
    faceCadence: Int,
    delegates: Triple<String, String, String>,
    mode: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        FpsChip(fps)
        HandCountChip(handCount)
        FaceCadenceChip(faceCadence)
        DelegateChip(delegates)
        ModeChip(mode)
    }
}
```

### Mode Toggle Example
```kotlin
IconButton(
    onClick = {
        val nextMode = when (PerformanceConfig.currentMode) {
            Mode.LITE -> Mode.BALANCED
            Mode.BALANCED -> Mode.ACCURACY
            Mode.ACCURACY -> Mode.LITE
        }
        PerformanceConfig.applyPreset(nextMode)
        
        // Persist
        val prefs = getSharedPreferences("expressora_prefs", MODE_PRIVATE)
        prefs.edit().putString("performance_mode", nextMode.name).apply()
        
        Toast.makeText(context, "Switched to ${nextMode.label}", LENGTH_SHORT).show()
    }
) {
    Icon(Icons.Default.Settings, "Performance Mode")
}
```

## Testing Checklist
- [ ] Chips update in real-time (< 1s latency)
- [ ] Mode toggle applies immediately
- [ ] Mode persists across app restarts
- [ ] Auto-fallback toast appears under sustained low FPS
- [ ] Debug warning banner shows in Debug builds
- [ ] Startup summary includes device info

## Phase I & J Notes
- **Phase I**: Build settings already updated (R8, ABI splits, hardware acceleration)
- **Phase J**: Test matrix requires physical devices — document test scenarios in ACCEPTANCE_TESTS.md

