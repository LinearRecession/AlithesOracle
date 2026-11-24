# AlithesOracle — Final Review & Agent Build Specification

> **Purpose:** This document is the **single source of truth** for building **AlithesOracle**.
> It is written so an implementation agent (including smaller, fast models such as **Gemini 4 Argon**) can build, wire, and test the entire Java application step-by-step without guessing library versions, JSON shapes, SQL schemas, or edge cases.
>
> **Reference files already in this repo:**
> - `docs/contracts/jev.md` — verified TypeSafe AI Jev API summary
> - `docs/contracts/typesafe-openapi.json` — official TypeSafe OpenAPI v0.2.0 spec
> - `docs/ARCHITECTURE.md` — earlier exploratory architecture (superseded by this file wherever they differ)

---

## 0. Final Architecture Review (What Was Simplified & Why)

Before writing code, here is what changed from the earlier multi-module draft (`docs/ARCHITECTURE.md`) to make v1 **rock-solid and stupid simple**:

| Area | Earlier Draft | Final v1 Design (Use This) | Why |
|---|---|---|---|
| **Build system** | 10-module Gradle (Kotlin DSL) | **Single-module Maven** (`pom.xml`) | Maven 3.9 is installed locally (Gradle is not). One `pom.xml` with clean Java packages eliminates cross-module boilerplate and build errors. |
| **Database access** | Spring Data JDBC + Testcontainers | **Spring `JdbcClient` (explicit SQL)** + Flyway + **H2 (PostgreSQL mode) for tests** | Explicit SQL avoids ORM magic. `mvn test` runs completely offline with zero Docker requirement; PostgreSQL 16 is used in `docker-compose.yml`. |
| **NL → GDELT queries** | Apache OpenNLP (`.bin` models) + YAML lexicon | **Pure-Java keyword/entity extractor + Jev Choice** | No external binary model files to download or bundle. Still follows "Java proposes, Jev decides". |
| **HTML extraction** | Readability4J | **Jsoup only** (`<article>`/`<main>`/`<p>` filter) | One battle-tested dependency (`jsoup 1.24.1`), 25 lines of deterministic code. |
| **Date check (Jev weakness)** | Regex date parser + relative-date NLP + extra Jev Choice | **GDELT window filter + HTML `publishedAt` check in Java + semantic `is_recent_event` Noul** | Jev cannot compare dates reliably. Java checks `publishedAt < windowStart - 24h`, while Jev only answers a semantic question ("is this reported as new/recent news rather than an old historical recap?"). |
| **Rate limiting & retries** | Resilience4j + ShedLock | **Simple synchronized limiter + retry loop + `AtomicBoolean` scan lock** | Live testing confirmed GDELT strictly returns `HTTP 429` if called faster than once per 5 seconds. A 20-line synchronized 5.5s spacer is foolproof and testable at `0ms` in unit tests. |
| **Tests** | Testcontainers + ArchUnit + jqwik | **JUnit 5 + AssertJ + H2 + WireMock + MockMvc** | Fast, deterministic, 100% offline `mvn test`. |

### Ground Rules for Implementation Agents
1. **Follow Steps 1 through 9 in exact order.** Do not skip ahead.
2. **Run `mvn test` after every step.** Do not move to the next step until `mvn test` exits `0`.
3. **Never call live GDELT or live Jev in tests.** Mock all HTTP calls with WireMock.
4. **Never log `TYPESAFE_API_KEY`.**
5. **Never change field names, SQL column names, or JSON keys** specified in this document.
6. **Keep everything in one Maven module** under base package `io.alithes.oracle`.

---

## 1. System Overview (Mental Model)

AlithesOracle answers yes/no questions about real-world news events (e.g., *"Has Country X officially announced a snap election?"*).

```
User POST /v1/questions
   │
   ▼
[1. AdmissionService] ──► Java checks dates (windowStart >= now-80d, deadline > windowStart)
   │                      Jev checks: is this a discrete, unambiguous, newsworthy event?
   │                      If NO -> save as REJECTED, return 422.
   ▼
[2. QueryCompiler]    ──► Java builds 3-4 GDELT keyword queries.
   │                      Jev picks the best 1-2 queries.
   │                      Save question as ACTIVE, return 201.
   ▼
[3. ScanScheduler]    ──► Every 15 minutes, scans all ACTIVE questions:
   │
   ├─► [GdeltClient]      Search GDELT DOC 2.0 API (max 1 request per 5.5s globally).
   ├─► [Deduplication]    Normalize URL; skip URLs already saved for this question.
   ├─► [Headline Screen]  Ask Jev: is this headline relevant? Skip if p < 0.30.
   ├─► [ArticleFetcher]   Download HTML (max 2MB), extract text with Jsoup, compute SHA-256 + SimHash.
   ├─► [EvidenceAssessor] (Optional paragraph screen if long) + 1 Jev call with:
   │                      relevant, stance_fits, stance_a, stance_b (reversed), is_recent, attribution.
   ├─► [ResolutionEngine] Pure deterministic Java math:
   │                      weight each article -> cluster duplicates/syndication -> apply 4 rules ->
   │                      ACTIVE | RESOLVED_YES | RESOLVED_NO | DISPUTED | UNRESOLVABLE.
   └─► [WebhookNotifier]  If resolved and webhookUrl is set, POST HMAC-SHA256 signed JSON.
```

---

## 2. Exact Project Layout

Create this exact directory structure in the workspace root (`/Users/efstathiospapafotis/Downloads/AlithesOracle`):

```text
pom.xml
docker-compose.yml
src/
├── main/
│   ├── java/io/alithes/oracle/
│   │   ├── OracleApplication.java
│   │   ├── config/
│   │   │   ├── AppConfig.java
│   │   │   └── OracleProperties.java
│   │   ├── domain/
│   │   │   ├── QuestionStatus.java
│   │   │   ├── EvidenceStage.java
│   │   │   ├── FetchStatus.java
│   │   │   ├── Question.java
│   │   │   ├── Evidence.java
│   │   │   ├── Assessment.java
│   │   │   ├── Resolution.java
│   │   │   └── ScanLog.java
│   │   ├── util/
│   │   │   ├── UrlNormalizer.java
│   │   │   ├── SimHash.java
│   │   │   └── PromptInjectionDetector.java
│   │   ├── db/
│   │   │   ├── QuestionRepository.java
│   │   │   ├── EvidenceRepository.java
│   │   │   ├── AssessmentRepository.java
│   │   │   ├── ResolutionRepository.java
│   │   │   └── ScanLogRepository.java
│   │   ├── client/
│   │   │   ├── JevClient.java
│   │   │   ├── JevModels.java
│   │   │   ├── JevException.java
│   │   │   ├── GdeltClient.java
│   │   │   ├── GdeltQuerySanitizer.java
│   │   │   ├── GdeltArticle.java
│   │   │   ├── GdeltException.java
│   │   │   ├── ArticleFetcher.java
│   │   │   └── FetchedArticle.java
│   │   ├── service/
│   │   │   ├── SourceTierRegistry.java
│   │   │   ├── ResolutionEngine.java
│   │   │   ├── AdmissionService.java
│   │   │   ├── QueryCompiler.java
│   │   │   ├── EvidenceAssessor.java
│   │   │   ├── ScanService.java
│   │   │   ├── ScanScheduler.java
│   │   │   ├── QuestionService.java
│   │   │   └── WebhookNotifier.java
│   │   └── web/
│   │       ├── QuestionController.java
│   │       ├── ApiDtos.java
│   │       └── GlobalExceptionHandler.java
│   └── resources/
│       ├── application.yml
│       ├── source-tiers.properties
│       └── db/migration/
│           └── V1__init.sql
└── test/
    ├── java/io/alithes/oracle/
    │   ├── util/UtilitiesTest.java
    │   ├── db/RepositoryTest.java
    │   ├── client/JevClientTest.java
    │   ├── client/GdeltClientTest.java
    │   ├── client/ArticleFetcherTest.java
    │   ├── service/ResolutionEngineTest.java
    │   ├── service/EndToEndScanTest.java
    │   └── web/QuestionControllerTest.java
    └── resources/
        └── application-test.yml
```

