import unittest

from app.api.providers import to_response
from app.core.config import Settings
from app.schemas.interviews import InterviewType
from app.services.ai_router import AIProviderConfig
from app.services.interview_products import INTERVIEW_PRODUCTS
from app.services.system_configs import DEFAULT_SYSTEM_CONFIGS


class BusinessDefaultTests(unittest.TestCase):
    def test_new_users_receive_one_default_credit(self) -> None:
        self.assertEqual(DEFAULT_SYSTEM_CONFIGS["new_user_default_credits"].value, 1)

    def test_every_interview_type_costs_one_credit(self) -> None:
        for interview_type in InterviewType:
            self.assertEqual(INTERVIEW_PRODUCTS[interview_type].credit_cost, 1)

    def test_provider_response_counts_environment_credentials(self) -> None:
        settings = Settings(
            deepseek_api_key="sk-test",
            tencent_cloud_app_id="app-id",
            tencent_cloud_secret_id="secret-id",
            tencent_cloud_secret_key="secret-key",
        )

        deepseek = to_response(
            AIProviderConfig(
                id="deepseek-v4-flash",
                provider_type="llm",
                purpose="general",
                enabled=True,
                priority=10,
                provider_name="deepseek",
                model_name="deepseek-v4-flash",
            ),
            settings,
        )
        tencent = to_response(
            AIProviderConfig(
                id="tencent-asr-realtime",
                provider_type="asr",
                purpose="interview",
                enabled=True,
                priority=10,
                provider_name="tencent",
                model_name="16k_zh",
            ),
            settings,
        )

        self.assertTrue(deepseek.has_api_key)
        self.assertEqual(deepseek.api_key_preview, "环境变量已配置")
        self.assertTrue(tencent.has_api_key)
        self.assertEqual(tencent.api_key_preview, "环境变量已配置")


if __name__ == "__main__":
    unittest.main()
