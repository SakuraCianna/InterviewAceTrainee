# Structured Evaluation and Report Enhancement Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace single-score AI output with validated multi-dimensional evidence, drive dynamic stage completion from trusted coverage, and move all AI report enhancement into independent `GENERATE_REPORT` jobs while preserving an immediate deterministic report.

**Architecture:** The turn Worker evaluates outside transactions, then a short transaction persists validated dimensions, applies a pure `StageProgressPolicy`, and either inserts another Turn or completes the Session/Stage with a base report. `ReportJobHandler` performs report synthesis outside completion transactions and publishes a new immutable Revision with compare-and-set semantics. The three pre-existing dirty AI files are incorporated here; they must not be discarded or committed separately.

**Tech Stack:** Java 21, Spring AI 2.0, DeepSeek Chat, Spring JDBC, PostgreSQL JSONB, RabbitMQ/Outbox, JUnit 5, AssertJ

---

## File map

- Create `V3__structured_reports_and_ai_observability.sql`.
- Create focused evaluation records, `StageProgressPolicy`, `InterviewReportAssembler` and `ReportJobHandler` under `aiwork/worker`.
- Modify `InterviewAiGenerator`, `SpringAiInterviewGenerator`, `InterviewEvaluation` and `InterviewPromptPolicy` for the new JSON contract.
- Modify `AiJobWorker` only as queue/lease orchestration and handler dispatch; preserve its ACK ordering and state guards.
- Extend package service projections so the Worker can complete/unlock Stages in the same transaction.

## Task 1: Add report revisions, dimension scores and AI observability schema

**Files:**
- Create: `Backend/src/main/resources/db/migration/V3__structured_reports_and_ai_observability.sql`
- Create: `Backend/src/test/java/icu/sakuracianna/mianba/platform/database/StructuredReportSchemaTest.java`
- Modify: `Backend/src/test/java/icu/sakuracianna/mianba/interview/packageflow/JobInterviewPackagePostgresIT.java`

- [ ] **Step 1: Write a failing migration-resource test**

```java
assertThat(sql)
        .contains("CREATE TABLE report_revisions")
        .contains("CREATE TABLE turn_dimension_scores")
        .contains("generation_status")
        .contains("report_scope")
        .contains("package_id")
        .contains("operation", "prompt_version", "validation_status");
```

- [ ] **Step 2: Verify the test fails**

```powershell
.\mvnw.cmd '-Dtest=StructuredReportSchemaTest' test
```

- [ ] **Step 3: Create V3 with backward-compatible report migration**

Add nullable `package_id`, `report_scope`, generation fields, current revision and enhancement metadata to `reports`; make `session_id` nullable, replace its table-level UNIQUE with partial unique indexes and enforce exactly one subject:

```sql
CHECK (num_nonnulls(session_id, package_id) = 1);
CREATE UNIQUE INDEX ux_reports_session ON reports(session_id) WHERE session_id IS NOT NULL;
CREATE UNIQUE INDEX ux_reports_package ON reports(package_id) WHERE package_id IS NOT NULL;
```

Create `report_revisions(report_id, revision_no, source, report_json, generated_job_id, provider_name, model_name, prompt_version, rubric_version, output_schema_version, created_at)` with unique report+revision. Create `turn_dimension_scores` with unique turn+dimension, 0–100 score and bounded evidence/comment.

Extend `ai_jobs` with package/report FKs and indexed lookups. Extend `ai_call_logs` with operation, prompt/output-schema versions, validation status, finish reason and attempt number. Backfill every existing report as Session scope, base-ready/template, revision 1 before adding NOT NULL constraints.

- [ ] **Step 4: Add PostgreSQL tests for report subject XOR, revision uniqueness and role grants**

Run:

```powershell
.\mvnw.cmd -Pintegration verify
```

Expected: V1–V3 migrate and constraints PASS in the dedicated database.

- [ ] **Step 5: Commit**

```powershell
git add -- Backend/src/main/resources/db/migration/V3__structured_reports_and_ai_observability.sql Backend/src/test/java/icu/sakuracianna/mianba/platform/database/StructuredReportSchemaTest.java Backend/src/test/java/icu/sakuracianna/mianba/interview/packageflow/JobInterviewPackagePostgresIT.java
```

```powershell
git commit -m "新增结构化评分与报告版本表"
```

## Task 2: Define and validate the structured evaluation contract