---

## 3. Exact Build & Configuration Files

### 3.1 `pom.xml`
Use these exact verified Maven Central versions (Java 21 target, works on JDK 21 and JDK 25):

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-parent</artifactId>
        <version>3.5.16</version>
        <relativePath/>
    </parent>

    <groupId>io.alithes</groupId>
    <artifactId>alithes-oracle</artifactId>
    <version>0.1.0-SNAPSHOT</version>
    <name>alithes-oracle</name>

    <properties>
        <java.version>21</java.version>
        <jsoup.version>1.24.1</jsoup.version>
        <wiremock.version>3.13.2</wiremock.version>
    </properties>

    <dependencies>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-jdbc</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-validation</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-actuator</artifactId>
        </dependency>
        <dependency>
            <groupId>org.flywaydb</groupId>
            <artifactId>flyway-core</artifactId>
        </dependency>
        <dependency>
            <groupId>org.flywaydb</groupId>
            <artifactId>flyway-database-postgresql</artifactId>
        </dependency>
        <dependency>
            <groupId>org.postgresql</groupId>
            <artifactId>postgresql</artifactId>
            <scope>runtime</scope>
        </dependency>
        <dependency>
            <groupId>org.jsoup</groupId>
            <artifactId>jsoup</artifactId>
            <version>${jsoup.version}</version>
        </dependency>

        <!-- Test dependencies -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>com.h2database</groupId>
            <artifactId>h2</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.wiremock</groupId>
            <artifactId>wiremock-standalone</artifactId>
            <version>${wiremock.version}</version>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
            </plugin>
        </plugins>
    </build>
</project>
```

### 3.2 `src/main/resources/application.yml`
```yaml
server:
  port: 8080

