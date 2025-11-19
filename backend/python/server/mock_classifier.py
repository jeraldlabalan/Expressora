"""
Mock classifier for testing landmark streaming architecture.
Returns sample glosses with confidence scores and tone tags.
"""
import random
import time
from typing import List, Tuple, Optional

# Sample gloss vocabulary
SAMPLE_GLOSSES = [
    "HELLO", "THANK_YOU", "YES", "NO", "PLEASE", "SORRY", "GOODBYE",
    "HOW", "WHAT", "WHERE", "WHEN", "WHY", "WHO", "NAME", "NICE",
    "MEET", "YOU", "I", "LOVE", "FRIEND", "FAMILY", "HOME", "SCHOOL",
    "WORK", "FOOD", "WATER", "HELP", "STOP", "GO", "COME", "SEE",
    "HEAR", "KNOW", "THINK", "FEEL", "HAPPY", "SAD", "ANGRY", "TIRED"
]

# Tone tags
TONES = ["neutral", "question", "exclamation", "statement"]

class MockClassifier:
    """
    Mock classifier that returns random glosses for testing.
    In production, this would be replaced with actual ML model inference.
    """
    
    def __init__(self, processing_delay: float = 0.05):
        """
        Args:
            processing_delay: Simulated processing time in seconds
        """
        self.processing_delay = processing_delay
        self.last_gloss: Optional[str] = None
        self.gloss_history: List[str] = []
        
    def classify_hands(self, landmark_frame) -> Tuple[Optional[str], float]:
        """
        Classify hand gestures from landmark frame.
        Returns gloss predictions from hands only.
        
        Args:
            landmark_frame: Landmark frame data
            
        Returns:
            Tuple of (gloss_label, confidence) or (None, 0.0) if no hands detected
        """
        # Simulate processing delay
        time.sleep(self.processing_delay)
        
        # Step 5: Post-Processing Validation - Enhanced hand presence check
        if not hasattr(landmark_frame, 'hands') or not landmark_frame.hands:
            return None, 0.0
        
        hand_landmarks = list(landmark_frame.hands)
        hand_count = len(hand_landmarks) // 63 if hand_landmarks else 0
        
        if hand_count == 0:
            return None, 0.0
        
        # Additional validation: Check landmark quality
        # Ensure landmarks are not all zeros or invalid positions
        valid_hand_found = False
        for i in range(hand_count):
            hand_start = i * 63
            hand_end = hand_start + 63
            hand_data = hand_landmarks[hand_start:hand_end]
            
            # Check if hand has valid landmarks (not all zeros)
            non_zero_count = sum(1 for x in hand_data if abs(x) > 0.001)
            if non_zero_count >= 15:  # At least 15 valid landmarks
                valid_hand_found = True
                break
        
        if not valid_hand_found:
            # No valid hand detected - return None
            return None, 0.0
        
        # Generate random gloss from hands (in production, this would be actual ML inference)
        if self.last_gloss and random.random() < 0.3:
            gloss = self.last_gloss
        else:
            gloss = random.choice(SAMPLE_GLOSSES)
        
        confidence = random.uniform(0.90, 0.99)  # Always >= 90% to pass threshold
        self.last_gloss = gloss
        
        return gloss, confidence
    
    def classify_face(self, landmark_frame) -> Tuple[str, float]:
        """
        Classify facial expression/tone from landmark frame.
        Returns tone predictions from face only (NOT gloss words).
        
        Args:
            landmark_frame: Landmark frame data
            
        Returns:
            Tuple of (tone_tag, confidence) or (None, 0.0) if no face detected
        """
        # Simulate processing delay
        time.sleep(self.processing_delay * 0.5)  # Face processing is faster
        
        # Check if face is present
        face_count = len(landmark_frame.face) // 3 if hasattr(landmark_frame, 'face') and landmark_frame.face else 0
        
        if face_count == 0:
            return None, 0.0
        
        # Generate random tone (in production, this would be actual face expression model)
        tone = random.choice(TONES)
        confidence = random.uniform(0.85, 0.95)  # Slightly lower confidence for tone
        
        # Format tone tag
        tone_tag = f"/{tone}" if not tone.startswith("/") else tone
        
        return tone_tag, confidence
    
    def classify(self, landmark_frames: List) -> Tuple[List[Tuple[str, float, str]], str]:
        """
        Legacy method for backward compatibility.
        Now delegates to separate hand/face classifiers.
        
        Args:
            landmark_frames: List of landmark frame data
            
        Returns:
            Tuple of (list of (gloss, confidence, tone_tag), dominant_tone)
        """
        if not landmark_frames:
            return [], "neutral"
        
        # Use first frame for classification
        frame = landmark_frames[0]
        
        # Classify hands and face separately
        gloss, gloss_conf = self.classify_hands(frame)
        tone, tone_conf = self.classify_face(frame)
        
        # Build return format (legacy)
        glosses = []
        if gloss:
            glosses.append((gloss, gloss_conf, tone if tone else "neutral"))
        
        dominant_tone = tone if tone else "neutral"
        if not dominant_tone.startswith("/"):
            dominant_tone = f"/{dominant_tone}"
        
        return glosses, dominant_tone
    
    def build_sentence(self, glosses: List[Tuple[str, float, str]]) -> str:
        """
        Build a sentence from glosses.
        
        Args:
            glosses: List of (gloss, confidence, tone_tag) tuples
            
        Returns:
            Formatted sentence string
        """
        if not glosses:
            return ""
        
        sentence_parts = [gloss[0] for gloss in glosses]
        sentence = " ".join(sentence_parts)
        
        # Add punctuation based on tone
        dominant_tone = glosses[0][2] if glosses else "neutral"
        if dominant_tone == "question":
            sentence += "?"
        elif dominant_tone == "exclamation":
            sentence += "!"
        else:
            sentence += "."
        
        return sentence

