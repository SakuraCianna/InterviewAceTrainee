# Job Interview Package Frontend and Release Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deliver the user-visible three-stage package flow, explicit base/enhancing/enhanced report states, refresh-safe stage continuation, history/admin compatibility, and production-grade verification.

**Architecture:** A focused `useJobInterviewPackage` hook owns package requests and recovery, while presentational package-progress and report-status components remain independent of the 1654-line room container. `InterviewRoom` selects package APIs only for `job` and preserves current standalone behavior for the other three types. Backend projections, smoke scripts and docs are updated in the same release slice.

**Tech Stack:** React 19, TypeScript 5.9 strict, Vite 7, Vitest 4, Testing Library, CSS Modules, Spring MockMvc, PowerShell verification, Bash CI smoke

---

## File map

- Modify `Frontend/src/pages/interview/types.ts` and `interviewApi.ts`.
- Create `hooks/useJobInterviewPackage.ts` and tests.
- Create package progress/report-status components with colocated CSS Modules and tests.
- Modify `InterviewRoom.tsx` only to compose the new hook/components and select job versus standalone API paths.
- Extend backend history/admin projections, CI smoke, verification matrix and user-facing docs.

## Task 1: Add typed package API contracts

**Files:**
- Modify: `Frontend/src/pages/interview/types.ts`
- Modify: `Frontend/src/pages/interview/interviewApi.ts`
- Modify: `Frontend/src/pages/interview/interviewApi.test.ts`

- [ ] **Step 1: Write failing request and response tests**

Assert endpoint, CSRF, idempotency reuse and payloads for package create/get/active/start-stage. Network retry must reuse the same key exactly as current Session start.

- [ ] **Step 2: Add strict types**

```ts
export type JobInterviewStageCode =
  | "TECHNICAL_FIRST"
  | "TECHNICAL_SECOND"
  | "HR_FINAL";

export type PackageStageStatus =
  | "LOCKED" | "UNLOCKED" | "IN_PROGRESS"
  | "COMPLETED" | "EXPIRED" | "CANCELLED";

export type ReportGenerationStatus =
  | "BASE_READY" | "ENHANCING" | "ENHANCED" | "ENHANCEMENT_FAILED";

export type JobInterviewPackage = {
  package_id: string;
  status: "ACTIVE" | "COMPLETED" | "EXPIRED" | "CANCELLED";
  current_stage_code: JobInterviewStageCode;
  expires_at: string;
  charged_credit: 0 | 3;
  voucher_used: boolean;
  stages: PackageStage[];
  current_session: InterviewStateResponse | null;
  report: InterviewReport | null;
};
```

Extend `InterviewReport` with `report_scope`, `generation_status`, `summary_source` and `current_revision`.

- [ ] **Step 3: Implement API functions**

```ts
export function createJobInterviewPackage(payload: {
  package_id: string;
  first_session_id: string;
  material_id: string;
}, options: OperationRequestOptions) {
  return retryNetworkOnce(
    () => requestJson<JobInterviewPackage>("/api/interview-packages", {
      method: "POST",
      credentials: "include",
      headers: csrfHeaders(jsonHeaders),
      idempotencyKey: options.idempotencyKey,
      signal: options.signal,
      body: JSON.stringify(payload),
    }),
    options.signal,
  );
}

export function getActiveJobInterviewPackage(signal?: AbortSignal) {
  return requestJson<JobInterviewPackage | null>(
    "/api/interview-packages/active", { ...credentials, signal }, null,
  );
}

export function getJobInterviewPackage(packageId: string, signal?: AbortSignal) {
  return requestJson<JobInterviewPackage>(
    `/api/interview-packages/${encodeURIComponent(packageId)}`,
    { ...credentials, signal },
  );
}

export function startJobInterviewStage(packageId: string, stage: JobInterviewStageCode,
  sessionId: string, options: OperationRequestOptions) {
  return retryNetworkOnce(
    () => requestJson<JobInterviewPackage>(
      `/api/interview-packages/${encodeURIComponent(packageId)}/stages/${stage}/start`,
      {
        method: "POST",
        credentials: "include",
        headers: csrfHeaders(jsonHeaders),
        idempotencyKey: options.idempotencyKey,
        signal: options.signal,
        body: JSON.stringify({ session_id: sessionId }),
      },
    ),
    options.signal,
  );
}
```

