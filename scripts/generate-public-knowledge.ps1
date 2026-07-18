$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$workspaceRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot "..")).Path
$targetRoot = Join-Path $workspaceRoot "Backend\src\main\resources\knowledge-base"
$resolvedParent = (Resolve-Path -LiteralPath (Split-Path -Parent $targetRoot)).Path
if (-not $resolvedParent.StartsWith($workspaceRoot, [StringComparison]::OrdinalIgnoreCase)) {
    throw "知识库目标不在当前工作区内"
}

$jobRoot = Join-Path $targetRoot "job"
$postgraduateRoot = Join-Path $targetRoot "postgraduate"
New-Item -ItemType Directory -Force -Path $jobRoot, $postgraduateRoot | Out-Null
$utf8NoBom = [Text.UTF8Encoding]::new($false)

$officialOccupationSource = "https://srsj.cngy.gov.cn/Files/UploadFile/SiteFile/20160720104022026/2026/04/14/8a8384d8e7d34d90b1a8e191a4f585cb.pdf"
$marketSource = "https://hrss.sz.gov.cn/attachment/1/1703/1703629/12735120.pdf"
$blsSource = "https://www.bls.gov/ooh/"
$salesSource = "https://www.bls.gov/ooh/sales/"
$customerServiceSource = "https://www.bls.gov/ooh/office-and-administrative-support/customer-service-representatives.htm"
$postgraduatePolicySource = "https://www.moe.gov.cn/srcsite/A15/moe_778/s3261/202509/t20250918_1413836.html"
$postgraduateWorkSource = "https://www.moe.gov.cn/jyb_xwfb/gzdt_gzdt/s5987/202602/t20260228_1429541.html"

