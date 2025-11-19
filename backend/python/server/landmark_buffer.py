"""
Landmark buffer with "Max 7 Gloss" logic and silence/pause trigger (Tweak 1).
Hands-down detection for triggering translation when user lowers hands.
"""
import time
from typing import List, Optional
from collections import deque

class LandmarkBuffer:
    """
    Buffers landmark frames and implements "Max 7 Gloss" logic.
    Includes silence/pause trigger for immediate translation when user stops signing.
    """
    
    def __init__(self, max_glosses: int = 7, silence_threshold: float = 2.0):
        """
        Args:
            max_glosses: Maximum number of glosses to buffer before emitting
            silence_threshold: Time in seconds to wait before forcing emit on silence (Tweak 1)
        """
        self.max_glosses = max_glosses
        self.silence_threshold = silence_threshold
        self.buffer: deque = deque(maxlen=max_glosses)
        self.last_frame_time: Optional[float] = None
        self.last_gloss: Optional[str] = None
        
    def add_frame(self, landmark_frame, gloss: str, timestamp: float):
        """
        Add a landmark frame with its predicted gloss to the buffer.
        
        Args:
            landmark_frame: The landmark frame data
            gloss: The predicted gloss label
            timestamp: Current timestamp in seconds
        """
        self.last_frame_time = timestamp
        self.last_gloss = gloss
        
        # If gloss changed, consider emitting
        if len(self.buffer) > 0 and self.buffer[-1][1] != gloss:
            # Gloss transition detected
            pass
        
        self.buffer.append((landmark_frame, gloss, timestamp))
    
    def should_emit(self, current_time: float) -> bool:
        """
        Check if buffer should be emitted.
        
        Tweak 1: Silence/Pause Trigger - if no frames received for >silence_threshold seconds,
        force emit even if buffer is not full.
        
        Returns:
            True if buffer should be emitted
        """
        # Check silence trigger (Tweak 1)
        if self.last_frame_time is not None:
            time_since_last_frame = current_time - self.last_frame_time
            if time_since_last_frame > self.silence_threshold:
                return True  # Force emit on silence
        
        # Check max glosses
        if len(self.buffer) >= self.max_glosses:
            return True
        
        return False
    
    def get_buffer(self) -> List[tuple]:
        """
        Get current buffer contents.
        
        Returns:
            List of (landmark_frame, gloss, timestamp) tuples
        """
        return list(self.buffer)
    
    def clear(self):
        """Clear the buffer."""
        self.buffer.clear()
        self.last_frame_time = None
        self.last_gloss = None
    
    def is_empty(self) -> bool:
        """Check if buffer is empty."""
        return len(self.buffer) == 0


class HandsDownDetector:
    """
    Detects when user's hands are down (wrist.y > 0.9) for > 1.5 seconds.
    Wrist landmark is at index 0 of each hand (y-coordinate at index 1 in flattened array).
    """
    
    def __init__(self, threshold_y: float = 0.9, duration_threshold: float = 1.5):
        """
        Args:
            threshold_y: Y-coordinate threshold (0.9 = bottom of screen)
            duration_threshold: Duration in seconds hands must be down (> 1.5s)
        """
        self.threshold_y = threshold_y
        self.duration_threshold = duration_threshold
        self.hands_down_start: Optional[float] = None
    
    def check(self, hand_landmarks: List[float], current_time: float) -> bool:
        """
        Check if hands are down based on wrist y-coordinate.
        
        Args:
            hand_landmarks: Flattened array [x0,y0,z0, x1,y1,z1, ...]
                           Wrist is at indices 0-2 (y at index 1)
                           Can contain one or two hands (63 or 126 floats)
            current_time: Current timestamp in seconds
            
        Returns:
            True if hands have been down for > duration_threshold
        """
        if not hand_landmarks or len(hand_landmarks) < 3:
            # No hands detected, reset
            self.hands_down_start = None
            return False
        
        # Extract wrist y-coordinate from first hand (index 1: [x0, y0, z0, ...])
        # If two hands, check both wrists (first at index 1, second at index 64)
        wrist_y = hand_landmarks[1] if len(hand_landmarks) > 1 else 0.0
        
        # If two hands detected, check the lower wrist (higher y value)
        if len(hand_landmarks) >= 63:
            # Second hand wrist y-coordinate (at index 64: [hand0_63coords, hand1_x0, hand1_y0, ...])
            second_wrist_y = hand_landmarks[64] if len(hand_landmarks) > 64 else wrist_y
            wrist_y = max(wrist_y, second_wrist_y)  # Use the lower hand (higher y value)
        
        # Check if wrist is in bottom region (> 0.9)
        if wrist_y > self.threshold_y:
            if self.hands_down_start is None:
                self.hands_down_start = current_time
            else:
                # Check if duration exceeded threshold
                duration = current_time - self.hands_down_start
                if duration > self.duration_threshold:
                    return True
        else:
            # Hands not down, reset timer
            self.hands_down_start = None
        
        return False
    
    def reset(self):
        """Reset detection state."""
        self.hands_down_start = None

