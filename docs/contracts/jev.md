# Jev (TypeSafe AI) API contract (checked 2026-11-22)

Sources: https://docs.typesafe.ai (llms.txt index), `api/system-one`, `api/errors`, `models`, `model-jaggedness`, and the OpenAPI spec https://api.typesafe.ai/openapi.json (v0.2.0). The spec is saved next to this file as `typesafe-openapi.json`.

## Endpoint
- `POST https://api.typesafe.ai/v1/systemone`
- Headers: `Authorization: Bearer $TYPESAFE_API_KEY`, `Content-Type: application/json`
- Optional headers: `TypeSafe-Danger-Zone-Long-Context: true` (raises both context limits to 262k; slower and less accurate, so **don't use it in this project**), `TypeSafe-Danger-Zone-Images: true` (not needed)
- `GET https://api.typesafe.ai/v1/models` lists the aliases. Versioned IDs are accepted even if they aren't listed.

## Request (`SystemOneRequest`)
| Field | Type | Notes |
|---|---|---|
| `model` | string, required | Pin `jev-1.14.0`. The aliases `jev-latest` and `jev-preview` move with each release. |
| `state` | string \| object \| array, required | The content every question refers to. Questions can point at parts of it by name, e.g. `` `article.paragraphs` ``. |
| `questions` | map name → Question, required, ≥ 1 | Question names are **not** sent to the model. |
| `assets` | map, optional | Images only. Not used. |

### Question types (`type` is the discriminator)
| type | Fields | Constraints |
|---|---|---|
| `noul` | `instructions` (string\|object\|array\|null), `criteria` optional `{"true": "...", "false": "..."}` | Answer: probability of yes/true |
| `choice` | `instructions`, `criteria` **required**: map option → description | 1–255 options |
| `score` | `instructions`, `criteria` **required**: ordered array of level descriptions | 1–10 levels, scored from 0 |
| `box` | images only | not used |

## Response (`SystemOneResponse`)
```json
{
  "model": "jev-1.14.0",
  "answers": {
    "q_noul":   {"type": "noul", "noul": 0.98},
    "q_choice": {"type": "choice", "choice": "calm", "confidence": 0.7, "probabilities": {"angry": 0.15, "calm": 0.8, "excited": 0.05}},
    "q_score":  {"type": "score", "score": 1.7, "confidence": 0.55, "legend": {"0": "...", "1": "...", "2": "..."}, "probabilities": {"0": 0.1, "1": 0.1, "2": 0.8}}
  },
  "usage": {"input_tokens": 120, "output_tokens": 12}
}
```
`model` in the response is always the **versioned** ID. Log it with every assessment.

## Limits and pricing (Jev 1.14 and 1.13)
- Context: **64k tokens per request** (state plus all questions) and **32k per question** (state plus any one question). There is no limit on how many questions a request has.
- Body: at most 32 MiB; UTF-8 JSON without a BOM; at most 64 levels of nesting; finite numbers only. Supports `Content-Encoding: gzip` or `zstd` (one layer only).
- Rate limits: set per organization (tokens/s and requests/s), visible in the developer console.
- Price: \$0.042 per million input tokens. Output is free.

## Errors
| Status | Meaning | Adapter behaviour |
|---|---|---|
| 400 | Request-rule violation; `max_tokens_exceeded` means the context is too long | Not retryable. Raise `ContextTooLargeException` so the caller shrinks the state. |
| 401 | Bad or missing key | Not retryable. Raise an alert. |
| 413 | Body over 32 MiB | Not retryable |
| 415 | Bad Content-Encoding | Not retryable (bug) |
| 422 | Schema validation (`detail[]` with `loc`, `msg`, `type`) | Not retryable (bug) |
| 429 | Rate limited; may include `retry-after` | Retry with backoff, honouring `retry-after` |
| 529 | Overloaded | Retry with exponential backoff plus jitter |

## SDKs
Official SDKs exist for Python (`typesafe_sdk`) and JavaScript only. **There is no official Java SDK**, so we build our own adapter on `java.net.http.HttpClient` with Jackson. Model `Question`/`Answer` as sealed interfaces, using Jackson `@JsonTypeInfo(property = "type")`.

## Documented weak spots ("model jaggedness") and how this project handles them
1. **Literal reading.** State the exact condition and put boundary cases in `criteria`.
2. **Math and counting.** Do these in code.
3. **Comparing dates, or checking whether one falls in a window.** Unreliable. Ask Jev to *pick* a candidate date (a Choice with a `not_stated` option) and compare the dates in Java.
4. **Indirection.** Point at named parts of the state directly.
5. **Large state with irrelevant detail.** Accuracy drops. Screen paragraphs with Nouls first.
6. **Adversarial content can move answers.** Use explicit criteria plus the multi-source quorum.
7. **Instructions and criteria that contradict each other.** Keep them aligned (never map `true` to "no").
8. **Choice option order.** Jev tends to favour the first option. Ask the critical choices twice with the options in different orders and compare.
9. **Generation.** Jev can't do it. Generate candidates in code or with an LLM, and let Jev choose.

Recommended starting Noul thresholds: ≥ 0.7 means yes, ≤ 0.3 means no, and anything in between is uncertain or goes to review.

## Order-bias measurement (to be filled in by WP-0)
_TBD_
