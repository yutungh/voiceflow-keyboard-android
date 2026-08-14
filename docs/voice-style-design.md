# Voice Style Design

Voice styles change presentation without changing the speaker's facts, intent, certainty, boundaries, relationship, or underlying emotion.

## Style ladder

| Style | Intended result | Rewriting strength |
| --- | --- | --- |
| Friends | The same speaker talking naturally to a friend | Minimal cleanup |
| Family | Familiar, supportive everyday language for close family | Light relationship-aware phrasing |
| Partner | Intimate partner language only when the source supports it | Light relationship-aware phrasing |
| Work | Concise, polished, send-ready professional writing | Moderate rewriting |
| Haiku | Exactly three poetic lines targeting a 5-7-5 syllable pattern | Strong constrained rewriting |
| Pirate | Readable pirate cadence without invented nautical facts | Strong persona rewriting |
| Shakespearean | Accurate archaic constructions and theatrical cadence | Strong persona rewriting |
| Noir Detective | Concise hard-boiled diction without invented crime or scenery | Strong persona rewriting |
| Wizard | Grand fantasy diction without invented supernatural events | Strong persona rewriting |

## Pressure-test cases

Every built-in prompt is designed around these cases:

1. **Already-clean neutral message:** Friends stays nearly unchanged; Family remains familiar but does not manufacture warmth; Work may improve concision and professional phrasing.
2. **Slang, humor, or profanity:** Friends preserves it; Family preserves it when natural for the relationship; Work replaces overly casual wording only when needed for professional readability and does not censor substantive meaning.
3. **Hedging and uncertainty:** Every style preserves `I think`, `maybe`, `probably`, conditions, and other meaningful limits on certainty.
4. **Request or boundary:** Every style preserves whether the source is a question, suggestion, request, statement, or command. Politeness must not weaken a boundary or turn a suggestion into a demand.
5. **Neutral logistics:** Family and Partner must not add affection, pet names, hearts, reassurance, or excitement.
6. **Conflict, bad news, health, grief, or money:** Relationship styles remain emotionally faithful and avoid decorative warmth, humor, or emojis.
7. **Affection already present:** Family preserves family affection; Partner may express partner intimacy; neither escalates beyond the source.
8. **Short reply:** Styles keep it short rather than expanding it into a full message or email.
9. **Lists and steps:** All styles use bullets for unordered items and numbering when sequence matters.
10. **Names, numbers, dates, URLs, and jargon:** Every style avoids guessing unless a correction is obvious from nearby context.

If two styles produce similar output for an already-clear neutral sentence, that is expected. Their differences should become visible when the source contains casual phrasing, relationship cues, rambling speech, or content that benefits from professional restructuring.