$roles = @(
    [PSCustomObject]@{ Slug="backend-engineer"; Title="后端开发工程师"; Category="technology"; Responsibilities="设计可靠接口、数据模型和并发处理，定位线上故障并控制变更风险"; Skills="Java 或同类语言、数据库、缓存、消息队列、可观测性与安全边界"; Scenario="订单接口在流量增长后出现超时和重复扣款" },
    [PSCustomObject]@{ Slug="frontend-engineer"; Title="前端开发工程师"; Category="technology"; Responsibilities="实现可访问、可维护且性能稳定的用户界面，管理状态和接口异常"; Skills="TypeScript、组件设计、浏览器原理、性能、测试和无障碍"; Scenario="核心表单在弱网与移动端出现重复提交和布局抖动" },
    [PSCustomObject]@{ Slug="mobile-engineer"; Title="移动端开发工程师"; Category="technology"; Responsibilities="构建稳定的移动应用，处理设备差异、离线状态、耗电和发布质量"; Skills="Android 或 iOS、网络、存储、生命周期、监控和灰度发布"; Scenario="新版本在部分低端设备上启动变慢并偶发崩溃" },
    [PSCustomObject]@{ Slug="test-engineer"; Title="测试开发工程师"; Category="technology"; Responsibilities="识别质量风险、设计分层测试并建设可持续的自动化反馈"; Skills="测试策略、接口自动化、端到端测试、性能测试和缺陷分析"; Scenario="支付链路迭代频繁但回归窗口只有半天" },
    [PSCustomObject]@{ Slug="data-engineer"; Title="数据工程师"; Category="technology"; Responsibilities="建设可信数据管道、口径和质量监控，保障数据及时性与可追溯性"; Skills="SQL、批流处理、数据建模、调度、质量和权限治理"; Scenario="经营日报连续两天出现指标迟到且各部门口径不一致" },
    [PSCustomObject]@{ Slug="ai-engineer"; Title="AI 应用工程师"; Category="technology"; Responsibilities="把模型能力转化为可评测、可观测、可降级的业务功能"; Skills="检索增强、提示词、评测、内容安全、成本和推理部署"; Scenario="智能问答召回准确但回答仍会引用错误版本政策" },

    [PSCustomObject]@{ Slug="b2b-sales"; Title="B2B 销售顾问"; Category="sales"; Responsibilities="发现客户业务问题、建立价值共识并推动多角色决策链完成采购"; Skills="需求发现、价值量化、异议处理、商务推进和客户成功协同"; Scenario="客户认可方案价值但预算负责人迟迟不进入会议" },
    [PSCustomObject]@{ Slug="key-account-manager"; Title="大客户经理"; Category="sales"; Responsibilities="经营关键客户关系、制定账户计划并扩大长期合作价值"; Skills="账户地图、关系管理、机会判断、谈判和续约增长"; Scenario="核心客户因竞品低价提出降价百分之二十的续约要求" },
    [PSCustomObject]@{ Slug="retail-sales"; Title="零售销售顾问"; Category="sales"; Responsibilities="在合规前提下识别到店需求、完成产品匹配并维护服务体验"; Skills="快速需求判断、产品演示、成交推进、投诉预防和复购"; Scenario="高峰期同时有三组顾客需要咨询且其中一组情绪急躁" },
    [PSCustomObject]@{ Slug="channel-sales"; Title="渠道销售经理"; Category="sales"; Responsibilities="发展并赋能合作伙伴，协调渠道冲突并共同完成市场目标"; Skills="伙伴分层、政策设计、联合商机、预测和冲突治理"; Scenario="两家渠道伙伴同时申报同一客户并互相质疑报备有效性" },
    [PSCustomObject]@{ Slug="presales-consultant"; Title="售前解决方案顾问"; Category="sales"; Responsibilities="把客户问题转成可验证方案，协同销售完成技术澄清和价值证明"; Skills="业务分析、方案设计、演示、招投标、范围和风险管理"; Scenario="客户要求两周内完成覆盖全部遗留系统的概念验证" },
    [PSCustomObject]@{ Slug="sales-operations"; Title="销售运营专员"; Category="sales"; Responsibilities="维护销售流程和数据质量，提供预测、漏斗分析与激励执行支持"; Skills="CRM、漏斗指标、预测、数据分析、流程优化和跨部门沟通"; Scenario="季度末预测偏差扩大且大量商机长期停留在同一阶段" },

    [PSCustomObject]@{ Slug="brand-marketing"; Title="品牌营销专员"; Category="marketing"; Responsibilities="建立一致品牌表达，规划传播活动并评估品牌资产变化"; Skills="用户洞察、定位、创意简报、媒介协同和品牌度量"; Scenario="新品声量上升但目标人群对核心卖点记忆很低" },
    [PSCustomObject]@{ Slug="growth-marketing"; Title="增长营销专员"; Category="marketing"; Responsibilities="围绕获客、激活和留存设计实验并平衡规模与单位经济性"; Skills="漏斗分析、实验设计、渠道归因、生命周期和预算优化"; Scenario="付费渠道注册增长但三十日留存显著下降" },
    [PSCustomObject]@{ Slug="content-marketing"; Title="内容营销专员"; Category="marketing"; Responsibilities="根据受众问题规划内容资产并连接传播、线索和品牌目标"; Skills="选题、编辑、分发、搜索优化、转化路径和复盘"; Scenario="内容阅读量稳定但有效线索连续两个季度下降" },
    [PSCustomObject]@{ Slug="performance-marketing"; Title="效果广告优化师"; Category="marketing"; Responsibilities="管理广告投放、素材测试和归因质量，持续优化获客成本"; Skills="平台投放、数据分析、素材实验、归因和预算节奏"; Scenario="平台报告转化增长但内部订单系统没有同步提升" },
    [PSCustomObject]@{ Slug="market-research"; Title="市场研究专员"; Category="marketing"; Responsibilities="设计研究方案并把定性定量证据转化为可行动市场判断"; Skills="抽样、访谈、问卷、统计、竞争分析和洞察表达"; Scenario="管理层要求用一周判断新城市是否值得进入" },

    [PSCustomObject]@{ Slug="user-operations"; Title="用户运营专员"; Category="operations"; Responsibilities="按用户生命周期设计触达、权益和留存机制并验证效果"; Skills="用户分层、活动、触达、留存、数据分析和体验治理"; Scenario="新用户首周活跃正常但第二周出现集中流失" },
    [PSCustomObject]@{ Slug="content-operations"; Title="内容运营专员"; Category="operations"; Responsibilities="建设内容供给、分发和质量规则，平衡增长、生态与安全"; Skills="内容策略、作者运营、分发机制、审核和指标体系"; Scenario="热点带来流量峰值但低质重复内容快速增加" },
    [PSCustomObject]@{ Slug="community-operations"; Title="社群运营专员"; Category="operations"; Responsibilities="建立社群价值、互动节奏和治理规则，促进成员长期参与"; Skills="社群定位、活动、用户关系、冲突处理和转化边界"; Scenario="核心群活跃依赖红包，停止激励后讨论迅速减少" },
    [PSCustomObject]@{ Slug="product-operations"; Title="产品运营专员"; Category="operations"; Responsibilities="连接产品能力和使用场景，推动功能采用并反馈真实用户问题"; Skills="功能推广、数据分析、用户教育、反馈闭环和项目协同"; Scenario="重要新功能上线一个月后目标用户使用率仍不足百分之十" },
    [PSCustomObject]@{ Slug="ecommerce-operations"; Title="电商运营专员"; Category="operations"; Responsibilities="管理商品、流量、转化和履约协同，提升可持续经营结果"; Skills="商品规划、活动、页面转化、库存、平台规则和利润分析"; Scenario="大促销售额达标但退款率和缺货率同时上升" },

    [PSCustomObject]@{ Slug="customer-service"; Title="客户服务专员"; Category="customer-service"; Responsibilities="准确理解客户问题，在权限和时效内解决并沉淀可复用知识"; Skills="倾听、问题诊断、情绪安抚、工单、知识库和升级机制"; Scenario="客户因重复转接而愤怒并要求立即给出超权限补偿" },
    [PSCustomObject]@{ Slug="customer-success"; Title="客户成功经理"; Category="customer-service"; Responsibilities="推动客户采用产品并实现业务价值，识别续约和流失风险"; Skills="成功计划、产品采用、健康度、业务复盘和续约协同"; Scenario="客户已上线半年但只有一个部门持续使用产品" },
    [PSCustomObject]@{ Slug="complaint-specialist"; Title="客诉处理专员"; Category="customer-service"; Responsibilities="调查复杂投诉、协调责任和补救方案并推动根因改善"; Skills="事实核查、合规、谈判、跨部门协调和根因分析"; Scenario="公开平台出现传播中的投诉且内部记录存在信息缺口" },

    [PSCustomObject]@{ Slug="recruiter"; Title="招聘专员"; Category="human-resources"; Responsibilities="澄清用人需求、建设候选人渠道并保障公平高效的甄选体验"; Skills="岗位分析、寻访、结构化面试、候选人沟通和招聘数据"; Scenario="业务要求两周到岗但岗位画像存在互相矛盾的条件" },
    [PSCustomObject]@{ Slug="hr-business-partner"; Title="HRBP"; Category="human-resources"; Responsibilities="理解业务目标并通过组织、人才和机制方案支持团队发展"; Skills="组织诊断、人才盘点、绩效、员工关系和变革沟通"; Scenario="业务高速扩张但中层管理者离职率明显上升" },
    [PSCustomObject]@{ Slug="learning-development"; Title="培训与发展专员"; Category="human-resources"; Responsibilities="识别能力差距、设计学习项目并验证行为和绩效变化"; Skills="需求分析、课程设计、学习运营、评估和知识沉淀"; Scenario="培训满意度很高但三个月后业务指标没有改善" },

    [PSCustomObject]@{ Slug="financial-accountant"; Title="财务会计"; Category="finance"; Responsibilities="准确记录业务交易、完成结账和报表并维护内控证据"; Skills="会计准则、结账、对账、税务、内控和异常分析"; Scenario="月末发现收入系统与总账存在跨期差异" },
    [PSCustomObject]@{ Slug="financial-analyst"; Title="财务分析师"; Category="finance"; Responsibilities="连接经营数据和财务结果，支持预算、预测与资源配置决策"; Skills="预算、预测、差异分析、建模、业务理解和管理报告"; Scenario="收入增长但现金流和利润率同时恶化" },
    [PSCustomObject]@{ Slug="internal-auditor"; Title="内部审计专员"; Category="finance"; Responsibilities="基于风险评估流程和控制有效性，提出可执行改进建议"; Skills="风险评估、抽样、证据、内控、沟通和整改跟踪"; Scenario="采购流程权限合规但供应商集中度异常升高" },

    [PSCustomObject]@{ Slug="procurement-specialist"; Title="采购专员"; Category="supply-chain"; Responsibilities="管理寻源、比价、合同和供应风险，平衡成本质量与交付"; Skills="供应商评估、谈判、合同、成本分析和合规"; Scenario="关键供应商提出涨价并缩短报价有效期" },
    [PSCustomObject]@{ Slug="supply-chain-planner"; Title="供应链计划专员"; Category="supply-chain"; Responsibilities="协调需求、库存和供应能力，降低缺货与积压风险"; Skills="需求预测、库存策略、产销协同、情景分析和异常管理"; Scenario="促销预测提高后供应商确认产能不足" },
    [PSCustomObject]@{ Slug="logistics-specialist"; Title="物流运营专员"; Category="supply-chain"; Responsibilities="设计和监控运输履约，处理异常并持续优化成本和时效"; Skills="运输管理、承运商、路由、成本、时效和异常复盘"; Scenario="区域极端天气导致大量订单延误且客服咨询激增" },

    [PSCustomObject]@{ Slug="product-manager"; Title="产品经理"; Category="product"; Responsibilities="发现值得解决的问题，定义边界并推动跨职能团队交付可验证价值"; Skills="用户研究、需求判断、方案权衡、指标、路线图和协作"; Scenario="大客户强烈要求的功能与产品长期方向冲突" },
    [PSCustomObject]@{ Slug="project-manager"; Title="项目经理"; Category="product"; Responsibilities="建立目标、范围、计划和风险机制，推动多方按承诺交付"; Skills="计划、依赖、风险、沟通、变更和复盘"; Scenario="上线日期固定但关键依赖团队交付延迟两周" },
    [PSCustomObject]@{ Slug="business-analyst"; Title="业务分析师"; Category="product"; Responsibilities="澄清业务目标和流程，把多方需求转成一致、可验证的方案"; Skills="流程分析、需求建模、数据分析、利益相关方和验收"; Scenario="三个部门对同一审批流程提出互相冲突的要求" },

    [PSCustomObject]@{ Slug="ux-designer"; Title="用户体验设计师"; Category="design"; Responsibilities="研究用户任务并设计清晰、一致、可验证的交互体验"; Skills="用户研究、信息架构、交互、原型、可用性测试和无障碍"; Scenario="核心流程转化下降但访谈用户普遍声称页面没有问题" },
    [PSCustomObject]@{ Slug="visual-designer"; Title="视觉设计师"; Category="design"; Responsibilities="把品牌和信息目标转化为有层级、可扩展的视觉表达"; Skills="版式、色彩、字体、品牌系统、交付和设计评审"; Scenario="多渠道活动物料快速增加但品牌一致性持续下降" },
    [PSCustomObject]@{ Slug="service-designer"; Title="服务设计师"; Category="design"; Responsibilities="从端到端旅程识别前台与后台断点并设计协同服务机制"; Skills="旅程地图、服务蓝图、研究、共创、试点和度量"; Scenario="客户满意度下降但每个单独接触点的评分都不低" }
)

