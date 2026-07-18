package icu.sakuracianna.mianba.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.util.List;
import org.junit.jupiter.api.Test;

class JavaPoolingTransformersEmbeddingModelTest {

    @Test
    void appliesAttentionMaskAndMeanPoolsEachBatch() {
        float[][][] tokens = {
            {{1.0f, 3.0f}, {5.0f, 7.0f}, {100.0f, 100.0f}},
            {{2.0f, 4.0f}, {6.0f, 8.0f}, {10.0f, 12.0f}}
        };
        long[][] mask = {{1, 1, 0}, {1, 1, 1}};

        List<float[]> pooled =
                JavaPoolingTransformersEmbeddingModel.meanPooling(tokens, mask);

        assertThat(pooled.get(0)).containsExactly(3.0f, 5.0f);
        assertThat(pooled.get(1)).containsExactly(6.0f, 8.0f);
    }

    @Test
    void closesEveryNativeResourceWhenOneCloseFails() throws Exception {
        AutoCloseable session = mock(AutoCloseable.class);
        AutoCloseable tokenizer = mock(AutoCloseable.class);
        doThrow(new IllegalStateException("session close failed")).when(session).close();

        assertThatThrownBy(
                        () -> JavaPoolingTransformersEmbeddingModel.closeResources(
                                session, tokenizer))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("session close failed");

        verify(session).close();
        verify(tokenizer).close();
    }
}
