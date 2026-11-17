package pl.pjatk.ai.sllapp.web.websocket;

final class SampleFixtures {
    private SampleFixtures() {}

    static String hand(long seq) {
        String point = "{\"x\":0.5,\"y\":1.2,\"z\":-0.1}";
        return "{\"type\":\"hand_sample\",\"seq\":" + seq + ",\"positionId\":null,\"hand\":{"
                + "\"handedness\":\"LEFT\",\"score\":0.95,\"extra\":{\"source\":\"camera\"},\"landmarks\":["
                + (point + ",").repeat(20) + point + "]}}";
    }

    static String noHand(long seq) {
        return "{\"type\":\"no_hand\",\"seq\":" + seq + ",\"positionId\":null}";
    }
}
