package icu.sakuracianna.mianba.interview.service;

/** 通过会话归属和当前轮次状态校验后，可供 TTS/ASR 使用的最小语音上下文。 */
public record SpeechContext(String interviewType, int turnIndex, String questionText) {
}
