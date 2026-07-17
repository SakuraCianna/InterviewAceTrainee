package icu.sakuracianna.mianba.interview.packageflow;

public enum JobInterviewStage {
    TECHNICAL_FIRST(1),
    TECHNICAL_SECOND(2),
    HR_FINAL(3);

    private final int sequence;

    JobInterviewStage(int sequence) {
        this.sequence = sequence;
    }

    public int sequence() {
        return sequence;
    }
}
