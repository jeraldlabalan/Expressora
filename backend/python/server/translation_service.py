"""
Translation Service using Google Gemini 1.5 Flash API with local fallback.
Implements hybrid intelligence: Cloud-first with automatic fallback to local rules.
"""
import os
import logging
import google.generativeai as genai
from tenacity import retry, stop_after_attempt, wait_fixed, RetryError
from dotenv import load_dotenv

# Load environment variables from .env file
load_dotenv()

logger = logging.getLogger(__name__)


class TranslationService:
    """
    Hybrid translation service using Gemini 1.5 Flash as primary engine
    with local rule-based fallback for reliability.
    """
    
    def __init__(self):
        """Initialize the translation service with Gemini API."""
        api_key = os.getenv("GOOGLE_API_KEY")
        if api_key:
            try:
                genai.configure(api_key=api_key)
                # Use Flash for higher rate limits (15 RPM vs 2 RPM on Pro)
                # Use Gemini 2.5 Flash (current standard)
                self.model = genai.GenerativeModel('gemini-2.5-flash')
                logger.info("TranslationService initialized with Gemini 2.5 Flash")
            except Exception as e:
                logger.warning(f"Failed to initialize Gemini API: {e}. Using local fallback only.")
                self.model = None
        else:
            logger.warning("GOOGLE_API_KEY not found in environment. Using local fallback only.")
            self.model = None
    
    @retry(stop=stop_after_attempt(2), wait=wait_fixed(1))
    def _call_gemini(self, glosses, tone):
        """
        Call Gemini 2.5 Flash API to translate glosses to both English and Filipino.
        
        Args:
            glosses: List of gloss labels (e.g., ["EAT", "YOU"])
            tone: Tone tag (e.g., "/question", "/neutral")
            
        Returns:
            Tuple of (english_sentence, filipino_sentence)
            
        Raises:
            Exception: If API call fails (will trigger retry)
        """
        if not self.model:
            raise Exception("No API Key configured")
        
        # Construct prompt for FSL-to-bilingual translation
        prompt = (
            f"You are an FSL (Filipino Sign Language) interpreter. "
            f"Translate the following glosses into natural sentences in BOTH English and Filipino. "
            f"Use the provided facial tone for context. "
            f"Output format must be exactly: 'English: [sentence] | Filipino: [sentence]'\n\n"
            f"Glosses: {' '.join(glosses)}\n"
            f"Tone: {tone}\n\n"
            f"Output:"
        )
        
        try:
            response = self.model.generate_content(prompt)
            response_text = response.text.strip()
            logger.info(f"Gemini translation successful: {response_text}")
            
            # Parse bilingual response
            english_sentence = ""
            filipino_sentence = ""
            
            if "English:" in response_text and "Filipino:" in response_text:
                # Parse format: "English: [sentence] | Filipino: [sentence]"
                parts = response_text.split("|")
                for part in parts:
                    part = part.strip()
                    if part.startswith("English:"):
                        english_sentence = part.replace("English:", "").strip()
                    elif part.startswith("Filipino:"):
                        filipino_sentence = part.replace("Filipino:", "").strip()
            else:
                # Fallback: if format doesn't match, use entire response as English
                # and generate simple Filipino translation
                english_sentence = response_text
                filipino_sentence = self._simple_filipino_translation(glosses, tone)
                logger.warning(f"Could not parse bilingual format, using fallback. Response: {response_text}")
            
            return english_sentence, filipino_sentence
        except Exception as e:
            # Log full error details including response if available
            error_details = f"Gemini API call failed: {type(e).__name__}: {str(e)}"
            if hasattr(e, 'response'):
                error_details += f" | Response: {e.response}"
            logger.error(error_details)
            raise
    
    def _simple_filipino_translation(self, glosses, tone):
        """
        Simple Filipino translation fallback when Gemini doesn't provide Filipino.
        Basic word-for-word translation with tone handling.
        """
        if not glosses:
            return ""
        
        # Simple word mapping (can be expanded)
        word_map = {
            "hello": "Kumusta",
            "thank": "Salamat",
            "you": "ikaw",
            "yes": "oo",
            "no": "hindi",
            "please": "pakiusap",
            "sorry": "pasensya",
            "goodbye": "paalam",
            "how": "paano",
            "what": "ano",
            "where": "saan",
            "when": "kailan",
            "why": "bakit",
            "who": "sino",
            "name": "pangalan",
            "nice": "maganda",
            "meet": "makilala",
            "i": "ako",
            "love": "mahal",
            "friend": "kaibigan",
            "family": "pamilya",
            "home": "bahay",
            "school": "paaralan",
            "work": "trabaho",
            "food": "pagkain",
            "water": "tubig",
            "help": "tulong",
            "stop": "tigil",
            "go": "pumunta",
            "come": "dumating",
            "see": "makita",
            "hear": "marinig",
            "know": "alam",
            "think": "isip",
            "feel": "pakiramdam",
            "happy": "masaya",
            "sad": "malungkot",
            "angry": "galit",
            "tired": "pagod"
        }
        
        # Translate each gloss
        filipino_words = []
        for gloss in glosses:
            gloss_lower = gloss.lower()
            if gloss_lower in word_map:
                filipino_words.append(word_map[gloss_lower])
            else:
                # Keep original if no mapping
                filipino_words.append(gloss)
        
        filipino_text = " ".join(filipino_words)
        
        # Apply tone
        if tone == "/question":
            return f"{filipino_text}?"
        elif tone == "/exclamation":
            return f"{filipino_text}!"
        else:
            return f"{filipino_text}."
    
    def _call_local_rules(self, glosses, tone):
        """
        Local rule-based fallback translation.
        Simple grammar rules for when Gemini is unavailable.
        Returns both English and Filipino translations.
        
        Args:
            glosses: List of gloss labels
            tone: Tone tag
            
        Returns:
            Tuple of (english_sentence, filipino_sentence)
        """
        if not glosses:
            return "", ""
        
        # Convert glosses to lowercase for processing
        text = " ".join(glosses).lower()
        
        # Generate English sentence
        if tone == "/question":
            english = f"Are you {text}?"
        elif tone == "/negative":
            words = text.split()
            if words:
                english = f"{words[0].capitalize()} not {' '.join(words[1:])}." if len(words) > 1 else f"{words[0].capitalize()} not."
            else:
                english = text.capitalize() + "."
        else:
            english = text.capitalize() + "."
        
        # Generate Filipino sentence using simple translation
        filipino = self._simple_filipino_translation(glosses, tone)
        
        return english, filipino
    
    def translate(self, glosses, tone):
        """
        Public method to translate glosses to both English and Filipino.
        Tries Gemini first, falls back to local rules on failure.
        
        Args:
            glosses: List of gloss labels
            tone: Tone tag
            
        Returns:
            Tuple of (english_sentence, filipino_sentence, tone, source_string)
            source_string is either "Cloud (Gemini)" or "Offline (Local)"
        """
        try:
            # Try Cloud first
            english, filipino = self._call_gemini(glosses, tone)
            return english, filipino, tone, "Cloud (Gemini)"
        except (RetryError, Exception) as e:
            # Fallback to Local on any failure
            logger.warning(f"Gemini failed: {e}. Switching to Local fallback.")
            english, filipino = self._call_local_rules(glosses, tone)
            return english, filipino, tone, "Offline (Local)"