$aspects = @(
    [PSCustomObject]@{ Slug="role-overview"; Label="岗位理解"; Prompt="请说明这个岗位如何为组织和客户创造价值，并给出优先级判断。"; Evidence="候选人能把职责、对象、结果和约束连接起来，而不是只罗列日常动作。" },
    [PSCustomObject]@{ Slug="core-competency"; Label="核心能力"; Prompt="请选择两项最关键能力，分别说明你如何学习、应用并验证结果。"; Evidence="回答包含具体行为、指标、复盘和能力迁移，不用空泛性格词代替证据。" },
    [PSCustomObject]@{ Slug="behavioral-example"; Label="行为经历"; Prompt="请用 STAR 结构讲述一次高压力协作，并说明你本人作出的关键判断。"; Evidence="情境和任务简洁，行动可归因于本人，结果有证据且能说明反思。" },
    [PSCustomObject]@{ Slug="scenario-practice"; Label="情景实战"; Prompt="面对给定业务情景，你会先确认什么、如何行动、怎样设止损和复盘。"; Evidence="先澄清事实与目标，再分阶段行动；能识别合规、客户和经营风险。" },
    [PSCustomObject]@{ Slug="evaluation-rubric"; Label="评价标准"; Prompt="如果你是面试官，会用哪些可观察证据区分合格、良好和优秀回答。"; Evidence="评价标准与岗位结果相关、可观察、可追问，并避免年龄、性别等歧视因素。" }
)

