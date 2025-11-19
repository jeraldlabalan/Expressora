"""
Grammar Engine for converting glosses to proper English sentences.
Applies grammar rules based on tone tags to generate natural sentences.
"""
from typing import List


class GrammarEngine:
    """
    Converts list of glosses + tone into proper English sentences.
    Applies basic grammar rules based on tone tags.
    """
    
    # Question words that typically start questions
    QUESTION_STARTERS = ["DO", "DID", "ARE", "IS", "WILL", "CAN", "WOULD", "COULD", "SHOULD"]
    
    # Action verbs that work with "Do/Did"
    ACTION_VERBS = ["EAT", "DRINK", "GO", "COME", "SEE", "HEAR", "KNOW", "THINK", "FEEL", 
                    "HELP", "WORK", "STOP", "START", "MEET", "LOVE", "LIKE", "WANT", "NEED"]
    
    # State verbs that work with "Are/Is"
    STATE_VERBS = ["HAPPY", "SAD", "ANGRY", "TIRED", "SORRY", "NICE", "GOOD", "BAD"]
    
    def __init__(self):
        """Initialize the grammar engine."""
        pass
    
    def generate_sentence(self, glosses: List[str], tone_tag: str) -> str:
        """
        Generate a proper English sentence from glosses and tone.
        
        Args:
            glosses: List of gloss labels (e.g., ["EAT", "YOU"])
            tone_tag: Tone tag (e.g., "/question", "/neutral", "/serious", "/exclamation")
            
        Returns:
            Proper English sentence (e.g., "Did you eat?" for ["EAT", "YOU"] + "/question")
        """
        if not glosses:
            return ""
        
        # Normalize tone tag (remove leading slash if present)
        tone = tone_tag.lstrip("/").lower() if tone_tag else "neutral"
        
        # Convert glosses to lowercase for processing
        gloss_lower = [g.lower() for g in glosses]
        
        # Apply grammar rules based on tone
        if tone == "question":
            return self._generate_question(gloss_lower)
        elif tone == "exclamation":
            return self._generate_exclamation(gloss_lower)
        elif tone == "serious" or tone == "neutral":
            return self._generate_statement(gloss_lower)
        else:
            # Default to statement
            return self._generate_statement(gloss_lower)
    
    def _generate_question(self, glosses: List[str]) -> str:
        """
        Generate a question sentence.
        
        Examples:
            ["eat", "you"] -> "Did you eat?"
            ["happy", "you"] -> "Are you happy?"
            ["where", "you"] -> "Where are you?"
        """
        if not glosses:
            return ""
        
        # Check for question words
        question_words = ["how", "what", "where", "when", "why", "who", "which"]
        first_gloss = glosses[0]
        
        if first_gloss in question_words:
            # Question word at start - form: "QuestionWord + verb + subject + ...?"
            if len(glosses) > 1:
                # Reorder: question word + rest
                sentence = " ".join(glosses).capitalize()
                return sentence + "?"
            else:
                return first_gloss.capitalize() + "?"
        
        # Check if first gloss is an action verb
        if first_gloss.upper() in self.ACTION_VERBS:
            # Use "Do/Did" + subject + verb
            verb = first_gloss
            rest = glosses[1:] if len(glosses) > 1 else []
            
            # Determine subject (look for "you", "i", "he", "she", "they")
            subject = "you"  # Default
            if "i" in rest:
                subject = "I"
            elif "he" in rest:
                subject = "he"
            elif "she" in rest:
                subject = "she"
            elif "they" in rest:
                subject = "they"
            elif "you" in rest:
                subject = "you"
            
            # Remove subject from rest
            rest_filtered = [g for g in rest if g.lower() not in ["i", "you", "he", "she", "they", "we"]]
            
            # Build question: "Did + subject + verb + rest?"
            if rest_filtered:
                return f"Did {subject} {verb} {' '.join(rest_filtered)}?".capitalize()
            else:
                return f"Did {subject} {verb}?".capitalize()
        
        # Check if first gloss is a state verb/adjective
        elif first_gloss.upper() in self.STATE_VERBS:
            # Use "Are/Is" + subject + adjective
            adjective = first_gloss
            rest = glosses[1:] if len(glosses) > 1 else []
            
            subject = "you"  # Default
            if "i" in rest:
                subject = "I"
            elif "he" in rest:
                subject = "he"
            elif "she" in rest:
                subject = "she"
            elif "they" in rest:
                subject = "they"
            
            rest_filtered = [g for g in rest if g.lower() not in ["i", "you", "he", "she", "they", "we"]]
            
            if rest_filtered:
                return f"Are {subject} {adjective} {' '.join(rest_filtered)}?".capitalize()
            else:
                return f"Are {subject} {adjective}?".capitalize()
        
        # Default: simple question format
        sentence = " ".join(glosses).capitalize()
        return sentence + "?"
    
    def _generate_exclamation(self, glosses: List[str]) -> str:
        """
        Generate an exclamation sentence.
        
        Examples:
            ["happy", "you"] -> "You are happy!"
            ["nice", "meet", "you"] -> "Nice to meet you!"
        """
        if not glosses:
            return ""
        
        # Simple exclamation: capitalize and add "!"
        sentence = " ".join(glosses).capitalize()
        return sentence + "!"
    
    def _generate_statement(self, glosses: List[str]) -> str:
        """
        Generate a statement sentence.
        
        Examples:
            ["hello", "you"] -> "Hello, you."
            ["thank", "you"] -> "Thank you."
        """
        if not glosses:
            return ""
        
        # Simple statement: capitalize and add "."
        sentence = " ".join(glosses).capitalize()
        return sentence + "."

