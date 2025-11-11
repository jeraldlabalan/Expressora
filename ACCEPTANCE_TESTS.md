# Expressora Recognition Module — Acceptance Test Matrix

## Test Environment Setup
- **Build Configuration**: Release build with R8 enabled, resource shrinking on
- **Devices Required**:
  - Low-end: < 4GB RAM, older GPU (e.g., Android 8-9)
  - Mid-range: 4-6GB RAM, Mali/Adreno GPU (e.g., Android 10-11)
  - High-end: >= 6GB RAM, recent GPU (e.g., Android 12+)
- **Logging**: Enable `adb logcat | grep -E "(RecognitionDiag|HandLandmarker|TfLiteInterpreter)"`

## Phase A — Config Foundation ✅
**Acceptance Gate**: Startup one-liner prints with all fields

### Test
1. Launch app in Release build
2. Check logcat for startup summary

**Expected Output**:
```
480x360 | latest | Hands: GPU H=2 thr=0.60 detectN=5 | Face: CPU /5f | Clf: INT8 GPU thr=0.65 hold=3 | TARGET=18FPS | Device: Pixel6 (Tensor) / GPU | Mode: Balanced
```

**Pass Criteria**:
- ✅ All fields present
- ✅ Device info includes model and GPU status
- ✅ Mode matches SharedPreferences setting
- ✅ Debug warning appears if Debug build

---

## Phase B — Camera & Frame Path ✅
**Acceptance Gate**: Zero allocations per frame; ≥12 FPS idle at 480×360

### Test
1. Launch app with hands hidden
2. Monitor logcat for GC events: `adb logcat | grep "GC_"`
3. Check FPS overlay/logcat

**Pass Criteria**:
- ✅ No GC events every frame (occasional is okay)
- ✅ FPS ≥ 12 on mid-range device (idle, no hands)
- ✅ Camera analyzer logs "directYUV=true"

---

## Phase C — Hands Pipeline ✅
**Acceptance Gate**: ≥15 FPS with two hands; motion gating stable; instant detect on tracking loss

### Test Scenario 1: Two Hands Performance
1. Show two hands to camera
2. Move slowly, then quickly
3. Monitor FPS

**Pass Criteria**:
- ✅ FPS ≥ 15 on mid-range during two-hand tracking
- ✅ FPS ≥ 20 on high-end
- ✅ No UI jank (frames < 10 FPS for > 2s)

### Test Scenario 2: Motion Gating
1. Hold hand still for 3 seconds
2. Check logcat for "Hand stationary" messages
3. Move hand

**Pass Criteria**:
- ✅ Classifier invocations reduce when still
- ✅ Motion detected immediately on movement
- ✅ Tokens don't spam when hand idle

### Test Scenario 3: Tracking Drift Watchdog
1. Show hand, then quickly hide it
2. Repeat 3 times within 5 seconds
3. Check logcat for "Tracking drift detected"

**Pass Criteria**:
- ✅ Detect cadence lowers (log shows "lowering detect cadence to X")
- ✅ Hand reappears within 1 second
- ✅ Cadence restores when tracking stable

---

## Phase D — Face Pipeline ✅ (Placeholder)
**Acceptance Gate**: ≥15 FPS with hands+face; auto-pause/resume of blendshapes

### Test (When Face Pipeline Integrated)
1. Show face + hands
2. Monitor FPS
3. Simulate heavy load

**Pass Criteria**:
- ✅ FPS ≥ 15 with face enabled
- ✅ Blendshapes pause if FPS < target for >2s
- ✅ Blendshapes resume if FPS > target+2 for >2s

---

## Phase E — Classifier Lane ✅
**Acceptance Gate**: Correct delegate chosen; logit smoothing reduces flicker; origin badge correct

### Test Scenario 1: Delegate Selection
1. Launch app
2. Check logcat for "Delegate probe order" and "✓ [Delegate] enabled"

**Pass Criteria**:
- ✅ GPU chosen on compatible devices
- ✅ Falls back to NNAPI or XNNPACK if GPU unsupported
- ✅ Startup summary shows correct delegate

### Test Scenario 2: Logit Smoothing
1. Perform same sign repeatedly
2. Observe token output (not logcat raw predictions)

**Pass Criteria**:
- ✅ Token doesn't flicker between similar classes
- ✅ Confidence stabilizes over 3-5 frames
- ✅ Same sign yields same token consistently

### Test Scenario 3: Origin Badge
1. Perform ASL sign (e.g., "HELLO")
2. Perform FSL sign (if model supports)
3. Check sequence display for origin

**Pass Criteria**:
- ✅ Origin shows "ASL", "FSL", or "UNKNOWN"
- ✅ Confidence displayed with origin
- ✅ Tilde (~) shown if estimate from priors

---

## Phase F — Adaptive Controller ✅
**Acceptance Gate**: Adaptive controller raises/lowers cadences; no oscillation

### Test Scenario: Load Response
1. Launch app
2. Monitor FPS for 2 minutes
3. Add/remove hands repeatedly

**Pass Criteria**:
- ✅ Frame skip increases when FPS drops below 0.8 × target
- ✅ Frame skip decreases when FPS exceeds 1.2 × target
- ✅ No rapid oscillation (changes < 1/minute)
- ✅ Hand detect cadence adjusts on drift
- ✅ Hands lane never starved

---

## Phase G — Sequencer & Bus ✅
**Acceptance Gate**: Max 7 tokens; alphabet auto-commit; Send publishes payload

