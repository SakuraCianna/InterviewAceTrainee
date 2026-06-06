import unittest
from datetime import datetime

from app.api.interviews import answer_interview_question, build_next_question_override
from app.core.config import Settings
from app.schemas.interviews import InterviewAnswerRequest, InterviewType
from app.services.ai_call_logs import InMemoryAICallLogStore
from app.services.ai_router import AIProviderConfig
from app.services.content_safety_logs import InMemoryContentSafetyLogStore
from app.services.interview_material_context import InterviewMaterialContext
from app.services.interview_ai import assess_answer_quality
from app.services.interview_question_bank import _select_steps, question_bank_inventory, postgraduate_school_tier
from app.services.interview_runtime import InMemoryInterviewRuntimeStore, build_interview_steps
from app.services.provider_configs import InMemoryProviderConfigStore


class InterviewFlowTests(unittest.TestCase):
    def disabled_provider_store(self) -> InMemoryProviderConfigStore:
        return InMemoryProviderConfigStore(
            [
                AIProviderConfig(
                    id="disabled-test-llm",
                    provider_type="llm",
                    purpose="interview",
                    enabled=False,
                    priority=10,
                    provider_name="deepseek",
                    model_name="deepseek-v4-flash",
                )
            ]
        )

    def job_context(self, job_title: str, job_requirements: str, resume_text: str, keywords: list[str]) -> InterviewMaterialContext:
        return InterviewMaterialContext(
            id=f"material-{job_title}",
            user_email="candidate@example.com",
            interview_type=InterviewType.JOB,
            resume_filename="resume.pdf",
            resume_content_type="application/pdf",
            resume_text=resume_text,
            job_title=job_title,
            job_requirements=job_requirements,
            target_school=None,
            major=None,
            research_direction=None,
            profile_summary=resume_text,
            keywords=keywords,
            created_at=datetime(2026, 6, 5),
        )

    def postgraduate_context(
        self,
        target_school: str,
        major: str = "计算机科学与技术",
        research_direction: str = "大模型教育应用",
    ) -> InterviewMaterialContext:
        return InterviewMaterialContext(
            id=f"material-{target_school}-{major}",
            user_email="student@example.com",
            interview_type=InterviewType.POSTGRADUATE,
            resume_filename=None,
            resume_content_type=None,
            resume_text=None,
            job_title=None,
            job_requirements=None,
            target_school=target_school,
            major=major,
            research_direction=research_direction,
            profile_summary="目标院校与报考专业已填写。",
            keywords=[major, research_direction],
            created_at=datetime(2026, 6, 5),
        )

    def test_filler_answer_stays_on_current_question(self) -> None:
        decision = assess_answer_quality(
            InterviewType.POSTGRADUATE,
            "请做一段 90 秒左右的中文自我介绍。",
            "你好你好",
            "复试开场",
        )

        self.assertFalse(decision.acceptable)
        self.assertEqual(decision.reason_code, "filler")
        self.assertIn("不会进入下一题", decision.retry_question or "")

    def test_generic_job_answer_requires_structured_signals(self) -> None:
        decision = assess_answer_quality(
            InterviewType.JOB,
            "请介绍一个最能证明你能胜任这个岗位的项目。",
            "我之前参加过一个项目，整体过程比较顺利，我也比较努力，和团队配合得还可以，最后大家觉得效果不错。",
            "专业一面",
        )

        self.assertFalse(decision.acceptable)
        self.assertEqual(decision.reason_code, "too_generic")
        self.assertIn("岗位匹配", decision.retry_question or "")

    def test_preset_question_bank_separates_exam_scenarios(self) -> None:
        civil_steps = build_interview_steps(InterviewType.CIVIL_SERVICE, session_id="civil-bank-case")
        ielts_steps = build_interview_steps(InterviewType.IELTS, session_id="ielts-bank-case")

        self.assertIn("基层", civil_steps[0].question_text)
        self.assertIn("群众", civil_steps[0].question_text)
        self.assertTrue(ielts_steps[0].question_text.startswith("Let's talk"))
        self.assertNotEqual(civil_steps[0].question_text, ielts_steps[0].question_text)

    def test_postgraduate_question_difficulty_follows_school_tier(self) -> None:
        elite_steps = build_interview_steps(
            InterviewType.POSTGRADUATE,
            self.postgraduate_context("清华大学"),
            session_id="postgraduate-elite-case",
        )
        standard_steps = build_interview_steps(
            InterviewType.POSTGRADUATE,
            self.postgraduate_context("普通地方学院"),
            session_id="postgraduate-standard-case",
        )

        self.assertIn("顶尖院校复试", elite_steps[0].question_text)
        self.assertIn("文献差异", elite_steps[3].question_text)
        self.assertIn("基础复试", standard_steps[0].question_text)
        self.assertNotEqual(elite_steps[1].question_text, standard_steps[1].question_text)

    def test_postgraduate_school_tier_uses_formal_school_config(self) -> None:
        self.assertEqual(postgraduate_school_tier("北京大学"), "elite")
        self.assertEqual(postgraduate_school_tier("华南理工大学"), "high")
        self.assertEqual(postgraduate_school_tier("湖南师范大学"), "advanced")
        self.assertEqual(postgraduate_school_tier("普通地方学院"), "standard")

    def test_question_bank_is_rich_and_ordered_by_difficulty(self) -> None:
        inventory = question_bank_inventory()
        expected_min_choices = {
            "job": 14,
            "postgraduate": 16,
            "civil_service": 14,
            "ielts": 15,
        }

        for scenario, min_choices in expected_min_choices.items():
            self.assertIn(scenario, inventory)
            for round_info in inventory[scenario]["rounds"]:
                self.assertGreaterEqual(
                    round_info["choice_count"],
                    min_choices,
                    f"{scenario} {round_info['round_name']} choices are too thin",
                )
            self.assertEqual(
                inventory[scenario]["difficulty_scores"],
                sorted(inventory[scenario]["difficulty_scores"]),
                f"{scenario} questions must progress from easier to harder",
            )
            if scenario != "ielts":
                self.assertGreaterEqual(
                    inventory[scenario]["min_question_chars"],
                    28,
                    f"{scenario} question bank contains rough short questions",
                )

    def test_question_bank_does_not_select_same_question_twice_in_one_session(self) -> None:
        steps = _select_steps(
            [
                ("第一轮", "easy", ["请说明你如何定位线上问题？", "请介绍一次你拆解复杂任务的经历？"]),
                ("第二轮", "medium", ["请说明你如何定位线上问题？", "请说明你如何用指标复盘交付结果？"]),
            ],
            seed="case",
        )

        self.assertEqual(steps[0].question_text, "请说明你如何定位线上问题？")
        self.assertEqual(steps[1].question_text, "请说明你如何用指标复盘交付结果？")

    def test_four_scenario_flow_uses_distinct_formal_stage_logic(self) -> None:
        job_steps = build_interview_steps(
            InterviewType.JOB,
            self.job_context(
                job_title="Python 后端工程师",
                job_requirements="负责 FastAPI、PostgreSQL、Redis 和线上稳定性。",
                resume_text="智能客服 RAG 项目：我负责 FastAPI 接口、Redis 缓存和接口延迟优化。",
                keywords=["FastAPI", "Redis", "线上稳定性"],
            ),
            session_id="job-formal-flow",
        )
        postgraduate_steps = build_interview_steps(
            InterviewType.POSTGRADUATE,
            self.postgraduate_context("浙江大学", major="法学", research_direction="民商法案例研究"),
            session_id="postgraduate-formal-flow",
        )
        civil_steps = build_interview_steps(InterviewType.CIVIL_SERVICE, session_id="civil-formal-flow")
        ielts_steps = build_interview_steps(InterviewType.IELTS, session_id="ielts-formal-flow")

        self.assertEqual(
            [step.round_name for step in job_steps],
            ["岗位理解", "项目证据", "方案取舍", "指标复盘", "根因定位", "压力追问", "协作与动机", "终面收束"],
        )
        self.assertEqual(
            [step.round_name for step in postgraduate_steps],
            ["复试开场", "专业基础", "项目与科研潜力", "文献与英文", "导师方向适配", "学术规范与压力"],
        )
        self.assertEqual(
            [step.round_name for step in civil_steps],
            ["岗位认知", "综合分析", "计划组织", "人际沟通", "应急处置", "现场追问收束"],
        )
        self.assertEqual(
            [step.round_name for step in ielts_steps],
            ["Part 1", "Part 1", "Part 1 Follow-up", "Part 2", "Part 2 Follow-up", "Part 3", "Part 3 Follow-up"],
        )
        self.assertIn("智能客服 RAG 项目", " ".join(step.question_text for step in job_steps))
        self.assertIn("法条体系", " ".join(step.question_text for step in postgraduate_steps))
        self.assertIn("追问", civil_steps[-1].question_text)
        self.assertIn("you described", ielts_steps[4].question_text.lower())

    def test_civil_service_slogan_answer_requires_concrete_execution(self) -> None:
        decision = assess_answer_quality(
            InterviewType.CIVIL_SERVICE,
            "某地群众对新政策理解不一致并出现情绪波动，你会如何处理？",
            "我会坚持为人民服务，保持耐心，听从领导安排，积极沟通协调，把工作做好，维护群众利益。",
            "应急处置",
        )

        self.assertFalse(decision.acceptable)
        self.assertEqual(decision.reason_code, "too_generic")
        self.assertIn("具体措施", decision.retry_question or "")

    def test_question_bank_tracks_independent_role_and_major_banks(self) -> None:
        inventory = question_bank_inventory()

        self.assertGreaterEqual(inventory["job"]["role_bank_count"], 200)
        self.assertGreaterEqual(inventory["postgraduate"]["major_bank_count"], 100)

    def test_job_questions_are_specialized_by_career_and_user_project(self) -> None:
        backend_context = self.job_context(
            job_title="Python 后端工程师",
            job_requirements="负责 FastAPI 服务、PostgreSQL 建模、Redis 缓存、RAG 检索链路和接口稳定性。",
            resume_text="智能客服 RAG 项目：我负责检索链路、向量库召回、Redis 缓存和接口延迟优化。",
            keywords=["FastAPI", "Redis", "RAG", "向量库"],
        )
        design_context = self.job_context(
            job_title="UI/UX 设计师",
            job_requirements="负责用户旅程、交互逻辑、设计系统、可用性验证和高保真原型。",
            resume_text="招聘后台体验改版项目：我负责用户旅程梳理、组件规范和可用性测试。",
            keywords=["用户旅程", "设计系统", "可用性验证"],
        )

        backend_questions = " ".join(
            step.question_text
            for step in build_interview_steps(InterviewType.JOB, backend_context, session_id="backend-project-case")
        )
        design_questions = " ".join(
            step.question_text
            for step in build_interview_steps(InterviewType.JOB, design_context, session_id="designer-project-case")
        )

        self.assertIn("智能客服 RAG 项目", backend_questions)
        self.assertIn("接口延迟", backend_questions)
        self.assertIn("FastAPI", backend_questions)
        self.assertIn("用户旅程", design_questions)
        self.assertIn("设计系统", design_questions)
        self.assertNotIn("Redis 缓存", design_questions)
        self.assertNotIn("招聘专员", design_questions)
        self.assertNotIn("岗位画像", design_questions)
        self.assertNotIn("保密合规", design_questions)
        self.assertNotIn("用户、业务和团队", design_questions)
        self.assertNotIn("用户旅程、交互逻辑、设计系统、可用性验证、用户旅程", design_questions)

    def test_job_preset_matching_prioritizes_target_title_over_resume_domain(self) -> None:
        design_context = self.job_context(
            job_title="UI/UX 设计师",
            job_requirements="负责用户旅程、交互逻辑、设计系统、可用性验证和高保真原型。",
            resume_text="招聘后台体验改版项目：我负责用户旅程梳理、组件规范和可用性测试。",
            keywords=["用户旅程", "设计系统", "可用性验证"],
        )

        questions = " ".join(
            step.question_text
            for step in build_interview_steps(InterviewType.JOB, design_context, session_id="designer-priority-case")
        )

        self.assertIn("UI/UX 设计师", questions)
        self.assertIn("用户旅程", questions)
        self.assertIn("设计系统", questions)
        self.assertNotIn("招聘专员", questions)
        self.assertNotIn("岗位画像", questions)
        self.assertNotIn("保密合规", questions)
        self.assertNotIn("用户、业务和团队", questions)
        self.assertNotIn("用户旅程、交互逻辑、设计系统、可用性验证、用户旅程", questions)

    def test_healthcare_job_questions_use_patient_care_language(self) -> None:
        nurse_context = self.job_context(
            job_title="临床护士",
            job_requirements="负责病区护理、医嘱执行、患者沟通、护理文书和风险预警。",
            resume_text="三甲医院实习：我负责生命体征记录、患者宣教和护理交接。",
            keywords=["护理", "医嘱", "患者沟通", "风险预警"],
        )

        questions = " ".join(
            step.question_text
            for step in build_interview_steps(InterviewType.JOB, nurse_context, session_id="job-nurse-audit")
        )

        self.assertIn("患者", questions)
        self.assertIn("护理", questions)
        self.assertIn("医嘱", questions)
        self.assertNotIn("用户、业务和团队", questions)
        self.assertNotIn("技术或业务取舍", questions)
        self.assertNotIn("业务方", questions)

    def test_postgraduate_questions_are_specialized_by_major(self) -> None:
        computer_questions = " ".join(
            step.question_text
            for step in build_interview_steps(
                InterviewType.POSTGRADUATE,
                self.postgraduate_context("清华大学", major="计算机科学与技术", research_direction="大模型教育应用"),
                session_id="postgraduate-computer-case",
            )
        )
        law_questions = " ".join(
            step.question_text
            for step in build_interview_steps(
                InterviewType.POSTGRADUATE,
                self.postgraduate_context("清华大学", major="法学", research_direction="民商法案例研究"),
                session_id="postgraduate-law-case",
            )
        )

        self.assertIn("数据结构与算法复杂度", computer_questions)
        self.assertIn("模型评估", computer_questions)
        self.assertIn("法条体系", law_questions)
        self.assertIn("法律适用", law_questions)
        self.assertNotIn("技术路线、实验验证", law_questions)
        self.assertNotIn("工程实现", law_questions)
        self.assertNotEqual(computer_questions, law_questions)

    def test_postgraduate_humanities_questions_avoid_engineering_experiment_frame(self) -> None:
        law_questions = " ".join(
            step.question_text
            for step in build_interview_steps(
                InterviewType.POSTGRADUATE,
                self.postgraduate_context("浙江大学", major="法学", research_direction="民商法案例研究"),
                session_id="pg-law-audit",
            )
        )

        self.assertIn("法条体系", law_questions)
        self.assertIn("案例争点", law_questions)
        self.assertNotIn("技术路线", law_questions)
        self.assertNotIn("实验验证", law_questions)
        self.assertNotIn("工程实现", law_questions)

    def test_adaptive_followup_uses_seeded_next_question_from_session(self) -> None:
        store = InMemoryInterviewRuntimeStore()
        material_context = self.job_context(
            job_title="Python 后端工程师",
            job_requirements="负责 FastAPI 服务、PostgreSQL 建模、Redis 缓存和接口稳定性。",
            resume_text="智能客服 RAG 项目：我负责检索链路、向量库召回、Redis 缓存和接口延迟优化。",
            keywords=["FastAPI", "Redis", "RAG"],
        )
        state = store.create_session(
            "candidate@example.com",
            "seeded-followup-session",
            InterviewType.JOB,
            material_context,
        )
        expected_next_question = state.next_question

        followup_question = build_next_question_override(
            current_state=state,
            answer_text="我负责智能客服 RAG 项目的 FastAPI 接口、Redis 缓存和向量库召回优化，主要目标是降低接口延迟并提升回答命中率。",
            provider_store=self.disabled_provider_store(),
            ai_call_log_store=InMemoryAICallLogStore(),
            content_safety_log_store=InMemoryContentSafetyLogStore(),
            settings=Settings(),
            user_email="candidate@example.com",
        )

        self.assertIsNotNone(expected_next_question)
        self.assertIsNotNone(followup_question)
        self.assertIn(expected_next_question.text[:36], followup_question or "")

    def test_answer_api_requires_supplement_before_advancing(self) -> None:
        store = InMemoryInterviewRuntimeStore()
        store.create_session("student@example.com", "retry-session", InterviewType.POSTGRADUATE)
        claims = {"sub": "student@example.com", "role": "user", "session_id": "auth-session"}

        retry_response = answer_interview_question(
            session_id="retry-session",
            payload=InterviewAnswerRequest(answer_text="你好你好"),
            claims=claims,
            interview_store=store,
            provider_store=self.disabled_provider_store(),
            ai_call_log_store=InMemoryAICallLogStore(),
            content_safety_log_store=InMemoryContentSafetyLogStore(),
            settings=Settings(),
        )

        self.assertEqual(retry_response.status, "in_progress")
        self.assertEqual(retry_response.current_step_index, 0)
        self.assertIsNotNone(retry_response.current_question)
        self.assertIn("不会进入下一题", retry_response.current_question.text)

        accepted_response = answer_interview_question(
            session_id="retry-session",
            payload=InterviewAnswerRequest(
                answer_text=(
                    "我本科阶段系统学习过数据结构、计算机网络和机器学习课程，也做过智能教育方向的课程项目。"
                    "报考这个专业是因为我希望继续研究大模型在学习反馈和个性化训练中的应用。"
                )
            ),
            claims=claims,
            interview_store=store,
            provider_store=self.disabled_provider_store(),
            ai_call_log_store=InMemoryAICallLogStore(),
            content_safety_log_store=InMemoryContentSafetyLogStore(),
            settings=Settings(),
        )

        self.assertEqual(accepted_response.current_step_index, 1)
        self.assertIsNotNone(accepted_response.current_question)
        self.assertEqual(accepted_response.current_question.round_name, "专业基础")
        self.assertIn("你刚才提到", accepted_response.current_question.text)

    def test_complete_interview_flow_keeps_questions_unique_and_generates_report(self) -> None:
        store = InMemoryInterviewRuntimeStore()
        material_context = self.job_context(
            job_title="Python 后端工程师",
            job_requirements="负责 FastAPI、PostgreSQL、Redis、RAG 检索链路、接口稳定性和线上问题定位。",
            resume_text="智能客服 RAG 项目：我负责 FastAPI 接口、Redis 缓存、PostgreSQL 索引和向量召回优化。",
            keywords=["FastAPI", "Redis", "PostgreSQL", "RAG"],
        )
        initial_state = store.create_session(
            "candidate@example.com",
            "complete-job-flow",
            InterviewType.JOB,
            material_context,
        )
        claims = {"sub": "candidate@example.com", "role": "user", "session_id": "auth-session"}
        answer_text = (
            "我负责过智能客服 RAG 项目，背景是岗位 JD 要求接口稳定性和业务交付。"
            "我负责 FastAPI 接口、Redis 缓存、PostgreSQL 索引和向量召回优化，"
            "先比较方案成本、事务边界和上线风险，再用日志指标、延迟数据和用户反馈复盘结果，"
            "最后把问题定位流程沉淀给团队。"
        )

        seen_questions: list[str] = []
        response = None
        for _ in range(initial_state.total_steps):
            current_state = store.get_session("candidate@example.com", "complete-job-flow")
            self.assertIsNotNone(current_state)
            self.assertIsNotNone(current_state.current_question)
            seen_questions.append(current_state.current_question.text)
            response = answer_interview_question(
                session_id="complete-job-flow",
                payload=InterviewAnswerRequest(answer_text=answer_text),
                claims=claims,
                interview_store=store,
                provider_store=self.disabled_provider_store(),
                ai_call_log_store=InMemoryAICallLogStore(),
                content_safety_log_store=InMemoryContentSafetyLogStore(),
                settings=Settings(),
            )

        self.assertIsNotNone(response)
        self.assertEqual(response.status, "completed")
        self.assertIsNotNone(response.report)
        self.assertEqual(len(response.report.turns), initial_state.total_steps)
        normalized_questions = ["".join(question.split()).lower() for question in seen_questions]
        self.assertEqual(len(normalized_questions), len(set(normalized_questions)))


if __name__ == "__main__":
    unittest.main()
