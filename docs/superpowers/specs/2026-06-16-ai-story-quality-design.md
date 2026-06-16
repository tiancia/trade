# AI Story Quality Gate Design

## Goal

Improve the backend AI novel generator so it produces more readable, less mechanical short web fiction for Fanqie-like platforms. The genre should remain dynamic: the generator may choose male-frequency, female-frequency, suspense, period, infinite-flow, urban fantasy, or other ranking-driven topics. The quality rules are fixed and should prevent generic AI prose, weak openings, middle-section drift, and forced sequel hooks.

## Current Flow

The current backend flow lives under `backend/src/main/java/com/trade/story`:

1. `StoryTrendCollector` gathers ranking/trend text or falls back to configured hot topics.
2. `AiStoryPromptBuilder.buildTopicPrompt` asks the model for a full story plan JSON.
3. `AiStoryService` writes planned sections one by one.
4. `AiStoryResponseParser` parses section JSON and normalizes paragraphs.
5. `StoryFileRepository` saves a UTF-8 text file under `story/`.

This flow is stable but linear. Once a weak plan or section is accepted, later sections inherit the problem. Generated samples show the most visible issues in the second half: template escalation, enemies appearing only to be defeated, sudden mythology/bloodline expansion, repeated mystery car hooks, and conclusions that open new conflicts instead of paying off the existing story.

## Recommended Approach

Use approach 2: prompt improvements plus quality gates plus targeted rewrite.

The new flow is:

1. Collect trend context.
2. Ask the model for several topic candidates and a selected plan.
3. Generate each planned section.
4. Run a local quality gate on the section.
5. If the section fails, ask the model to rewrite the same section with concrete failure reasons.
6. After all sections pass or exhaust retry limits, run a final editor pass.
7. Save the edited result and include light quality metadata in logs.

This improves output quality without building a costly full multi-agent pipeline or changing the rest of the application.

## Scope

In scope:

- Backend story generation only.
- New prompt contracts for topic selection, section drafting, section rewriting, and final editing.
- Local deterministic checks for obvious quality failures.
- Optional AI-based rewrite and final editing passes.
- Configuration switches and retry limits.
- Unit tests using deterministic strings, not real AI calls.

Out of scope:

- Frontend changes.
- Publishing automation.
- Reader analytics ingestion.
- New external ranking providers beyond the existing `trade.story.trend-source-urls`.
- Training or fine-tuning a model.
- Changing global AI temperature/model defaults in a way that affects trading or Polymarket modules.

## Quality Rules

The generator should treat ranking text as topic input, not as a style to copy. It must avoid copying book names, character names, promotional phrases, or specific plots from ranking pages.

Fixed quality rules:

- The first 800 Chinese characters must contain a concrete pressure event, a protagonist choice, and a first counterattack opportunity.
- Every section must include at least one choice, counterattack, reveal, reversal, or irreversible consequence.
- The protagonist must solve problems through established information, ability, relationship, or cost, not pure coincidence.
- Antagonists must have an interest motive, not only shout, sneer, or act as decoration.
- Supporting characters must affect the scene through information, leverage, emotion, or tradeoff.
- Payoffs must come from planned setup; late-stage new bloodlines, secret organizations, mysterious old men, black cars, and similar new mainline hooks are blocked in final sections unless already seeded in the plan.
- The final section must resolve the main conflict and should not open a new main plot.
- Prose should prefer concrete action, dialogue, numbers, objects, sensory detail, and cause-effect movement over abstract summary.
- Avoid stock AI phrases such as "命运的齿轮开始转动", "全场震惊", "空气凝固", "嘴角勾起一抹弧度", "她不知道的是", "从此踏上新征程".

## Components

### StoryQualityGate

Add a small domain component under `com.trade.story.decision` or `com.trade.story.application`.

It evaluates:

- Section content length against the target range.
- First-section opening requirements.
- Forbidden phrase count.
- Overlong paragraphs after parser normalization.
- Final-section forbidden new-hook patterns.
- Repetition of section titles or abrupt story restarts.
- Missing section summary.
- Basic action-density hints: at least one marker of decision, conflict, reveal, consequence, or dialogue in each section.

The gate returns a `StoryQualityReport` with:

