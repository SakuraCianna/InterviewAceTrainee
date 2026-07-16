# Job Interview Package Core Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the transactional database and backend API core for a three-stage job interview package with one-time billing, whole-package voucher redemption, immutable material snapshots, ordered unlocking, and lazy later-stage Session creation.

**Architecture:** `interview_packages` is the billing and lifecycle aggregate; `interview_package_stages` stores three ordered stages and optionally links one Session. A dedicated package service owns package transactions so `JdbcInterviewService` remains responsible for standalone non-job Sessions. AI-driven completion and report enhancement belong to plan 2.

**Tech Stack:** Java 21, Spring Boot 4.1, Spring JDBC, PostgreSQL 18, Flyway 12, JUnit 5, AssertJ, Maven Failsafe

---

## File map

- Create `Backend/src/main/resources/db/migration/V2__job_interview_packages.sql`.
- Create packageflow types under `Backend/src/main/java/icu/sakuracianna/mianba/interview/packageflow/`.
- Create `Backend/src/main/java/icu/sakuracianna/mianba/interview/web/JobInterviewPackageController.java`.
- Modify `JdbcInterviewService`, `InterviewProductController`, retention and erasure services only at their package seams.
- Add unit, MockMvc, migration-resource and dedicated-PostgreSQL tests.

## Task 1: Add the package schema and database contracts

**Files:**
- Create: `Backend/src/test/java/icu/sakuracianna/mianba/platform/database/JobInterviewPackageSchemaTest.java`
- Create: `Backend/src/main/resources/db/migration/V2__job_interview_packages.sql`
- Create: `Backend/src/test/java/icu/sakuracianna/mianba/interview/packageflow/JobInterviewPackagePostgresIT.java`

- [ ] **Step 1: Write the failing migration-resource test**

```java
@Test
void migrationDefinesPackageStagesBillingAndTurnMetadata() throws Exception {
    try (InputStream input = getClass().getResourceAsStream(
            "/db/migration/V2__job_interview_packages.sql")) {
        assertThat(input).isNotNull();
        String sql = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        assertThat(sql)
                .contains("CREATE TABLE interview_packages")
                .contains("CREATE TABLE interview_package_stages")
                .contains("charged_credit IN (0, 3)")
                .contains("TECHNICAL_FIRST", "TECHNICAL_SECOND", "HR_FINAL")
                .contains("related_package_id", "redeemed_package_id")
                .contains("section_code", "question_type", "parent_turn_id");
    }
}
```

- [ ] **Step 2: Verify it fails**

Run from `Backend`:

```powershell
.\mvnw.cmd '-Dtest=JobInterviewPackageSchemaTest' test
```

Expected: FAIL because V2 is absent.

- [ ] **Step 3: Create V2 without editing V1**

Define `interview_packages` with owner/material/voucher FKs, idempotency key, request hash, status, current stage, `charged_credit`, plan/rubric versions, bounded object `material_snapshot`, version, 30-day expiry and erasure timestamps. Enforce:

```sql
CHECK ((charged_credit = 3 AND voucher_id IS NULL)
    OR (charged_credit = 0 AND voucher_id IS NOT NULL));
CREATE UNIQUE INDEX ux_interview_packages_one_active_user
    ON interview_packages(user_id) WHERE status = 'ACTIVE';
```

Define `interview_package_stages` with stage code, sequence, status, nullable unique Session FK, object `plan_snapshot/context_snapshot`, min/max turns, target minutes, version and lifecycle timestamps. Enforce unique package+stage and package+sequence.

Extend existing tables:

```sql
ALTER TABLE credit_ledger
    ADD COLUMN related_package_id uuid REFERENCES interview_packages(id) ON DELETE SET NULL;
ALTER TABLE vouchers
    ADD COLUMN redeemed_package_id uuid REFERENCES interview_packages(id) ON DELETE SET NULL,
    ADD CONSTRAINT ck_vouchers_single_redemption_target
        CHECK (num_nonnulls(redeemed_session_id, redeemed_package_id) <= 1);
ALTER TABLE turns
    ADD COLUMN section_code varchar(48),
    ADD COLUMN question_type varchar(48),
    ADD COLUMN topic_code varchar(80),
    ADD COLUMN parent_turn_id uuid REFERENCES turns(id) ON DELETE SET NULL;
```