### Test Scenario 1: Token Cap
1. Perform 8 signs rapidly
2. Check token buffer

**Pass Criteria**:
- ✅ Buffer caps at 7 tokens
- ✅ Auto-commit triggers on 7th token
- ✅ Bus event published

### Test Scenario 2: Alphabet Mode
1. Fingerspell "CAT" (C-A-T)
2. Wait 1 second
3. Perform non-letter sign

**Pass Criteria**:
- ✅ Letters accumulate in word buffer
- ✅ Word "CAT" commits after 1s idle
- ✅ Non-letter triggers immediate commit

### Test Scenario 3: Bus Payload
1. Perform 3 signs
2. Tap Send
3. Check logcat for "Published sequence" message

**Pass Criteria**:
- ✅ Payload includes tokens
- ✅ Payload includes origin
- ✅ Payload includes confidence
- ✅ Nonmanuals field present (empty for now)

---

## Phase H — Diagnostics UI (In Progress)
**Acceptance Gate**: Overlay chips display; mode toggle persists

### Test Scenario 1: Chips Display
1. Launch app
2. Check for overlay chips

**Pass Criteria**:
- ✅ FPS updates in real-time (< 1s latency)
- ✅ Hand count shows 0/1/2
- ✅ Delegate shown (GPU/GPU, NNAPI/GPU, etc.)
- ✅ Mode shown (Lite/Balanced/Accuracy)

### Test Scenario 2: Mode Toggle
1. Tap mode toggle icon
2. Check Toast
3. Restart app

**Pass Criteria**:
- ✅ Mode changes immediately
- ✅ Toast confirms switch
- ✅ Mode persists after restart

### Test Scenario 3: Auto-Fallback
1. Simulate low-end device (or use Debug build)
2. Wait for FPS < 5 for 10 seconds

**Pass Criteria**:
- ✅ Toast appears: "Switched to Lite mode"
- ✅ Mode actually switches
- ✅ Settings link provided to revert

---

## Phase I — Build & Packaging ✅
**Acceptance Gate**: Release APK with ABI splits installs; hardware acceleration confirmed

### Test
1. Build Release APK: `./gradlew assembleRelease`
2. Check `app/build/outputs/apk/release/` for multiple APKs
3. Install on device
4. Verify overlay renders smoothly

**Pass Criteria**:
- ✅ Separate APKs for arm64-v8a, armeabi-v7a, x86, x86_64
- ✅ Universal APK available
- ✅ R8 minification active (check build logs)
- ✅ No overlay rendering lag

---

## Phase J — Final Validation
**Acceptance Gate**: All FPS targets met; origin badges correct; tokens ≤7

### Comprehensive Test Matrix

#### Device: Low-End
| Scenario | Target FPS | Actual FPS | Pass? |
|----------|-----------|------------|-------|
| Idle (no hands) | ≥12 | ___ | ⬜ |
| One hand slow | ≥15 | ___ | ⬜ |
| Two hands slow | ≥12 | ___ | ⬜ |
| Face only | ≥15 | ___ | ⬜ |

#### Device: Mid-Range
| Scenario | Target FPS | Actual FPS | Pass? |
|----------|-----------|------------|-------|
| Idle (no hands) | ≥15 | ___ | ⬜ |
| One hand slow | ≥20 | ___ | ⬜ |
| Two hands slow | ≥15 | ___ | ⬜ |
| Two hands fast | ≥15 | ___ | ⬜ |
| Hands + face | ≥15 | ___ | ⬜ |

#### Device: High-End
| Scenario | Target FPS | Actual FPS | Pass? |
|----------|-----------|------------|-------|
| Idle (no hands) | ≥20 | ___ | ⬜ |
| Two hands fast | ≥18 | ___ | ⬜ |
| Hands + face | ≥18 | ___ | ⬜ |

---

## Final Checklist
- [ ] All FPS targets met on mid-range device
- [ ] Origin badges display correctly (ASL/FSL/UNKNOWN)
- [ ] Tokens cap at 7 with debouncing
- [ ] Diagnostics match chosen preset & delegate
- [ ] Mode toggle persists across restarts
- [ ] No sustained FPS < 10 for > 2s
- [ ] Debug warning appears in Debug builds
- [ ] Auto-fallback triggers under sustained low FPS

---

## Performance Tuning Knobs
If targets not met, adjust in `PerformanceConfig.kt`:

| Knob | Lower FPS | Raise FPS |
|------|-----------|-----------|
| `handDetectionCadence` | 8→10 | 5→4 |
| `FACE_DEFAULT_CADENCE` | 5→8 | 5→3 |
| `BASE_FRAME_SKIP` | 2→3 | 3→2 |
| `CONFIDENCE_THRESHOLD` | 0.65→0.70 | 0.65→0.60 |
| `DEBOUNCE_FRAMES` | 3→4 | 3→2 |
| `INTERPRETER_THREADS` | 2→1 | 1→2 |

---

## 60-Second Logcat Trace Capture
For detailed analysis:

```bash
adb logcat -c  # Clear
# Start recognition session
sleep 60
adb logcat -d | grep -E "(RecognitionDiag|FPS|HandLandmarker|TfLite)" > trace_60s.log
```

Analyze trace for:
- Average FPS (grep "FPS:")
- Delegate chosen (grep "delegate enabled")
- Hand tracking stability (grep "Tracking drift")
- Classifier gating (grep "Hand stationary")

