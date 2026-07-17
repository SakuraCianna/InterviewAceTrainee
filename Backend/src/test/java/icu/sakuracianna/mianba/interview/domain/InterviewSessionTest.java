package icu.sakuracianna.mianba.interview.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class InterviewSessionTest {

    @Test
    void answerMovesThroughAwaitingAiAndCompletesOnlyAfterResult() {
        InterviewSession session = InterviewSession.start(
                UUID.randomUUID(), UUID.randomUUID(), InterviewType.CIVIL_SERVICE, 2);

        session.queueAnswer(0, "idem-1", "我会先核实情况，再按优先级协调资源。", session.version());
        assertThat(session.status()).isEqualTo(SessionStatus.AWAITING_AI);

        session.applyAiResult(session.version(), "请说明你会如何通知相关群众？", false);
        assertThat(session.status()).isEqualTo(SessionStatus.ACTIVE);
        assertThat(session.currentTurnIndex()).isEqualTo(1);

        session.queueAnswer(1, "idem-2", "我会公开时间表和责任人，并提供反馈渠道。", session.version());
        session.applyAiResult(session.version(), null, true);

        assertThat(session.status()).isEqualTo(SessionStatus.COMPLETED);
    }

    @Test
    void duplicateOrOutOfOrderAnswerCannotAdvanceTwice() {
        InterviewSession session = InterviewSession.start(
                UUID.randomUUID(), UUID.randomUUID(), InterviewType.IELTS, 3);
        session.queueAnswer(0, "same-key", "A sufficiently detailed answer.", session.version());

        assertThatThrownBy(() -> session.queueAnswer(0, "other-key", "duplicate", session.version()))
                .isInstanceOf(IllegalSessionTransitionException.class);
    }
}