foreach ($role in $roles) {
    $roleDirectory = Join-Path $jobRoot $role.Category
    New-Item -ItemType Directory -Force -Path $roleDirectory | Out-Null
    $roleSources = if ($role.Category -eq "sales") {
        "$officialOccupationSource,$marketSource,$salesSource"
    } elseif ($role.Category -eq "customer-service") {
        "$officialOccupationSource,$marketSource,$customerServiceSource"
    } else {
        "$officialOccupationSource,$marketSource,$blsSource"
    }
    foreach ($aspect in $aspects) {
        $id = "job-$($role.Slug)-$($aspect.Slug)"
        $title = "$($role.Title)：$($aspect.Label)"
        $content = @"
---
id: $id
domain: job
category: $($role.Category)
title: $title
aliases: $($role.Title),$($role.Slug)
tags: $($role.Category),$($aspect.Label),$($role.Skills)
level: representative
source_version: 2026.07.1
sources: $roleSources
---
# $title

## 适用范围
本材料用于 $($role.Title) 的代表性面试训练。岗位名称在不同组织可能对应不同职责，练习时应以真实招聘说明、业务阶段和当地法规为准，不把行业统计直接当作某家公司的录用标准。

## 核心知识与表达
- **价值与对象**：$($role.Responsibilities)。回答不能停留在任务清单，要说明服务对象、业务结果、质量标准和不可突破的边界。
- **专业能力**：围绕$($role.Skills)说明“概念或工具—适用条件—实际动作—验证指标—局限”。只说“熟悉、负责、参与”不构成能力证据。
- **结构化表达**：先用一句话给出判断，再列出两到三个关键依据；经历题使用“背景与目标—本人行动—可核验结果—反事实复盘”，方案题使用“事实—假设—方案—风险—监控与退出条件”。
- **证据口径**：区分个人贡献和团队成果，说明时间范围、数据来源、基线和对照。无法披露业务数据时可使用比例、区间或过程证据，但不得编造数字。

## 研究与实践问题
专题问题：$($aspect.Prompt)

实践情景：$($role.Scenario)。请按以下顺序作答：
1. 列出必须先确认的事实、利益相关方和成功标准，并指出哪些信息仍是假设。
2. 给出至少两个可选方案，比较成本、时效、客户影响、合规与长期可维护性。
3. 说明你会亲自完成什么、需要谁协作、在哪个节点升级，以及触发止损或回滚的条件。
4. 设计一个领先指标和一个结果指标；若结果不及预期，说明如何定位原因并形成可复用改进。

追问包括：“当时有哪些替代方案”“最关键的反对意见是什么”“你依据什么作出取舍”“如何排除结果只是外部环境造成”“如果资源减半会改变什么”。

## 综合评价
- **优秀**：$($aspect.Evidence) 同时能量化取舍，主动识别反例和风险，个人贡献清晰，复盘能迁移到新场景。
- **合格**：理解岗位基本职责，行动顺序可执行，有一项可信证据并能回答主要追问，但指标、替代方案或长期影响仍不完整。
- **需加强**：只复述术语或岗位说明，行动与结果无法对应，把团队成果全部归因于个人，或遇到追问频繁改变事实口径。
- **红线信号**：泄露前雇主或客户机密、伪造业绩、忽略隐私/安全/财务/劳动合规，或使用年龄、性别、民族等敏感特征作胜任判断。

## 资料边界
本材料依据公开职业分类、2025 年岗位市场资料和 2024—2034 职业展望整理，仅用于训练问题与评价维度，不提供薪资承诺、录用预测或具体公司的内部流程。
"@
        $path = Join-Path $roleDirectory "$id.md"
        [IO.File]::WriteAllText($path, ($content -replace "`r`n", "`n").TrimEnd() + "`n", $utf8NoBom)
    }
}

