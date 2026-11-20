package pl.pjatk.ai.sllapp.gametest;

import java.io.IOException;
import java.time.Clock;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import pl.pjatk.ai.sllapp.game.GameEngine;
import pl.pjatk.ai.sllapp.game.GameRules;
import pl.pjatk.ai.sllapp.game.GameState;
import pl.pjatk.ai.sllapp.game.RandomWordPicker;
import pl.pjatk.ai.sllapp.game.WordCatalog;

@RestController
@Profile("game-test")
@RequestMapping("/game-test")
class GameTestController {
    private static final long ROUND_SECONDS = 60;

    private final WordCatalog catalog;
    private final Clock clock;
    private ActiveGame activeGame;

    GameTestController(Clock clock) throws IOException {
        this.clock = clock;
        catalog = WordCatalog.loadBundled();
    }

    @GetMapping(value = {"", "/"}, produces = MediaType.TEXT_HTML_VALUE)
    Resource page() {
        return new ClassPathResource("game-test/index.html");
    }

    @GetMapping(value = "/app.js", produces = "text/javascript")
    Resource script() {
        return new ClassPathResource("game-test/app.js");
    }

    @GetMapping(value = "/styles.css", produces = "text/css")
    Resource styles() {
        return new ClassPathResource("game-test/styles.css");
    }

    @PostMapping("/api/game")
    synchronized View start() {
        var now = clock.instant();
        var engine = new GameEngine(GameRules.defaults(), new RandomWordPicker(catalog), now,
                now.plusSeconds(ROUND_SECONDS));
        activeGame = new ActiveGame(UUID.randomUUID(), engine);
        return View.from(activeGame.id(), engine.state(now));
    }

    @GetMapping("/api/game/{gameId}")
    synchronized View state(@PathVariable("gameId") UUID gameId) {
        return View.from(gameId, engine(gameId).state(clock.instant()));
    }

    @PostMapping(value = "/api/game/{gameId}/complete", consumes = MediaType.APPLICATION_JSON_VALUE)
    synchronized Attempt complete(@PathVariable("gameId") UUID gameId, @RequestBody CompletionRequest request) {
        if (request.positionId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A position ID is required.");
        }
        var engine = engine(gameId);
        var now = clock.instant();
        int before = engine.state(now).score();
        boolean accepted = engine.completePosition(request.positionId(), now);
        GameState after = engine.state(now);
        return new Attempt(accepted, after.score() - before, View.from(gameId, after));
    }

    private GameEngine engine(UUID gameId) {
        if (activeGame == null || !activeGame.id().equals(gameId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Start a new game to continue.");
        }
        return activeGame.engine();
    }

    private record ActiveGame(UUID id, GameEngine engine) {}

    record View(UUID gameId, GameState state, char expectedLetter) {
        private static View from(UUID id, GameState state) {
            return new View(id, state, state.expectedLetter());
        }
    }

    record Attempt(boolean accepted, int scoreChange, View game) {}

    record CompletionRequest(Long positionId) {}
}
