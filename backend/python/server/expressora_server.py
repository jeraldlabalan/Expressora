"""
Expressora gRPC Server for landmark streaming architecture.
Handles bidirectional streaming of landmarks and returns translation events.
"""
import argparse
import logging
import time
import sys
import os
from concurrent import futures
import grpc
import numpy as np

# Add server directory to path for imports
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

# Import generated gRPC code
# Proto files are in the same directory (server/)
import expressora_pb2
import expressora_pb2_grpc

from landmark_buffer import HandsDownDetector
from mock_classifier import MockClassifier
from grammar_engine import GrammarEngine
from translation_service import TranslationService

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)

# Confidence threshold for accepting gloss predictions
CONFIDENCE_THRESHOLD = 0.90  # 90% confidence required


class ExpressoraTranslationServicer(expressora_pb2_grpc.TranslationServiceServicer):
    """
    gRPC servicer implementing TranslationService.
    Handles bidirectional streaming of landmarks and returns translation events.
    """
    
    def __init__(self):
        self.classifier = MockClassifier(processing_delay=0.05)
        self.translator = TranslationService()  # Hybrid translation (Gemini + Offline fallback)
        self.hands_down_detector = HandsDownDetector(threshold_y=0.9, duration_threshold=1.5)
        
        # Step 2: Multi-Frame Validation - Track recent detections for temporal consistency
        self._recent_gloss_detections = []  # Buffer for recent detections
        self._min_consistent_frames = 3  # Require 3 frames for stability (kills ghost flicker)
        
        # Step 3: Motion Detection Validation - Track previous hand positions
        self._previous_hand_landmarks = None  # Store previous frame's hand landmarks
        self._min_movement_threshold = 0.01  # REDUCED from 0.05 to 0.01 (1% instead of 5%)
        
        # TONE event optimization - trigger only after gloss registration
        self._last_tone_event = None
        self._last_gloss_yielded = False  # Track if a GLOSS was just yielded
    
    def _calculate_hand_span(self, hand_chunk):
        """
        Calculate hand span: Euclidean distance from wrist to middle finger tip.
        
        Args:
            hand_chunk: List of 63 floats representing one hand [x,y,z, x,y,z...]
                       Wrist is at indices 0-2, Middle finger tip is at indices 36-38
        
        Returns:
            Span value (normalized 0.0-1.0). Returns 0.0 if chunk is invalid.
        """
        if len(hand_chunk) < 39:
            return 0.0
        wrist = np.array(hand_chunk[0:3])
        mid_tip = np.array(hand_chunk[36:39])
        return np.linalg.norm(wrist - mid_tip)

    def StreamLandmarks(self, request_iterator, context):
        """
        Stateless bidirectional streaming RPC handler.
        
        Receives: stream of LandmarkFrame
        Returns: stream of RecognitionEvent (GLOSS, TONE, or HANDS_DOWN events)
        
        This method is stateless - it does not buffer glosses or auto-translate.
        The client manages the gloss list and triggers translation separately.
        """
        logger.info("🟢 New landmark stream started - waiting for frames...")
        
        # Reset hands-down detector for new stream
        self.hands_down_detector.reset()
        
        try:
            frame_count = 0
            for landmark_frame in request_iterator:
                if frame_count == 0:
                    logger.info("✅ First landmark frame received!")
                frame_count += 1
                current_time = time.time()
                
                # Log frame reception for debugging
                hand_count = len(landmark_frame.hands) // 63 if landmark_frame.hands else 0
                face_count = len(landmark_frame.face) // 3 if landmark_frame.face else 0
                pose_count = len(landmark_frame.pose) // 3 if landmark_frame.pose else 0
                
                if frame_count <= 5 or frame_count % 30 == 0:
                    logger.info(f"📥 Received frame #{frame_count}: hands={hand_count}, face={face_count}, pose={pose_count}")
                
                # Extract hand landmarks for hands-down detection
                hand_landmarks = list(landmark_frame.hands) if landmark_frame.hands else []
                hand_count = len(hand_landmarks) // 63 if hand_landmarks else 0  # 21 landmarks * 3 coordinates per hand
                
                # Check hands-down detection (informational only, no action)
                hands_down = self.hands_down_detector.check(hand_landmarks, current_time)
                if hands_down:
                    # Yield HANDS_DOWN event (informational only)
                    hands_down_event = expressora_pb2.RecognitionEvent(
                        type=expressora_pb2.RecognitionEvent.Type.HANDS_DOWN,
                        label="hands_down",
                        confidence=1.0
                    )
                    logger.info("👋 HANDS_DOWN event detected (informational only)")
                    yield hands_down_event
                    self.hands_down_detector.reset()  # Reset after yielding
                
                # Step 1: Hand Presence Validation - Validate that hand landmarks actually exist before processing GLOSS events
                if hand_count == 0:
                    # No hands detected, skip GLOSS classification
                    # Still process TONE events (face detection)
                    if frame_count <= 5 or frame_count % 30 == 0:
                        logger.debug("⚠️ No hands detected, skipping GLOSS classification")
                    pass
                else:
                    # Additional validation: Check landmark quality
                    # Ensure landmarks are not all zeros or invalid
                    valid_hand_detected = False
                    for i in range(hand_count):
                        hand_start = i * 63
                        hand_end = hand_start + 63
                        if hand_end > len(hand_landmarks):
                            continue
                        hand_data = hand_landmarks[hand_start:hand_end]
                        
                        # Check if hand has valid landmarks (not all zeros)
                        non_zero_count = sum(1 for x in hand_data if abs(x) > 0.001)
                        if non_zero_count >= 15:  # At least 15 valid landmarks
                            valid_hand_detected = True
                            break
                    
                    if not valid_hand_detected:
                        if frame_count <= 5 or frame_count % 30 == 0:
                            logger.debug(f"⚠️ Hand validation failed: insufficient valid landmarks (hands={hand_count})")
                        gloss_label, gloss_confidence = None, 0.0
                        self._previous_hand_landmarks = None
                    elif valid_hand_detected:
                        # Geometric Sanity Check: Filter ghost hands using hand span
                        # Ghost hands are typically collapsed to a tiny point (span < 0.08)
                        # Real hands, even far away, rarely drop below 5-8% of screen size
                        max_span = 0.0
                        valid_span_found = False
                        
                        for i in range(hand_count):
                            hand_start = i * 63
                            hand_end = hand_start + 63
                            if hand_end > len(hand_landmarks):
                                continue
                            hand_chunk = hand_landmarks[hand_start:hand_end]
                            span = self._calculate_hand_span(hand_chunk)
                            max_span = max(max_span, span)
                            
                            if span >= 0.08:
                                valid_span_found = True
                                if frame_count <= 5 or frame_count % 30 == 0:
                                    logger.debug(f"✅ Hand {i+1} passed geometric check (span: {span:.4f} >= 0.08)")
                        
                        if not valid_span_found:
                            # All detected hands are ghosts (collapsed/tiny)
                            if frame_count <= 5 or frame_count % 30 == 0:
                                logger.info(f"👻 Ghost Hand Ignored (max span: {max_span:.4f} < 0.08)")
                            gloss_label, gloss_confidence = None, 0.0
                            self._previous_hand_landmarks = None
                        else:
                            # At least one hand has valid span - proceed with motion detection
                            # Step 3: Motion Detection Validation - Check if hands have moved
                            hands_moved = True  # Default to True for first frame
                            if self._previous_hand_landmarks is not None:
                                # Calculate average landmark displacement
                                total_displacement = 0.0
                                landmark_count = 0
                                
                                for i in range(min(hand_count, len(self._previous_hand_landmarks) // 63)):
                                    hand_start = i * 63
                                    hand_end = hand_start + 63
                                    if hand_end > len(hand_landmarks) or hand_end > len(self._previous_hand_landmarks):
                                        continue
                                    current_hand = np.array(hand_landmarks[hand_start:hand_end])
                                    prev_hand = np.array(self._previous_hand_landmarks[hand_start:hand_end])
                                    
                                    # Calculate Euclidean distance for each landmark
                                    for j in range(0, 63, 3):  # Process x,y,z triplets
                                        if j + 2 < len(current_hand) and j + 2 < len(prev_hand):
                                            current_point = current_hand[j:j+3]
                                            prev_point = prev_hand[j:j+3]
                                            displacement = np.linalg.norm(current_point - prev_point)
                                            total_displacement += displacement
                                            landmark_count += 1
                                
                                if landmark_count > 0:
                                    avg_displacement = total_displacement / landmark_count
                                    hands_moved = avg_displacement >= self._min_movement_threshold
                                    
                                    if not hands_moved:
                                        if frame_count <= 5 or frame_count % 30 == 0:
                                            logger.debug(f"⏸️ Hands static (displacement: {avg_displacement:.4f} < {self._min_movement_threshold})")
                            else:
                                # First frame - no previous data, allow processing
                                hands_moved = True
                            
                            # Update previous landmarks for next frame
                            self._previous_hand_landmarks = hand_landmarks.copy() if hand_landmarks else None
                            
                            if hands_moved:
                                # Valid hand detected and moving - proceed with GLOSS classification
                                # Classify hands separately (for GLOSS events)
                                gloss_label, gloss_confidence = self.classifier.classify_hands(landmark_frame)
                                if frame_count <= 5 or frame_count % 30 == 0:
                                    logger.debug(f"🔍 Classified hands: {gloss_label} (confidence: {gloss_confidence:.2f})")
                            else:
                                # Hands not moving - skip GLOSS classification to prevent static false positives
                                gloss_label, gloss_confidence = None, 0.0
                                if frame_count <= 5 or frame_count % 30 == 0:
                                    logger.debug("⏸️ Skipping GLOSS: hands not moving")
                    else:
                        # No valid hand detected, skip GLOSS classification
                        gloss_label, gloss_confidence = None, 0.0
                        # Reset previous landmarks if no valid hand
                        self._previous_hand_landmarks = None
                
                # Process GLOSS events with multi-frame validation
                if gloss_label and gloss_confidence >= CONFIDENCE_THRESHOLD:
                    # Step 2: Multi-Frame Validation - Require temporal consistency
                    # Add current prediction to buffer
                    self._recent_gloss_detections.append((gloss_label, gloss_confidence))
                    
                    # Keep buffer size manageable (keep last 5 frames)
                    if len(self._recent_gloss_detections) > 5:
                        self._recent_gloss_detections.pop(0)
                    
                    # Check if last N frames have the same gloss
                    if len(self._recent_gloss_detections) >= self._min_consistent_frames:
                        recent_labels = [item[0] for item in self._recent_gloss_detections[-self._min_consistent_frames:]]
                        
                        if len(set(recent_labels)) == 1:  # All same label
                            # Validated - yield GLOSS event
                            validated_label = recent_labels[0]
                            gloss_event = expressora_pb2.RecognitionEvent(
                                type=expressora_pb2.RecognitionEvent.Type.GLOSS,
                                label=validated_label,
                                confidence=gloss_confidence
                            )
                            logger.info(f"✅ GLOSS event (validated): {validated_label} (confidence: {gloss_confidence:.2f})")
                            yield gloss_event
                            # Clear buffer after successful yield
                            self._recent_gloss_detections.clear()
                            # Mark that a GLOSS was just yielded - trigger tone detection
                            self._last_gloss_yielded = True
                        else:
                            # Inconsistent - reset buffer and reject
                            if frame_count <= 5 or frame_count % 30 == 0:
                                logger.debug(f"⏳ GLOSS pending validation: {gloss_label} (inconsistent across frames)")
                            self._recent_gloss_detections.clear()
                    else:
                        # Not enough frames yet - wait for more
                        if frame_count <= 5 or frame_count % 30 == 0:
                            logger.debug(f"⏳ GLOSS pending validation: {gloss_label} (need {self._min_consistent_frames} consistent frames, have {len(self._recent_gloss_detections)})")
                
                # Classify face separately (for TONE events - NOT gloss words)
                # OPTIMIZATION: Only process tone detection after a GLOSS event is successfully validated
                if self._last_gloss_yielded:
                    tone_label, tone_confidence = self.classifier.classify_face(landmark_frame)
                    if tone_label and tone_confidence >= 0.80:  # Lower threshold for tone (0.80)
                        # Only yield TONE if label changed (prevent duplicate tones)
                        if self._last_tone_event != tone_label:
                            # Yield TONE event
                            tone_event = expressora_pb2.RecognitionEvent(
                                type=expressora_pb2.RecognitionEvent.Type.TONE,
                                label=tone_label,
                                confidence=tone_confidence
                            )
                            logger.info(f"😊 TONE event: {tone_label} (confidence: {tone_confidence:.2f})")
                            yield tone_event
                            self._last_tone_event = tone_label
                    # Reset flag after processing tone
                    self._last_gloss_yielded = False
                    
        except Exception as e:
            logger.error(f"Error in landmark stream: {e}", exc_info=True)
            context.set_code(grpc.StatusCode.INTERNAL)
            context.set_details(str(e))
    
    def TranslateSequence(self, request, context):
        """
        Unary RPC handler for translation.
        
        Receives: GlossSequence (list of glosses + tone)
        Returns: TranslationResult (sentence + source)
        
        This method uses the Hybrid TranslationService (Gemini + Offline fallback).
        """
        try:
            glosses = list(request.glosses)
            tone = request.dominant_tone if request.dominant_tone else "/neutral"
            
            logger.info(f"📝 TranslateSequence called: {len(glosses)} glosses, tone={tone}")
            
            if not glosses:
                return expressora_pb2.TranslationResult(
                    sentence="",
                    sentence_filipino="",
                    tone=tone,
                    source="Offline (Local)"
                )
            
            # Use Hybrid TranslationService (Gemini + Offline fallback)
            english, filipino, result_tone, source = self.translator.translate(glosses, tone)
            
            logger.info(f"✅ Translation result: English='{english}' | Filipino='{filipino}' (source: {source}, tone: {result_tone})")
            
            return expressora_pb2.TranslationResult(
                sentence=english,
                sentence_filipino=filipino,
                tone=result_tone,
                source=source
            )
            
        except Exception as e:
            logger.error(f"Error in TranslateSequence: {e}", exc_info=True)
            context.set_code(grpc.StatusCode.INTERNAL)
            context.set_details(str(e))
            
            # Return error result
            return expressora_pb2.TranslationResult(
                sentence=f"Translation error: {str(e)}",
                sentence_filipino="",
                tone=tone,
                source="Offline (Local)"
            )


def serve(port: int = 50051, host: str = "0.0.0.0"):
    """
    Start the gRPC server.
    
    Args:
        port: Port to listen on
        host: Host to bind to
    """
    server = grpc.server(futures.ThreadPoolExecutor(max_workers=10))
    
    # Add servicer using generated code
    expressora_pb2_grpc.add_TranslationServiceServicer_to_server(
        ExpressoraTranslationServicer(), server
    )
    
    server.add_insecure_port(f"{host}:{port}")
    server.start()
    
    logger.info(f"Expressora gRPC server started on {host}:{port}")
    
    try:
        server.wait_for_termination()
    except KeyboardInterrupt:
        logger.info("Shutting down server...")
        server.stop(0)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Expressora gRPC Server")
    parser.add_argument("--port", type=int, default=50051, help="Server port")
    parser.add_argument("--host", type=str, default="0.0.0.0", help="Server host")
    
    args = parser.parse_args()
    serve(port=args.port, host=args.host)