$schoolProfiles = @(
    @("research-university", "研究型综合大学", "学术潜力、基础理论、研究问题意识与长期学习能力", "从一个宽泛兴趣收敛出可研究问题，并说明变量、证据和可行的第一步"),
    @("science-engineering-university", "理工类院校", "数理基础、工程问题拆解、实验或项目证据与技术表达", "面对性能、成本和可靠性冲突时建立约束模型并设计验证实验"),
    @("finance-economics-university", "财经类院校", "经济逻辑、数据意识、案例分析、政策边界与商业表达", "用机制、数据和替代解释分析一个经营或政策现象"),
    @("normal-university", "师范类院校", "学科基础、教育理解、表达组织、研究方法与职业伦理", "把一个课堂观察转化为可验证的教育研究问题并处理学习者差异"),
    @("medical-university", "医药类院校", "专业基础、证据意识、伦理安全、实践规范与持续学习", "在证据不完整时说明临床或实验判断、风险沟通和升级边界"),
    @("agriculture-university", "农林类院校", "专业基础、实验设计、产业与生态问题、实践价值", "平衡产量、生态、成本与可推广性，设计田间或产业验证"),
    @("foreign-studies-university", "外语类院校", "语言能力、跨文化理解、文本分析与清晰论证", "比较同一概念在两种语言文化中的表达差异并给出文本证据"),
    @("politics-law-university", "政法类院校", "规范分析、事实与观点区分、论证结构和公共责任", "对权利冲突案例区分事实、规则、解释路径和程序保障"),
    @("arts-university", "艺术类院校", "作品逻辑、创作过程、审美判断、研究意识与表达", "从作品意图、媒介选择、受众反馈和迭代说明创作判断"),
    @("sports-university", "体育类院校", "专项基础、训练科学、实践反思、安全与团队协作", "依据训练负荷和个体反应调整计划并控制伤病风险"),
    @("industry-characteristic-university", "行业特色院校", "行业问题、专业工具、实践场景和职业规范", "选择一个行业真实约束，解释专业工具如何落地及其失效条件"),
    @("local-key-university", "地方重点院校", "区域问题意识、专业基础、实践经历和发展匹配", "把区域产业或公共问题转化为与专业训练匹配的实践课题"),
    @("joint-program", "中外合作项目", "双语沟通、跨文化协作、学术规范和适应能力", "说明一次跨文化误解的诊断、沟通调整和学术规范处理"),
    @("professional-degree-program", "专业学位项目", "真实问题解决、职业素养、项目证据和反思", "定义一个行业问题的交付物、利益相关方、评价指标和落地风险"),
    @("academic-degree-program", "学术学位项目", "理论基础、文献意识、研究问题和方法匹配", "从文献分歧识别研究缺口，并论证方法为何能回答问题"),
    @("c9-representative", "C9 高校类型", "扎实基础、研究潜力、证据质量与学术表达", "从课程或项目中提炼可证伪问题，并比较方法、资源和失败边界", "c9"),
    @("985-representative", "985 高校类型", "专业基础、综合分析、实践证据与学习潜力", "围绕真实问题提出多种方案，并说明取舍、验证和复盘", "985"),
    @("211-representative", "211 高校类型", "核心概念、实践迁移、结构表达与发展匹配", "把一个课程知识点迁移到真实案例，说明假设、数据和局限", "211"),
    @("general-undergraduate-representative", "普通本科院校类型", "基础掌握、真实经历、学习计划与持续改进", "选择一项薄弱能力，设计三个月可检查进展的学习实践计划", "general-undergraduate")
)