Add predicate-matched indexes and explicit `mianba_api`/`mianba_worker` grants; never use `ALL TABLES`.

- [ ] **Step 4: Run the resource test**

```powershell
.\mvnw.cmd '-Dtest=JobInterviewPackageSchemaTest' test
```

Expected: PASS.

- [ ] **Step 5: Add real PostgreSQL constraint tests**

Reuse the dedicated database guards from `OutboxPublisherPostgresIT`. Test billing XOR, duplicate active packages, duplicate stage code/sequence, invalid stage ranges and role grants. Example invalid row:

```java
assertThatThrownBy(() -> jdbc.update("""
        INSERT INTO interview_packages(
            id,user_id,start_idempotency_key,request_hash,status,current_stage_code,
            charged_credit,plan_version,rubric_version,material_snapshot,expires_at)
        VALUES (?,?,'bad',?,'ACTIVE','TECHNICAL_FIRST',0,
                'job-cn-v1','job-rubric-v1','{}'::jsonb,now()+interval '30 days')
        """, UUID.randomUUID(), userId, "0".repeat(64)))
        .isInstanceOf(DataIntegrityViolationException.class);
```

- [ ] **Step 6: Run integration verification when the dedicated DB variables exist**

```powershell
.\mvnw.cmd -Pintegration verify
```

Expected: V1+V2 migrate and all ITs PASS. Never substitute a development or production database.

- [ ] **Step 7: Commit**

```powershell
git add -- Backend/src/main/resources/db/migration/V2__job_interview_packages.sql Backend/src/test/java/icu/sakuracianna/mianba/platform/database/JobInterviewPackageSchemaTest.java Backend/src/test/java/icu/sakuracianna/mianba/interview/packageflow/JobInterviewPackagePostgresIT.java
```

```powershell
git commit -m "新增工作面试套餐数据库结构"
```

## Task 2: Define immutable stages and dynamic plans

**Files:**
- Create: `Backend/src/main/java/icu/sakuracianna/mianba/interview/packageflow/JobInterviewStage.java`
- Create: `Backend/src/main/java/icu/sakuracianna/mianba/interview/packageflow/JobInterviewPlan.java`
- Test: `Backend/src/test/java/icu/sakuracianna/mianba/interview/packageflow/JobInterviewPlanTest.java`

- [ ] **Step 1: Write the failing plan test**

```java
@Test
void chineseEnterpriseV1HasThreeOrderedDynamicStages() {
    JobInterviewPlan plan = JobInterviewPlan.chineseEnterpriseV1();
    assertThat(plan.stages()).extracting(JobInterviewPlan.StagePlan::code)
            .containsExactly(TECHNICAL_FIRST, TECHNICAL_SECOND, HR_FINAL);
    assertThat(plan.stage(TECHNICAL_FIRST).minTurns()).isEqualTo(8);
    assertThat(plan.stage(TECHNICAL_FIRST).maxTurns()).isEqualTo(12);
    assertThat(plan.stage(TECHNICAL_SECOND).minTurns()).isEqualTo(7);
    assertThat(plan.stage(HR_FINAL).maxTurns()).isEqualTo(8);
    assertThat(plan.stage(TECHNICAL_FIRST).requiredSections())
            .contains("INTRODUCTION", "ALGORITHM_REASONING");
}
```

- [ ] **Step 2: Verify compilation fails**

```powershell
.\mvnw.cmd '-Dtest=JobInterviewPlanTest' test
```

- [ ] **Step 3: Implement the enum and copied immutable records**

```java
public enum JobInterviewStage {
    TECHNICAL_FIRST(1), TECHNICAL_SECOND(2), HR_FINAL(3);
    private final int sequence;
    JobInterviewStage(int sequence) { this.sequence = sequence; }
    public int sequence() { return sequence; }
}
```

`JobInterviewPlan.chineseEnterpriseV1()` returns:

- Technical first: 8–12, target 50, introduction/resume/foundations/role knowledge/algorithm reasoning.
- Technical second: 7–12, target 60, project/system design/tradeoff/incident/business impact.
- HR final: 5–8, target 25, motivation/stability/values/career/compensation/tech-risk verification.

Reject duplicate stage codes and invalid ranges in compact constructors.

- [ ] **Step 4: Run and commit**

```powershell
.\mvnw.cmd '-Dtest=JobInterviewPlanTest' test
```