**Files:**
- Create: `Backend/src/main/java/icu/sakuracianna/mianba/aiwork/worker/DimensionEvaluation.java`
- Modify: `Backend/src/main/java/icu/sakuracianna/mianba/aiwork/worker/InterviewEvaluation.java`
- Modify: `Backend/src/main/java/icu/sakuracianna/mianba/aiwork/worker/InterviewAiGenerator.java`
- Modify: `Backend/src/main/java/icu/sakuracianna/mianba/aiwork/worker/SpringAiInterviewGenerator.java`
- Test: `Backend/src/test/java/icu/sakuracianna/mianba/aiwork/worker/SpringAiInterviewGeneratorTest.java`
- Test: `Backend/src/test/java/icu/sakuracianna/mianba/aiwork/worker/InterviewEvaluationTest.java`

- [ ] **Step 1: Replace the four-field parser test with the versioned contract**

```json
{
  "score": 82,
  "feedback": "回答给出了具体取舍，但缺少容量依据。",
  "dimensions": [{
    "code": "SYSTEM_DESIGN",
    "score": 80,
    "evidence": "选择缓存降低读取延迟",
    "comment": "需要补充失效与一致性策略"
  }],
  "coveredSections": ["SYSTEM_DESIGN"],
  "coveredTopics": ["CACHE_CONSISTENCY"],
  "riskFlags": ["CAPACITY_EVIDENCE_MISSING"],
  "shouldEndStage": false,
  "nextQuestion": "缓存失效时如何保证一致性？",
  "nextSection": "SYSTEM_DESIGN",
  "nextQuestionType": "SYSTEM_DESIGN",
  "nextTopicCode": "CACHE_CONSISTENCY"
}
```

Tests reject unknown/duplicate fields, invalid codes, duplicate dimension/topic entries, oversized evidence, score mismatch, invalid English-only output and algorithm feedback claiming compiled/passed code.

- [ ] **Step 2: Verify tests fail**

```powershell
.\mvnw.cmd '-Dtest=SpringAiInterviewGeneratorTest,InterviewEvaluationTest' test
```

- [ ] **Step 3: Implement immutable records and strict parser validation**

`InterviewEvaluation` contains overall score/feedback, copied lists of `DimensionEvaluation`, covered sections/topics, risk flags, end suggestion and next-question metadata. Keep the existing output sanitization and injection boundaries. Replace the current log-only `checkConsistency` with a validator that throws `new AiWorkerException("AI_OUTPUT_INVALID", "评分、维度和反馈内容不一致", true)` when score, feedback polarity or dimension averages materially contradict.

- [ ] **Step 4: Update prompt contracts by Stage**

Technical first requires the five sections and instructs algorithm evaluation to assess spoken approach, complexity and edge cases without claiming execution. Technical second consumes first-stage snapshot and permits linked deep follow-ups. HR consumes both snapshots and forbids sensitive-attribute discrimination.

Do not request chain-of-thought. Ask for concise evidence fields and exact JSON only.

- [ ] **Step 5: Run tests and commit the three pre-existing dirty files together with their tests**

```powershell
.\mvnw.cmd '-Dtest=SpringAiInterviewGeneratorTest,InterviewEvaluationTest,InterviewAiGeneratorConditionTest' test
```

```powershell
git add -- Backend/src/main/java/icu/sakuracianna/mianba/aiwork/worker/InterviewAiGenerator.java Backend/src/main/java/icu/sakuracianna/mianba/aiwork/worker/SpringAiInterviewGenerator.java Backend/src/main/java/icu/sakuracianna/mianba/aiwork/worker/InterviewEvaluation.java Backend/src/main/java/icu/sakuracianna/mianba/aiwork/worker/DimensionEvaluation.java Backend/src/test/java/icu/sakuracianna/mianba/aiwork/worker
```

```powershell
git commit -m "升级面试结构化评分契约"
```

## Task 3: Implement deterministic stage-progress decisions

**Files:**
- Create: `Backend/src/main/java/icu/sakuracianna/mianba/aiwork/worker/StageProgressPolicy.java`
- Test: `Backend/src/test/java/icu/sakuracianna/mianba/aiwork/worker/StageProgressPolicyTest.java`

- [ ] **Step 1: Write the decision-table tests**

```java
assertThat(policy.decide(plan, 7, requiredSections, true)).isEqualTo(CONTINUE);
assertThat(policy.decide(plan, 8, requiredSections, true)).isEqualTo(COMPLETE);
assertThat(policy.decide(plan, 8, Set.of("INTRODUCTION"), true)).isEqualTo(CONTINUE);
assertThat(policy.decide(plan, 12, Set.of(), false)).isEqualTo(FORCE_COMPLETE);
```