foreach ($school in $schoolProfiles) {
    $id = "postgraduate-school-$($school[0])"
    $title = "$($school[1])代表性复试准备"
    $schoolTier = if ($school.Count -ge 5) { $school[4] } else { "representative" }
    $content = @"
---
id: $id
domain: postgraduate
category: school
title: $title
aliases: $($school[1]),$($school[0])
tags: 复试,院校类型,$($school[2])
school_tier: $schoolTier
source_version: 2026.07.1
sources: $postgraduatePolicySource,$postgraduateWorkSource
---
# $title

## 定位
本材料是院校类型的代表性练习样本，不代表任何具体学校、学院或导师组的当年复试办法。教育部 2026 年规定要求招生单位自行制定并提前公布复试录取办法，因此练习前必须再次核验目标院校官方网站的时间、形式、材料、成绩使用办法和学术要求。

## 核心知识与表达
- **培养定位**：$($school[2])。回答应把课程基础、项目证据、报考动机和未来计划连成因果链，而不是背诵学校宣传语。
- **研究表达**：用“问题—已有证据—知识缺口—拟用方法—预期产出—伦理与失败边界”说明计划；区分已经完成、正在进行和入学后的设想。
- **匹配论证**：只引用已核验的培养方案、研究平台或公开导师方向，说明匹配点和仍需补足的能力，不虚构联系、成果或录取承诺。
- **追问应对**：不会时准确界定知识边界，给出查证来源和验证步骤；英文问答应优先保证概念准确和论证清晰。

## 研究与实践问题
锚点问题：$($school[3])。进一步说明这个问题为什么适合研究生阶段、你已有何种直接或间接证据、最可能失败在哪里，以及前三个月如何形成可检查的阶段成果。

追问可覆盖文献来源、样本或数据、方法选择、替代解释、失败经历、学术规范、团队分工与资源限制。回答必须区分事实、推测和个人意愿，并说明若关键假设不成立将如何调整。

## 综合评价
- **优秀**：基础与问题匹配，证据可追溯，能比较方法并主动讨论局限、伦理和复现，规划具体且允许被检验。
- **合格**：动机真实，具备基本专业表达和一段可核验经历，研究计划方向合理，但文献、方法或阶段目标仍较粗。
- **需加强**：只讲院校名气或个人愿望，项目贡献含混，研究问题过大，无法解释资料来源和方法为何适用。
- **红线信号**：虚构论文、竞赛、导师联系或实习，传播保密试题，贬低其他考生，或把非公开消息当作录取依据。

## 安全与边界
不得收集、传播或推断保密试题，不提供“押题保证”和录取预测。涉及具体政策时只引用目标院校当年公开通知；本知识库仅生成通用训练问题和评价维度。
"@
    [IO.File]::WriteAllText((Join-Path $postgraduateRoot "$id.md"), ($content -replace "`r`n", "`n").TrimEnd() + "`n", $utf8NoBom)
}

