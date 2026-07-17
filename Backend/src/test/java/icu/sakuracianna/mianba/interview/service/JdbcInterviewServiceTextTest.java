package icu.sakuracianna.mianba.interview.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import icu.sakuracianna.mianba.platform.web.ApiException;
import org.junit.jupiter.api.Test;

class JdbcInterviewServiceTextTest {

    @Test
    void answerAllowsLineBreaksButRejectsNullCharacter() {
        assertThat(JdbcInterviewService.normalizeAnswerText("  第一行\n第二行  "))
                .isEqualTo("第一行\n第二行");
        assertThatThrownBy(() -> JdbcInterviewService.normalizeAnswerText("回答\u0000内容"))
                .isInstanceOfSatisfying(ApiException.class,
                        error -> assertThat(error.detail()).isEqualTo("validation_failed"));
    }
}