Also test that next sections must come from the plan and already-covered required sections are not selected unless the question is a linked follow-up.

- [ ] **Step 2: Implement the pure policy**

```java
enum Decision { CONTINUE, COMPLETE, FORCE_COMPLETE }

Decision decide(StagePlan plan, int completedTurns,
        Set<String> coveredSections, boolean modelSuggestsEnd) {
    if (completedTurns >= plan.maxTurns()) return FORCE_COMPLETE;
    if (completedTurns >= plan.minTurns()
            && coveredSections.containsAll(plan.requiredSections())
            && modelSuggestsEnd) return COMPLETE;
    return CONTINUE;
}
```

- [ ] **Step 3: Run and commit**

```powershell
.\mvnw.cmd '-Dtest=StageProgressPolicyTest' test
```

```powershell
git add -- Backend/src/main/java/icu/sakuracianna/mianba/aiwork/worker/StageProgressPolicy.java Backend/src/test/java/icu/sakuracianna/mianba/aiwork/worker/StageProgressPolicyTest.java
```

```powershell
git commit -m "实现工作面试阶段收束策略"
```

## Task 4: Extract deterministic report assembly and trusted snapshots

**Files:**
- Create: `Backend/src/main/java/icu/sakuracianna/mianba/aiwork/worker/InterviewReportAssembler.java`
- Create: `Backend/src/main/java/icu/sakuracianna/mianba/aiwork/worker/StageContextSnapshotFactory.java`
- Modify: `Backend/src/test/java/icu/sakuracianna/mianba/aiwork/worker/AiJobWorkerReportTest.java`
- Create: `Backend/src/test/java/icu/sakuracianna/mianba/aiwork/worker/StageContextSnapshotFactoryTest.java`

- [ ] **Step 1: Move report pure-function tests to the new assembler before moving code**

Keep existing Chinese/IELTS assertions and add dimension aggregation, base generation metadata, package aggregation and algorithm wording tests.

- [ ] **Step 2: Verify tests fail, then move assembly without behavior loss**

```powershell
.\mvnw.cmd '-Dtest=AiJobWorkerReportTest,StageContextSnapshotFactoryTest' test
```

`InterviewReportAssembler` returns a `ReportDraft(totalScore, body)` with `generation_status=ENHANCING` when a report job will be queued and template summary source. It does not call `InterviewAiGenerator`.

`StageContextSnapshotFactory` accepts only validated dimensions, bounded evidence, covered topics and risk codes. It excludes question/answer text, full report JSON and raw material.

- [ ] **Step 3: Run and commit**

```powershell
.\mvnw.cmd '-Dtest=AiJobWorkerReportTest,StageContextSnapshotFactoryTest' test
```

```powershell
git add -- Backend/src/main/java/icu/sakuracianna/mianba/aiwork/worker/InterviewReportAssembler.java Backend/src/main/java/icu/sakuracianna/mianba/aiwork/worker/StageContextSnapshotFactory.java Backend/src/test/java/icu/sakuracianna/mianba/aiwork/worker/AiJobWorkerReportTest.java Backend/src/test/java/icu/sakuracianna/mianba/aiwork/worker/StageContextSnapshotFactoryTest.java
```

```powershell
git commit -m "提取基础报告与跨场快照组装"
```

## Task 5: Make turn completion dynamic and transaction-only

**Files:**
- Modify: `Backend/src/main/java/icu/sakuracianna/mianba/aiwork/worker/AiJobWorker.java`
- Create: `Backend/src/test/java/icu/sakuracianna/mianba/aiwork/worker/AiJobWorkerPackageFlowTest.java`
- Modify: `Backend/src/test/java/icu/sakuracianna/mianba/aiwork/worker/AiJobWorkerStateGuardTest.java`

- [ ] **Step 1: Add tests for continue, complete and force-complete branches**

Assert one transaction persists dimensions and coverage; CONTINUE inserts a typed Turn with optional `parent_turn_id`; COMPLETE creates a Session base report+Revision, completes Stage, writes the next Stage snapshot, unlocks only the next Stage and queues one report job+Outbox. HR completion additionally creates the package base report and package report job.

- [ ] **Step 2: Preserve lease and ACK guards**

The handler must still lock and update in job → package → stage → session → turn order, commit before ACK, and reject stale Session/package state. No `ChatClient.call()` or `synthesizeReportSummary()` may appear below `transactions.executeWithoutResult`.

- [ ] **Step 3: Implement dynamic database writes**