$majorProfiles = @(
    @("computer-science", "计算机科学与技术", "数据结构、操作系统、网络、数据库、算法与工程实践", "为高并发数据服务选择索引、缓存和一致性策略，并用复杂度与故障实验验证"),
    @("software-engineering", "软件工程", "需求、架构、质量、项目协作、演化与工程伦理", "面对持续变化的需求，设计可演化架构、质量门禁和可回滚交付方案"),
    @("artificial-intelligence", "人工智能", "数学基础、机器学习、数据、评测、可靠性与伦理", "数据分布变化时比较模型、特征和检索方案，设计离线与在线评测并控制偏差"),
    @("electronic-information", "电子信息", "电路、信号、通信、系统设计、实验和工程约束", "在噪声、带宽、功耗和成本约束下设计信号链路并定位测量误差"),
    @("mechanical-engineering", "机械工程", "力学、设计、制造、测量、可靠性和工程实践", "针对关键部件失效，从载荷、材料、制造误差和测试数据提出验证方案"),
    @("civil-engineering", "土木工程", "结构、材料、施工、风险、安全和全生命周期", "在荷载、地质、施工和耐久性不确定条件下比较结构方案并设置安全监测"),
    @("business-administration", "工商管理", "战略、组织、营销、运营、财务和案例分析", "企业增长放缓时用客户、竞争、组织和财务证据识别主因并配置资源"),
    @("accounting", "会计", "会计准则、审计、财务分析、内控和职业判断", "对收入确认时点争议识别合同事实、准则依据、重大判断和审计证据"),
    @("finance", "金融", "经济金融基础、风险、市场、计量、监管和数据分析", "在收益目标和流动性约束下构建风险情景，解释模型假设与监管边界"),
    @("applied-economics", "应用经济学", "经济理论、计量方法、政策分析、因果意识和数据", "评估一项政策影响时区分相关与因果，选择识别策略并检验平行趋势或工具有效性"),
    @("law", "法学", "规范解释、案例分析、论证、程序意识和法律伦理", "对事实不完整的权利冲突案件形成请求权基础、争点、解释路径和程序方案"),
    @("public-administration", "公共管理", "公共政策、组织治理、数据证据、利益相关方和伦理", "公共服务效率与公平冲突时绘制利益相关方并设计可评估的政策试点"),
    @("education", "教育学", "教育理论、研究方法、学习者、课程与评价", "针对学习差异设计教学干预，说明理论机制、评价工具和对照方案"),
    @("psychology", "心理学", "基础理论、研究设计、统计、测量、伦理和应用边界", "研究压力与表现关系时处理操作化、量表信效度、混杂变量与知情同意"),
    @("journalism-communication", "新闻传播学", "传播理论、研究方法、平台生态、内容判断和伦理", "分析平台热点传播时连接理论、内容样本、算法环境、受众反应和伦理风险"),
    @("chinese-language-literature", "中国语言文学", "文本细读、文学史、理论、文献和论证表达", "围绕一组文本差异提出解释，结合版本、语境和理论形成可反驳论证"),
    @("foreign-language-literature", "外国语言文学", "语言能力、文本分析、跨文化、理论和研究方法", "比较原文与译文的叙事或文化意涵，用细读证据说明理论选择与翻译取舍"),
    @("biology", "生物学", "细胞与分子基础、实验设计、数据、文献和生物安全", "验证某基因是否影响细胞表型，设置阳性阴性对照、重复、统计分析和救援实验"),
    @("chemistry", "化学", "基础理论、实验操作、表征、数据、实验安全和科研规范", "合成结果异常时从反应机理、纯化、光谱表征和空白对照定位原因并评估安全"),
    @("clinical-medicine", "临床医学", "医学基础、临床思维、证据、沟通、伦理和患者安全", "面对症状相似但风险不同的病例建立鉴别诊断，安排检查优先级并沟通不确定性")
)

