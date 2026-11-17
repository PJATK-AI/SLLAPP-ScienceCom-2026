package pl.pjatk.ai.sllapp.web.websocket.message;

import tools.jackson.databind.JsonNode;

public record SampleMessage(String type, long seq, JsonNode hand) {}