- `passed`
- `score`
- `issues`
- `rewriteAdvice`

The gate does not try to judge literary quality perfectly. It catches easy failures and turns them into concrete rewrite instructions.

### StoryRewritePolicy

Add a policy helper that decides whether to retry a section. It uses:

- `trade.story.quality-gate-enabled`
- `trade.story.max-section-rewrite-attempts`
- `trade.story.final-editor-pass-enabled`

Default behavior:

- Gate enabled.
- One rewrite attempt per failed section.
- Final editor pass enabled.
- If rewrite also fails, keep the better-scoring draft and log the quality issues.

### Prompt Builder Additions

Extend `AiStoryPromptBuilder` with:

- `buildTopicPrompt` updates: require multiple candidate topics internally and select one by freshness, audience pull, opening pressure, payoff clarity, originality, and completion fit.
- `buildSectionRewritePrompt`: sends the original section, quality issues, story plan, prior summary, and section target. It must preserve facts and only rewrite the section body/metadata.
- `buildFinalEditPrompt`: sends the full draft and asks for an editor pass that removes generic AI texture, strengthens scene detail, fixes continuity, and closes the ending without changing core events.

The final edit response can reuse a simple JSON schema:

```json
{
  "editedSections": [
    {
      "section": 1,
      "sectionTitle": "string",
      "content": "string",
      "sectionSummary": "string"
    }
  ],
  "editorNotes": ["string"]
}
```

### Parser Additions

Keep `AiStoryResponseParser` backward compatible. Add parsing for the final edit response only if the implementation chooses to accept structured edited sections. If final editing returns plain section bodies, normalize with the existing content normalization logic.

### Service Flow

`AiStoryService.generateSection` should:

1. Generate a draft with the current section prompt.
2. Parse and normalize it.
3. Run `StoryQualityGate`.
4. If the report fails and retry remains, build a rewrite prompt and regenerate.
5. Evaluate the rewrite and keep the higher-scoring draft.
6. Continue with continuity notes from the selected draft.

After planned and continuation sections:

1. Run the final editor pass when enabled.
2. Recompute actual character count after editing.
3. Save the edited drafts.

The lock and file persistence behavior remain unchanged.

## Configuration

Add properties to `AiStoryProperties`:

- `qualityGateEnabled = true`
- `maxSectionRewriteAttempts = 1`
- `finalEditorPassEnabled = true`
- `minSectionQualityScore = 70`
- `forbiddenPhraseMaxCount = 1`
- `openingCheckChars = 800`

Do not change `trade.ai.client.temperature` globally. If request-level AI options are added later, story generation can use them without affecting trading.

## Error Handling

- If a rewrite prompt or final editor pass fails to parse, persist the parse error with the existing `AiResponseParseErrorSink`.
- If final editing fails, save the pre-edit drafts instead of failing the whole story.
- If quality gate fails after all retries, log issues and save the better draft so scheduled generation remains resilient.
- If topic planning fails, keep the existing fallback topic behavior.

## Testing

Add focused unit tests:

- `StoryQualityGateTest` checks forbidden phrases, weak opening, final-section new-hook patterns, and passing concrete prose.
- `AiStoryPromptBuilderTest` checks rewrite prompt includes quality issues and preserves story/section context.
- `AiStoryResponseParserTest` checks final edit response parsing if structured editing is implemented.
- `AiStoryServiceTest` uses a fake `AiTextClient` to verify failed sections trigger one rewrite and the better draft is selected.

Existing parser and file repository tests should continue passing.

## Migration Risk

Token usage will increase because failed sections may be rewritten and final editing adds one more model call. Defaults keep the increase bounded: at most one rewrite per section plus one final pass.

The main behavior risk is over-filtering creative prose. To avoid that, the quality gate should be advisory and retry-limited. It should never reject all output indefinitely.

## Acceptance Criteria

- Story generation still produces a saved UTF-8 `.txt` file.
- Existing topic planning, section drafting, continuation, and parse-error persistence continue to work.
- A section containing obvious AI stock phrases or weak opening structure triggers rewrite when the quality gate is enabled.
- Final sections are warned or rewritten when they introduce unseeded new mainline hooks.
- Final editor failure does not fail the whole generation.
- Tests pass for story decision/application changes.