- [ ] **Step 4: Run and commit**

```powershell
npm test -- --run src/pages/interview/interviewApi.test.ts
```

```powershell
git add -- Frontend/src/pages/interview/types.ts Frontend/src/pages/interview/interviewApi.ts Frontend/src/pages/interview/interviewApi.test.ts
```

```powershell
git commit -m "接入工作面试套餐前端接口"
```

## Task 2: Build refresh-safe package state management

**Files:**
- Create: `Frontend/src/pages/interview/hooks/useJobInterviewPackage.ts`
- Create: `Frontend/src/pages/interview/hooks/useJobInterviewPackage.test.tsx`

- [ ] **Step 1: Write hook tests**

Cover initial active-package recovery, create with stable IDs/key, start-stage double-click suppression, abort on unmount, network replay, stale response rejection and report enhancement polling.

```ts
expect(createPackage).toHaveBeenCalledTimes(1);
expect(createPackage).toHaveBeenCalledWith(
  expect.objectContaining({ package_id: packageId, first_session_id: sessionId }),
  expect.objectContaining({ idempotencyKey }),
);
```

- [ ] **Step 2: Implement the hook with refs for operation identity**

Return `packageState`, `currentSession`, `isLoading`, `isStartingStage`, `error`, `create`, `startStage`, `refresh` and `clear`. Keep generated package/session IDs and idempotency keys stable until success or authoritative conflict reconciliation.

When `generation_status === "ENHANCING"`, poll package/session state with the existing visibility/offline behavior; do not treat enhancement failure as an interview failure.

- [ ] **Step 3: Run and commit**

```powershell
npm test -- --run src/pages/interview/hooks/useJobInterviewPackage.test.tsx
```

```powershell
git add -- Frontend/src/pages/interview/hooks/useJobInterviewPackage.ts Frontend/src/pages/interview/hooks/useJobInterviewPackage.test.tsx
```

```powershell
git commit -m "实现工作面试套餐状态管理"
```

## Task 3: Add package progress and report enhancement UI

**Files:**
- Create: `Frontend/src/pages/interview/components/JobInterviewPackageProgress.tsx`
- Create: `Frontend/src/pages/interview/components/JobInterviewPackageProgress.module.css`
- Create: `Frontend/src/pages/interview/components/JobInterviewPackageProgress.test.tsx`
- Create: `Frontend/src/pages/interview/components/ReportEnhancementStatus.tsx`
- Create: `Frontend/src/pages/interview/components/ReportEnhancementStatus.module.css`
- Create: `Frontend/src/pages/interview/components/ReportEnhancementStatus.test.tsx`
- Modify: `Frontend/src/pages/interview/components/InterviewReport.tsx`

- [ ] **Step 1: Write accessible component tests**

Verify stage labels, locked/current/completed semantics, target time, turn range, start-next callback, 30-day deadline and all four report generation states. Locked stages must not expose an enabled start button.

- [ ] **Step 2: Implement package progress as a pure component**

```tsx
<ol aria-label="工作面试套餐进度">
  {stages.map((stage) => (
    <li key={stage.stage_code} data-status={stage.status}>
      <strong>{STAGE_LABELS[stage.stage_code]}</strong>
      <span>{stage.min_turns}–{stage.max_turns} 轮 · 约 {stage.target_duration_minutes} 分钟</span>
      {stage.status === "UNLOCKED" && <button onClick={() => onStart(stage.stage_code)}>开始</button>}
    </li>
  ))}
</ol>
```

- [ ] **Step 3: Implement non-blocking report status**

- `BASE_READY`: “基础复盘已生成”。
- `ENHANCING`: “AI 深度分析生成中”，keep report visible.
- `ENHANCED`: “AI 深度分析已更新”。
- `ENHANCEMENT_FAILED`: “深度分析暂未完成，当前复盘仍可正常使用”。