```powershell
git add -- Backend/src/main/java/icu/sakuracianna/mianba/interview/packageflow Backend/src/test/java/icu/sakuracianna/mianba/interview/packageflow/JobInterviewPlanTest.java
```

```powershell
git commit -m "定义工作面试套餐阶段计划"
```

## Task 3: Implement atomic package creation and billing

**Files:**
- Create: `Backend/src/main/java/icu/sakuracianna/mianba/interview/packageflow/JobInterviewPackageService.java`
- Create: `Backend/src/main/java/icu/sakuracianna/mianba/interview/packageflow/JobInterviewPackageView.java`
- Create: `Backend/src/main/java/icu/sakuracianna/mianba/interview/packageflow/JdbcJobInterviewPackageService.java`
- Test: `Backend/src/test/java/icu/sakuracianna/mianba/interview/packageflow/JdbcJobInterviewPackageServiceTest.java`

- [ ] **Step 1: Write tests for credit, Voucher, admin and replay paths**

The public contract is:

```java
public interface JobInterviewPackageService {
    JobInterviewPackageView create(UUID userId, UUID packageId, UUID firstSessionId,
            UUID materialId, String idempotencyKey);
    Optional<JobInterviewPackageView> active(UUID userId);
    JobInterviewPackageView get(UUID userId, UUID packageId);
    JobInterviewPackageView startStage(UUID userId, UUID packageId,
            JobInterviewStage stage, UUID sessionId, String idempotencyKey);
}
```

Assert one `credit_balance - 3` plus a ledger row with `related_package_id`, or one Voucher redemption with no balance update. Same-key replay returns the same IDs with no repeated side effect; changed request content returns 409.

- [ ] **Step 2: Verify tests fail**

```powershell
.\mvnw.cmd '-Dtest=JdbcJobInterviewPackageServiceTest' test
```

- [ ] **Step 3: Implement one `@Transactional` creation method**

Order locks and writes as follows: replay → lock user → replay → validate ready `job` material → build bounded material snapshot → select Voucher or require 3 credits → insert Package → insert all Stage rows → ledger or Voucher update → insert uncharged first Session → bind technical-first Stage → insert first introduction Turn.

Never copy `resume_text` into `material_snapshot`; reuse existing normalization limits for summary, job title, JD summary and keywords.

- [ ] **Step 4: Run tests and commit**

```powershell
.\mvnw.cmd '-Dtest=JdbcJobInterviewPackageServiceTest' test
```

```powershell
git add -- Backend/src/main/java/icu/sakuracianna/mianba/interview/packageflow Backend/src/test/java/icu/sakuracianna/mianba/interview/packageflow/JdbcJobInterviewPackageServiceTest.java
```

```powershell
git commit -m "实现工作面试套餐原子创建"
```

## Task 4: Add owner-scoped APIs and lazy later-stage start

**Files:**
- Create: `Backend/src/main/java/icu/sakuracianna/mianba/interview/web/JobInterviewPackageController.java`
- Test: `Backend/src/test/java/icu/sakuracianna/mianba/interview/web/JobInterviewPackageControllerTest.java`
- Modify: `Backend/src/main/java/icu/sakuracianna/mianba/interview/packageflow/JdbcJobInterviewPackageService.java`

- [ ] **Step 1: Write MockMvc tests for create, active, get and start-stage**

```java
mockMvc.perform(post("/api/interview-packages")
        .header("Idempotency-Key", "package-create-0001")
        .contentType(MediaType.APPLICATION_JSON)
        .content("""
                {"package_id":"%s","first_session_id":"%s","material_id":"%s"}
                """.formatted(packageId, sessionId, materialId)))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.current_stage_code").value("TECHNICAL_FIRST"));
```

- [ ] **Step 2: Implement endpoints**

- `POST /api/interview-packages`
- `GET /api/interview-packages/active`
- `GET /api/interview-packages/{packageId}`
- `POST /api/interview-packages/{packageId}/stages/{stageCode}/start`

Accept only UUIDs and idempotency keys. Never accept owner, price, order, plan or snapshots from clients.

- [ ] **Step 3: Implement lazy start with Package → Stage locking**

Require Package `ACTIVE`, Stage `UNLOCKED`, no bound Session and unexpired package. Set Session expiry to `min(now + 24h, package expiry)`. Replays return the bound Session; another key cannot bind a second Session.

- [ ] **Step 4: Run and commit**

