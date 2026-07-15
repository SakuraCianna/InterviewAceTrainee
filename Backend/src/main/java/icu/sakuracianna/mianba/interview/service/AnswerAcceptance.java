package icu.sakuracianna.mianba.interview.service;

import icu.sakuracianna.mianba.aiwork.service.TaskView;

/** 回答入队后的会话快照与可轮询异步任务。 */
public record AnswerAcceptance(InterviewView session, TaskView task) {
}