Do not call the base report incomplete or temporary.

- [ ] **Step 4: Run and commit**

```powershell
npm test -- --run src/pages/interview/components/JobInterviewPackageProgress.test.tsx src/pages/interview/components/ReportEnhancementStatus.test.tsx src/pages/interview/components/InterviewReport.test.tsx
```

```powershell
git add -- Frontend/src/pages/interview/components
```

```powershell
git commit -m "展示套餐进度与报告增强状态"
```

## Task 4: Integrate package flow without expanding InterviewRoom responsibilities

**Files:**
- Modify: `Frontend/src/pages/interview/InterviewRoom.tsx`
- Modify: `Frontend/src/pages/interview/InterviewRoom.module.css` only for container placement.
- Create: `Frontend/src/pages/interview/InterviewRoom.packageFlow.test.tsx`
- Modify: `Frontend/src/pages/interview/InterviewRoom.materialUpload.test.tsx`

- [ ] **Step 1: Write end-user flow tests**

Test job selection → material upload → package create → technical-first Session; completion shows base report plus unlocked technical second; starting it uses package stage endpoint. Test that postgraduate/civil-service/IELTS still call `/api/interviews` directly.

- [ ] **Step 2: Compose the new hook**

Replace only the `job` start branch:

```ts
if (selectedModule.type === "job") {
  await packageFlow.create({ materialId: currentMaterial.id });
} else {
  await startStandaloneInterview(selectedModule.type, currentMaterial?.id);
}
```

Render `JobInterviewPackageProgress` above the current Session panel. Keep audio, ASR, TTS, answer submission and `useInterviewTask` unchanged for the bound Session.

- [ ] **Step 3: Reconcile authoritative state after primary task success**

Refresh the Package first, then use its `current_session`; never infer next-stage unlock from the task payload alone. Show the completed Stage report immediately while the report enhancement task proceeds independently.

- [ ] **Step 4: Run and commit**

```powershell
npm test -- --run src/pages/interview/InterviewRoom.packageFlow.test.tsx src/pages/interview/InterviewRoom.materialUpload.test.tsx
```

```powershell
git add -- Frontend/src/pages/interview/InterviewRoom.tsx Frontend/src/pages/interview/InterviewRoom.module.css Frontend/src/pages/interview/InterviewRoom.packageFlow.test.tsx Frontend/src/pages/interview/InterviewRoom.materialUpload.test.tsx
```

```powershell
git commit -m "串联三阶段工作面试套餐体验"
```

## Task 5: Make history and admin views package-aware

**Files:**
- Modify: backend package/history/admin service projections and their existing tests.
- Modify: `Frontend/src/pages/admin/types.ts`
- Modify: `Frontend/src/pages/admin/AdminShell.tsx`
- Modify: history rendering within `InterviewRoom.tsx` or extract a focused history component if the change exceeds 40 lines.

- [ ] **Step 1: Add backend projection tests**

History returns one Package row for new job flows with stage completion summary and package score; it does not show three duplicate top-level job entries. Admin Session detail keeps all three linked Sessions and report revisions but never returns raw `input_ref`, Prompt or resume text.

- [ ] **Step 2: Implement bounded owner/admin queries**

Add package history endpoint or a discriminated history projection with `entry_type: "SESSION" | "PACKAGE"`. Keep legacy job Sessions readable as Session entries.

- [ ] **Step 3: Add frontend rendering tests and implementation**

Display “工作面试套餐”, `2/3 场已完成`, current Stage and package score. Admin AI call rows show operation, validation status, model, tokens and latency.

- [ ] **Step 4: Run and commit**

```powershell
.\mvnw.cmd '-Dtest=AdminServiceTest,AdminControllerTest' test
```

```powershell
npm test -- --run
```

```powershell
git add -- Backend/src/main/java/icu/sakuracianna/mianba/admin Backend/src/test/java/icu/sakuracianna/mianba/admin Frontend/src/pages/admin Frontend/src/pages/interview
```

