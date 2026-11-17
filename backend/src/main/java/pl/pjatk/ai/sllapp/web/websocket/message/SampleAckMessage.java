package pl.pjatk.ai.sllapp.web.websocket.message;

public record SampleAckMessage(String type, long seq) {
    public static SampleAckMessage of(long seq) {
        return new SampleAckMessage("sample_ack", seq);
    }
}
