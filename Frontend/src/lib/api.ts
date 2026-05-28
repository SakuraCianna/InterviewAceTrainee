export const CSRF_COOKIE_NAME = "mianba_csrf_token";
export const CSRF_HEADER_NAME = "X-CSRF-Token";

export type ApiErrorPayload = {
  detail?: string;
  message?: string;
};

const API_ERROR_MESSAGES: Record<string, string> = {
  active_interview_not_found: "当前没有未完成的面试训练。",
  admin_required: "当前账号没有管理员权限。",
  admin_role_required: "当前账号不是管理员账号，请联系管理员确认权限。",
  asr_provider_failed: "语音识别服务调用失败，请稍后重试。",
  asr_provider_not_available: "语音识别服务暂时不可用，请联系管理员检查配置。",
  audio_file_empty: "没有收到有效录音，请重新录制后提交。",
  audio_duration_too_long: "当前音频超过短音频识别限制，请使用实时语音通道完成长回答。",
  audio_file_too_large: "录音文件过大，请缩短回答时长后重试。",
  auth_attempt_rate_limited: "登录或验证尝试过于频繁，请稍后再试。",
  cannot_change_own_admin_role: "不能撤销当前登录管理员自己的管理员权限，请使用另一个管理员账号操作。",
  cannot_disable_own_admin_account: "不能停用当前登录管理员自己的账号，请使用另一个管理员账号操作。",
  content_safety_policy_violation: "当前回答包含不适合进入训练的内容，请回到面试问题本身，避免违法违规、作弊、隐私或恶意诱导内容。",
  credit_balance_cannot_be_negative: "次数调整后不能小于 0，请重新填写。",
  csrf_token_required: "页面登录状态已刷新，请重新打开页面后再操作。",
  email_already_registered: "该邮箱已经注册，请直接登录。",
  email_code_login_disabled: "邮箱验证码登录暂时关闭，请使用密码登录或联系管理员。",
  email_code_rate_limited: "验证码发送过于频繁，请稍后再试。",
  email_from_address_missing: "邮件发件地址未配置，请联系管理员处理。",
  image_resume_ocr_dependency_missing: "图片简历识别依赖未安装，请先上传 PDF、DOCX 或 TXT 简历。",
  image_resume_ocr_not_configured: "图片简历识别暂未开启，请先上传 PDF、DOCX 或 TXT 简历。",
  insufficient_credits: "面试次数不足，请添加官方微信 Teptysuki666 开通次数。",
  internal_server_error: "服务器暂时不可用，请稍后重试。",
  interview_material_not_found: "未找到已分析的面试资料，请重新提交资料。",
  interview_material_required: "该模块需要先提交面试资料。",
  interview_report_not_found: "该训练暂未生成复盘报告。",
  interview_session_already_completed: "这场训练已经完成，不能继续提交回答。",
  interview_session_not_found: "未找到这场面试训练，请刷新页面后重试。",
  invalid_access_token: "登录状态已失效，请重新登录。",
  invalid_credentials: "邮箱或密码不正确，请重新输入。",
  invalid_email_code: "邮箱验证码不正确或已过期，请重新获取。",
  job_material_required_fields: "工作面试需要上传简历，并填写应聘岗位和岗位要求。",
  llm_request_succeeded: "模型连接测试通过。",
  missing_access_token: "请先登录后再继续操作。",
  password_login_disabled: "密码登录暂时关闭，请使用邮箱验证码登录或联系管理员。",
  postgraduate_major_required: "研究生复试需要先填写报考专业。",
  postgraduate_school_major_required: "研究生复试需要先填写目标院校和报考专业。",
  provider_config_not_found: "未找到该服务配置。",
  provider_disabled: "该服务配置已停用。",
  provider_not_available: "当前没有可用的模型或语音服务配置。",
  provider_not_supported: "当前供应商暂不支持该项测试。",
  provider_type_not_supported: "当前服务类型暂不支持测试。",
  refund_case_not_found: "未找到该退款或纠纷记录。",
  registration_closed: "当前暂未开放自助注册，请联系管理员开通账号。",
  request_validation_failed: "提交内容格式不正确，请检查后重试。",
  resend_api_key_missing: "邮件服务密钥未配置，请联系管理员处理。",
  resend_delivery_failed: "验证码邮件发送失败，请稍后重试或联系管理员。",
  resume_file_too_large: "简历文件过大，请控制在系统限制以内。",
  resume_text_empty: "简历没有提取到有效文字，请换成文字版 PDF、DOCX 或 TXT 文件。",
  session_replaced: "该账号已在其他设备登录，当前会话已下线。",
  system_config_not_found: "未找到该系统配置项。",
  tencent_cloud_configuration_validated: "腾讯云配置已通过基础校验。",
  tencent_cloud_app_id_missing: "腾讯云 AppID 未配置，请联系管理员处理。",
  tencent_cloud_credentials_missing: "腾讯云密钥未配置，请联系管理员处理。",
  tts_provider_failed: "语音合成服务调用失败，请稍后重试。",
  tts_provider_not_available: "语音合成服务暂时不可用，请联系管理员检查配置。",
  unsupported_audio_format: "录音格式暂不支持，请使用 WAV、MP3、M4A 或 WebM 格式。",
  unsupported_email_provider: "当前邮件服务供应商不受支持，请联系管理员处理。",
  unsupported_resume_format: "当前支持 TXT、PDF、DOCX 和常见图片格式，请更换简历文件。",
  user_disabled: "该账号已被停用，请联系管理员处理。",
  user_not_found: "未找到该用户。",
};

export function getApiErrorMessage(payload: unknown, fallback = "操作失败，请稍后重试。") {
  if (!payload || typeof payload !== "object") {
    return fallback;
  }
  const data = payload as ApiErrorPayload;
  if (data.message) {
    return data.message;
  }
  if (data.detail && API_ERROR_MESSAGES[data.detail]) {
    return API_ERROR_MESSAGES[data.detail];
  }
  return fallback;
}

export function getCookie(name: string) {
  const prefix = `${encodeURIComponent(name)}=`;
  const cookie = document.cookie
    .split(";")
    .map((item) => item.trim())
    .find((item) => item.startsWith(prefix));
  if (!cookie) {
    return "";
  }
  return decodeURIComponent(cookie.slice(prefix.length));
}

export function csrfHeaders(headers: HeadersInit = {}): HeadersInit {
  const token = getCookie(CSRF_COOKIE_NAME);
  if (!token) {
    return headers;
  }
  return {
    ...headers,
    [CSRF_HEADER_NAME]: token,
  };
}
