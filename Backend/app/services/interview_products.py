from app.schemas.interviews import InterviewProduct, InterviewType


INTERVIEW_PRODUCTS: dict[InterviewType, InterviewProduct] = {
    InterviewType.JOB: InterviewProduct(
        id=InterviewType.JOB,
        name="工作面试",
        tagline="完整模拟求职面试链路",
        description="覆盖岗位专业面、项目深挖、表达稳定性和 HR 动机面。",
        rounds=["专业一面", "专业二面", "HR 面"],
        credit_cost=3,
        pricing_unit="complete_experience",
        report_focus=["专业能力", "项目表达", "追问承压", "岗位匹配", "HR 表达"],
    ),
    InterviewType.POSTGRADUATE: InterviewProduct(
        id=InterviewType.POSTGRADUATE,
        name="研究生复试面试",
        tagline="一次走完复试问答",
        description="围绕自我介绍、专业基础、科研兴趣、毕业设计和导师沟通进行模拟。",
        rounds=["复试模拟"],
        credit_cost=1,
        pricing_unit="single_session",
        report_focus=["专业基础", "科研潜力", "表达逻辑", "导师沟通"],
    ),
    InterviewType.CIVIL_SERVICE: InterviewProduct(
        id=InterviewType.CIVIL_SERVICE,
        name="考公面试",
        tagline="结构化表达和临场组织训练",
        description="覆盖综合分析、组织协调、人际沟通、应急应变等常见题型。",
        rounds=["结构化模拟"],
        credit_cost=1,
        pricing_unit="single_session",
        report_focus=["审题能力", "结构化表达", "公共视角", "语言稳定性"],
    ),
    InterviewType.IELTS: InterviewProduct(
        id=InterviewType.IELTS,
        name="雅思口语面试",
        tagline="Part 1 到 Part 3 的口语陪练",
        description="模拟雅思口语问答节奏，训练流利度、词汇、语法和观点展开。",
        rounds=["Part 1", "Part 2", "Part 3"],
        credit_cost=2,
        pricing_unit="speaking_test",
        report_focus=["fluency", "vocabulary", "grammar", "pronunciation", "coherence"],
    ),
}


def list_interview_products() -> list[InterviewProduct]:
    return list(INTERVIEW_PRODUCTS.values())


def get_interview_product(product_id: InterviewType) -> InterviewProduct:
    return INTERVIEW_PRODUCTS[product_id]
