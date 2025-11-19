# Translation System Debugging Report

## Critical Issues Found

### 1. **Confidence Threshold Mismatch** ⚠️ CRITICAL
**Location:** `backend/python/server/mock_classifier.py:73`

**Problem:**
- Mock classifier generates confidence: `random.uniform(0.7, 0.95)` (70% to 95%)
- Server requires: `CONFIDENCE_THRESHOLD = 0.90` (90%)
- **Result:** Many glosses (70%-89% confidence) are being filtered out and never processed

**Impact:** 
- Glosses with confidence < 90% are silently ignored
- User sees no recognition happening
- Buffer never fills up to trigger translation

**Fix:** Update mock classifier to always return >= 0.90 confidence for testing

### 2. **Proto Files May Not Be Regenerated**
**Location:** Android proto generation

**Problem:**
- `source` field was added to proto but proto files may not be regenerated
- Android app may be using old proto without `source` field

**Fix:** Regenerate proto files with `./gradlew.bat generateDebugProto` or rebuild project

### 3. **TranslationService Dependencies**
**Location:** `backend/python/server/translation_service.py`

**Potential Issues:**
- Missing `google-generativeai` package
- Missing `python-dotenv` package  
- Missing `tenacity` package
- Missing `.env` file with `GOOGLE_API_KEY`

**Check:** Verify all dependencies are installed and `.env` file exists

## Recommended Fixes

### Fix 1: Update Mock Classifier Confidence
```python
# In mock_classifier.py line 73
confidence = random.uniform(0.90, 0.99)  # Always >= 90% for testing
```

### Fix 2: Regenerate Proto Files
```bash
# Windows
.\gradlew.bat generateDebugProto

# Or rebuild entire project
.\gradlew.bat build
```

### Fix 3: Verify Python Dependencies
```bash
cd backend/python
pip install -r requirements.txt
```

### Fix 4: Check .env File
```bash
# Ensure .env file exists in backend/python/ with:
GOOGLE_API_KEY=your_api_key_here
```

## Debugging Steps

1. **Check Server Logs:**
   - Look for "GLOSS event" messages
   - Look for "Ignoring low confidence gloss" messages
   - Check if TranslationService initializes correctly

2. **Check Android Logs:**
   - Filter for "RecognitionViewModel" and "LandmarkStreamer"
   - Look for "Translation event received" messages
   - Check for any proto-related errors

3. **Test Confidence Threshold:**
   - Temporarily lower threshold to 0.70 for testing
   - Or update mock classifier to always return >= 0.90

4. **Verify Connection:**
   - Check if gRPC connection is established
   - Verify server is receiving landmark frames
   - Check if events are being sent from server