```powershell
git commit -m "补齐套餐历史与管理端观测"
```

## Task 6: Update product copy, contracts and operational verification

**Files:**
- Modify: `Frontend/src/pages/interview/modules.ts`
- Modify: `Frontend/src/pages/HomePage.tsx`
- Modify: `需求文档.md`
- Modify: `操作手册.md` if package recovery changes support steps.
- Modify: `docs/refactor/async-task-contract.md`
- Modify: `docs/refactor/verification-matrix.md`
- Modify: `deploy/ci-business-smoke.sh`
- Modify: `Backend/src/main/java/icu/sakuracianna/mianba/platform/web/HealthController.java` only if readiness needs a package/report invariant probe.

- [ ] **Step 1: Correct user-visible capability claims**

Describe three independent ordered Sessions, one-time 3-credit or whole-package Voucher billing, dynamic stage durations, spoken algorithm reasoning and non-blocking report enhancement. Remove the IELTS “pronunciation score from transcript” promise.

- [ ] **Step 2: Update the async task contract**

Document `GENERATE_REPORT` as public internal capability, its report-target input, independent retry/lease behavior and rule that report failure cannot change Session/Package completion.

- [ ] **Step 3: Extend the business smoke**

Using existing safe CI fixtures, verify: create job Package once, bill once, complete/unlock order, lazy next Session, HR package completion, base reports, report enhancement failure fallback, Rabbit messages without resume/answer/report bodies, and owner isolation.

- [ ] **Step 4: Run documentation/config checks and commit**

```powershell
.\scripts\check-env-schema.ps1
```

```powershell
.\scripts\check-frontend-config.ps1
```

```powershell
git add -- Frontend/src/pages/interview/modules.ts Frontend/src/pages/HomePage.tsx 需求文档.md 操作手册.md docs/refactor/async-task-contract.md docs/refactor/verification-matrix.md deploy/ci-business-smoke.sh Backend/src/main/java/icu/sakuracianna/mianba/platform/web/HealthController.java
```

```powershell
git commit -m "更新工作面试套餐产品与运维文档"
```

## Task 7: Full verification, reviewer and release handoff

**Files:**
- Modify only task-scoped files when verification or reviewer findings require fixes.

- [ ] **Step 1: Run the repository check entrypoint**

```powershell
.\scripts\check-all.ps1
```

Expected: backend verify, npm audit, 0 failed frontend tests, typecheck and Vite build all PASS.

- [ ] **Step 2: Run dedicated integration verification**

```powershell
Push-Location Backend
.\mvnw.cmd -Pintegration verify
Pop-Location
```

Expected: PostgreSQL migration/constraint/Outbox/package tests PASS.

- [ ] **Step 3: Validate Compose without using production secrets**

Use only `deploy/compose.env.example` through the existing CI-safe validation path. Do not overwrite the real root `.env`.

```powershell
.\scripts\check-env-schema.ps1
```

- [ ] **Step 4: Inspect final diff and line endings**

```powershell
git status --short --branch
```

```powershell
git diff --check
```

Expected: only planned files; non-Windows scripts and source remain LF, `.cmd` remains CRLF.

- [ ] **Step 5: Dispatch the required independent reviewer**

The reviewer must check requirement coverage, migration safety, billing/voucher idempotency, ownership, lock order, prompt injection, report fallback, type safety, performance, compatibility, tests and docs. Required conclusion: `通过`, `需要修改`, or `存在风险但可接受`.

- [ ] **Step 6: Fix reviewer findings and rerun affected plus full checks**

Do not dismiss findings without evidence. Record any intentionally deferred issue and its risk.

- [ ] **Step 7: Create the final verification commit if fixes exist**

```powershell
git add -- Backend Frontend deploy docs 需求文档.md 操作手册.md
```

```powershell
git commit -m "完成工作面试套餐全链路验证"
```

Skip when no verification/reviewer changes exist. Do not push or deploy without a separate explicit instruction.