```powershell
.\mvnw.cmd '-Dtest=JobInterviewPackageControllerTest,JdbcJobInterviewPackageServiceTest' test
```

```powershell
git add -- Backend/src/main/java/icu/sakuracianna/mianba/interview/web/JobInterviewPackageController.java Backend/src/main/java/icu/sakuracianna/mianba/interview/packageflow Backend/src/test/java/icu/sakuracianna/mianba/interview/web/JobInterviewPackageControllerTest.java
```

```powershell
git commit -m "开放工作面试套餐阶段接口"
```

## Task 5: Route job products to packages and preserve other types

**Files:**
- Modify: `Backend/src/main/java/icu/sakuracianna/mianba/interview/service/JdbcInterviewService.java`
- Modify: `Backend/src/main/java/icu/sakuracianna/mianba/interview/web/InterviewProductController.java`
- Test: `Backend/src/test/java/icu/sakuracianna/mianba/interview/service/JdbcInterviewServiceStateTest.java`
- Test: `Backend/src/test/java/icu/sakuracianna/mianba/interview/web/InterviewControllerTest.java`

- [ ] **Step 1: Add a failing direct-job rejection test**

```java
assertThatThrownBy(() -> service.start(
        userId, UUID.randomUUID(), "job", materialId, "legacy-job-start"))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("工作面试请通过套餐流程开始");
```

- [ ] **Step 2: Add the guard before billing**

```java
if (type == InterviewType.JOB) {
    throw new ApiException(HttpStatus.CONFLICT,
            "job_interview_package_required", "工作面试请通过套餐流程开始");
}
```

Expose `package_required=true`, cost 3, stages and target durations in the job product. Keep postgraduate/civil-service/IELTS behavior unchanged.

- [ ] **Step 3: Run and commit**

```powershell
.\mvnw.cmd '-Dtest=JdbcInterviewServiceStateTest,InterviewControllerTest' test
```

```powershell
git add -- Backend/src/main/java/icu/sakuracianna/mianba/interview/service/JdbcInterviewService.java Backend/src/main/java/icu/sakuracianna/mianba/interview/web/InterviewProductController.java Backend/src/test/java/icu/sakuracianna/mianba/interview/service/JdbcInterviewServiceStateTest.java Backend/src/test/java/icu/sakuracianna/mianba/interview/web/InterviewControllerTest.java
```

```powershell
git commit -m "将工作面试入口切换为套餐流程"
```

## Task 6: Add package expiry, retention and verification

**Files:**
- Modify: `Backend/src/main/java/icu/sakuracianna/mianba/platform/maintenance/DataRetentionScheduler.java`
- Modify: `Backend/src/main/java/icu/sakuracianna/mianba/interview/service/SessionContentEraser.java`
- Test: corresponding existing test files.

- [ ] **Step 1: Test and implement bounded expiry/erasure**

Expired active Packages become `EXPIRED`; unfinished Stages become `EXPIRED`; no new Session starts. After the existing 90-day boundary, set Package `material_snapshot` and Stage `context_snapshot` to empty objects and record `content_erased_at`. Use indexed bounded batches.

- [ ] **Step 2: Run focused tests**

```powershell
.\mvnw.cmd '-Dtest=DataRetentionSchedulerTest,SessionContentEraserTest,JobInterviewPackageSchemaTest,JobInterviewPlanTest,JdbcJobInterviewPackageServiceTest,JobInterviewPackageControllerTest' test
```

Expected: PASS.

- [ ] **Step 3: Run backend verification**

```powershell
.\mvnw.cmd verify
```

Expected: 0 failures and successful JAR packaging.

- [ ] **Step 4: Inspect scope and commit**

```powershell
git status --short --branch
```

```powershell
git diff --check
```

```powershell
git add -- Backend/src/main/java/icu/sakuracianna/mianba/platform/maintenance/DataRetentionScheduler.java Backend/src/main/java/icu/sakuracianna/mianba/interview/service/SessionContentEraser.java Backend/src/test/java/icu/sakuracianna/mianba/platform/maintenance/DataRetentionSchedulerTest.java Backend/src/test/java/icu/sakuracianna/mianba/interview/service/SessionContentEraserTest.java
```

```powershell
git commit -m "补齐工作面试套餐过期与内容擦除"
```

The pre-existing AI edits must remain uncommitted and untouched until plan 2 incorporates them deliberately.
