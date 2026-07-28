# Post-transcription polishing prompts

This file is the source of truth for Stow’s optional post-transcription polish behavior.

As of v2.5 polish is driven by **presets** — named, user-editable prompts. This document defines the request structure shared by all presets and the verbatim text of the two built-in defaults.

Implementation note: presets and the system-prompt assembly live in `PolishPresets.kt`; the HTTP call lives in `TranscriptionPolisher.kt`. Both must stay aligned with this document.

## Request structure

Every polish request is a Groq chat completion with exactly two messages:

| Role | Content |
|---|---|
| `system` | The selected preset's prompt, with the Jargon Dictionary applied (see below) |
| `user` | The raw transcript, verbatim |

Parameters:

- Model: `llama-3.1-8b-instant` (free-tier friendly)
- `temperature`: `0.2`
- No `max_tokens` is sent
- Timeouts (OkHttp): 30 s connect, 60 s read, 30 s write

> Before v2.5 the jargon list and the transcript were both interpolated into the *system* message and the user message was a fixed placeholder string. The rules text of **Clean prose** is unchanged from that version; only the message structure moved.

## Jargon Dictionary injection

`PolishPresets.buildSystemPrompt(preset, jargon)` decides where jargon terms land:

1. If the preset text contains `{{JARGON_LIST}}`, the comma-separated terms are substituted at that position (or `(none)` when the dictionary is empty).
2. Otherwise, when the dictionary is non-empty, this block is appended to the end of the system message:

   ```
   Jargon List (preserve these exactly):
   <comma-separated terms>
   ```

3. When the dictionary is empty and the preset has no placeholder, nothing is appended.

This means user-authored presets get jargon support automatically without having to remember the placeholder.

## Preset management

- Presets are stored as a JSON array in `SharedPreferences` (`StowPrefs` → `polish_presets`); the last-used preset id is in `selected_polish_preset`.
- The two built-ins are seeded on first use and re-created if storage is cleared, so the list is never missing a default.
- Built-ins may be renamed, edited, and **reset** to their shipped text; they cannot be deleted. Custom presets can be deleted.
- The selected preset is used by manual **Polish**, by auto-polish (which has no UI moment of its own), and by **Re-polish** in history.

## Failure behaviour

All failures fall back to the raw transcript:

- Network error, non-2xx response, unparseable body, empty content, or a preset with a blank prompt → `onError`.
- Auto-polish failure: the raw transcript is shown on the editable result screen and copied, with a "Polish failed — raw transcription copied" toast.
- Manual polish failure: the previous text is restored and the error is shown as a toast.

---

## Built-in preset: "Clean prose"

**Design goals.** Light cleanup only, aimed at short voice notes (for example field and project notes). It should:

- Remove filler words and vocalizations
- Fix spelling, grammar, capitalization, and punctuation
- Apply **very light** rephrasing only when needed for readability (broken word order, incomplete sentences)
- Stay close to the original length and meaning
- Keep the speaker’s natural voice (no formal rewrite, no expansion into essays)
- Preserve technical terms, project names, company names, and proper nouns exactly
- Return plain cleaned text only (no markdown, bullets, headings, or commentary)

**Prompt (verbatim):**

```
You are a careful transcription cleaner for professional voice notes (civil engineering, construction, project notes).

Your job is light cleanup only. Follow these rules strictly and in order:

1. Remove filler words and vocalizations: um, uh, er, ah, like, you know, kind of, sort of, basically, actually (when used as filler), yeah, so, well (when used as filler), and similar. Remove them completely.

2. Fix spelling mistakes, grammar errors, and add correct capitalization and punctuation.

3. Apply very light rephrasing only when it significantly improves readability (e.g. fixing broken word order or incomplete sentences). Keep the original meaning, structure, and the speaker’s natural voice.

4. Do NOT add any new information, explanations, summaries, or content that was not spoken.
5. Do NOT expand short notes into longer text or turn them into essays.
6. Do NOT rewrite for style or make the language more formal/professional beyond the light fixes above.
7. Preserve technical terms, project names, company names, and proper nouns exactly. Use the provided Jargon List when available.
8. Keep the cleaned output similar in length to the original transcription.

Output ONLY the cleaned plain text. No explanations, no quotes, no additional commentary.
```

---

## Built-in preset: "Task capture"

**Design goals.** Unlike Clean prose, this preset deliberately **restructures** the capture: it splits spoken action items into Markdown checkboxes grouped by job, and keeps everything else as prose under a Notes heading. It still never invents or expands content.

**Prompt (verbatim):**

```
You are polishing a dictated voice capture from a construction project
engineer. The capture may contain project tasks, personal tasks, general
notes, or any jumbled mix — often switching topics mid-stream.

Output Markdown only. No preamble, no commentary, no code fences.

Classify each item individually:

1. ACTION ITEMS — anything the speaker states as something to do — become
   exactly one line each: "- [ ] <task>".
   Group them under headings in this priority:
   - "## <job>" when a job is named or clearly implied (e.g. 6100,
     P2-141, El Toro). Use the job name/number as spoken.
   - "## Personal" for personal errands and home tasks.
   - "## Unsorted" for action items with no clear home.
2. EVERYTHING ELSE — thoughts, observations, meeting recap, journal
   content — stays as cleaned-up prose or plain bullets under "## Notes",
   in the order spoken.

Rules:
- Omit any heading that would be empty. If the capture contains no action
  items at all, output only the polished text: no headings, no checkboxes.
- One task per line, even if the speaker ran several together.
- Keep the speaker's wording. Fix obvious transcription errors from
  context. Never invent, merge, or expand items.
- If a due date or timeframe is spoken, append it in parentheses at the
  end of the task line, e.g. "- [ ] Order check valves (by Friday)".
- Preserve names, quantities, and job numbers exactly as spoken.
```
