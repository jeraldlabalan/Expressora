# Enhanced Translation System Prompt

## Overview

This document describes the recommended system prompt for the Sign Language Translation API to properly handle ASL/FSL glosses and code-switching scenarios.

## System Prompt Template

```
You are a specialized Sign Language Translator. You will receive a sequence of glosses (capitalized words representing signs).

**Input Sources:**

  - Glosses may be from American Sign Language (ASL) or Filipino Sign Language (FSL).
  - ASL uses Topic-Comment structure (e.g., 'STORE I GO').
  - FSL often mixes English loan words with local syntax.

**Your Task:**

1.  Identify the grammatical intent (Question, Statement, Command).
2.  Convert the gloss sequence into a natural, grammatically correct sentence in [Target Language: English or Filipino].
3.  Respect code-switching: If glosses mix contexts, unify them into a coherent sentence.

**Example:**
Input: 'TIME WHAT?' → Output: 'What time is it?'
Input: 'FATHER WORK WHERE' → Output: 'Where does father work?'
```

## Detailed Grammar Rules

### ASL Grammar Patterns

1. **Topic-Comment Structure**
   - Topic comes first, followed by comment
   - Example: `STORE I GO` → "I go to the store"
   - Example: `BOOK YOU READ` → "You read a book"

2. **Question Formation**
   - WH-questions: WH-word typically comes at the end
   - Example: `TIME WHAT?` → "What time is it?"
   - Example: `WHERE YOU GO?` → "Where are you going?"

3. **Negation**
   - Negation markers appear after the verb
   - Example: `I LIKE NOT` → "I don't like it"

### FSL Grammar Patterns

1. **Mixed Syntax**
   - Often uses English word order with Filipino grammar markers
   - Example: `MOTHER COOK FOOD` → "Ang nanay ay nagluluto ng pagkain" (The mother is cooking food)

2. **Question Formation**
   - Similar to ASL but may use Filipino question words
   - Example: `ANO TIME?` → "Ano ang oras?" (What time is it?)

3. **Verb Aspect**
   - FSL may include aspect markers in glosses
   - Handle progressive, perfective, and other aspects appropriately

## Code-Switching Handling

When the input contains glosses from both ASL and FSL:

1. **Identify the dominant language** based on:
   - Origin metadata (if provided in GlossSequence proto)
   - Majority of glosses in the sequence
   - Context clues

2. **Unify the sentence** using the target language's grammar:
   - If target is English: Use English grammar with ASL/FSL concepts
   - If target is Filipino: Use Filipino grammar with appropriate loan words

3. **Preserve semantic meaning** even when mixing languages:
   - Example: `ASL_MOTHER FSL_COOK FOOD` → "The mother is cooking food" (unified in English)

## Implementation Notes

### Server-Side Requirements

1. **GlossSequence Proto Enhancement**
   - Add optional `origin` field to `GlossSequence` message
   - Field type: `string` (values: "ASL", "FSL", "UNKNOWN")
   - This allows the client to pass origin metadata for each sequence

2. **Prompt Integration**
   - Include the system prompt in the translation service initialization
   - Update prompt dynamically based on target language (English/Filipino)
   - Consider adding few-shot examples for better performance

3. **Grammar Rule Engine**
   - Implement rule-based preprocessing for common patterns
   - Use LLM for complex cases and code-switching scenarios
   - Fallback to template-based translation if LLM fails

### Client-Side Integration

The Android client now:
- Tracks origin (ASL/FSL) for each recognized gloss
- Passes origin metadata to translation API (if proto supports it)
- Falls back gracefully if origin field is not available in proto

## Example Translations

### ASL Examples

| Input Glosses | Expected Output (English) |
|--------------|---------------------------|
| `HELLO HOW YOU?` | "Hello, how are you?" |
| `STORE I GO` | "I go to the store" |
| `TIME WHAT?` | "What time is it?" |
| `BOOK YOU READ` | "You read a book" |

### FSL Examples

| Input Glosses | Expected Output (Filipino) | Expected Output (English) |
|--------------|---------------------------|---------------------------|
| `MOTHER COOK FOOD` | "Ang nanay ay nagluluto ng pagkain" | "The mother is cooking food" |
| `ANO TIME?` | "Ano ang oras?" | "What time is it?" |

### Code-Switching Examples

| Input Glosses | Origin | Expected Output |
|--------------|--------|-----------------|
| `ASL_MOTHER FSL_COOK FOOD` | Mixed | "The mother is cooking food" |
| `HELLO FSL_KUMUSTA` | Mixed | "Hello, how are you?" (unified) |

## Testing Checklist

- [ ] ASL-only sequences translate correctly
- [ ] FSL-only sequences translate correctly
- [ ] Mixed ASL/FSL sequences are unified coherently
- [ ] Question formation works for both languages
- [ ] Topic-Comment structure is handled for ASL
- [ ] Target language (English/Filipino) is respected
- [ ] Origin metadata is used when available
- [ ] Fallback works when origin is not provided

## References

- ASL Grammar: [Linguistic Society of America - ASL](https://www.linguisticsociety.org/resource/american-sign-language)
- FSL Grammar: [Filipino Sign Language Research](https://fsl.org.ph)
- Code-Switching in Sign Languages: [Research Papers](https://scholar.google.com)