Persist `turn_dimension_scores`, section/topic metadata and evaluation feedback. Use `StageProgressPolicy`; on completion insert base report and Revision 1 in the same transaction, then create `GENERATE_REPORT` job with `report_id/package_id` and an Outbox event.

- [ ] **Step 4: Run and commit**

```powershell
.\mvnw.cmd '-Dtest=AiJobWorkerPackageFlowTest,AiJobWorkerStateGuardTest,AiJobWorkerReportTest' test
```

```powershell
git add -- Backend/src/main/java/icu/sakuracianna/mianba/aiwork/worker/AiJobWorker.java Backend/src/test/java/icu/sakuracianna/mianba/aiwork/worker/AiJobWorkerPackageFlowTest.java Backend/src/test/java/icu/sakuracianna/mianba/aiwork/worker/AiJobWorkerStateGuardTest.java
```

```powershell
git commit -m "实现动态面试阶段完成事务"
```

## Task 6: Handle report enhancement as a separate job

**Files:**
- Create: `Backend/src/main/java/icu/sakuracianna/mianba/aiwork/worker/ReportJobHandler.java`
- Create: `Backend/src/main/java/icu/sakuracianna/mianba/aiwork/worker/ReportEnhancement.java`
- Modify: `Backend/src/main/java/icu/sakuracianna/mianba/aiwork/worker/AiJobWorker.java`
- Test: `Backend/src/test/java/icu/sakuracianna/mianba/aiwork/worker/ReportJobHandlerTest.java`

- [ ] **Step 1: Write timeout, invalid-output, success and late-result tests**

Success inserts Revision N+1 then CAS-updates `reports.report_json/current_revision/generation_status/summary_source`. Runtime failure, 30-second operation timeout or invalid output keeps Revision 1 and sets `ENHANCEMENT_FAILED`; it never changes Session/Stage/Package completion.

- [ ] **Step 2: Dispatch by job kind before loading turn input**

```java
switch (claim.kind()) {
    case GENERATE_FOLLOW_UP -> turnJobHandler.handle(claim);
    case GENERATE_REPORT -> reportJobHandler.handle(claim);
    default -> throw new AiWorkerException("AI_JOB_KIND_UNSUPPORTED", "任务类型尚未开放", false);
}
```

The report model call occurs outside a database transaction and uses bounded report input: base summary, dimensions, strengths, improvements and evidence snippets. It never receives raw resume or full answers.

- [ ] **Step 3: Record a separate AI call observation**

Write operation `REPORT_ENHANCEMENT` or `PACKAGE_REPORT`, prompt/schema versions, validation status, attempt and available token/cost metadata. Never store prompt/output bodies.

- [ ] **Step 4: Run and commit**

```powershell
.\mvnw.cmd '-Dtest=ReportJobHandlerTest,AiJobWorkerStateGuardTest' test
```

```powershell
git add -- Backend/src/main/java/icu/sakuracianna/mianba/aiwork/worker Backend/src/test/java/icu/sakuracianna/mianba/aiwork/worker/ReportJobHandlerTest.java
```

```powershell
git commit -m "拆分AI报告增强任务"
```

## Task 7: Verify AI, report and package completion behavior

**Files:**
- Modify only files already in this plan if verification exposes defects.

- [ ] **Step 1: Run all AI and package tests**

```powershell
.\mvnw.cmd '-Dtest=SpringAiInterviewGeneratorTest,InterviewEvaluationTest,StageProgressPolicyTest,AiJobWorkerReportTest,AiJobWorkerPackageFlowTest,AiJobWorkerStateGuardTest,ReportJobHandlerTest,StageContextSnapshotFactoryTest' test
```

- [ ] **Step 2: Run full backend verification**

```powershell
.\mvnw.cmd verify
```

- [ ] **Step 3: Run dedicated PostgreSQL integration verification**

```powershell
.\mvnw.cmd -Pintegration verify
```

- [ ] **Step 4: Prove no external call remains in completion transactions**

```powershell
rg -n "executeWithoutResult|\.call\(\)|synthesizeReportSummary" src/main/java/icu/sakuracianna/mianba/aiwork/worker
```

Expected: model calls only in pre-transaction evaluation or `ReportJobHandler`, never inside completion callbacks.

- [ ] **Step 5: Inspect scope**

```powershell
git status --short --branch
```

```powershell
git diff --check
```

Expected: no unaccounted files, no whitespace errors and all three handoff AI edits are now either intentionally incorporated or replaced by tested code.
