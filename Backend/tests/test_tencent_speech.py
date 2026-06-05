import unittest

from app.core.config import Settings
from app.services.tencent_speech import TencentSpeechClient, _parse_voice_types


class TencentSpeechVoiceTests(unittest.TestCase):
    def test_parse_voice_types_deduplicates_invalid_values(self) -> None:
        self.assertEqual(_parse_voice_types("603006, 502005, bad, 603006"), [603006, 502005])

    def test_tts_voice_is_stable_for_one_interview_session(self) -> None:
        client = TencentSpeechClient(Settings(tencent_tts_voice_types="603006,502005,602005,603005"))

        first_question_voice = client._resolve_tts_voice_type(text="请做一段自我介绍。", session_id="same-session")
        followup_voice = client._resolve_tts_voice_type(text="请谈谈基层治理。", session_id="same-session")

        self.assertEqual(first_question_voice, followup_voice)
        self.assertIn(first_question_voice, [603006, 502005, 602005, 603005])


if __name__ == "__main__":
    unittest.main()