foreach ($major in $majorProfiles) {
    $id = "postgraduate-major-$($major[0])"
    $title = "$($major[1])复试专业能力练习"
    $content = @"
---
id: $id
domain: postgraduate
category: major
title: $title
aliases: $($major[1]),$($major[0])
tags: 复试,专业能力,$($major[2])
school_tier: not-applicable
source_version: 2026.07.1
sources: $postgraduatePolicySource,$postgraduateWorkSource
---
# $title

## 适用边界
本材料用于 $($major[1]) 的代表性专业复试训练，不替代目标院校当年专业目录、参考书目和复试细则。不同院系即使专业名称相同，研究方向和评价方式也可能不同；教育部规定之外的具体要求必须以招生单位官网为准。

## 核心知识与表达
- **知识主线**：围绕$($major[2])建立概念网络。不能只背定义，要能解释概念之间的因果、层级或约束关系，并指出常见混淆。
- **回答结构**：先给结论和适用条件，再展开机制或推导；用课程实验、项目、论文图表或公开数据作证据，最后说明局限、反例和验证办法。
- **方法意识**：明确研究对象、变量或材料、对照与基线、数据采集、分析方法和误差来源。定性判断要说明编码或解释依据，定量结论要说明样本、指标与不确定性。
- **文献表达**：区分原始研究、综述和教材结论；介绍文献时说明研究问题、方法、主要发现和局限，不以“有研究表明”代替可核验来源。
- **知识边界**：遇到不会的问题，准确说出已知部分、未知点、拟查阅的权威来源和验证步骤，不编造术语、数据、论文或导师观点。

## 研究与实践问题
专业锚点问题：$($major[3])。

请结合一段真实课程、实验、项目、实习或论文阅读经历回答：
1. 问题为什么重要，已有方案解决了什么、还缺什么；你的问题能否被证伪或被数据回答？
2. 你本人负责哪一步，为什么选择该方法；至少比较一个替代方案及其成本、假设和适用条件。
3. 数据、样本、材料或案例来自哪里；如何设置对照、重复、质量检查，并处理缺失、偏差或异常结果？
4. 描述一次失败或与预期不一致的结果。你如何区分操作错误、方法限制与理论假设错误？
5. 如果进入复试院系，你会如何把问题收敛为三个月可完成的阶段任务，并遵守数据、实验、临床或研究伦理？

## 综合评价
- **优秀**：概念准确且能连接机制，问题具体可研究；证据和个人贡献可追溯，方法与问题匹配，能主动提出反例、替代解释、复现与伦理边界。
- **合格**：掌握主要基础概念，能完整讲述一段真实经历并给出基本方法和结果；在提示下可以识别局限，但文献比较、误差分析或研究计划仍较粗。
- **需加强**：堆砌术语、结论先于证据，把相关当因果；无法解释变量、对照、样本或个人贡献，失败经历只归咎外部，研究计划宽泛且无法验证。
- **红线信号**：虚构论文、数据、实验或临床经历，篡改结果，泄露隐私或保密材料，忽视生物/实验/患者安全，传播保密试题或作出录取概率承诺。

建议面试官分别从“基础知识 25%、问题与方法 25%、证据与实践 20%、批判反思 15%、表达与学术诚信 15%”记录可观察证据；该权重只用于通用训练，不替代目标院校评分办法。
"@
    [IO.File]::WriteAllText((Join-Path $postgraduateRoot "$id.md"), ($content -replace "`r`n", "`n").TrimEnd() + "`n", $utf8NoBom)
}

$generated = Get-ChildItem -LiteralPath $targetRoot -Recurse -Filter "*.md"
$jobCount = @($generated | Where-Object { $_.FullName.StartsWith($jobRoot, [StringComparison]::OrdinalIgnoreCase) }).Count
$postgraduateCount = @($generated | Where-Object { $_.FullName.StartsWith($postgraduateRoot, [StringComparison]::OrdinalIgnoreCase) }).Count
if ($jobCount -lt 200 -or $postgraduateCount -lt 30) {
    throw "知识库数量不足：岗位=$jobCount，考研=$postgraduateCount"
}
Write-Output "生成公共知识文档：岗位=$jobCount，考研=$postgraduateCount，总计=$($generated.Count)"