spring:
  application:
    name: alithes-oracle
  threads:
    virtual:
      enabled: true
  datasource:
    url: ${DATABASE_URL:jdbc:postgresql://localhost:5432/alithes}
    username: ${DATABASE_USER:alithes}
    password: ${DATABASE_PASSWORD:alithes}
  flyway:
    enabled: true
    locations: classpath:db/migration

oracle:
  jev:
    base-url: ${JEV_BASE_URL:https://api.typesafe.ai}
    api-key: ${TYPESAFE_API_KEY:}
    model: ${JEV_MODEL:jev-1.14.0}
    reject-alias: true
    timeout-ms: 15000
    max-retries: 3
    retry-base-delay-ms: 1000
  gdelt:
    base-url: ${GDELT_BASE_URL:https://api.gdeltproject.org}
    min-interval-ms: 5500
    timeout-ms: 15000
    max-retries: 3
    retry-base-delay-ms: 6000
    max-window-split-depth: 3
  fetcher:
    timeout-ms: 10000
    max-bytes: 2097152
    user-agent: "AlithesOracle/1.0 (+https://alithes.io)"
  scanner:
    enabled: true
    fixed-delay-ms: 900000
    overlap-minutes: 30
    max-articles-per-scan: 30
    headline-min-score: 0.30
  webhook:
    secret: ${WEBHOOK_SECRET:dev-secret-change-me}
    timeout-ms: 5000
    max-retries: 3
  policy:
    version: 1
    min-cluster-confirm-weight: 0.55
    min-confirm-clusters: 3
    min-trusted-clusters: 2
    min-confirm-sum: 2.20
    max-deny-ratio: 0.50
    dispute-threshold: 1.50
    post-deadline-dispute-confirm-sum: 1.00
    max-failed-scan-ratio: 0.20
```

### 3.3 `src/test/resources/application-test.yml`
```yaml
spring:
  datasource:
    url: jdbc:h2:mem:alithes;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1
    driver-class-name: org.h2.Driver
    username: sa
    password: ""
  flyway:
    enabled: true

oracle:
  jev:
    api-key: test-key
    model: jev-1.14.0
    reject-alias: true
    retry-base-delay-ms: 10
  gdelt:
    min-interval-ms: 0
    retry-base-delay-ms: 10
  scanner:
    enabled: false
  webhook:
    secret: test-webhook-secret
```

### 3.4 `src/main/resources/source-tiers.properties`
Create a properties file mapping lowercase domains (without `www.`) to weights:
- Tier 1 (`1.0`): `reuters.com`, `apnews.com`, `bbc.com`, `bbc.co.uk`, `ft.com`, `bloomberg.com`, `wsj.com`, `nytimes.com`, `washingtonpost.com`, `theguardian.com`, `economist.com`, `afp.com`, `france24.com`, `dw.com`, `aljazeera.com`, `nhk.or.jp`, `abc.net.au`, `cbc.ca`, `npr.org`, `pbs.org`
- Tier 2 (`0.7`): `cnn.com`, `cnbc.com`, `politico.com`, `politico.eu`, `axios.com`, `euronews.com`, `sky.com`, `independent.co.uk`, `telegraph.co.uk`, `lemonde.fr`, `spiegel.de`, `elpais.com`, `ansa.it`, `kyodonews.net`, `scmp.com`, `straitstimes.com`, `thehindu.com`, `timesofisrael.com`, `haaretz.com`, `japantimes.co.jp`, `usatoday.com`, `latimes.com`, `forbes.com`, `time.com`, `newsweek.com`, `marketwatch.com`, `barrons.com`, `techcrunch.com`, `theverge.com`, `arstechnica.com`
- Blocked (`0.0`): `theonion.com`, `babylonbee.com`, `clickhole.com`, `worldnewsdailyreport.com`
- Any domain not listed defaults to `0.3`.
- Subdomain matching rule: strip leading `www.` and `m.`, then check exact match; if not found, strip the leftmost label once (e.g. `uk.reuters.com` → `reuters.com`) and check again.

### 3.5 `docker-compose.yml`
```yaml
services:
  postgres:
    image: postgres:16-alpine
    environment:
      POSTGRES_DB: alithes
      POSTGRES_USER: alithes
      POSTGRES_PASSWORD: alithes
    ports:
      - "5432:5432"
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U alithes"]
      interval: 5s
      timeout: 3s
      retries: 10
```

---

## 4. Exact Database Schema (`V1__init.sql`)

**Compatibility rule:** Every type below works identically on **PostgreSQL 16** and **H2 (`MODE=PostgreSQL`)**.
- Store UUIDs as `VARCHAR(36)`
- Store UTC `Instant` as `TIMESTAMP` (read/write using `Timestamp.from(instant)` and `rs.getTimestamp(col).toInstant()`)
- Store lists as JSON strings in `TEXT` columns using Jackson

```sql
CREATE TABLE questions (
    id                   VARCHAR(36) PRIMARY KEY,
    text                 TEXT NOT NULL,
    resolution_criteria  TEXT,
    window_start         TIMESTAMP NOT NULL,
    deadline             TIMESTAMP NOT NULL,
    grace_hours          INT NOT NULL DEFAULT 48,
    status               VARCHAR(32) NOT NULL,
    status_reason        TEXT,
    search_queries_json  TEXT NOT NULL,
    webhook_url          TEXT,
    last_scan_end        TIMESTAMP,
    created_at           TIMESTAMP NOT NULL,
    updated_at           TIMESTAMP NOT NULL
);

CREATE INDEX idx_questions_status ON questions(status);

CREATE TABLE evidence (
    id              VARCHAR(36) PRIMARY KEY,
    question_id     VARCHAR(36) NOT NULL REFERENCES questions(id) ON DELETE CASCADE,
    url             TEXT NOT NULL,
    normalized_url  TEXT NOT NULL,
    domain          VARCHAR(255) NOT NULL,
    title           TEXT NOT NULL,
    seen_at         TIMESTAMP NOT NULL,
    published_at    TIMESTAMP,
    language        VARCHAR(64),
    source_country  VARCHAR(128),
    stage           VARCHAR(32) NOT NULL,
    fetch_status    VARCHAR(32) NOT NULL,
    headline_score  DOUBLE PRECISION,
    content_sha256  VARCHAR(64),
    excerpt         TEXT,
    simhash         BIGINT NOT NULL DEFAULT 0,
    created_at      TIMESTAMP NOT NULL,
    CONSTRAINT uq_evidence_question_url UNIQUE (question_id, normalized_url)
);

CREATE INDEX idx_evidence_question_stage ON evidence(question_id, stage);

CREATE TABLE assessments (
    id                VARCHAR(36) PRIMARY KEY,
    evidence_id       VARCHAR(36) NOT NULL UNIQUE REFERENCES evidence(id) ON DELETE CASCADE,
    question_id       VARCHAR(36) NOT NULL REFERENCES questions(id) ON DELETE CASCADE,
    model             VARCHAR(64) NOT NULL,
    p_relevant        DOUBLE PRECISION NOT NULL,
    p_stance_fits     DOUBLE PRECISION NOT NULL,
    p_confirms        DOUBLE PRECISION NOT NULL,
    p_planned         DOUBLE PRECISION NOT NULL,
    p_speculation     DOUBLE PRECISION NOT NULL,
    p_denies          DOUBLE PRECISION NOT NULL,
    stance_unstable   BOOLEAN NOT NULL,
    stance_confidence DOUBLE PRECISION NOT NULL,
    p_fresh           DOUBLE PRECISION NOT NULL,
    attribution       VARCHAR(64) NOT NULL,
    injection_flag    BOOLEAN NOT NULL,
    input_tokens      INT NOT NULL,
    request_sha256    VARCHAR(64) NOT NULL,
    response_json     TEXT NOT NULL,
    created_at        TIMESTAMP NOT NULL
);

CREATE INDEX idx_assessments_question ON assessments(question_id);

CREATE TABLE resolutions (
    question_id                   VARCHAR(36) PRIMARY KEY REFERENCES questions(id) ON DELETE CASCADE,
    outcome                       VARCHAR(32) NOT NULL,
    confidence                    DOUBLE PRECISION NOT NULL,
    confirm_sum                   DOUBLE PRECISION NOT NULL,
    deny_sum                      DOUBLE PRECISION NOT NULL,
    independent_clusters          INT NOT NULL,
    supporting_evidence_ids_json  TEXT NOT NULL,
    policy_version                INT NOT NULL,
    model                         VARCHAR(64) NOT NULL,
    details                       TEXT,
    resolved_at                   TIMESTAMP NOT NULL
);

CREATE TABLE scan_logs (
    id              VARCHAR(36) PRIMARY KEY,
    question_id     VARCHAR(36) NOT NULL REFERENCES questions(id) ON DELETE CASCADE,
    started_at      TIMESTAMP NOT NULL,
    finished_at     TIMESTAMP NOT NULL,
    gdelt_ok        BOOLEAN NOT NULL,
    articles_found  INT NOT NULL,
    new_evidence    INT NOT NULL,
    error           TEXT
);

CREATE INDEX idx_scan_logs_question ON scan_logs(question_id);
```

---

## 5. Exact Domain Models & Pure Utilities

### 5.1 Enums (`io.alithes.oracle.domain`)
- `QuestionStatus`: `ACTIVE`, `REJECTED`, `RESOLVED_YES`, `RESOLVED_NO`, `DISPUTED`, `UNRESOLVABLE` (with `public boolean isFinal() { return this != ACTIVE; }`)
- `EvidenceStage`: `NEW`, `SKIPPED`, `ASSESSED`, `FAILED`
- `FetchStatus`: `NOT_FETCHED`, `OK`, `TITLE_ONLY`

### 5.2 Records (`io.alithes.oracle.domain`)
- `Question(UUID id, String text, String resolutionCriteria, Instant windowStart, Instant deadline, int graceHours, QuestionStatus status, String statusReason, List<String> searchQueries, String webhookUrl, Instant lastScanEnd, Instant createdAt, Instant updatedAt)`
  - Helper method: `public Instant finalAt() { return deadline.plus(Duration.ofHours(graceHours)); }`
- `Evidence(UUID id, UUID questionId, String url, String normalizedUrl, String domain, String title, Instant seenAt, Instant publishedAt, String language, String sourceCountry, EvidenceStage stage, FetchStatus fetchStatus, Double headlineScore, String contentSha256, String excerpt, long simhash, Instant createdAt)`
- `Assessment(UUID id, UUID evidenceId, UUID questionId, String model, double pRelevant, double pStanceFits, double pConfirms, double pPlanned, double pSpeculation, double pDenies, boolean stanceUnstable, double stanceConfidence, double pFresh, String attribution, boolean injectionFlag, int inputTokens, String requestSha256, String responseJson, Instant createdAt)`
- `Resolution(UUID questionId, QuestionStatus outcome, double confidence, double confirmSum, double denySum, int independentClusters, List<UUID> supportingEvidenceIds, int policyVersion, String model, String details, Instant resolvedAt)`
- `ScanLog(UUID id, UUID questionId, Instant startedAt, Instant finishedAt, boolean gdeltOk, int articlesFound, int newEvidence, String error)`

### 5.3 Pure Utilities (`io.alithes.oracle.util`)

#### `UrlNormalizer.normalize(String rawUrl)`
1. Parse with `URI.create(rawUrl.trim())` (on failure, return `rawUrl.trim().toLowerCase(Locale.ROOT)`).
2. Lowercase scheme and host; strip leading `www.` and `m.` from host.
3. Strip fragment (`#...`).
4. Strip trailing `/` from path (unless path is empty).
5. Filter query parameters: drop any param whose lowercase key starts with `utm_`, or equals `amp`, `fbclid`, `gclid`, `ref`, `source`, `outputType`. Sort remaining query params alphabetically.
6. Reassemble `scheme://host/path?sortedQuery`.

#### `SimHash.compute(String text)` (exact algorithm, 64-bit)
```java
public final class SimHash {
    public static long compute(String text) {
        if (text == null || text.isBlank()) return 0L;
        int[] bits = new int[64];
        String[] tokens = text.toLowerCase(Locale.ROOT).split("[^a-z0-9]+");
        for (String token : tokens) {
            if (token.length() < 3) continue;
            long h = fnv1a64(token);
            for (int b = 0; b < 64; b++) {
                if (((h >>> b) & 1L) != 0L) bits[b]++;
                else bits[b]--;
            }
        }
        long result = 0L;
        for (int b = 0; b < 64; b++) {
            if (bits[b] > 0) result |= (1L << b);
        }
        return result;
    }

    public static int hammingDistance(long a, long b) {
        return Long.bitCount(a ^ b);
    }

    private static long fnv1a64(String s) {
        long hash = 0xcbf29ce484222325L;
        for (int i = 0; i < s.length(); i++) {
            hash ^= s.charAt(i);
            hash *= 0x100000001b3L;
        }
        return hash;
    }
}
```

#### `PromptInjectionDetector.isSuspicious(String text)`
Return `true` if `text != null` and lowercase text contains any of these substrings:
`"ignore previous instructions"`, `"ignore all previous"`, `"disregard previous"`, `"disregard the above"`, `"forget your instructions"`, `"system prompt"`, `"answer yes to all"`, `"output confirms_occurred"`, `"you must answer yes"`.

---

## 6. External API Contracts (Exact Wire Formats)

### 6.1 TypeSafe AI — Jev System One API (Verified OpenAPI v0.2.0)

- **URL:** `POST {oracle.jev.base-url}/v1/systemone`
- **Headers:**
  - `Authorization: Bearer {oracle.jev.api-key}`
  - `Content-Type: application/json`
- **Startup Check:** If `oracle.jev.reject-alias` is `true` and `oracle.jev.model` equals `"jev-latest"` or `"jev-preview"`, throw `IllegalStateException` on startup. Always pin `"jev-1.14.0"`.

**Exact Request JSON:**
```json
{
  "model": "jev-1.14.0",
  "state": {
    "question": "Has Country X announced a snap election?",
    "article": { "title": "...", "paragraphs": ["..."] }
  },
  "questions": {
    "q_yesno": {
      "type": "noul",
      "instructions": "Is this article about the event in `question`?",
      "criteria": {
        "true": "Reports on that exact event",
        "false": "Different event or only passing mention"
      }
    },
    "q_pick": {
      "type": "choice",
      "instructions": "What does the article report?",
      "criteria": {
        "option_a": "Description of A",
        "option_b": "Description of B"
      }
    }
  }
}
```
*(Note: On `noul`, `criteria` is optional — omit it or include `{"true": "...", "false": "..."}`. On `choice`, `criteria` is a `LinkedHashMap<String, String>` preserving insertion order so reversed-order `stance_b` actually reverses the JSON keys!)*

**Exact Response JSON (HTTP 200):**
```json
{
  "model": "jev-1.14.0",
  "answers": {
    "q_yesno": {
      "type": "noul",
      "noul": 0.94
    },
    "q_pick": {
      "type": "choice",
      "choice": "option_a",
      "confidence": 0.88,
      "probabilities": {
        "option_a": 0.91,
        "option_b": 0.09
      }
    }
  },
  "usage": {
    "input_tokens": 312,
    "output_tokens": 14
  }
}
```

**Exact Error Handling in `JevClient`:**
| HTTP Status | Action |
|---|---|
| `200` | Parse JSON; return `JevResponse(model, answers, inputTokens, outputTokens, requestSha256, rawResponseBody)`. |
| `429` or `529` | Retry up to `max-retries`. If `Retry-After` header is an integer (seconds), sleep that long (capped at 30s); else sleep `retryBaseDelayMs * 2^(attempt-1)`. |
| `400` with body containing `max_tokens_exceeded` | Do NOT retry. Throw `JevContextTooLargeException`. |
| `400`, `401`, `413`, `415`, `422` | Do NOT retry. Throw `JevClientException(statusCode, body)`. |
| Network timeout / `5xx` | Retry up to `max-retries` with exponential backoff, then throw `JevClientException`. |

---

### 6.2 GDELT DOC 2.0 API (Verified Behavior)

- **URL:** `GET {oracle.gdelt.base-url}/api/v2/doc/doc`
- **Query parameters (URL-encoded):**
  - `query` — sanitized GDELT boolean query
  - `mode=artlist`
  - `format=json`
  - `maxrecords=250`
  - `sort=datedesc`
  - `startdatetime=YYYYMMDDHHMMSS` (UTC)
  - `enddatetime=YYYYMMDDHHMMSS` (UTC)

**Exact Success Response JSON (HTTP 200):**
```json
{
  "articles": [
    {
      "url": "https://www.reuters.com/world/example-2026-11-22/",
      "url_mobile": "",
      "title": "Country X Prime Minister Calls Snap Election",
      "seendate": "20261122T143000Z",
      "socialimage": "https://...",
      "domain": "reuters.com",
      "language": "English",
      "sourcecountry": "United Kingdom"
    }
  ]
}
```

**Critical GDELT Quirks (MUST handle all 4):**
1. **Strict rate limit (`HTTP 429`):** GDELT returns `429 Too Many Requests` with `"Please limit requests to one every 5 seconds..."`. `GdeltClient` MUST guard every HTTP call with a `synchronized` method enforcing at least `oracle.gdelt.min-interval-ms` (default `5500ms`) between requests! Retry `429` and `5xx` up to `max-retries` with backoff.
2. **Empty results:** When no articles match, GDELT returns HTTP 200 with `{}` or `{"articles": []}` or whitespace. Return `List.of()`.
3. **HTTP 200 plain-text/HTML error:** When a query is invalid (e.g. phrase too short), GDELT returns **HTTP 200** with plain text like `"Your query was too short or too general."`. Always check `String trimmed = body.trim();`. If `trimmed.isEmpty()` return `List.of()`. If `!trimmed.startsWith("{")`, throw non-retryable `GdeltQueryException(trimmed)`.
4. **250-result truncation:** If `articles.size() >= 250` and `Duration.between(from, to).toMinutes() > 60` and `depth < maxWindowSplitDepth`, split `[from, to]` at `mid = from.plus(Duration.between(from, to).dividedBy(2))`, query `[from, mid]` and `[mid, to]` recursively (`depth + 1`), and merge deduplicated by `url`.

#### `GdeltQuerySanitizer.sanitize(String raw)`
1. Replace smart quotes `“”` with `"`, strip characters outside `[A-Za-z0-9\s"():\-]`.
2. Collapse multiple spaces into one and trim.
3. For unquoted tokens (not `OR`, not `AND`, not starting with `domain:`/`sourcelang:`/`sourcecountry:`/`theme:`): drop tokens shorter than 3 characters.
4. If the query contains ` OR ` and is not already wrapped in parentheses around the `OR` terms, wrap the `OR` clause in `(...)`.
5. Truncate safely to 200 characters (without leaving an unmatched `"` or `(`).
6. If the resulting string is blank or shorter than 3 characters, throw `IllegalArgumentException`.

---

## 7. Exact Pipeline Services Specification

### 7.1 `AdmissionService` (Called on `POST /v1/questions`)
1. **Deterministic Java checks first (no Jev call):**
   - `windowStart` must not be before `clock.instant().minus(Duration.ofDays(80))` (GDELT DOC 2.0 only keeps ~3 months). If older -> reject: `"windowStart is older than 80 days (GDELT limit)"`.
   - `deadline` must be strictly after `windowStart`. If not -> reject: `"deadline must be after windowStart"`.
   - `text` must be between 10 and 500 characters.
2. **One Jev call:**
   - `state`: `{"question": text, "resolution_criteria": criteriaOrEmpty}`
   - `questions`:
     - `question_type` (`choice`):
       - `discrete_event`: `"Asks whether a specific, verifiable public event happened (announcement, resignation, election, law passed, deal signed, strike, sanction)"`
       - `numeric_threshold`: `"Depends on an exact number, price, sports score, or statistic"`
       - `subjective`: `"Depends on opinion, quality, or value judgment"`
       - `unverifiable`: `"Private event, rumor-only, or cannot be checked from public news"`
     - `is_ambiguous` (`noul`): `"Could two reasonable readers disagree about what counts as YES for this question?"`
     - `is_newsworthy` (`noul`): `"Would mainstream international or national news outlets plausibly report this event if it happened?"`
3. **Accept rule:**
   - `question_type.choice` equals `"discrete_event"` AND `question_type.probabilities.get("discrete_event") >= 0.60`
   - AND `is_ambiguous.noul < 0.45`
   - AND `is_newsworthy.noul >= 0.50`
   - Otherwise return `AdmissionResult.rejected(reasons)`.

### 7.2 `QueryCompiler` (Java proposes, Jev decides)
- If caller supplied non-empty `searchQueries` override: sanitize each, deduplicate, cap at 3, and return them (skip Jev).
- Otherwise:
  1. **Extract entities & keywords in Java:**
     - Find quoted phrases `"..."` in `question.text()`.
     - Find multi-word capitalized phrases (e.g. `European Central Bank`, `United Nations`) — skip a single capitalized word only if it is the very first word of the sentence and is a stopword (`Has`, `Did`, `Will`, `Was`, `Have`, `The`, `Is`).
     - Tokenize remaining words (lowercase, length ≥ 4, not in a built-in English stopword list of ~80 common words like `that`, `this`, `with`, `from`, `have`, `been`, `officially`, `announced`? — keep action verbs like `announced`, `resigned`, `election`, `sanctions`, `merger`, `strike`, `passed`, `signed`!).
  2. **Build up to 4 sanitized candidate queries** (`LinkedHashMap<String, String> candidates`):
     - `q1`: Top entity phrase (quoted) + top 2 non-entity keywords.
     - `q2`: Top entity phrase (quoted) + `(keyword1 OR keyword2)` (if ≥ 2 keywords).
     - `q3`: Top 3–4 distinctive keywords joined with spaces.
     - `q4`: Top 2 distinctive keywords joined with spaces (broadest fallback).
     - Remove any candidate that fails `GdeltQuerySanitizer` or duplicates another candidate.
  3. If only 1 valid candidate exists, return it directly.
  4. Otherwise, **one Jev `choice` call**:
     - `state`: `{"question": question.text(), "resolution_criteria": criteriaOrEmpty}`
     - `question "best_query"`: `choice` with `instructions = "Which GDELT search query is most likely to find news articles that directly answer the question without excessive unrelated noise?"` and `criteria = candidates`.
  5. Keep the winning choice plus the runner-up if its probability `>= 0.25` (at most **2 queries** per question so we preserve GDELT's 1-req/5.5s quota).

### 7.3 `ArticleFetcher`
- Uses `java.net.http.HttpClient` (connect timeout `10s`, follow redirects `NORMAL`).
- Sends `User-Agent: {oracle.fetcher.user-agent}` and `Accept: text/html,application/xhtml+xml`.
- Reads response as `InputStream` and stops after `oracle.fetcher.max-bytes` (2 MB).
- If HTTP status is not `200`, or content type does not contain `html`, or any `IOException` / timeout occurs: return `FetchedArticle.titleOnly(title)`.
- Parse HTML with `Jsoup.parse(html, url)`:
  - Extract `publishedAt` (optional): try `<meta property="article:published_time">`, `<meta name="pubdate">`, `<meta name="publish-date">`, `<time datetime="...">`. Parse with `Instant.parse` or `OffsetDateTime.parse(...).toInstant()` (ignore parse failures).
  - Remove elements: `script, style, nav, header, footer, aside, noscript, form, iframe, svg`.
  - Select `<p>` tags inside `article, main` first; collect trimmed text of paragraphs with length `>= 40` chars. If total length `< 300`, fall back to all `<p>` tags in `body`.
  - If total extracted length `< 300`: return `FetchedArticle.titleOnly(title)`.
  - Otherwise return `FetchedArticle(FetchStatus.OK, paragraphs, fullJoinedText, publishedAt, sha256(fullJoinedText), excerptFirst2000Chars, SimHash.compute(fullJoinedText))`.
- For `TITLE_ONLY`: `sha256 = sha256(title)`, `excerpt = title`, `simhash = SimHash.compute(title)`.

### 7.4 `EvidenceAssessor`
Given `(Question q, Evidence ev, FetchedArticle fetched)`:

1. **Injection check:** `boolean injection = PromptInjectionDetector.isSuspicious(ev.title() + " " + fetched.joinedText());`
2. **Paragraph selection (handles Jev's large-state weakness):**
   - If `fetched.status() == TITLE_ONLY`: `selectedParagraphs = List.of(ev.title())`.
   - Else if `fetched.paragraphs().size() <= 8` and `fetched.joinedText().length() <= 4000`: use `fetched.paragraphs()`.
   - Else (long article): take at most first 25 paragraphs, send **1 screening Jev call** with `state = {"question": q.text(), "paragraphs": {"p0": ..., "p1": ...}}` and one `noul` per paragraph (`"Does paragraphs.p{i} discuss the event in question?"`). Keep `p0` (lead) plus paragraphs with `noul >= 0.45`, sorted by score descending, capped at 8 paragraphs and 6,000 total chars.
3. **Main assessment (1 Jev call):**
   - `state`:
     ```json
     {
       "question": "<q.text>",
       "resolution_criteria": "<q.resolutionCriteria or ''>",
       "article": {
         "title": "<ev.title>",
         "domain": "<ev.domain>",
         "paragraphs": ["..."]
       }
     }
     ```
   - `questions` (all 6 in ONE request — `state` is billed only once!):
     1. `relevant` (`noul`):
        - `instructions`: `"Is `article` about the specific event described in `question`?"`
        - `criteria`: `{"true": "Reports on that exact event involving the same actors", "false": "Different event, different actors, or unrelated"}`
     2. `stance_fits` (`noul`):
        - `instructions`: `"Does `article` state or imply anything about whether the event in `question` has happened or not?"`
     3. `stance_a` (`choice`, `LinkedHashMap` in this exact order):
        - `instructions`: `"What does `article` report about the event in `question`?"`
        - `criteria`:
          - `confirms_occurred`: `"States as fact that the event has already happened"`
          - `planned_or_future`: `"Says it is expected, scheduled, or will happen, but has not yet"`
          - `speculation_or_rumor`: `"Reports rumors, unconfirmed claims, or speculation"`
          - `denies_or_not_occurred`: `"States the event did not happen, was denied, or was cancelled"`
     4. `stance_b` (`choice`, `LinkedHashMap` in **exact reverse order** of `stance_a`!):
        - Same `instructions`, keys inserted in order: `denies_or_not_occurred`, `speculation_or_rumor`, `planned_or_future`, `confirms_occurred`.
     5. `is_recent` (`noul`):
        - `instructions`: `"Does `article` report the event as newly occurring around the time the article was published, rather than recounting an older historical event from months or years ago?"`
     6. `attribution` (`choice`):
        - `instructions`: `"How does `article` source its main claim about the event?"`
        - `criteria`:
          - `official_source_cited`: `"Quotes or cites an official, government, company, or primary source"`
          - `direct_reporting`: `"The journalist or news outlet reports it directly as confirmed fact"`
          - `anonymous_sources`: `"Relies mainly on unnamed or anonymous sources"`
          - `opinion_or_analysis`: `"Opinion, editorial, or commentary piece"`
   - If `JevContextTooLargeException` is thrown, truncate `paragraphs` to first 3 paragraphs (max 1,500 chars) and retry once.
4. **Combine `stance_a` and `stance_b` in Java:**
   - For each of the 4 stance options: `p = (stanceA.prob(opt) + stanceB.prob(opt)) / 2.0`.
   - `stanceUnstable = !stanceA.choice().equals(stanceB.choice()) || Math.abs(stanceA.prob("confirms_occurred") - stanceB.prob("confirms_occurred")) > 0.25`.
   - `stanceConfidence = Math.min(stanceA.confidence(), stanceB.confidence())`.
   - Build and return `Assessment`.

### 7.5 `ResolutionEngine` (Pure Deterministic Java — Core IP)

Inputs: `(Question q, List<EvidenceWithAssessment> items, List<ScanLog> scanLogs, Instant now)`
Output: `Optional<Resolution>` (`Optional.empty()` means stay `ACTIVE`).

#### Step A: Score each assessed item `i`
If `a.injectionFlag()` is `true` OR `a.pStanceFits() < 0.30`:
- `confirmWeight = 0.0`, `denyWeight = 0.0`.

Otherwise:
- `tier = sourceTierRegistry.weightFor(ev.domain())` (`1.0`, `0.7`, `0.3`, or `0.0`)
- `attr`:
  - `"official_source_cited"` → `1.0`
  - `"direct_reporting"` → `0.9`
  - `"anonymous_sources"` → `0.5`
  - `"opinion_or_analysis"` → `0.2`
  - anything else → `0.5`
- `fetch = (ev.fetchStatus() == FetchStatus.OK) ? 1.0 : 0.4`
- `stability = (a.stanceUnstable() || a.stanceConfidence() < 0.50) ? 0.5 : 1.0`
- `fresh`:
  - If `ev.publishedAt() != null && ev.publishedAt().isBefore(q.windowStart().minus(Duration.ofHours(24)))` → `0.0` (hard Java date gate!)
  - Else if `a.pFresh() >= 0.50` → `1.0`
  - Else → `a.pFresh()`
- `base = a.pRelevant() * fresh * tier * attr * fetch * stability`
- `confirmWeight = base * a.pConfirms()`
- `denyWeight = base * a.pDenies()`

#### Step B: Syndication & Domain Clustering
Sort scored items by `Math.max(confirmWeight, denyWeight)` descending.
Maintain `List<Cluster> clusters`. For each item `x`:
- Normalize title: `x.title().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9 ]", "").replaceAll("\\s+", " ").trim()`.
- Check if `x` matches any existing cluster `c` where ANY member `m` of `c` satisfies:
  1. `SimHash.hammingDistance(x.simhash(), m.simhash()) <= 3` (syndicated wire copy), **OR**
  2. `normalizedTitle(x).equals(normalizedTitle(m))` (exact same headline), **OR**
  3. `canonicalDomain(x.domain()).equals(canonicalDomain(m.domain()))` (same publisher counts at most once!).
- If it matches, add `x` to that cluster; otherwise create a new cluster containing `x`.
- For each cluster `c`:
  - `c.confirmWeight = max(item.confirmWeight)` in `c`
  - `c.denyWeight = max(item.denyWeight)` in `c`
  - `c.trusted = any item in c has tier >= 0.7`
  - `c.bestEvidenceId = evidenceId of the item with highest confirmWeight in c`

#### Step C: Apply Policy v1 (Exact Order of Checks)
Let:
- `confirmClusters = clusters with c.confirmWeight >= 0.55`
- `trustedConfirmClusters = confirmClusters where c.trusted == true`
- `confirmSum = sum of top 5 c.confirmWeight across all clusters`
- `denySum = sum of top 5 c.denyWeight across all clusters`
- `supportingIds = bestEvidenceId of top 5 confirmClusters`

Evaluate in this **exact order**:
1. **DISPUTED (contradiction):**
   If `confirmSum >= 1.50 && denySum >= 1.50`
   → return `Resolution(DISPUTED, confidence = 0.50, ...)`.
2. **RESOLVED_YES (quorum met):**
   If `confirmClusters.size() >= 3 && trustedConfirmClusters.size() >= 2 && confirmSum >= 2.20 && denySum < 0.50 * confirmSum`
   → return `Resolution(RESOLVED_YES, confidence = Math.min(0.99, confirmSum / 3.0), ...)`.
3. **Still before deadline + grace:**
   If `now.isBefore(q.finalAt())`
   → return `Optional.empty()` (question stays `ACTIVE`).
4. **Past deadline + grace (`!now.isBefore(q.finalAt())`):**
   - `long totalScans = scanLogs.size();`
   - `long failedScans = scanLogs.stream().filter(s -> !s.gdeltOk()).count();`
   - `double failedRatio = (totalScans == 0) ? 1.0 : (double) failedScans / totalScans;`
   - If `failedRatio > 0.20`
     → return `Resolution(UNRESOLVABLE, confidence = 0.0, ..., details = "GDELT coverage gap: failed scan ratio=" + failedRatio)`.
   - Else if `confirmSum >= 1.00`
     → return `Resolution(DISPUTED, confidence = 0.50, ..., details = "Deadline passed with partial evidence below YES quorum")`.
   - Else
     → `double noConf = Math.max(0.50, Math.min(0.99, 1.0 - (confirmSum / 2.20)));`
     → return `Resolution(RESOLVED_NO, confidence = noConf, ...)`.

### 7.6 `ScanService` & `ScanScheduler`
- `ScanScheduler` runs `@Scheduled(fixedDelayString = "${oracle.scanner.fixed-delay-ms}")`.
  - If `!props.scanner().enabled()`, do nothing.
  - Guard with `private final AtomicBoolean running = new AtomicBoolean(false);` — if already running, skip tick.
  - Fetch all `ACTIVE` questions from `QuestionRepository` and call `scanService.scanQuestion(question.id())` sequentially (sequential is ideal because GDELT is globally limited to 1 req / 5.5s anyway!).
- `ScanService.scanQuestion(UUID questionId)`:
  1. Load `Question q`. If not found or `q.status().isFinal()`, return.
  2. Compute scan window:
     - `Instant now = clock.instant();`
     - `Instant scanFrom = (q.lastScanEnd() == null) ? q.windowStart() : max(q.windowStart(), q.lastScanEnd().minus(Duration.ofMinutes(30)));`
     - `Instant scanTo = min(now, q.finalAt());`
  3. For each query in `q.searchQueries()` (only if `scanFrom.isBefore(scanTo)`):
     - Call `gdeltClient.search(query, scanFrom, scanTo)`.
     - Catch exceptions: record `gdeltOk = false` and `errorMsg`, do NOT crash the whole scan.
  4. Deduplicate & save new articles into `evidence` (`stage = NEW`, `fetchStatus = NOT_FETCHED`). Ignore duplicates (unique constraint on `(question_id, normalized_url)`).
  5. Process up to `max-articles-per-scan` (30) `NEW` evidence items for this question:
     - **Headline prefilter:** call Jev with `state = {"question": q.text(), "headline": ev.title(), "domain": ev.domain()}` and `noul relevant = "Could an article with this headline be about the event in question?"`.
     - Save `headlineScore`. If `< 0.30`, mark `stage = SKIPPED` and continue.
     - **Fetch:** `FetchedArticle fetched = articleFetcher.fetch(ev.url(), ev.title())`. Update `evidence` row (`fetchStatus`, `publishedAt`, `contentSha256`, `excerpt`, `simhash`).
     - **Assess:** call `evidenceAssessor.assess(q, ev, fetched)`. Save `Assessment`, mark `stage = ASSESSED`. (On non-retryable failure, mark `stage = FAILED` and continue.)
  6. Save `ScanLog`. If `gdeltOk`, update `question.lastScanEnd = scanTo`.
  7. Run `resolutionEngine.evaluate(q, assessedItems, scanLogs, now)`:
     - If `Optional<Resolution>` is present:
       - Save `Resolution` and update `question.status = resolution.outcome()`.
       - If `q.webhookUrl() != null && !q.webhookUrl().isBlank()`, call `webhookNotifier.notify(q.webhookUrl(), resolution)`.

### 7.7 `WebhookNotifier`
- Serializes `Resolution` as JSON.
- Computes `HMAC-SHA256(secret, bodyBytes)` as lowercase hex and sends header `X-Alithes-Signature: sha256=<hex>`.
- `POST` to `webhookUrl` with 5s timeout, retrying up to 3 times on non-2xx / IO errors. Never fail the scan if webhook delivery fails (log warning).

---

## 8. Exact REST API (`QuestionController`)

| Method | Path | Request / Behavior | Response |
|---|---|---|---|
| `POST` | `/v1/questions` | Body: `CreateQuestionRequest(text, resolutionCriteria, windowStart, deadline, graceHours?, searchQueries?, webhookUrl?)`. Runs Admission + QueryCompiler. | `201 Created` (`QuestionResponse`) or `422 Unprocessable Entity` (`ProblemDetail` with `reasons`) |
| `GET` | `/v1/questions/{id}` | Fetch question by UUID | `200 OK` (`QuestionResponse`) or `404` |
| `GET` | `/v1/questions/{id}/evidence` | List evidence + joined assessment (optional `?stage=ASSESSED`) | `200 OK` (list of `EvidenceDetailResponse`) or `404` |
| `GET` | `/v1/questions/{id}/resolution` | Fetch resolution | `200 OK` (`Resolution`) or `404` if not resolved yet |
| `POST` | `/v1/questions/{id}/rescan` | Synchronously triggers `scanService.scanQuestion(id)` | `200 OK` (updated `QuestionResponse`) or `404` |
| `POST` | `/v1/questions/{id}/review` | Body: `ManualReviewRequest(outcome, reason)` where `outcome` is `RESOLVED_YES`, `RESOLVED_NO`, or `UNRESOLVABLE`. Only allowed when question is `DISPUTED` (else `409 Conflict`). Writes `Resolution` with `model="manual"`. | `200 OK` (`Resolution`) |

`GlobalExceptionHandler` (`@RestControllerAdvice`) returns standard Spring `ProblemDetail` (RFC 7807) for `400`, `404`, `409`, `422`, `500`.

---

## 9. Step-by-Step Build Plan (Execute In Exact Order)

> **Instructions for the builder agent:** Complete one step at a time. Run the exact `mvn` command listed at the end of each step. Fix any error before starting the next step.

### Step 1: Project Skeleton & Config
- Create `pom.xml`, `docker-compose.yml`, `application.yml`, `application-test.yml`, `source-tiers.properties`, `OracleApplication.java` (`@SpringBootApplication`, `@EnableScheduling`, `@ConfigurationPropertiesScan`), `AppConfig.java` (`@Bean Clock clock()`, `@Bean HttpClient httpClient()`), and `OracleProperties.java` (`@ConfigurationProperties(prefix = "oracle")`).
- **Verify:** `mvn test-compile` passes.

### Step 2: Domain Models & Pure Utilities
- Implement all 8 files in `domain/` and all 3 files in `util/` (`UrlNormalizer`, `SimHash`, `PromptInjectionDetector`).
- Write `UtilitiesTest.java` testing:
  - `UrlNormalizer` strips `utm_*`, `amp`, `#frag`, `www.`, trailing `/`, sorts params.
  - `SimHash`: identical texts -> distance `0`; 95%-similar wire copy -> distance `<= 3`; unrelated texts -> distance `> 10`.
  - `PromptInjectionDetector` flags `"ignore previous instructions"` and returns `false` on normal news text.
- **Verify:** `mvn test -Dtest=UtilitiesTest` passes.

### Step 3: Flyway Schema & JDBC Repositories
- Create `src/main/resources/db/migration/V1__init.sql` (exact SQL from Section 4).
- Implement `QuestionRepository`, `EvidenceRepository`, `AssessmentRepository`, `ResolutionRepository`, `ScanLogRepository` using Spring's `JdbcClient` + `ObjectMapper`.
  - In `EvidenceRepository.insertIfAbsent(Evidence e)`: check existence by `(question_id, normalized_url)` first (or catch `DuplicateKeyException`) so it returns `boolean inserted` cleanly on both Postgres and H2.
- Write `RepositoryTest.java` (`@SpringBootTest`, `@ActiveProfiles("test")`) testing CRUD for all 5 tables and verifying duplicate `(question_id, normalized_url)` is safely ignored.
- **Verify:** `mvn test -Dtest=RepositoryTest` passes.

### Step 4: Jev Client
- Implement `JevModels.java`, `JevException.java` (plus `JevContextTooLargeException`), and `JevClient.java` per Section 6.1.
- Enforce the startup alias check (`jev-latest` / `jev-preview` rejected when `reject-alias=true`).
- Write `JevClientTest.java` with WireMock covering:
  1. `noul` + `choice` combined request & response parsing (including `confidence`, `probabilities`, `usage.input_tokens`, `model`).
  2. `choice` preserves key insertion order in serialized JSON (`stance_a` vs `stance_b`).
  3. `429` with `Retry-After: 0` retries and succeeds on attempt 2.
  4. `529` retries and succeeds on attempt 2.
  5. `400` with `max_tokens_exceeded` throws `JevContextTooLargeException` without retrying.
  6. `401` throws `JevClientException` without retrying.
- **Verify:** `mvn test -Dtest=JevClientTest` passes.

### Step 5: GDELT Client & Query Sanitizer
- Implement `GdeltQuerySanitizer.java`, `GdeltArticle.java`, `GdeltException.java`, and `GdeltClient.java` per Section 6.2.
- Write `GdeltClientTest.java` with WireMock covering:
  1. Valid JSON response with 2 articles + `seendate` (`20261122T143000Z`) parsed to exact UTC `Instant`.
  2. Empty `{}` response -> returns empty list.
  3. HTTP 200 with plain text `"Your query was too short"` -> throws `GdeltQueryException` (no retry).
  4. HTTP 429 `"Please limit requests to one every 5 seconds"` -> retries and succeeds.
  5. 250-article response splits the time window in half and merges results.
  6. Sanitizer unit tests (drops 2-letter tokens, wraps `OR` in parens, strips bad chars, caps length).
- **Verify:** `mvn test -Dtest=GdeltClientTest` passes.

### Step 6: Article Fetcher
- Implement `FetchedArticle.java` and `ArticleFetcher.java` per Section 7.3.
- Write `ArticleFetcherTest.java` with WireMock covering:
  1. Normal HTML page with `<article>`, `<p>`, `<script>`, `<nav>`, and `<meta property="article:published_time">` -> returns `OK`, strips scripts/nav, extracts publishedAt, computes SHA-256 and SimHash.
  2. HTTP 403 / paywall -> returns `TITLE_ONLY` without throwing.
  3. Short HTML (< 300 chars text) -> returns `TITLE_ONLY`.
  4. Oversized body (> max-bytes) -> truncates safely without OOM.
- **Verify:** `mvn test -Dtest=ArticleFetcherTest` passes.

### Step 7: Resolution Engine & Source Tiers
- Implement `SourceTierRegistry.java` (loads `source-tiers.properties`) and `ResolutionEngine.java` per Section 7.5.
- Write `ResolutionEngineTest.java` (plain JUnit 5, NO Spring context!) with at least these 10 tests:
  1. 3 independent tier-1/2 confirming articles -> `RESOLVED_YES`.
  2. 10 syndicated copies (identical text / SimHash distance <= 3) from 10 different domains -> collapses to 1 cluster -> stays `ACTIVE` (NOT `RESOLVED_YES`!).
  3. 5 different articles all from `reuters.com` -> collapses to 1 cluster -> stays `ACTIVE`.
  4. 3 confirming articles all from unknown tier-3 domains (`0.3`) -> fails `minTrustedClusters=2` -> stays `ACTIVE`.
  5. 3 confirming + 3 denying tier-1 articles -> `DISPUTED`.
  6. Article with `publishedAt` 5 days before `windowStart` -> `fresh=0.0` -> ignored.
  7. Article with `injectionFlag=true` -> weight `0.0` -> ignored.
  8. Past `deadline + grace` with 0 confirming evidence and `0%` failed scans -> `RESOLVED_NO`.
  9. Past `deadline + grace` with `30%` failed GDELT scans -> `UNRESOLVABLE` (never false NO!).
  10. Replay determinism: calling `evaluate(...)` twice on the same inputs returns identical `Resolution`.
- **Verify:** `mvn test -Dtest=ResolutionEngineTest` passes.

### Step 8: Core Services & End-to-End Scan Test
- Implement `AdmissionService`, `QueryCompiler`, `EvidenceAssessor`, `WebhookNotifier`, `ScanService`, `ScanScheduler`, and `QuestionService`.
- Write `EndToEndScanTest.java` (`@SpringBootTest`, `@ActiveProfiles("test")`, WireMock for GDELT + Jev + article host + webhook receiver, injectable mutable `Clock`):
  1. Creating a subjective question -> returns rejected (`REJECTED` in DB).
  2. Creating a valid question -> `ACTIVE` -> run `scanService.scanQuestion(id)` with 3 mocked tier-1 confirming articles -> transitions to `RESOLVED_YES` and delivers signed HMAC webhook!
  3. Creating another valid question -> 0 articles found -> advance `Clock` past `deadline + grace` -> run `scanService.scanQuestion(id)` -> transitions to `RESOLVED_NO`.
- **Verify:** `mvn test -Dtest=EndToEndScanTest` passes.

### Step 9: REST API & Full Suite Verification
- Implement `ApiDtos.java`, `QuestionController.java`, and `GlobalExceptionHandler.java` per Section 8.
- Write `QuestionControllerTest.java` (`@SpringBootTest`, `@AutoConfigureMockMvc`, `@ActiveProfiles("test")`) testing all 6 endpoints including `422` on rejected question, `404` on unknown ID, and `200`/`409` on manual review of `DISPUTED` vs `ACTIVE`.
- **Final Verification:** Run `mvn clean test`. Every test must pass with 0 failures and 0 errors.

---

## 10. Running Locally After Build

```bash
# 1. Start PostgreSQL
docker compose up -d

# 2. Set your TypeSafe API key and run the app
export TYPESAFE_API_KEY="your-key-here"
mvn spring-boot:run

# 3. Check health
curl http://localhost:8080/actuator/health

# 4. Submit a question
curl -X POST http://localhost:8080/v1/questions \
  -H "Content-Type: application/json" \
  -d '{
    "text": "Has Country X officially announced a snap election?",
    "resolutionCriteria": "Official government announcement counts; rumors do not.",
    "windowStart": "2026-11-01T00:00:00Z",
    "deadline": "2026-12-31T23:59:59Z"
  }'
```
