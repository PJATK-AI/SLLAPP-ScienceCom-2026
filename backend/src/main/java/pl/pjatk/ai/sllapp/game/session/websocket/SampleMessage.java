package pl.pjatk.ai.sllapp.game.session.websocket;

import tools.jackson.databind.JsonNode;

record SampleMessage(String type, long seq, JsonNode hand) {}
